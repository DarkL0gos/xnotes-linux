package com.xnotes.editor

import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.InteractionController
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.AddPage
import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.DeletePage
import com.xnotes.core.history.EraseItems
import com.xnotes.core.history.History
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.deepCopy
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.platform.File
import com.xnotes.core.tools.Tool

/** Something the editor refused or had nothing to do for; the host words it for the user. */
enum class EditorNotice {
    /** A note keeps at least one page. */
    KEEP_ONE_PAGE,

    /** "Erase page" on a page with nothing on it. */
    PAGE_ALREADY_EMPTY,
}

/** What the paged editor needs from the app around it. Every call is on the UI thread. */
interface NoteEditorHost {
    fun requestRender()

    /** The document or the undo stack changed: refresh undo buttons, page count, dirty state. */
    fun contentChanged()

    /** Zoom, scroll or the current page changed. */
    fun viewChanged()

    fun notice(notice: EditorNotice)

    /** Before an undo/redo step: commit anything still being typed so it is the first thing taken back. */
    fun beforeHistoryStep() {}

    /** After an undo/redo step changed the model, before caches are repaired: republish derived layout. */
    fun republishFlow() {}

    /** After an undo/redo step was repaired into the caches: resync anything mirroring the model. */
    fun afterHistoryRepair() {}
}

/**
 * The paged note editor without any UI toolkit: document swap, undo/redo with in-place cache repair,
 * view commands, page operations (add, delete, copy/paste, the side panel's multi-selection) and page
 * styles/margins. The Android editor and the desktop host both drive the canvas through this, so the
 * editing behaviour cannot drift between them. Gestures stay in [InteractionController].
 *
 * [newPageList] makes the page selection and page clipboard lists; the Android host passes Compose
 * snapshot lists so its UI observes them.
 */
class NoteEditor(
    val state: CanvasState,
    val history: History,
    val controller: InteractionController,
    private val textMeasurer: TextMeasurer,
    private val host: NoteEditorHost,
    newPageList: () -> MutableList<Page> = { ArrayList() },
) {
    /** Pages selected in the side panel (identity, so they survive reordering). */
    private val selectedPages = newPageList()

    /** Deep-cloned pages held for paste (cleared when the document changes). */
    private val pageClipboard = newPageList()

    val document: Document get() = state.document

    fun pageAt(index: Int): Page? = state.document.pages.getOrNull(index)

    // --- document ---

    /**
     * Make [doc] the open document, dropping everything that belonged to the previous one: the
     * gesture in flight, the item and page selections, the page clipboard (its clones reference the
     * outgoing note), the undo stack and every cache. [onSwapped] runs right after the swap, for
     * host state that must follow the new document before the caches are dropped.
     */
    fun install(doc: Document, onSwapped: () -> Unit = {}) {
        controller.commitTextEdit()
        controller.clearSelection()
        controller.resetGestureState() // drop the outgoing note's fling/elastic so it can't bleed in
        clearPageSelection()
        pageClipboard.clear() // clones reference the outgoing document; don't paste them into another
        state.document = doc
        onSwapped()
        history.clear()
        state.invalidateAllCaches()
        state.relayout()
    }

    // --- tools ---

    fun selectTool(t: Tool) = controller.setTool(t)

    /** Arm [target], or if it is already armed, return to the previous tool (no-op if none yet). */
    fun toggleTool(target: Tool) {
        if (controller.tool == target) controller.previousTool?.let { selectTool(it) }
        else selectTool(target)
    }

    /** Switch to the single previous tool; no-op on a fresh launch with no previous tool. */
    fun toggleToPreviousTool() {
        controller.previousTool?.let { selectTool(it) }
    }

    /** Run the action a two/three-finger tap or stylus double-tap is mapped to; "none" does nothing. */
    fun dispatchTapGesture(action: String) = when (action) {
        "undo" -> undo()
        "redo" -> redo()
        "toggle_pan" -> toggleTool(Tool.PAN)
        "toggle_eraser" -> toggleTool(Tool.ERASER)
        "toggle_previous" -> toggleToPreviousTool()
        else -> Unit
    }

    // --- history ---

    fun undo() {
        host.beforeHistoryStep() // the open typing burst is the first thing Ctrl+Z takes back
        val command = history.nextUndo
        val pagesBefore = state.document.pages.size
        val was = touchedRegions(command)
        history.undo()
        afterHistory(
            structural = state.document.pages.size != pagesBefore,
            regions = spanning(was, touchedRegions(command)),
        )
    }

    fun redo() {
        host.beforeHistoryStep()
        val command = history.nextRedo
        val pagesBefore = state.document.pages.size
        val was = touchedRegions(command)
        history.redo()
        afterHistory(
            structural = state.document.pages.size != pagesBefore,
            regions = spanning(was, touchedRegions(command)),
        )
    }

    /** Where [command]'s items sit, before or after it ran: null when it can't say, empty when there is none. */
    private fun touchedRegions(command: Command?): List<Pair<Page, Rect>>? {
        if (command == null) return emptyList()
        return command.touched(itemPageLocator())?.map { (page, item) -> page to item.paintBounds() }
    }

    private fun spanning(
        before: List<Pair<Page, Rect>>?,
        after: List<Pair<Page, Rect>>?,
    ): List<Pair<Page, Rect>>? = if (before == null || after == null) null else before + after

    /**
     * Finds the page an item sits on, for commands that hold items but not pages. The index is built
     * on first use and only then: most commands carry their own page and never ask.
     */
    private fun itemPageLocator(): (CanvasItem) -> Page? {
        var index: HashMap<CanvasItem, Page>? = null
        return { item ->
            val built = index ?: HashMap<CanvasItem, Page>().also { map ->
                for (page in state.document.pages) for (it in page.items) map[it] = page
                index = map
            }
            built[item]
        }
    }

    private fun afterHistory(structural: Boolean, regions: List<Pair<Page, Rect>>?) {
        controller.clearSelection()
        if (structural) state.relayout() // page add/remove shifts layout; page-keyed caches survive
        // The in-place repaint below reads the published flow snapshot: republish it first
        // so an undone/redone flow edit repaints at its post-history layout.
        host.republishFlow()
        // Repair the ink caches rather than dropping them — dropping blanked every visible page to
        // bare paper for a frame (the undo/redo flicker). Only AddPage/DeletePage change the page
        // set, so relayout (which re-renders the sharp viewport) is gated on that. A command that
        // named its regions repairs just those, here and now; one that couldn't hands every cached
        // page to the cache thread instead, because repainting them all inline is a stall long
        // enough to time out input on a dense note.
        if (regions == null) state.refreshAllInk() else state.repairInkRegions(regions)
        host.afterHistoryRepair()
        state.document.dirty = true
        state.clampScroll()
        host.contentChanged()
        host.requestRender()
    }

    // --- view ---

    private fun afterView() {
        host.viewChanged()
        host.requestRender()
    }

    fun zoomIn() { state.zoomByStep(true); afterView() }
    fun zoomOut() { state.zoomByStep(false); afterView() }
    fun fitWidth() { state.fitWidth(); afterView() }
    fun fitHeight() { state.fitHeight(); afterView() }
    fun fitPage() { state.fitPage(); afterView() }
    fun prevPage() { state.goToPage(state.prevPageIndex(state.currentPageIndex())); afterView() }
    fun nextPage() { state.goToPage(state.nextPageIndex(state.currentPageIndex())); afterView() }
    fun goToPage(index: Int) { state.goToPage(index); afterView() }

    // --- pages ---

    /**
     * Insert a blank page at [index] (clamped into range), sized from the page at [refIndex] so the
     * note stays uniform (falling back to A4 portrait). Undoable; relayouts and refreshes. Returns
     * the new page's final index.
     */
    private fun insertBlankPageAt(index: Int, refIndex: Int): Int {
        val pages = state.document.pages
        val ref = pages.getOrNull(refIndex) ?: pages.getOrNull(index) ?: pages.lastOrNull()
        val (w, h) = if (ref != null) ref.width to ref.height else PageSize.A4.pixels(Orientation.PORTRAIT, state.document.dpi)
        val at = index.coerceIn(0, pages.size)
        val page = Page(w, h)
        controller.clearSelection() // inserting shifts later page indices; drop any stale item selection
        pages.add(at, page)
        history.push(AddPage(state.document, page, at))
        state.document.dirty = true
        state.relayout()
        host.contentChanged()
        host.requestRender()
        return at
    }

    /** Common tail for a side-panel page edit: re-layout, refresh the chrome, repaint. */
    private fun afterPageEdit() {
        controller.clearSelection()
        state.document.dirty = true
        state.relayout()
        state.clampScroll()
        host.contentChanged()
        host.requestRender()
    }

    /** Toolbar "Add page": insert a blank page right after the current one (sized from it) and go to it. */
    fun addPage() {
        val current = state.currentPageIndex()
        val at = insertBlankPageAt(current + 1, current)
        goToPage(at)
    }

    /**
     * Append a blank page at the very end — used by the pull-past-the-end gesture. Stays at the
     * current scroll position so the user is not yanked to the new page; they can scroll to it.
     */
    fun addPageAtEnd() {
        insertBlankPageAt(state.document.pages.size, state.document.pages.lastIndex)
    }

    fun deleteCurrentPage() {
        if (state.document.pages.size <= 1) {
            host.notice(EditorNotice.KEEP_ONE_PAGE)
            return
        }
        val index = state.currentPageIndex()
        val page = state.document.pages[index]
        state.document.pages.removeAt(index)
        history.push(DeletePage(state.document, page, index))
        state.document.dirty = true
        state.invalidatePage(page)
        state.relayout()
        host.contentChanged()
        host.requestRender()
    }

    /** Insert a blank page right after [index] (sized from it) and reveal it. */
    fun insertPageAfter(index: Int) {
        goToPage(insertBlankPageAt(index + 1, index))
    }

    /** Clear all of a page's items but keep the page (and its PDF/template background). Undoable. */
    fun erasePage(index: Int) {
        val page = pageAt(index) ?: return
        if (page.items.isEmpty()) { host.notice(EditorNotice.PAGE_ALREADY_EMPTY); return }
        val removals = page.items.map { page to it }
        page.items.clear()
        history.push(EraseItems(removals))
        state.invalidatePage(page)
        afterPageEdit()
    }

    /** Deep-clone [indices] (document order) into the page clipboard for a later paste. */
    fun copyPages(indices: List<Int>) {
        val pages = indices.distinct().sorted().mapNotNull { pageAt(it) }
        if (pages.isEmpty()) return
        pageClipboard.clear()
        pages.forEach { pageClipboard.add(it.deepCopy(textMeasurer)) }
    }

    /** Copy [indices] to the clipboard then delete them (kept ≥ 1 page). */
    fun cutPages(indices: List<Int>) {
        if (indices.isEmpty()) return
        if (indices.distinct().size >= state.document.pages.size) {
            host.notice(EditorNotice.KEEP_ONE_PAGE)
            return
        }
        copyPages(indices)
        deletePages(indices)
    }

    /** Insert fresh clones of the page clipboard right after [index]; selects nothing, reveals the first. */
    fun pastePagesAfter(index: Int) {
        if (pageClipboard.isEmpty()) return
        val pages = state.document.pages
        val firstAt = (index + 1).coerceIn(0, pages.size)
        var at = firstAt
        val cmds = ArrayList<Command>()
        for (src in pageClipboard) {
            val clone = src.deepCopy(textMeasurer) // fresh clone each paste, so repeated pastes are independent
            pages.add(at, clone)
            cmds.add(AddPage(state.document, clone, at))
            at++
        }
        history.push(CompositeCommand(cmds))
        afterPageEdit()
        goToPage(firstAt)
    }

    /** Delete [indices] as one undoable edit, refusing to empty the note. */
    fun deletePages(indices: List<Int>) {
        val pages = state.document.pages
        val targets = indices.filter { it in pages.indices }.distinct().sortedDescending()
        if (targets.isEmpty()) return
        if (targets.size >= pages.size) {
            host.notice(EditorNotice.KEEP_ONE_PAGE)
            return
        }
        val cmds = ArrayList<Command>()
        for (i in targets) { // descending, so each removeAt index stays valid and DeletePage stores the original index
            val page = pages[i]
            pages.removeAt(i)
            state.invalidatePage(page)
            cmds.add(DeletePage(state.document, page, i))
        }
        history.push(CompositeCommand(cmds))
        clearPageSelection()
        afterPageEdit()
    }

    // --- images ---

    /**
     * Put an image file of [width]×[height] pixels on a page, as one undoable edit: at most 60% of
     * the page each way, centred on [atContent] (clamped onto the paper, margins included) or
     * on the current page when null. The file must stay put while the note is open; saving embeds it.
     */
    fun insertImage(file: File, width: Int, height: Int, atContent: Pt? = null): ImageItem {
        val index = (atContent?.let { state.pageIndexAtContent(it) } ?: state.currentPageIndex())
            .coerceIn(0, state.document.pages.lastIndex)
        val page = state.document.pages[index]
        val pr = state.pageRects.getOrNull(index)
        val scale = minOf(1.0, page.width * 0.6 / width, page.height * 0.6 / height)
        val w = width * scale
        val h = height * scale
        val cover = state.footprint(page)
        val rect = if (atContent != null && pr != null) {
            Rect(
                (atContent.x - pr.left - w / 2 + cover.left).coerceIn(cover.left, cover.right - w),
                (atContent.y - pr.top - h / 2 + cover.top).coerceIn(cover.top, cover.bottom - h),
                w, h,
            )
        } else {
            Rect((page.width - w) / 2.0, (page.height - h) / 2.0, w, h)
        }
        val item = ImageItem(ImageData(file, width, height), rect)
        page.items.add(item)
        state.appendToCache(page, item)
        history.push(AddItem(page, item))
        state.document.dirty = true
        host.contentChanged()
        host.requestRender()
        return item
    }

    // --- side-panel page selection (multi-select) ---

    val canPastePages: Boolean get() = pageClipboard.isNotEmpty()
    val pageSelectionCount: Int get() = selectedPages.size
    val inPageSelectionMode: Boolean get() = selectedPages.isNotEmpty()

    fun isPageSelected(index: Int): Boolean {
        val p = pageAt(index) ?: return false
        return selectedPages.any { it === p }
    }

    /** Selected page indices in document order. */
    fun selectedPageIndices(): List<Int> =
        state.document.pages.mapIndexedNotNull { i, p -> if (selectedPages.any { it === p }) i else null }

    /** Toggle a page's membership in the selection (entering selection mode on the first add). */
    fun togglePageSelection(index: Int) {
        val p = pageAt(index) ?: return
        val at = selectedPages.indexOfFirst { it === p }
        if (at >= 0) selectedPages.removeAt(at) else selectedPages.add(p)
    }

    fun clearPageSelection() {
        if (selectedPages.isNotEmpty()) selectedPages.clear()
    }

    /** Forget the copied pages (their clones reference a document that is going away). */
    fun clearPageClipboard() = pageClipboard.clear()

    // --- page styles (paper colour + ruling): document-wide ("All Pages") and per-page ---

    /** The document-wide style override; per-page styles layer on top (see [PageStyle]). */
    val documentStyle: PageStyle get() = state.document.style

    /** The current page's own style override (an empty [PageStyle] when there is no page). */
    val currentPageStyle: PageStyle
        get() = state.document.pages.getOrNull(state.currentPageIndex())?.style ?: PageStyle()

    /** Replace the document-wide ("All Pages") style override. */
    fun setDocumentStyle(style: PageStyle) {
        val prev = state.document.style
        if (prev == style) return
        state.document.style = style
        applyStyleChange(prev, style, state.document.pages.toList())
    }

    /** Replace the current page's style override. */
    fun setCurrentPageStyle(style: PageStyle) {
        val page = state.document.pages.getOrNull(state.currentPageIndex()) ?: return
        val prev = page.style
        if (prev == style) return
        page.style = style
        applyStyleChange(prev, style, listOf(page))
    }

    // --- page margins (extra paper on any edge): document-wide ("All Pages") and per-page ---

    /** The document-wide margin override; per-page margins layer on top (see [PageMargins]). */
    val documentMargins: PageMargins get() = state.document.margins

    /** The current page's own margin override (an empty [PageMargins] when there is no page). */
    val currentPageMargins: PageMargins
        get() = state.document.pages.getOrNull(state.currentPageIndex())?.margins ?: PageMargins()

    /** Replace the document-wide ("All Pages") margin override. */
    fun setDocumentMargins(margins: PageMargins) {
        if (state.document.margins == margins) return
        state.document.margins = margins
        applyMarginChange()
    }

    /** Replace the current page's margin override. */
    fun setCurrentPageMargins(margins: PageMargins) {
        val page = state.document.pages.getOrNull(state.currentPageIndex()) ?: return
        if (page.margins == margins) return
        page.margins = margins
        applyMarginChange()
    }

    /**
     * Apply a margin change and persist it (dirty -> autosave) — deliberately **not** onto the undo
     * stack, like a style change. A margin resizes the paper, so every cached surface is now the
     * wrong shape: they are dropped rather than repaired, and the document is laid out again.
     */
    private fun applyMarginChange() {
        state.invalidatePageGeometry()
        state.relayout()
        state.document.dirty = true
        host.contentChanged()
        host.requestRender()
    }

    /**
     * Apply a style change to the caches and persist it (dirty -> autosave) — deliberately **not**
     * onto the undo stack. The paper colour is filled live each frame, so a colour-only change just
     * repaints; a ruling change ([pages] are the pages it may affect) rebuilds their background caches.
     */
    private fun applyStyleChange(prev: PageStyle, next: PageStyle, pages: List<Page>) {
        val rulingChanged = prev.template != next.template ||
            prev.patternColor != next.patternColor ||
            prev.spacing != next.spacing ||
            prev.accentColor != next.accentColor ||
            prev.params != next.params ||
            prev.colors != next.colors
        if (rulingChanged) {
            if (pages.size == 1) state.invalidateBackground(pages[0]) else state.invalidateAllBackgrounds()
        } else {
            state.invalidatePaper()
        }
        state.document.dirty = true
        host.contentChanged()
        host.requestRender()
    }
}
