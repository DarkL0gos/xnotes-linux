package com.xnotes.format

import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pdf.MarkupPainter
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.core.pdf.TextQuad
import com.xnotes.core.platform.epochMillisOf
import com.xnotes.core.platform.fixedDecimal
import com.xnotes.core.platform.utcFieldsOf

/**
 * Reads/writes a note's text markups as `markups.xfdf` inside the `.xnote` bundle: XFDF (ISO
 * 19444-1), one text markup annotation per markup on the note page it lies on, which is the page
 * of the same number in an exported PDF. For other tools `coords` and `rect` are in the PDF user
 * space of the page's source page (a page of no PDF: its own points, y up); xnotes reads back
 * `xnotes:quads`, in points as displayed, so loading never needs the PDF. Writing is a
 * deterministic serializer; reading is forgiving: unknown elements are skipped, an annotation
 * missing what xnotes needs is dropped, and malformed XML loads none.
 */
object MarkupsXfdf {
    const val ENTRY_NAME = "markups.xfdf"

    /** The largest entry read back. */
    const val MAX_BYTES = 32 * 1024 * 1024

    private const val NS_XFDF = "http://ns.adobe.com/xfdf/"
    private const val NS_XNOTES = "urn:xnotes:markups:1.0"

    /** Taken by a markup that names none, as Acrobat's default highlight. */
    private val DEFAULT_COLOR = Rgba(255, 255, 0)
    private const val DEFAULT_INTENSITY = 0.5

    // --- write ---

    /** Every page's markups, bottom first; [geometry] maps note page i's display points into PDF user space. */
    fun write(pages: List<Page>, geometry: (Int) -> PdfPageGeometry): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<xfdf xmlns=\"").append(NS_XFDF).append("\" xmlns:xnotes=\"").append(NS_XNOTES)
        append("\" xml:space=\"preserve\">\n")
        append(" <annots>\n")
        for ((i, page) in pages.withIndex()) {
            val marks = page.markups
            if (marks.isEmpty()) continue
            val g = geometry(i)
            for (m in marks) appendMarkup(i, m, g)
        }
        append(" </annots>\n")
        append("</xfdf>\n")
    }.encodeToByteArray()

    private fun StringBuilder.appendMarkup(page: Int, m: TextMarkup, g: PdfPageGeometry) {
        val coords = DoubleArray(8 * m.quads.size)
        var k = 0
        for (q in m.quads) {
            for (p in MarkupPainter.corners(q)) {
                val u = g.toUser(p.x, p.y)
                coords[k++] = u.x
                coords[k++] = u.y
            }
        }
        append("  <").append(m.type.id)
        attr("page", page.toString())
        attr("name", m.id)
        attr("color", "#" + listOf(m.color.r, m.color.g, m.color.b).joinToString("") { (it and 0xFF).toString(16).padStart(2, '0') })
        if (m.type == MarkupType.HIGHLIGHT) attr("opacity", num(m.intensity, 3))
        attr("flags", "print")
        attr("creationdate", date(m.created))
        attr("date", date(m.modified))
        if (coords.isNotEmpty()) {
            var l = Double.MAX_VALUE
            var b = Double.MAX_VALUE
            var r = -Double.MAX_VALUE
            var t = -Double.MAX_VALUE
            for (i in coords.indices step 2) {
                l = minOf(l, coords[i])
                r = maxOf(r, coords[i])
                b = minOf(b, coords[i + 1])
                t = maxOf(t, coords[i + 1])
            }
            attr("rect", listOf(l, b, r, t).joinToString(",") { num(it, 2) })
            attr("coords", coords.joinToString(",") { num(it, 2) })
        }
        if (m.type != MarkupType.HIGHLIGHT) attr("xnotes:intensity", num(m.intensity, 3))
        attr("xnotes:quads", m.quads.joinToString(",") { q ->
            "${num(q.left.toDouble(), 3)},${num(q.top.toDouble(), 3)},${num(q.right.toDouble(), 3)},${num(q.bottom.toDouble(), 3)},${q.quarter}"
        })
        attr("xnotes:text", m.text)
        val note = m.note
        if (note == null) {
            append("/>\n")
        } else {
            append(">\n   <contents>")
            text(note)
            append("</contents>\n  </").append(m.type.id).append(">\n")
        }
    }

    private fun StringBuilder.attr(name: String, value: String) {
        append(' ').append(name).append("=\"")
        for (c in value) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                // Attribute values fold line breaks and tabs to spaces unless they are references.
                c == '\n' -> append("&#10;")
                c == '\r' -> append("&#13;")
                c == '\t' -> append("&#9;")
                xmlChar(c) -> append(c)
            }
        }
        append('"')
    }

    private fun StringBuilder.text(value: String) {
        for (c in value.replace("\r\n", "\n")) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '\r' -> append("&#13;")
                c == '\n' || c == '\t' || xmlChar(c) -> append(c)
            }
        }
    }

    /** Whether XML 1.0 can hold [c] as written (surrogate halves stay, in pairs from Kotlin strings). */
    private fun xmlChar(c: Char): Boolean = c >= ' ' && c != '￾' && c != '￿'

    private fun num(v: Double, places: Int): String {
        val f = fixedDecimal(v, places)
        val s = if ('.' in f) f.trimEnd('0').trimEnd('.') else f
        return if (s == "-0") "0" else s
    }

    private fun date(epochMs: Long): String =
        "D:" + utcFieldsOf(epochMs).mapIndexed { i, v -> v.toString().padStart(if (i == 0) 4 else 2, '0') }.joinToString("") + "Z"

    // --- read ---

    /** Puts the markups in [bytes] on [pages], on top of any there. */
    fun readInto(pages: List<Page>, bytes: ByteArray) {
        val root = parse(bytes) ?: return
        val byPage = LinkedHashMap<Int, ArrayList<TextMarkup>>()
        for (el in markupElements(root)) {
            val m = markupOf(el, pages.size) ?: continue
            byPage.getOrPut(m.first) { ArrayList() } += m.second
        }
        for ((i, marks) in byPage) pages[i].markups = pages[i].markups + marks
    }

    // A note is a file from anywhere: refuse document types, so no entity is declared in it.
    private fun parse(bytes: ByteArray): XmlElement? = try {
        if ("<!DOCTYPE" in bytes.decodeToString()) null else XmlParser.parse(bytes)
    } catch (_: Exception) {
        null
    }

    private fun markupElements(root: XmlElement): List<XmlElement> {
        val out = ArrayList<XmlElement>()
        fun walk(n: XmlElement) {
            for (child in n.childElements()) {
                if (MarkupType.fromId(child.localName) != null) out += child else walk(child)
            }
        }
        walk(root)
        return out
    }

    /** [el]'s markup and the note page it is on; null when it lacks what xnotes needs. */
    private fun markupOf(el: XmlElement, pageCount: Int): Pair<Int, TextMarkup>? {
        val type = MarkupType.fromId(el.localName) ?: return null
        val page = plain(el, "page")?.trim()?.toIntOrNull()?.takeIf { it in 0 until pageCount } ?: return null
        val quads = quadsOf(own(el, "quads")) ?: return null
        val intensity = (own(el, "intensity") ?: plain(el, "opacity"))?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?.coerceIn(0.1, 1.0) ?: DEFAULT_INTENSITY
        val created = dateOf(plain(el, "creationdate"))
        val modified = dateOf(plain(el, "date"))
        val note = childText(el, "contents")?.takeIf { it.isNotEmpty() }
        return page to TextMarkup(
            id = plain(el, "name")?.takeIf { it.isNotBlank() } ?: TextMarkup.newId(),
            type = type,
            color = colorOf(plain(el, "color")) ?: DEFAULT_COLOR,
            intensity = intensity,
            quads = quads,
            text = own(el, "text").orEmpty(),
            note = note,
            created = created ?: modified ?: 0L,
            modified = modified ?: created ?: 0L,
        )
    }

    /** An attribute of XFDF's own, which takes no prefix. */
    private fun plain(el: XmlElement, name: String): String? =
        el.attributes.firstOrNull { it.qualifiedName == name }?.value

    /** An `xnotes:` attribute. */
    private fun own(el: XmlElement, name: String): String? =
        el.attributes.firstOrNull { it.namespaceUri == NS_XNOTES && it.localName == name }?.value

    private fun childText(el: XmlElement, name: String): String? =
        el.childElements().firstOrNull { it.localName == name }?.textContent

    private fun quadsOf(s: String?): List<TextQuad>? {
        val parts = s?.split(',') ?: return null
        if (parts.isEmpty() || parts.size % 5 != 0) return null
        val out = ArrayList<TextQuad>(parts.size / 5)
        for (i in parts.indices step 5) {
            val l = parts[i].trim().toFloatOrNull() ?: return null
            val t = parts[i + 1].trim().toFloatOrNull() ?: return null
            val r = parts[i + 2].trim().toFloatOrNull() ?: return null
            val b = parts[i + 3].trim().toFloatOrNull() ?: return null
            if (!(l.isFinite() && t.isFinite() && r.isFinite() && b.isFinite()) || r < l || b < t) return null
            val quarter = parts[i + 4].trim().toIntOrNull()?.takeIf { it in 0..3 } ?: 0
            out += TextQuad(l, t, r, b, quarter)
        }
        return out
    }

    private fun colorOf(s: String?): Rgba? {
        val v = s?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: return null
        return Rgba((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }

    /** A PDF date (D:YYYYMMDDHHmmSS and an optional Z or +HH'mm' offset, trailing fields optional) as epoch ms. */
    private fun dateOf(s: String?): Long? {
        val m = PDF_DATE.matchEntire(s?.trim() ?: return null) ?: return null
        val g = m.groupValues
        fun field(i: Int, default: Int) = g[i].takeIf { it.isNotEmpty() }?.toInt() ?: default
        val offset = when (g[7]) {
            "+", "-" -> (if (g[7] == "-") -1 else 1) * (g[8].toInt() * 60 + (g[9].takeIf { it.isNotEmpty() }?.toInt() ?: 0))
            else -> 0
        }
        return epochMillisOf(g[1].toInt(), field(2, 1), field(3, 1), field(4, 0), field(5, 0), field(6, 0), offset)
    }

    private val PDF_DATE = Regex("""D:(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?(\d{2})?(?:Z|([+-])(\d{2})'?(?:(\d{2})'?)?)?.*""")
}
