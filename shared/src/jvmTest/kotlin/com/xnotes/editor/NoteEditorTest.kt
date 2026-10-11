package com.xnotes.editor

import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.FakeUiScheduler
import com.xnotes.canvas.InteractionController
import com.xnotes.canvas.TestPalette
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorTest {
    private val notices = ArrayList<EditorNotice>()
    private var contentChanges = 0
    private var viewChanges = 0
    private var republished = 0

    private val state = CanvasState(Document.blank(3), FakeSurfaceFactory(), TestPalette()).apply {
        viewportW = 800
        viewportH = 1000
        relayout()
    }
    private val history = History()
    private val controller = InteractionController(state, history, FakeTextMeasurer(), requestRender = {}, scheduler = FakeUiScheduler())
    private val editor = NoteEditor(state, history, controller, FakeTextMeasurer(), object : NoteEditorHost {
        override fun requestRender() = Unit
        override fun contentChanged() { contentChanges++ }
        override fun viewChanged() { viewChanges++ }
        override fun notice(notice: EditorNotice) { notices += notice }
        override fun republishFlow() { republished++ }
    })

    private val pages get() = state.document.pages

    private fun stroke() =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(10.0, 10.0, 1.0), Sample(40.0, 30.0, 1.0)))

    @Test fun addingAPageInsertsAfterTheCurrentOneAndUndoes() {
        editor.addPage()
        assertEquals(4, pages.size)
        assertEquals(1, state.currentPageIndex())
        assertTrue(state.document.dirty)
        assertTrue(contentChanges > 0 && viewChanges > 0)

        editor.undo()
        assertEquals(3, pages.size)
        assertEquals(1, republished)
        editor.redo()
        assertEquals(4, pages.size)
    }

    @Test fun theLastPageCannotBeDeleted() {
        editor.deletePages(listOf(0, 1))
        assertEquals(1, pages.size)
        editor.deleteCurrentPage()
        assertEquals(1, pages.size)
        assertEquals(listOf(EditorNotice.KEEP_ONE_PAGE), notices)
    }

    @Test fun deletingSeveralPagesIsOneUndoStep() {
        val kept = pages[1]
        editor.deletePages(listOf(0, 2))
        assertEquals(listOf(kept), pages.toList())
        editor.undo()
        assertEquals(3, pages.size)
        assertSame(kept, pages[1])
    }

    @Test fun erasingAPageClearsItsItemsUndoably() {
        editor.erasePage(0)
        assertEquals(listOf(EditorNotice.PAGE_ALREADY_EMPTY), notices)

        pages[0].items += stroke()
        editor.erasePage(0)
        assertTrue(pages[0].items.isEmpty())
        editor.undo()
        assertEquals(1, pages[0].items.size)
    }

    @Test fun pastedPagesAreIndependentClones() {
        pages[0].items += stroke()
        editor.copyPages(listOf(0))
        assertTrue(editor.canPastePages)
        editor.pastePagesAfter(2)
        editor.pastePagesAfter(3)
        assertEquals(5, pages.size)
        assertNotSame(pages[0], pages[3])
        assertNotSame(pages[3], pages[4])
        assertEquals(1, pages[3].items.size)
    }

    @Test fun pageSelectionFollowsPagesByIdentity() {
        editor.togglePageSelection(2)
        editor.togglePageSelection(0)
        assertEquals(listOf(0, 2), editor.selectedPageIndices())
        editor.insertPageAfter(0) // shifts the old page 2 to index 3
        assertEquals(listOf(0, 3), editor.selectedPageIndices())
        editor.togglePageSelection(0)
        assertEquals(1, editor.pageSelectionCount)
        editor.clearPageSelection()
        assertFalse(editor.inPageSelectionMode)
    }

    @Test fun installingADocumentDropsTheOldOnesState() {
        editor.copyPages(listOf(0))
        editor.togglePageSelection(1)
        editor.addPage()
        val next = Document.blank(2)
        var swappedTo: Document? = null
        editor.install(next) { swappedTo = state.document }

        assertSame(next, state.document)
        assertSame(next, swappedTo)
        assertFalse(history.canUndo)
        assertFalse(editor.canPastePages)
        assertFalse(editor.inPageSelectionMode)
    }

    @Test fun anUndoOfAnItemRepairsInPlaceAndMarksTheNoteEdited() {
        val page = pages[0]
        val ink = stroke()
        page.items += ink
        history.push(AddItem(page, ink))
        state.document.dirty = false

        editor.undo()
        assertTrue(page.items.isEmpty())
        assertTrue(state.document.dirty)
        assertFalse(history.canUndo)
    }

    @Test fun styleAndMarginChangesAreNotUndoable() {
        editor.setDocumentStyle(PageStyle(template = PagePattern.GRID.id))
        assertEquals(PagePattern.GRID.id, editor.documentStyle.template)
        editor.setCurrentPageMargins(PageMargins(top = 40.0))
        assertEquals(40.0, editor.currentPageMargins.top!!, 0.0)
        assertTrue(state.document.dirty)
        assertFalse(history.canUndo)
    }

    @Test fun tapGesturesToggleBetweenTools() {
        editor.selectTool(Tool.PEN)
        editor.dispatchTapGesture("toggle_eraser")
        assertEquals(Tool.ERASER, controller.tool)
        editor.dispatchTapGesture("toggle_eraser")
        assertEquals(Tool.PEN, controller.tool)
        editor.dispatchTapGesture("none")
        assertEquals(Tool.PEN, controller.tool)
    }
}
