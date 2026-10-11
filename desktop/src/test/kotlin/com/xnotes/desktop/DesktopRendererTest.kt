package com.xnotes.desktop

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.stroke.Sample
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.text.PageBox
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopRendererTest {
    @Test fun pagePaintsShapesTextAndRasterImages() {
        val dir = Files.createTempDirectory("xnotes-render-").toFile()
        try {
            val imageFile = dir.resolve("blue.png")
            val blue = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
            for (y in 0..1) for (x in 0..1) blue.setRGB(x, y, Color.BLUE.rgb)
            ImageIO.write(blue, "png", imageFile)
            val doc = Document.blankPixels(width = 120.0, height = 140.0)
            val page = doc.pages[0]
            page.style = PageStyle(pageColor = Rgba(255, 255, 255), template = PagePattern.NONE.id)
            page.items += ShapeItem(ShapeKind.RECTANGLE, Pt(10.0, 10.0), Pt(45.0, 45.0),
                Rgba(0, 0, 0), fillRgba = Rgba(220, 0, 0))
            page.items += ImageItem(ImageData(imageFile, 2, 2), Rect(60.0, 10.0, 35.0, 35.0))
            page.items += TextItem(Pt(10.0, 70.0), 95.0, text = "Hello", rgba = Rgba(0, 0, 0),
                pointSize = 20.0, measurer = DesktopTextMeasurer)
            DesktopStorage(DesktopPaths(dir)).use { storage ->
                val note = dir.resolve("drawn.xnote")
                storage.save(OpenDocument.Note(note, doc), note)
                val loaded = (storage.open(note) as OpenDocument.Note).value
                val out = BufferedImage(120, 140, BufferedImage.TYPE_INT_ARGB)
                val graphics = out.createGraphics()
                try { PagePainter.paint(graphics, loaded, 0, null, false) } finally { graphics.dispose() }
                assertEquals(Color.WHITE.rgb, out.getRGB(110, 110))
                assertEquals(Color(220, 0, 0).rgb, out.getRGB(25, 25))
                assertEquals(Color.BLUE.rgb, out.getRGB(75, 25))
                val ink = (75..95).flatMap { y -> (10..75).map { x -> out.getRGB(x, y) } }
                assertNotEquals(0, ink.count { it == Color.BLACK.rgb })
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun multiplyLayerDarkensBackdrop() {
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        DesktopRenderer(image.createGraphics()).use { r ->
            val box = Rect(0.0, 0.0, 20.0, 20.0)
            r.fillRect(box, Rgba(100, 150, 200))
            r.saveLayerBlended(box, 1.0, BlendMode.MULTIPLY)
            r.fillRect(box, Rgba(200, 200, 0))
            r.restore()
        }
        val pixel = Color(image.getRGB(10, 10), true)
        assertTrue(kotlin.math.abs(pixel.red - 78) <= 1)
        assertTrue(kotlin.math.abs(pixel.green - 118) <= 1)
        assertEquals(0, pixel.blue)
    }

    @Test fun vectorStrokeSurvivesBundleAndRenders() {
        val dir = Files.createTempDirectory("xnotes-stroke-").toFile()
        try {
            val doc = Document.blankPixels(width = 100.0, height = 80.0)
            doc.pages[0].style = PageStyle(pageColor = Rgba(255, 255, 255))
            doc.pages[0].items += Stroke(Tool.PEN,
                ToolConfig(baseWidth = 10.0, pressureEnabled = false, rgba = Rgba(20, 40, 210)),
                listOf(Sample(12.0, 40.0, 1.0), Sample(50.0, 40.0, 1.0), Sample(88.0, 40.0, 1.0)))
            DesktopStorage(DesktopPaths(dir)).use { storage ->
                val file = dir.resolve("stroke.xnote")
                storage.save(OpenDocument.Note(file, doc), file)
                val loaded = (storage.open(file) as OpenDocument.Note).value
                val out = BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB)
                val graphics = out.createGraphics()
                try { PagePainter.paint(graphics, loaded, 0, null, false) } finally { graphics.dispose() }
                assertEquals(Color(20, 40, 210).rgb, out.getRGB(50, 40))
                assertEquals(Color.WHITE.rgb, out.getRGB(50, 20))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun svgCanBeProbedAndPainted() {
        val svg = Files.createTempFile("xnotes-image-", ".svg").toFile()
        try {
            svg.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"30\" height=\"20\"><rect width=\"30\" height=\"20\" fill=\"#00ff00\"/></svg>")
            assertEquals(30, DesktopImageCodec.probeFile(svg.path)?.width)
            val bitmap = DesktopImageCodec.decode(svg, 30, 20)
            assertNotNull(bitmap)
            assertEquals(Color.GREEN.rgb, bitmap!!.getRGB(15, 10))
        } finally { svg.delete() }
    }

    @Test fun flowingTextUsesBundledFontAndPaintsOnPage() {
        val font = DesktopFonts.resolve(FontSpec(18.0, FontFace("Lora")))
        assertTrue(font.family.contains("Lora", ignoreCase = true))
        val doc = Document.blankPixels(width = 400.0, height = 400.0)
        doc.pages[0].style = PageStyle(pageColor = Rgba(255, 255, 255))
        doc.flow.paragraphs += Paragraph(mutableListOf(Run("A flowing paragraph")))
        val flow = FlowLayout(DesktopTextMeasurer).apply { autoColor = { Rgba(0, 0, 0) } }
            .layout(doc.flow, listOf(PageBox(400.0, 400.0)), doc.dpi)
        val out = BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB)
        val graphics = out.createGraphics()
        try { PagePainter.paint(graphics, doc, 0, flow, false) } finally { graphics.dispose() }
        val darkPixels = (80..200).sumOf { y -> (80..300).count { x -> out.getRGB(x, y) == Color.BLACK.rgb } }
        assertTrue(darkPixels > 5)
    }
}
