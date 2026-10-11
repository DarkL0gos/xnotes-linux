package com.xnotes.session

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionStoreTest {
    private val root = Files.createTempDirectory("session-test").toFile()
    private val dir = File(root, "session")
    private val work = File(root, "work").apply { mkdirs() }
    private val store = SessionStore(dir, DocumentCodec(FakeImageCodec(), FakeTextMeasurer()), CanvasCodec(FakeImageCodec(), FakeTextMeasurer()))

    @After fun cleanup() {
        root.deleteRecursively()
    }

    private fun stroke(x: Double) =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, 1.0, 1.0), Sample(x + 5, 6.0, 1.0)))

    private fun note(strokes: Int, path: String? = "/notes/a.xnote"): Document =
        Document.blank(2).apply {
            repeat(strokes) { pages[0].items.add(stroke(it * 10.0)) }
            this.path = path
            displayName = path?.substringAfterLast('/')
            dirty = true
        }

    private fun documentFiles() = dir.listFiles()!!.map { it.name }.filter { it.startsWith("document-") }

    @Test fun noSessionLoadsNothing() {
        assertFalse(store.exists())
        assertNull(store.load(work))
    }

    @Test fun noteRoundTripsWithIdentityDirtyFlagAndView() {
        store.save(SessionDocument.Note(note(3)), mapOf("zoom" to 1.25, "scrollY" to 840.0))

        val snapshot = store.load(work)!!
        val doc = (snapshot.document as SessionDocument.Note).document
        assertEquals(2, doc.pages.size)
        assertEquals(3, doc.pages[0].items.size)
        assertEquals("/notes/a.xnote", doc.path)
        assertEquals("a.xnote", doc.displayName)
        assertTrue(doc.dirty)
        assertEquals(mapOf("zoom" to 1.25, "scrollY" to 840.0), snapshot.view)
    }

    @Test fun anUntitledNoteKeepsNoPath() {
        store.save(SessionDocument.Note(note(1, path = null)))
        val doc = (store.load(work)!!.document as SessionDocument.Note).document
        assertNull(doc.path)
        assertNull(doc.displayName)
    }

    @Test fun canvasRoundTrips() {
        val canvas = InfiniteDocument().apply {
            add(stroke(0.0))
            add(stroke(40.0))
            path = "/notes/board.xcanvas"
            displayName = "board.xcanvas"
            dirty = true
        }
        store.save(SessionDocument.Canvas(canvas))

        val doc = (store.load(work)!!.document as SessionDocument.Canvas).document
        assertEquals(2, doc.itemCount)
        assertEquals("/notes/board.xcanvas", doc.path)
        assertTrue(doc.dirty)
        assertTrue(documentFiles().single().endsWith(".xcanvas"))
    }

    @Test fun eachSaveLeavesExactlyOneDocument() {
        store.save(SessionDocument.Note(note(1)))
        store.save(SessionDocument.Note(note(2)))
        store.save(SessionDocument.Canvas(InfiniteDocument()))
        assertEquals(1, documentFiles().size)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun viewOnlySaveKeepsTheDocumentFile() {
        store.save(SessionDocument.Note(note(2)), mapOf("zoom" to 1.0))
        val before = documentFiles()
        store.save(SessionDocument.Note(note(2)), mapOf("zoom" to 2.0), writeDocument = false)

        assertEquals(before, documentFiles())
        assertEquals(2.0, store.load(work)!!.view["zoom"]!!, 0.0)
    }

    @Test fun viewOnlySaveStillWritesWhenTheKindChanged() {
        store.save(SessionDocument.Note(note(2)))
        store.save(SessionDocument.Canvas(InfiniteDocument()), writeDocument = false)
        assertTrue(store.load(work)!!.document is SessionDocument.Canvas)
    }

    /**
     * A crash after the new document is written but before the metadata commit must restore the
     * previous session intact — never the new content under the old file's identity.
     */
    @Test fun crashBeforeCommitRestoresThePreviousSession() {
        store.save(SessionDocument.Note(note(1, path = "/notes/a.xnote")))
        val committed = documentFiles().single()
        // What an interrupted save of another note leaves behind: its document, no metadata.
        File(dir, committed).copyTo(File(dir, "document-orphan.xnote"))
        File(dir, "session.json.tmp").writeText("{\"kind\":\"note\",\"fi")

        val doc = (store.load(work)!!.document as SessionDocument.Note).document
        assertEquals("/notes/a.xnote", doc.path)
        assertEquals(1, doc.pages[0].items.size)

        store.save(SessionDocument.Note(note(1)))
        assertEquals(1, documentFiles().size)
        assertFalse(File(dir, "session.json.tmp").exists())
    }

    @Test fun corruptMetadataRestoresNothing() {
        store.save(SessionDocument.Note(note(1)))
        File(dir, "session.json").writeText("{ not json")
        assertFalse(store.exists())
        assertNull(store.load(work))
    }

    @Test fun metadataCannotPointOutsideTheSessionDir() {
        File(root, "outside.xnote").writeText("x")
        dir.mkdirs()
        File(dir, "session.json").writeText("{\"kind\":\"note\",\"file\":\"../outside.xnote\",\"dirty\":true}")
        assertNull(store.load(work))
    }

    @Test fun corruptDocumentRestoresNothing() {
        store.save(SessionDocument.Note(note(1)))
        File(dir, documentFiles().single()).writeText("not a zip")
        assertNull(store.load(work))
    }

    @Test fun clearRemovesEverything() {
        store.save(SessionDocument.Note(note(1)))
        store.clear()
        assertFalse(store.exists())
        assertNull(store.load(work))
        assertTrue(documentFiles().isEmpty())
    }
}
