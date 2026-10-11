package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI

class GlueTest {

    private val ink = Rgba(1, 2, 3, 255)
    private val measurer = FakeTextMeasurer()

    private fun rect(l: Double, t: Double, r: Double, b: Double) = ShapeItem(ShapeKind.RECTANGLE, Pt(l, t), Pt(r, b), ink)

    private fun joining(a: CanvasItem, ai: Int, b: CanvasItem, bi: Int) = ConnectorItem(
        Routing.ELBOW,
        ConnectorEnd(Glue.point(a, ai)!!.at, a, ai),
        ConnectorEnd(Glue.point(b, bi)!!.at, b, bi),
        ink,
    )

    @Test fun aRectangleOffersItsCornersAndEdgeMiddlesWithTheWaysOut() {
        val points = Glue.points(rect(0.0, 0.0, 100.0, 60.0))
        assertEquals(8, points.size)
        val right = points.single { it.at == Pt(100.0, 30.0) }
        assertEquals(1, right.normals.size)
        assertTrue(right.normals[0].distanceTo(Pt(1.0, 0.0)) < 1e-12)
        assertEquals(2, points.single { it.at == Pt(100.0, 60.0) }.normals.size)
    }

    @Test fun aTurnedShapesPointsAndWaysOutTurnWithIt() {
        val s = rect(0.0, 0.0, 100.0, 60.0)
        val turn = Affine.rotateAbout(s.box.center, 0.3)
        s.applyTransform(turn)
        val out = Pt(kotlin.math.cos(0.3), kotlin.math.sin(0.3))
        val right = Glue.points(s).single { it.normals.size == 1 && it.normals[0].distanceTo(out) < 1e-9 }
        assertTrue(right.at.distanceTo(turn.apply(Pt(100.0, 30.0))) < 1e-9)
    }

    @Test fun textBoxesAndImagesOfferTheirBoxes() {
        val text = TextItem(Pt(10.0, 10.0), width = 100.0, text = "hi", measurer = measurer)
        assertEquals(8, Glue.points(text).size)
        val image = ImageItem(ImageData(File("x"), 10, 10), Rect(0.0, 0.0, 40.0, 20.0), angle = PI / 2.0)
        val pts = Glue.points(image).map { it.at }
        assertTrue(pts.any { it.distanceTo(Pt(20.0, -10.0)) < 1e-9 })
        assertTrue(Glue.accepts(text) && Glue.accepts(image))
    }

    @Test fun linesInkAndDrawnTextTakeNoGlue() {
        val line = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(10.0, 0.0), ink)
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(0.0, 0.0, 1.0), Sample(5.0, 5.0, 1.0)))
        assertTrue(Glue.points(line).isEmpty())
        assertFalse(Glue.accepts(line))
        assertFalse(Glue.accepts(stroke))
        assertNull(Glue.point(rect(0.0, 0.0, 10.0, 10.0), 99))
    }

    @Test fun theNearestPointWithinReachWins() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(200.0, 0.0, 300.0, 60.0)
        val hit = Glue.nearest(listOf(a, b), Pt(205.0, 28.0), 10.0)!!
        assertSame(b, hit.target)
        assertEquals(Pt(200.0, 30.0), hit.point.at)
        assertNull(Glue.nearest(listOf(a, b), Pt(150.0, 30.0), 10.0))
    }

    @Test fun aConnectorNeverGluesToItselfOrBackOntoOneHangingFromIt() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(200.0, 0.0, 300.0, 60.0)
        val c = joining(a, 5, b, 7)
        val d = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(150.0, 100.0)), ConnectorEnd(Pt(150.0, 30.0), c, 0), ink)
        val onC = Glue.points(c)[0].at
        assertEquals(Pt(150.0, 30.0), onC)
        assertNull(Glue.nearest(listOf(c), onC, 5.0, skip = c))
        assertSame(c, Glue.nearest(listOf(c), onC, 5.0, skip = d)!!.target)
        assertNull(Glue.nearest(listOf(d), Glue.points(d)[0].at, 5.0, skip = c))
    }

    @Test fun copiesKeepTheirJoinsAmongThemselvesAndLetGoOfTheRest() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(200.0, 0.0, 300.0, 60.0)
        val c = joining(a, 5, b, 7)
        val all = listOf(a, b, c).deepCopyAll(measurer)
        val copy = all[2] as ConnectorItem
        assertSame(all[0], copy.start.target)
        assertSame(all[1], copy.end.target)
        assertEquals(5, copy.start.point)
        assertNotSame(c, copy)

        val half = listOf(a, c).deepCopyAll(measurer)
        val loose = half[1] as ConnectorItem
        assertSame(half[0], loose.start.target)
        assertNull(loose.end.target)
        assertEquals(c.end.at, loose.end.at)
        // The original is untouched.
        assertSame(b, c.end.target)
    }

    @Test fun aCopiedPageKeepsItsConnectorsJoined() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(200.0, 0.0, 300.0, 60.0)
        val page = Page(500.0, 500.0, mutableListOf(a, b, joining(a, 5, b, 7)))
        val copy = page.deepCopy(measurer)
        val c = copy.items[2] as ConnectorItem
        assertSame(copy.items[0], c.start.target)
        assertSame(copy.items[1], c.end.target)
    }
}
