@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.xnotes.capi

import com.xnotes.canvas.CanvasFramePainter
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.ChromePalette
import com.xnotes.canvas.InteractionController
import com.xnotes.capi.c.xn_host
import com.xnotes.capi.c.xn_palette
import com.xnotes.capi.c.xn_pointer
import com.xnotes.capi.c.xn_pointer_event
import com.xnotes.capi.c.xn_page_style
import com.xnotes.capi.c.xn_shape_style
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.capi.c.xn_text_field
import com.xnotes.canvas.EditingField
import com.xnotes.core.pal.FontFace
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.memScoped
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.ShapeConfig
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.IntVar
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.editor.InfiniteCanvasEditor
import com.xnotes.editor.InfiniteCanvasHost
import com.xnotes.format.CanvasCodec
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.canvas.FrontInkSink
import com.xnotes.core.geometry.Rect
import com.xnotes.core.platform.pathString
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import kotlinx.cinterop.cstr
import kotlin.math.ceil
import kotlin.math.floor
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.platform.Runnable
import com.xnotes.core.platform.asInputStream
import com.xnotes.core.platform.asOutputStream
import com.xnotes.core.platform.fileOf
import com.xnotes.core.tools.Tool
import com.xnotes.editor.EditorNotice
import com.xnotes.editor.NoteEditor
import com.xnotes.editor.NoteEditorHost
import com.xnotes.format.DocumentCodec
import com.xnotes.input.PointerSample
import com.xnotes.input.SimplePointerEvent
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.use
import platform.posix.free
import platform.posix.memcpy
import platform.posix.strdup
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

/*
 * The exported C functions of xnotes.h. Handles are StableRefs to the Kotlin objects below. No
 * exception may cross into C (that would abort the process): every entry point catches and reports.
 */

/** A copy of the host's struct, owned here, so the host need not keep its own alive. */
private fun copyHost(host: CPointer<xn_host>): xn_host {
    val copy = nativeHeap.alloc<xn_host>()
    memcpy(copy.ptr, host, kotlinx.cinterop.sizeOf<xn_host>().toULong())
    return copy
}

/** Measures nothing: for reading or writing a note when the host gave no measurer. */
private object NoTextMeasurer : TextMeasurer {
    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags) =
        com.xnotes.core.geometry.Rect(0.0, 0.0, wrapWidth, font.pointSize)
    override fun lineHeight(font: FontSpec) = font.pointSize
    override fun metrics(font: FontSpec) = LineMetrics(font.pointSize * 0.8, font.pointSize * 0.2)
    override fun advances(text: String, font: FontSpec) = DoubleArray(text.length) { font.pointSize * 0.5 }
}

private object NoImageCodec : ImageCodec {
    override fun probeFile(path: String): ImageSize? = null
}

private fun codecFor(host: CPointer<xn_host>?): DocumentCodec {
    if (host == null) return DocumentCodec(NoImageCodec, NoTextMeasurer)
    val h = host.pointed
    return DocumentCodec(CImageCodec(h), CTextMeasurer(h))
}

/**
 * The slice of [page]'s PDF background inside [region] (page px), rendered by the host at [res]
 * device px per page px. The raster covers the page's content box only; a margin is paper beside it.
 */
/** Returns false when the host drew a stand-in for the exact render. */
internal fun paintPdfBackground(host: xn_host, doc: Document, page: Page, renderer: Renderer, res: Double, region: Rect): Boolean {
    val pdf = doc.pdfFile ?: return true
    val index = page.pdfPage ?: return true
    val draw = host.draw_pdf_page ?: return true
    val target = (renderer as? CRenderer)?.r ?: return true
    val l = region.left.coerceAtLeast(0.0)
    val t = region.top.coerceAtLeast(0.0)
    val r = region.right.coerceAtMost(page.width)
    val b = region.bottom.coerceAtMost(page.height)
    if (r <= l || b <= t) return true
    val rx = floor(l * res).toInt()
    val ry = floor(t * res).toInt()
    val rw = ceil(r * res).toInt() - rx
    val rh = ceil(b * res).toInt() - ry
    if (rw <= 0 || rh <= 0) return true
    return memScoped {
        // The pixel rect, mapped back to page px, so whole pixels land exactly.
        draw.invoke(
            host.ctx, target, pdf.pathString.cstr.ptr, index, ceil(page.width * res).toInt(), ceil(page.height * res).toInt(),
            rx, ry, rw, rh, rx / res, ry / res, rw / res, rh / res,
        ) != 0
    }
}

private fun reportError(error: CPointer<CPointerVar<ByteVar>>?, t: Throwable) {
    error?.set(0, strdup(t.message ?: t::class.simpleName ?: "error"))
}

internal class CNote(val document: Document)

/** An infinite canvas (.xcanvas). */
internal class CCanvasNote(val canvas: InfiniteDocument)

private fun isCanvasPath(path: String) = path.lowercase().endsWith("." + InfiniteDocument.EXTENSION)

private fun canvasCodecFor(host: CPointer<xn_host>?) = CanvasCodec(if (host == null) NoImageCodec else CImageCodec(host.pointed))

/*
 * A note handle holds a paged note (CNote) or an infinite canvas (CCanvasNote), an editor handle a
 * CEditor or a CCanvasEditor. The casts are checked: a paged-only call on a canvas throws, and
 * guard turns that into its fallback.
 */
private fun COpaquePointer.note(): CNote = asStableRef<Any>().get() as CNote

private fun COpaquePointer.canvasNote(): CCanvasNote? = asStableRef<Any>().get() as? CCanvasNote

/** The editor behind a handle, for a call that may change what is on screen: the next frame is a full one. */
private fun COpaquePointer.editor(): CEditor = (asStableRef<Any>().get() as CEditor).also { it.fullFrame = true }

/** The editor, for input, painting and timers, which say themselves what they changed. */
private fun COpaquePointer.editorQuiet(): CEditor = asStableRef<Any>().get() as CEditor

/** The canvas editor behind a handle, or null for a paged one; [quiet] as for [editorQuiet]. */
private fun COpaquePointer.canvas(quiet: Boolean = false): CCanvasEditor? =
    (asStableRef<Any>().get() as? CCanvasEditor)?.also { if (!quiet) it.editor.fullFrame = true }

@CName("xn_api_version")
fun xnApiVersion(): Int = 2

@CName("xn_free_string")
fun xnFreeString(s: CPointer<ByteVar>?) = free(s)

// --- documents ----------------------------------------------------------------------------------

@CName("xn_note_open")
fun xnNoteOpen(path: String?, workDir: String?, host: CPointer<xn_host>?, error: CPointer<CPointerVar<ByteVar>>?): COpaquePointer? =
    try {
        requireNotNull(path) { "path is null" }
        val dir = workDir?.let { fileOf(it) }
        if (isCanvasPath(path)) {
            val canvas = FileSystem.SYSTEM.source(path.toPath()).use { source ->
                canvasCodecFor(host).read(source.asInputStream(), dir)
            }
            canvas.path = path
            canvas.displayName = path.substringAfterLast('/')
            StableRef.create(CCanvasNote(canvas)).asCPointer()
        } else {
            val doc = FileSystem.SYSTEM.source(path.toPath()).use { source ->
                codecFor(host).read(source.asInputStream(), dir, dir)
            }
            doc.path = path
            doc.displayName = path.substringAfterLast('/')
            StableRef.create(CNote(doc)).asCPointer()
        }
    } catch (t: Throwable) {
        reportError(error, t)
        null
    }

@CName("xn_note_import_pdf")
fun xnNoteImportPdf(path: String?, host: CPointer<xn_host>?, error: CPointer<CPointerVar<ByteVar>>?): COpaquePointer? =
    try {
        requireNotNull(path) { "path is null" }
        val h = requireNotNull(host) { "host is null" }.pointed
        val sizes = ArrayList<Pair<Double, Double>>()
        memScoped {
            val cpath = path.cstr.ptr
            val count = h.pdf_page_count?.invoke(h.ctx, cpath) ?: error("the host reads no PDF")
            require(count >= 0) { "Not a readable PDF" }
            val w = alloc<DoubleVar>()
            val hh = alloc<DoubleVar>()
            for (i in 0 until count) {
                if (h.pdf_page_size?.invoke(h.ctx, cpath, i, w.ptr, hh.ptr) != 1) break
                sizes.add(w.value to hh.value)
            }
        }
        StableRef.create(CNote(Document.fromPdf(fileOf(path), sizes))).asCPointer()
    } catch (t: Throwable) {
        reportError(error, t)
        null
    }

@CName("xn_note_dpi")
fun xnNoteDpi(note: COpaquePointer?): Int = guard(150) { note!!.note().document.dpi }

@CName("xn_note_page_box")
fun xnNotePageBox(note: COpaquePointer?, page: Int, out: CPointer<DoubleVar>?): Int = guard(0) {
    val doc = note!!.note().document
    val p = doc.pages.getOrNull(page) ?: return@guard 0
    val box = pageBox(doc, p)
    out!![0] = box.x
    out[1] = box.y
    out[2] = box.w
    out[3] = box.h
    1
}

/** The paper the page occupies, margins included, without an editor's view. */
private fun pageBox(doc: Document, page: Page): Rect = exportState(doc).footprint(page)

/** A view-less canvas state over [doc], for its page geometry and ruling; it caches nothing. */
private fun exportState(doc: Document): CanvasState = CanvasState(doc, NoSurfaces, ChromePalette.DARK)

private object NoSurfaces : SurfaceFactory {
    override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double): RasterSurface =
        error("an export state draws no caches")
}

@CName("xn_note_paint_page")
fun xnNotePaintPage(note: COpaquePointer?, page: Int, renderer: COpaquePointer?, host: CPointer<xn_host>?, paper: UInt, pdfScale: Double) =
    guard(Unit) {
        val doc = note!!.note().document
        val p = doc.pages.getOrNull(page) ?: return@guard
        val h = host!!.pointed
        val st = exportState(doc)
        val r = CRenderer(h.renderer!!.pointed, renderer)
        val box = st.footprint(p)
        r.fillRect(box, st.effectivePageColor(p) ?: unpack(paper))
        paintPdfBackground(h, doc, p, r, pdfScale, box)  // the export host always draws exactly
        st.paintRuling(p, r, box)
        for (item in p.items) item.paint(r)
    }

@CName("xn_note_new")
fun xnNoteNew(pages: Int): COpaquePointer? =
    runCatching { StableRef.create(CNote(Document.blank(pages.coerceAtLeast(1)))).asCPointer() }.getOrNull()

@CName("xn_canvas_new")
fun xnCanvasNew(): COpaquePointer? =
    runCatching { StableRef.create(CCanvasNote(InfiniteDocument())).asCPointer() }.getOrNull()

@CName("xn_note_is_canvas")
fun xnNoteIsCanvas(note: COpaquePointer?): Int = runCatching { if (note!!.canvasNote() != null) 1 else 0 }.getOrDefault(0)

@CName("xn_note_save")
fun xnNoteSave(note: COpaquePointer?, path: String?, host: CPointer<xn_host>?, error: CPointer<CPointerVar<ByteVar>>?): Int =
    try {
        requireNotNull(note) { "note is null" }
        requireNotNull(path) { "path is null" }
        val canvas = note.canvasNote()?.canvas
        val doc = if (canvas == null) note.note().document else null
        val target = path.toPath()
        val tmp = (target.parent ?: ".".toPath()) / ".${target.name}.saving"
        val fs = FileSystem.SYSTEM
        try {
            fs.openReadWrite(tmp, mustCreate = false, mustExist = false).use { handle ->
                handle.resize(0L)
                val sink = handle.sink()
                try {
                    if (canvas != null) canvasCodecFor(host).write(canvas, sink.asOutputStream())
                    else codecFor(host).write(doc!!, sink.asOutputStream())
                } finally {
                    sink.close()
                }
                handle.flush() // on disk before the rename publishes it
            }
            try {
                fs.atomicMove(tmp, target)
            } catch (_: IOException) {
                fs.copy(tmp, target)
            }
        } finally {
            runCatching { fs.delete(tmp) }
        }
        if (canvas != null) {
            canvas.path = path
            canvas.displayName = target.name
            canvas.dirty = false
        } else {
            doc!!.path = path
            doc.displayName = target.name
            doc.dirty = false
        }
        1
    } catch (t: Throwable) {
        reportError(error, t)
        0
    }

@CName("xn_note_page_count")
fun xnNotePageCount(note: COpaquePointer?): Int = runCatching {
    if (note!!.canvasNote() != null) 0 else note.note().document.pages.size
}.getOrDefault(0)

@CName("xn_note_item_count")
fun xnNoteItemCount(note: COpaquePointer?, page: Int): Int = runCatching {
    note!!.canvasNote()?.let { return@runCatching it.canvas.itemCount }
    note.note().document.pages[page].items.size
}.getOrDefault(-1)

@CName("xn_note_is_dirty")
fun xnNoteIsDirty(note: COpaquePointer?): Int = runCatching {
    val dirty = note!!.canvasNote()?.canvas?.dirty ?: note.note().document.dirty
    if (dirty) 1 else 0
}.getOrDefault(0)

@CName("xn_note_close")
fun xnNoteClose(note: COpaquePointer?) {
    runCatching { note?.asStableRef<Any>()?.dispose() }
}

// --- editor -------------------------------------------------------------------------------------

internal class CEditor(note: CNote, private val host: xn_host) {
    private val scheduler = CScheduler(host)
    val history = History()
    private var palette: ChromePalette = ChromePalette.DARK
    private val surfaces = CSurfaceFactory(host)
    val state = CanvasState(note.document, surfaces, palette)
    val controller = InteractionController(
        state, history, CTextMeasurer(host),
        // A render right after a wet-ink update is that update's; any other may have changed anything.
        requestRender = {
            if (inkJustGrew) inkJustGrew = false else fullFrame = true
            host.request_render!!.invoke(host.ctx)
        },
        scheduler = scheduler,
        chromePalette = { palette },
        onContentChanged = { host.content_changed!!.invoke(host.ctx) },
        onViewChanged = { host.view_changed!!.invoke(host.ctx) },
        onToolChanged = { t -> host.tool_changed?.invoke(host.ctx, toolName(t)) },
        onSelectionMenu = { rect ->
            host.selection_menu?.invoke(host.ctx, if (rect != null) 1 else 0, rect?.x ?: 0.0, rect?.y ?: 0.0, rect?.w ?: 0.0, rect?.h ?: 0.0)
        },
        onTextEditStart = { field -> sendTextField(field) },
        onTextEditEnd = { host.text_edit?.invoke(host.ctx, null) },
        onContextMenu = { viewport, _, locked ->
            pressedLocked = locked
            host.context_menu?.invoke(host.ctx, viewport.x, viewport.y, if (locked != null) 1 else 0)
        },
    )

    private fun sendTextField(field: EditingField?) {
        val send = host.text_edit ?: return
        if (field == null) return send.invoke(host.ctx, null)
        memScoped {
            val f = alloc<xn_text_field>()
            fillField(f, field)
            withUtf16(field.face.id) { face, faceLen ->
                withUtf16(field.text) { text, textLen ->
                    f.face = face
                    f.face_len = faceLen
                    f.text = text
                    f.text_len = textLen
                    send.invoke(host.ctx, f.ptr)
                }
            }
        }
    }

    /** Pages whose background got a stand-in PDF render, redrawn once the host has the exact one. */
    val standIns = LinkedHashSet<Page>()

    /** The locked item the last context menu was opened on. */
    var pressedLocked: CanvasItem? = null
    val editor = NoteEditor(state, history, controller, CTextMeasurer(host), object : NoteEditorHost {
        override fun requestRender() = this@CEditor.requestRender()
        override fun contentChanged() = host.content_changed!!.invoke(host.ctx)
        override fun viewChanged() = host.view_changed!!.invoke(host.ctx)
        override fun notice(notice: EditorNotice) = host.notice!!.invoke(
            host.ctx,
            when (notice) {
                EditorNotice.KEEP_ONE_PAGE -> 1
                EditorNotice.PAGE_ALREADY_EMPTY -> 2
            },
        )
    })
    private val sharpSettle = Runnable { state.requestSharpViewport() }
    val images = CImageCodec(host)

    init {
        state.paintPageBackground = { page, renderer, res, region ->
            if (!paintPdfBackground(host, state.document, page, renderer, res, region)) standIns.add(page)
            state.paintRuling(page, renderer, region)
        }
        controller.setTool(Tool.PEN)
    }

    // --- frame damage ---
    //
    // Most frames while writing differ from the last only where the stroke grew. The controller
    // reports each growth to its front-ink sink; this one draws nothing (live is false, so the
    // canvas paints the stroke as usual) and only notes the screen rect the change can reach,
    // so the host may repaint just that. Everything else makes the next frame a full one.

    /** The next frame must repaint everything. */
    var fullFrame = true

    /** Viewport rect the frames since the last paint changed, when not [fullFrame]. */
    private var damage: Rect? = null

    /** Where the live stroke's moving part was drawn last, which its next growth repaints too. */
    private var lastInk: Rect? = null

    /** Set between a wet update and the render request that follows it. */
    private var inkJustGrew = false

    private val damageTracker = object : FrontInkSink {
        override val live = false
        override fun holding(item: CanvasItem) = false
        override fun hold(item: CanvasItem, page: Page) = false
        override fun holdFading(stroke: Stroke) = false

        override fun wet(stroke: Stroke?, pageIndex: Int?) {
            val rect = if (stroke != null && pageIndex != null) inkRect(stroke, pageIndex) else null
            if (rect == null) {
                fullFrame = true
                lastInk = null
                return
            }
            damage = listOfNotNull(damage, lastInk, rect).reduce { a, b -> a.union(b) }
            lastInk = rect
            inkJustGrew = true
        }

        override fun abandon() {
            fullFrame = true
            lastInk = null
        }
    }

    init {
        controller.frontInk = damageTracker
    }

    /**
     * The viewport rect the live stroke's next frame can differ in: its unsettled tail (points
     * before settledCount - 1 are final, which is what lets the wet cache bake them), or the whole
     * stroke when its shape can change all along (taper, straight lines, neon's glow).
     */
    private fun inkRect(stroke: Stroke, pageIndex: Int): Rect? {
        if (state.pageRects.getOrNull(pageIndex) == null) return null
        val ribbon = stroke.wetRibbon
        val local = if (ribbon != null && !stroke.config.neon && ribbon.pointCount > 0) {
            var l = Double.MAX_VALUE
            var t = Double.MAX_VALUE
            var r = -Double.MAX_VALUE
            var b = -Double.MAX_VALUE
            for (i in maxOf(0, ribbon.settledCount - 2) until ribbon.pointCount) {
                val hw = ribbon.hw(i)
                l = minOf(l, ribbon.cx(i) - hw)
                t = minOf(t, ribbon.cy(i) - hw)
                r = maxOf(r, ribbon.cx(i) + hw)
                b = maxOf(b, ribbon.cy(i) + hw)
            }
            Rect(l, t, r - l, b - t)
        } else {
            stroke.paintBounds()
        }
        val corners = listOf(Pt(local.left, local.top), Pt(local.right, local.top), Pt(local.left, local.bottom), Pt(local.right, local.bottom))
            .map { state.contentToViewport(state.fromPageSpace(pageIndex, it)) }
        // Antialiasing and rounding spill a little past the geometry.
        return Rect.bounding(corners).outset(INK_DAMAGE_PAD)
    }

    /** The rect the next frame may limit itself to, or null for a full frame. */
    fun frameDamage(): Rect? = if (fullFrame) null else damage

    fun requestRender() {
        fullFrame = true
        host.request_render!!.invoke(host.ctx)
    }

    /** Zoom or scroll moved outside a gesture: the host refreshes its view and repaints. */
    fun viewChanged() {
        host.view_changed!!.invoke(host.ctx)
        requestRender()
    }

    fun setViewport(width: Int, height: Int, pxPerDp: Double) {
        val resized = width != state.viewportW
        state.devicePxPerDp = pxPerDp
        state.viewportW = width
        state.viewportH = height
        state.relayout()
        if (!state.didInitialFit) state.establishInitialView() else if (resized) state.reflowFitWidthForResize()
        state.clampScroll()
        fullFrame = true
    }

    fun paint(target: COpaquePointer?) {
        damage = null
        fullFrame = false
        surfaces.drain()
        val r = CRenderer(host.renderer!!.pointed, target)
        val frame = CanvasFramePainter.paint(r, state)
        controller.drawOverlay(r)
        CanvasFramePainter.finish(state, frame)
        scheduler.removeCallbacks(sharpSettle)
        if (frame.sharpSettleNeeded) scheduler.postDelayed(sharpSettle, SHARP_SETTLE_MS)
    }

    fun setPalette(p: ChromePalette) {
        palette = p
        state.palette = p
        state.invalidateAllCaches()
        requestRender()
    }

    fun runTask(id: ULong) = scheduler.run(id)

    fun dispose() {
        scheduler.cancelAll()
        state.invalidateAllCaches()
        surfaces.dispose()
    }

    private companion object {
        const val SHARP_SETTLE_MS = 90L
        const val INK_DAMAGE_PAD = 4.0
    }
}


/** An infinite canvas editor over the host's services: the shared InfiniteCanvasEditor. */
internal class CCanvasEditor(note: CCanvasNote, private val host: xn_host) {
    private val scheduler = CScheduler(host)
    private val surfaces = CSurfaceFactory(host)
    val images = CImageCodec(host)
    val editor = InfiniteCanvasEditor(note.canvas, surfaces, scheduler, CTextMeasurer(host), object : InfiniteCanvasHost {
        override fun requestRender() = host.request_render!!.invoke(host.ctx)
        override fun contentChanged() = host.content_changed!!.invoke(host.ctx)
        override fun viewChanged() = host.view_changed!!.invoke(host.ctx)
        override fun toolChanged(tool: Tool) {
            host.tool_changed?.invoke(host.ctx, toolName(tool))
        }

        override fun selectionMenu(rect: Rect?) {
            host.selection_menu?.invoke(host.ctx, if (rect != null) 1 else 0, rect?.x ?: 0.0, rect?.y ?: 0.0, rect?.w ?: 0.0, rect?.h ?: 0.0)
        }

        override fun contextMenu(at: Pt, locked: CanvasItem?) {
            host.context_menu?.invoke(host.ctx, at.x, at.y, if (locked != null) 1 else 0)
        }
    })

    fun paint(target: COpaquePointer?) {
        surfaces.drain()
        editor.paint(CRenderer(host.renderer!!.pointed, target))
    }

    fun runTask(id: ULong) = scheduler.run(id)

    /** Scroll bars over the drawn extent and the view (see InfiniteCanvasEditor.scrollExtent). */
    fun scrollInfo(out: CPointer<DoubleVar>) {
        val vp = editor.viewport
        val ext = editor.scrollExtent()
        out[0] = (vp.scrollX - ext.left) * vp.zoom
        out[1] = (vp.scrollY - ext.top) * vp.zoom
        out[2] = (ext.w * vp.zoom - vp.widthPx).coerceAtLeast(0.0)
        out[3] = (ext.h * vp.zoom - vp.heightPx).coerceAtLeast(0.0)
        out[4] = vp.widthPx.toDouble()
        out[5] = vp.heightPx.toDouble()
    }

    fun scrollTo(x: Double, y: Double) {
        val vp = editor.viewport
        val ext = editor.scrollExtent()
        editor.scrollBy((ext.left + x / vp.zoom - vp.scrollX) * vp.zoom, (ext.top + y / vp.zoom - vp.scrollY) * vp.zoom)
    }

    /** The canvas background as a page style: paper, ruling, its colour and spacing. */
    fun backgroundStyle(o: xn_page_style) {
        val bg = editor.document.background
        o.has_page_color = if (bg.paperColor != null) 1 else 0
        o.page_color = bg.paperColor?.packed() ?: 0u
        o.pattern = PagePattern.entries.indexOf(bg.pattern)
        o.has_pattern_color = 1
        o.pattern_color = bg.patternColor.packed()
        o.spacing = bg.spacing
    }

    fun setBackgroundStyle(s: xn_page_style) {
        val now = editor.document.background
        editor.setBackground(
            CanvasBackground(
                pattern = PagePattern.entries.getOrNull(s.pattern) ?: now.pattern,
                patternColor = if (s.has_pattern_color != 0) unpack(s.pattern_color) else PageStyle.DEFAULT_PATTERN_COLOR,
                spacing = s.spacing.takeIf { it > 0.0 }?.coerceIn(PageStyle.MIN_SPACING, PageStyle.MAX_SPACING) ?: PageStyle.DEFAULT_SPACING,
                paperColor = if (s.has_page_color != 0) unpack(s.page_color) else null,
            ),
        )
    }

    fun dispose() {
        scheduler.cancelAll()
        editor.dispose()
        surfaces.dispose()
    }
}

/** Tools the infinite canvas has (no text boxes, flow text or screenshots there). */
private val CANVAS_TOOLS = setOf(
    Tool.PEN, Tool.DASHED, Tool.CALLIGRAPHY, Tool.SPEED, Tool.TAPER, Tool.HIGHLIGHTER, Tool.ERASER,
    Tool.PAN, Tool.SELECT, Tool.LASSO, Tool.SHAPE,
)

/** The canvas's keyboard and toolbar zoom step. */
private const val CANVAS_ZOOM_STEP = 1.25

internal fun shapeConfigOf(s: xn_shape_style, current: ShapeConfig): ShapeConfig = current.copy(
    shape = ShapeKind.entries.firstOrNull { it.id == s.kind?.toKString() } ?: current.shape,
    strokeWidth = s.width.coerceIn(DrawStyle.MIN_WIDTH, DrawStyle.MAX_WIDTH),
    fill = s.fill != 0,
    fillAlpha = s.fill_alpha.coerceIn(ShapeConfig.FILL_ALPHA_MIN, ShapeConfig.FILL_ALPHA_MAX),
    dashed = s.dashed != 0,
)

/** The shared colour (else the first's) and the first width of [styles]; returns how many there are. */
internal fun writeStyles(styles: List<DrawStyle>, color: CPointer<UIntVar>?, width: CPointer<DoubleVar>?): Int {
    val first = styles.firstOrNull() ?: return 0
    val shared = first.color.takeIf { c -> styles.all { it.color == c } } ?: first.color
    color?.pointed?.value = shared.packed()
    width?.pointed?.value = first.width
    return styles.size
}

private inline fun <T> guard(fallback: T, block: () -> T): T = try {
    block()
} catch (t: Throwable) {
    fallback
}

@CName("xn_editor_create")
fun xnEditorCreate(note: COpaquePointer?, host: CPointer<xn_host>?): COpaquePointer? = guard(null) {
    val canvas = note!!.canvasNote()
    if (canvas != null) StableRef.create(CCanvasEditor(canvas, copyHost(host!!))).asCPointer()
    else StableRef.create(CEditor(note.note(), copyHost(host!!))).asCPointer()
}

@CName("xn_editor_destroy")
fun xnEditorDestroy(editor: COpaquePointer?) = guard(Unit) {
    if (editor == null) return@guard
    val ref = editor.asStableRef<Any>()
    when (val e = ref.get()) {
        is CEditor -> e.dispose()
        is CCanvasEditor -> e.dispose()
    }
    ref.dispose()
}

@CName("xn_editor_set_viewport")
fun xnEditorSetViewport(editor: COpaquePointer?, width: Int, height: Int, pxPerDp: Double) =
    guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.setViewport(width, height, pxPerDp)
        return@guard
    }
    editor!!.editor().setViewport(width, height, pxPerDp) }

@CName("xn_editor_paint")
fun xnEditorPaint(editor: COpaquePointer?, renderer: COpaquePointer?) = guard(Unit) {
    editor!!.canvas(quiet = true)?.let { c ->
        c.paint(renderer)
        return@guard
    }
    editor!!.editorQuiet().paint(renderer) }

@CName("xn_editor_frame_damage")
fun xnEditorFrameDamage(editor: COpaquePointer?, out: CPointer<DoubleVar>?): Int = guard(0) {
    editor!!.canvas(quiet = true)?.let { c ->
        val r = c.editor.frameDamage() ?: return@guard 0
        out!![0] = r.x
        out[1] = r.y
        out[2] = r.w
        out[3] = r.h
        return@guard 1
    }
   
    val rect = editor!!.editorQuiet().frameDamage() ?: return@guard 0
    out!![0] = rect.x
    out[1] = rect.y
    out[2] = rect.w
    out[3] = rect.h
    1
}

@CName("xn_editor_run_task")
fun xnEditorRunTask(editor: COpaquePointer?, task: ULong) = guard(Unit) {
    editor!!.canvas(quiet = true)?.let { c ->
        c.runTask(task)
        return@guard
    }
    editor!!.editorQuiet().runTask(task) }

private fun event(e: xn_pointer_event): SimplePointerEvent {
    fun sample(p: xn_pointer) = PointerSample(p.id, p.x, p.y, p.pressure, p.tool_type)
    val pointers = List(e.pointer_count) { sample(e.pointers!![it]) }
    val history = List(e.history_size) { h ->
        e.history_time_ms!![h] to List(e.pointer_count) { i -> sample(e.history!![h * e.pointer_count + i]) }
    }
    return SimplePointerEvent(e.action, pointers, e.time_ms, e.action_index, e.button_state, history)
}

@CName("xn_editor_touch")
fun xnEditorTouch(editor: COpaquePointer?, e: CPointer<xn_pointer_event>?) = guard(Unit) {
    editor!!.canvas(quiet = true)?.let { c ->
        c.editor.onTouch(event(e!!.pointed))
        return@guard
    }
   
    editor!!.editorQuiet().controller.onTouch(event(e!!.pointed))
}

@CName("xn_editor_hover")
fun xnEditorHover(editor: COpaquePointer?, e: CPointer<xn_pointer_event>?) = guard(Unit) {
    editor!!.canvas(quiet = true)?.let { c ->
        c.editor.onHover(event(e!!.pointed))
        return@guard
    }
   
    editor!!.editorQuiet().controller.onHover(event(e!!.pointed))
}

@CName("xn_editor_set_tool")
fun xnEditorSetTool(editor: COpaquePointer?, toolId: String?): Int = guard(0) {
    editor!!.canvas()?.let { c ->
        val t = Tool.fromId(toolId)?.takeIf { it in CANVAS_TOOLS } ?: return@guard 0
        c.editor.tool = t
        return@guard 1
    }
   
    val tool = Tool.entries.firstOrNull { it.id == toolId } ?: return@guard 0
    editor!!.editor().editor.selectTool(tool)
    1
}

@CName("xn_editor_set_ink_color")
fun xnEditorSetInkColor(editor: COpaquePointer?, color: UInt) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.inkColor = unpack(color)
        return@guard
    }
    editor!!.editor().controller.pickInk(unpack(color)) }

@CName("xn_editor_undo")
fun xnEditorUndo(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.undo()
        return@guard
    }
   
    val e = editor!!.editor()
    if (e.history.canUndo) e.editor.undo()
}

@CName("xn_editor_redo")
fun xnEditorRedo(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.redo()
        return@guard
    }
   
    val e = editor!!.editor()
    if (e.history.canRedo) e.editor.redo()
}

@CName("xn_editor_can_undo")
fun xnEditorCanUndo(editor: COpaquePointer?): Int = guard(0) {
    editor!!.canvas(quiet = true)?.let { c ->
        return@guard if (c.editor.history.canUndo) 1 else 0
    }
    if (editor!!.editorQuiet().history.canUndo) 1 else 0 }

@CName("xn_editor_can_redo")
fun xnEditorCanRedo(editor: COpaquePointer?): Int = guard(0) {
    editor!!.canvas(quiet = true)?.let { c ->
        return@guard if (c.editor.history.canRedo) 1 else 0
    }
    if (editor!!.editorQuiet().history.canRedo) 1 else 0 }

@CName("xn_editor_escape")
fun xnEditorEscape(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.escape()
        return@guard
    }
    editor!!.editor().controller.escape() }

@CName("xn_editor_delete_selection")
fun xnEditorDeleteSelection(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.deleteSelection()
        return@guard
    }
    editor!!.editor().controller.deleteSelection() }

@CName("xn_editor_zoom_step")
fun xnEditorZoomStep(editor: COpaquePointer?, zoomIn: Int) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.zoomBy(if (zoomIn != 0) CANVAS_ZOOM_STEP else 1.0 / CANVAS_ZOOM_STEP)
        return@guard
    }
   
    val e = editor!!.editor().editor
    if (zoomIn != 0) e.zoomIn() else e.zoomOut()
}

@CName("xn_editor_zoom_at")
fun xnEditorZoomAt(editor: COpaquePointer?, x: Double, y: Double, factor: Double) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.zoomAt(x, y, factor)
        return@guard
    }
   
    val e = editor!!.editor()
    e.state.setZoomAnchored(Pt(x, y), e.state.zoom * factor)
    e.viewChanged()
}

@CName("xn_editor_scroll_by")
fun xnEditorScrollBy(editor: COpaquePointer?, dx: Double, dy: Double) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.scrollBy(dx, dy)
        return@guard
    }
   
    val e = editor!!.editor()
    e.state.scrollBy(dx, dy)
    e.viewChanged()
}

@CName("xn_editor_scroll_info")
fun xnEditorScrollInfo(editor: COpaquePointer?, out: CPointer<DoubleVar>?) = guard(Unit) {
    editor!!.canvas(quiet = true)?.let { c ->
        c.scrollInfo(out!!)
        return@guard
    }
   
    val st = editor!!.editorQuiet().state
    out!![0] = st.scrollX
    out[1] = st.scrollY
    out[2] = st.maxScrollX()
    out[3] = st.maxScrollY()
    out[4] = st.viewportW.toDouble()
    out[5] = st.viewportH.toDouble()
}

@CName("xn_editor_scroll_to")
fun xnEditorScrollTo(editor: COpaquePointer?, x: Double, y: Double) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.scrollTo(x, y)
        return@guard
    }
   
    val e = editor!!.editor()
    e.state.scrollBy(x - e.state.scrollX, y - e.state.scrollY)
    e.viewChanged()
}

@CName("xn_editor_fit_width")
fun xnEditorFitWidth(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.zoomToFit()
        return@guard
    }
    editor!!.editor().editor.fitWidth() }

@CName("xn_editor_go_to_page")
fun xnEditorGoToPage(editor: COpaquePointer?, index: Int) = guard(Unit) { editor!!.editor().editor.goToPage(index) }

@CName("xn_editor_current_page")
fun xnEditorCurrentPage(editor: COpaquePointer?): Int = guard(0) {
    editor!!.canvas(quiet = true)?.let { c ->
        return@guard 0
    }
    editor!!.editorQuiet().state.currentPageIndex() }

@CName("xn_editor_zoom")
fun xnEditorZoom(editor: COpaquePointer?): Double = guard(1.0) {
    editor!!.canvas(quiet = true)?.let { c ->
        return@guard c.editor.viewport.zoom
    }
    editor!!.editorQuiet().state.zoom }

@CName("xn_editor_set_palette")
fun xnEditorSetPalette(editor: COpaquePointer?, palette: CPointer<xn_palette>?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.palette = CPalette(palette!!.pointed)
        return@guard
    }
   
    editor!!.editor().setPalette(CPalette(palette!!.pointed))
}

@CName("xn_editor_page_style")
fun xnEditorPageStyle(editor: COpaquePointer?, allPages: Int, out: CPointer<xn_page_style>?) = guard(Unit) {
    editor!!.canvas(quiet = true)?.let { c ->
        c.backgroundStyle(out!!.pointed)
        return@guard
    }
   
    val e = editor!!.editorQuiet().editor
    val st = if (allPages != 0) e.documentStyle else e.currentPageStyle
    val o = out!!.pointed
    o.has_page_color = if (st.pageColor != null) 1 else 0
    o.page_color = st.pageColor?.packed() ?: 0u
    o.pattern = st.pattern?.let { PagePattern.entries.indexOf(it) } ?: -1
    o.has_pattern_color = if (st.patternColor != null) 1 else 0
    o.pattern_color = st.patternColor?.packed() ?: 0u
    o.spacing = st.spacing ?: 0.0
}

@CName("xn_editor_set_page_style")
fun xnEditorSetPageStyle(editor: COpaquePointer?, allPages: Int, style: CPointer<xn_page_style>?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.setBackgroundStyle(style!!.pointed)
        return@guard
    }
   
    val s = style!!.pointed
    val next = PageStyle(
        pageColor = if (s.has_page_color != 0) unpack(s.page_color) else null,
        pattern = PagePattern.entries.getOrNull(s.pattern),
        patternColor = if (s.has_pattern_color != 0) unpack(s.pattern_color) else null,
        spacing = s.spacing.takeIf { it > 0.0 }?.coerceIn(PageStyle.MIN_SPACING, PageStyle.MAX_SPACING),
    )
    val e = editor!!.editor().editor
    if (allPages != 0) e.setDocumentStyle(next) else e.setCurrentPageStyle(next)
}

@CName("xn_editor_add_page")
fun xnEditorAddPage(editor: COpaquePointer?) = guard(Unit) { editor!!.editor().editor.addPage() }

@CName("xn_editor_delete_current_page")
fun xnEditorDeleteCurrentPage(editor: COpaquePointer?) = guard(Unit) { editor!!.editor().editor.deleteCurrentPage() }

// --- pages panel ----------------------------------------------------------------------------------

@CName("xn_note_page_id")
fun xnNotePageId(note: COpaquePointer?, page: Int): Long = guard(0L) {
    note!!.note().document.pages.getOrNull(page)?.uid ?: 0L
}

/** The C array as page indices (none when it is missing or empty). */
private fun indices(pages: CPointer<IntVar>?, count: Int): List<Int> =
    if (pages == null || count <= 0) emptyList() else List(count) { pages[it] }

@CName("xn_editor_insert_page_after")
fun xnEditorInsertPageAfter(editor: COpaquePointer?, index: Int) = guard(Unit) {
    val e = editor!!.editor().editor
    e.insertPageAfter(index.coerceIn(-1, e.document.pages.lastIndex))
}

@CName("xn_editor_delete_pages")
fun xnEditorDeletePages(editor: COpaquePointer?, pages: CPointer<IntVar>?, count: Int) = guard(Unit) {
    editor!!.editor().editor.deletePages(indices(pages, count))
}

@CName("xn_editor_erase_page")
fun xnEditorErasePage(editor: COpaquePointer?, index: Int) = guard(Unit) { editor!!.editor().editor.erasePage(index) }

@CName("xn_editor_copy_pages")
fun xnEditorCopyPages(editor: COpaquePointer?, pages: CPointer<IntVar>?, count: Int) = guard(Unit) {
    editor!!.editorQuiet().editor.copyPages(indices(pages, count))
}

@CName("xn_editor_cut_pages")
fun xnEditorCutPages(editor: COpaquePointer?, pages: CPointer<IntVar>?, count: Int) = guard(Unit) {
    editor!!.editor().editor.cutPages(indices(pages, count))
}

@CName("xn_editor_can_paste_pages")
fun xnEditorCanPastePages(editor: COpaquePointer?): Int = guard(0) {
    if (editor!!.editorQuiet().editor.canPastePages) 1 else 0
}

@CName("xn_editor_paste_pages_after")
fun xnEditorPastePagesAfter(editor: COpaquePointer?, index: Int) = guard(Unit) {
    val e = editor!!.editor().editor
    e.pastePagesAfter(index.coerceIn(-1, e.document.pages.lastIndex))
}

// --- v2: tools, selection -----------------------------------------------------------------------

/** Tool ids as C strings, allocated once and kept for the life of the process. */
private val toolNames = HashMap<Tool, CPointer<ByteVar>>()

private fun toolName(t: Tool): CPointer<ByteVar>? = toolNames.getOrPut(t) { strdup(t.id)!! }

@CName("xn_editor_tool")
fun xnEditorTool(editor: COpaquePointer?): CPointer<ByteVar>? = guard(null) {
    editor!!.canvas(quiet = true)?.let { c ->
        return@guard toolName(c.editor.tool)
    }
    toolName(editor!!.editorQuiet().controller.tool) }

@CName("xn_editor_tool_width")
fun xnEditorToolWidth(editor: COpaquePointer?, toolId: String?): Double = guard(0.0) {
    editor!!.canvas(quiet = true)?.let { c ->
        return@guard c.editor.toolConfig(Tool.fromId(toolId) ?: return@guard 0.0).baseWidth
    }
   
    val tool = Tool.fromId(toolId) ?: return@guard 0.0
    editor!!.editorQuiet().controller.configFor(tool).baseWidth
}

@CName("xn_editor_set_tool_width")
fun xnEditorSetToolWidth(editor: COpaquePointer?, toolId: String?, width: Double) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        val t = Tool.fromId(toolId) ?: return@guard
        c.editor.setToolConfig(t, c.editor.toolConfig(t).copy(baseWidth = width.coerceIn(DrawStyle.MIN_WIDTH, DrawStyle.MAX_WIDTH)))
        return@guard
    }
   
    val tool = Tool.fromId(toolId) ?: return@guard
    val c = editor!!.editor().controller
    c.setToolConfig(tool, c.configFor(tool).copy(baseWidth = width.coerceIn(DrawStyle.MIN_WIDTH, DrawStyle.MAX_WIDTH)))
}

@CName("xn_editor_erase_mode")
fun xnEditorEraseMode(editor: COpaquePointer?): Int = guard(0) {
    editor!!.canvas(quiet = true)?.let { c ->
        return@guard if (c.editor.toolConfig(Tool.ERASER).eraseMode == EraseMode.AREA) 1 else 0
    }
   
    if (editor!!.editorQuiet().controller.configFor(Tool.ERASER).eraseMode == EraseMode.AREA) 1 else 0
}

@CName("xn_editor_set_erase_mode")
fun xnEditorSetEraseMode(editor: COpaquePointer?, mode: Int) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.setToolConfig(Tool.ERASER, c.editor.toolConfig(Tool.ERASER).copy(eraseMode = if (mode == 1) EraseMode.AREA else EraseMode.STROKE))
        return@guard
    }
   
    val c = editor!!.editor().controller
    c.setToolConfig(Tool.ERASER, c.configFor(Tool.ERASER).copy(eraseMode = if (mode == 1) EraseMode.AREA else EraseMode.STROKE))
}

@CName("xn_editor_set_shape_style")
fun xnEditorSetShapeStyle(editor: COpaquePointer?, style: CPointer<xn_shape_style>?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.shapeConfig = shapeConfigOf(style!!.pointed, c.editor.shapeConfig)
        return@guard
    }
   
    val c = editor!!.editor().controller
    c.shapeConfig = shapeConfigOf(style!!.pointed, c.shapeConfig)
}

internal fun fillField(f: xn_text_field, field: EditingField) {
    f.x = field.x
    f.y = field.y
    f.width = field.width
    f.height = field.height
    f.font_px = field.fontPx
    f.zoom = field.zoom
    f.color = field.rgba.packed()
    f.rotation = field.rotation
    f.face = null
    f.face_len = 0
    f.text = null
    f.text_len = 0
}

@CName("xn_editor_text_field")
fun xnEditorTextField(editor: COpaquePointer?, out: CPointer<xn_text_field>?): Int = guard(0) {
    val field = editor!!.editorQuiet().controller.editingField() ?: return@guard 0
    fillField(out!!.pointed, field)
    1
}

@CName("xn_editor_text_update")
fun xnEditorTextUpdate(editor: COpaquePointer?, text: CPointer<UShortVar>?, len: Int) = guard(Unit) {
    val e = editor!!.editor()
    e.controller.updateEditingText(if (text == null || len <= 0) "" else CharArray(len) { text[it].toInt().toChar() }.concatToString())
    e.requestRender()
}

@CName("xn_editor_text_commit")
fun xnEditorTextCommit(editor: COpaquePointer?) = guard(Unit) { editor!!.editor().controller.commitTextEdit() }

@CName("xn_editor_set_text_face")
fun xnEditorSetTextFace(editor: COpaquePointer?, face: String?) = guard(Unit) {
    editor!!.editor().controller.setTextFace(FontFace.fromId(face))
}

@CName("xn_editor_set_text_size")
fun xnEditorSetTextSize(editor: COpaquePointer?, size: Double) = guard(Unit) { editor!!.editor().controller.setTextPointSize(size) }

@CName("xn_editor_refresh_backgrounds")
fun xnEditorRefreshBackgrounds(editor: COpaquePointer?) = guard(Unit) {
    val e = editor!!.editor()
    val pages = e.standIns.toList()
    e.standIns.clear()
    // Just the background layer (and the sharp view over it); ink caches stay.
    for (page in pages) e.state.refreshBackground(page)
    e.requestRender()
}

@CName("xn_editor_insert_image")
fun xnEditorInsertImage(editor: COpaquePointer?, path: String?, atPoint: Int, x: Double, y: Double): Int = guard(0) {
    editor!!.canvas()?.let { c ->
        val size = c.images.probeFile(path ?: return@guard 0) ?: return@guard 0
        if (size.width <= 0 || size.height <= 0) return@guard 0
        c.editor.insertImage(fileOf(path), size.width, size.height, if (atPoint != 0) c.editor.viewport.viewportToContent(Pt(x, y)) else null)
        return@guard 1
    }
   
    val e = editor!!.editor()
    val size = e.images.probeFile(path ?: return@guard 0) ?: return@guard 0
    if (size.width <= 0 || size.height <= 0) return@guard 0
    val at = if (atPoint != 0) e.state.viewportToContent(Pt(x, y)) else null
    e.editor.insertImage(fileOf(path), size.width, size.height, at)
    1
}

@CName("xn_editor_has_selection")
fun xnEditorHasSelection(editor: COpaquePointer?): Int = guard(0) {
    editor!!.canvas(quiet = true)?.let { c ->
        return@guard if (c.editor.hasSelection) 1 else 0
    }
    if (editor!!.editorQuiet().controller.hasSelection) 1 else 0 }

@CName("xn_editor_selection_rect")
fun xnEditorSelectionRect(editor: COpaquePointer?, out: CPointer<DoubleVar>?): Int = guard(0) {
    editor!!.canvas(quiet = true)?.let { c ->
        val r = c.editor.selectionMenuRect() ?: return@guard 0
        out!![0] = r.x
        out[1] = r.y
        out[2] = r.w
        out[3] = r.h
        return@guard 1
    }
   
    val rect = editor!!.editorQuiet().controller.selectionMenuRect() ?: return@guard 0
    out!![0] = rect.x
    out[1] = rect.y
    out[2] = rect.w
    out[3] = rect.h
    1
}

@CName("xn_editor_select_all")
fun xnEditorSelectAll(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.selectAll()
        return@guard
    }
    editor!!.editor().controller.selectAll() }

@CName("xn_editor_cut")
fun xnEditorCut(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.cutSelection()
        return@guard
    }
    editor!!.editor().controller.cutSelection() }

@CName("xn_editor_copy")
fun xnEditorCopy(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.copySelection()
        return@guard
    }
    editor!!.editor().controller.copySelection() }

@CName("xn_editor_duplicate")
fun xnEditorDuplicate(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.duplicateSelection()
        return@guard
    }
    editor!!.editor().controller.duplicateSelection() }

@CName("xn_editor_bring_to_front")
fun xnEditorBringToFront(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.bringToFront()
        return@guard
    }
    editor!!.editor().controller.bringToFront() }

@CName("xn_editor_lock_selection")
fun xnEditorLockSelection(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.lockSelection()
        return@guard
    }
    editor!!.editor().controller.lockSelection() }

@CName("xn_editor_unlock_pressed")
fun xnEditorUnlockPressed(editor: COpaquePointer?) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.unlockPressed()
        return@guard
    }
   
    val e = editor!!.editor()
    val item = e.pressedLocked ?: return@guard
    e.pressedLocked = null
    e.controller.unlockItem(item)
    e.state.invalidateAllCaches() // the item's lock marker goes away
    e.requestRender()
}

@CName("xn_editor_can_paste")
fun xnEditorCanPaste(editor: COpaquePointer?): Int = guard(0) {
    editor!!.canvas(quiet = true)?.let { c ->
        return@guard if (c.editor.canPaste) 1 else 0
    }
    if (editor!!.editorQuiet().controller.hasClipboardItems()) 1 else 0 }

@CName("xn_editor_paste_at")
fun xnEditorPasteAt(editor: COpaquePointer?, x: Double, y: Double) = guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.pasteAt(c.editor.viewport.viewportToContent(Pt(x, y)))
        return@guard
    }
   
    val e = editor!!.editor()
    e.controller.pasteItemsAt(e.state.viewportToContent(Pt(x, y)))
}

@CName("xn_editor_selection_style")
fun xnEditorSelectionStyle(editor: COpaquePointer?, color: CPointer<UIntVar>?, width: CPointer<DoubleVar>?): Int = guard(0) {
    editor!!.canvas(quiet = true)?.let { c ->
        val styles = c.editor.selectionStyles()
        return@guard writeStyles(styles, color, width)
    }
   
    writeStyles(editor!!.editorQuiet().controller.selectionStyles(), color, width)
}

@CName("xn_editor_restyle_selection")
fun xnEditorRestyleSelection(editor: COpaquePointer?, setColor: Int, color: UInt, setWidth: Int, width: Double, preview: Int) =
    guard(Unit) {
    editor!!.canvas()?.let { c ->
        c.editor.restyleSelection(if (setColor != 0) unpack(color) else null, if (setWidth != 0) width.coerceIn(DrawStyle.MIN_WIDTH, DrawStyle.MAX_WIDTH) else null, preview != 0)
        return@guard
    }
   
        editor!!.editor().controller.restyleSelection(
            if (setColor != 0) unpack(color) else null,
            if (setWidth != 0) width.coerceIn(DrawStyle.MIN_WIDTH, DrawStyle.MAX_WIDTH) else null,
            preview != 0,
        )
    }

@Suppress("unused")
private fun keepImports(p: CPointer<ByteVar>?) = p?.toKString()

/** Test-only (not in xnotes.h): a full collection, so tests can make cleaners run. */
@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
@CName("xn_test_collect_garbage")
fun xnTestCollectGarbage() = guard(Unit) { kotlin.native.runtime.GC.collect() }
