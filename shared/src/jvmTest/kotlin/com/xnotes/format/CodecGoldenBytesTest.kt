package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.stroke.Sample
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Saved files must not change by a byte when the IO underneath the codecs is rewritten for
 * Kotlin/Native. The digests below were recorded from the java.io/java.util.zip implementation;
 * each entry is compared by name, and the whole bundle too.
 */
class CodecGoldenBytesTest {
    private val dir = Files.createTempDirectory("golden").toFile()

    @After fun cleanup() {
        dir.deleteRecursively()
    }

    private fun image(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    /** Text that exercises every JSON escape and multi-byte UTF-8, including a surrogate pair. */
    private val text = "Привет, \"мир\"\\ / \t tab\r\n\u0001\u001f 😀 end"

    private fun note(): Document {
        val doc = Document(dpi = 150)
        val page = Page(1240.0, 1754.0)
        page.items.add(
            Stroke(
                Tool.CALLIGRAPHY,
                ToolConfig(6.0, true, 0.40, 0.60, Rgba(0, 230, 118, 255)),
                MutableList(40) { Sample(10.0 + it * 3.125, 20.0 + (it % 7) * 1.1, 0.3 + (it % 5) * 0.1) },
            ),
        )
        page.items.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(1e6 + 0.5, -0.004, 1.0))))
        page.items.add(ImageItem(ImageData(image("a.png", byteArrayOf(9, 8, 7, 6, 5)), 64, 48), Rect(5.0, 6.0, 64.0, 48.0), angle = 0.5))
        page.items.add(ImageItem(ImageData(image("b.svg", "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()), 10, 10), Rect(1.0, 2.0, 10.0, 10.0)))
        page.items.add(TextItem(Pt(100.0, 110.0), width = 250.0, text = text, rgba = Rgba(236, 236, 236, 255), pointSize = 13.0, measurer = FakeTextMeasurer()))
        page.items.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(50.0, 30.0), Rgba(255, 92, 92, 255), 3.0, Rgba(255, 92, 92, 64)))
        doc.pages.add(page)
        doc.pages.add(Page(1240.0, 1754.0))
        doc.bookmarks.add(Bookmark(0, text))
        return doc
    }

    private fun canvas(): InfiniteDocument = InfiniteDocument().apply {
        add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), MutableList(25) { Sample(-500.0 + it * 7.5, 1e5 + it, 0.5) }))
        add(TextItem(Pt(-10.0, 20.0), width = 200.0, text = text, rgba = Rgba(1, 2, 3, 255), pointSize = 11.0, measurer = FakeTextMeasurer()))
        add(ImageItem(ImageData(image("c.png", byteArrayOf(1, 2, 3)), 3, 1), Rect(0.0, 0.0, 30.0, 10.0)))
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * [bundle] with every entry's DOS modification time and date zeroed, in the central directory
     * and in each local header: the only bytes that differ between two saves of the same document.
     */
    private fun withoutTimestamps(bundle: ByteArray): ByteArray {
        val b = bundle.copyOf()
        fun u16(at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
        fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        fun zero(at: Int, n: Int) { for (i in at until at + n) b[i] = 0 }
        var eocd = b.size - 22
        while (eocd >= 0 && u32(eocd) != 0x06054b50L) eocd--
        check(eocd >= 0) { "no end of central directory" }
        var p = u32(eocd + 16).toInt()
        repeat(u16(eocd + 10)) {
            check(u32(p) == 0x02014b50L) { "bad central directory entry at $p" }
            zero(p + 12, 4)
            val local = u32(p + 42).toInt()
            check(u32(local) == 0x04034b50L) { "bad local header at $local" }
            zero(local + 10, 4)
            p += 46 + u16(p + 28) + u16(p + 30) + u16(p + 32)
        }
        return b
    }

    /** Entry name -> digest of its uncompressed bytes, in bundle order. */
    private fun entries(bundle: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        ZipInputStream(bundle.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                out += e.name to sha(zis.readBytes())
            }
        }
        return out
    }

    private fun noteBytes(): ByteArray =
        ByteArrayOutputStream().also { DocumentCodec(FakeImageCodec(), FakeTextMeasurer()).write(note(), it) }.toByteArray()

    private fun canvasBytes(): ByteArray =
        ByteArrayOutputStream().also { CanvasCodec(FakeImageCodec(), FakeTextMeasurer()).write(canvas(), it) }.toByteArray()

    @Test fun noteBundleIsByteStable() {
        val bytes = noteBytes()
        assertEquals(NOTE_ENTRIES, entries(bytes).toString())
        assertEquals(NOTE_BUNDLE, sha(withoutTimestamps(bytes)))
    }

    @Test fun canvasBundleIsByteStable() {
        val bytes = canvasBytes()
        assertEquals(CANVAS_ENTRIES, entries(bytes).toString())
        assertEquals(CANVAS_BUNDLE, sha(withoutTimestamps(bytes)))
    }

    private companion object {
        const val NOTE_BUNDLE = "e86fd4541befc5ae1772a89eaa8ec587a0aaaeff765dbbd4ffe8a33af2984727"
        const val NOTE_ENTRIES =
            "[(assets/image-000.png, af5f6f5c5967c377e49193eca1ee0b98300a171cd3165c9a2410e8fb7c028674), (assets/image-001.svg, a87cba1d08bc5397e7f459b9339b2427c42d824e223839840731f0a2cdd42f69), (manifest.json, ff665cf57308b2cd5d6baa12f5c8f9c0d663451b3fa017726b488e01895402fb)]"
        const val CANVAS_BUNDLE = "36e7366ef8e32c411c3a70b6ca0ad644cd80a7df320b65bb9a4d22c5f27006e1"
        const val CANVAS_ENTRIES =
            "[(assets/image-000.png, 039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81), (manifest.json, 33abbc9199eb460cee30f613955cdb644c1c33deeae752253dfac8c83e1ee5a7)]"
    }
}
