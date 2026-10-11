package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeKindTest {

    private val box = Rect(10.0, 20.0, 100.0, 60.0)

    private fun assertPt(want: Pt, got: Pt) {
        assertEquals("x of $got", want.x, got.x, 1e-9)
        assertEquals("y of $got", want.y, got.y, 1e-9)
    }

    private fun Rect.inside(r: Rect) = left >= r.left - 1e-9 && top >= r.top - 1e-9 && right <= r.right + 1e-9 && bottom <= r.bottom + 1e-9

    @Test fun everyKindHasItsOwnIdAndReadsBack() {
        assertEquals(ShapeKind.all.size, ShapeKind.all.map { it.id }.toSet().size)
        for (k in ShapeKind.all) assertSame(k, ShapeKind.fromId(k.id))
        assertSame(ShapeKind.RECTANGLE, ShapeKind.fromId("hexagram"))
        assertNull(ShapeKind.fromIdOrNull("hexagram"))
        assertEquals("the nine the first versions wrote", 9, ShapeKind.all.count { it.isLegacy })
    }

    @Test fun kindsSayHowTheyBehave() {
        assertTrue(ShapeKind.LINE.isEndpointShape && ShapeKind.ARROW.isEndpointShape)
        assertTrue(ShapeKind.POLYGON.isFree && ShapeKind.POLYLINE.isFree && ShapeKind.CURVE.isFree)
        assertTrue(ShapeKind.CIRCLE.keepsAspect)
        assertFalse(ShapeKind.ELLIPSE.keepsAspect)
        assertTrue(ShapeKind.POLYGON.isClosed && ShapeKind.CURVE.isOpen)
    }

    @Test fun aRectangleHasFourCornersAndEightGluePoints() {
        val g = ShapeKind.RECTANGLE.build(box, ShapeParams())
        assertEquals(listOf(Pt(10.0, 20.0), Pt(110.0, 20.0), Pt(110.0, 80.0), Pt(10.0, 80.0)), g.corners)
        assertEquals(8, g.connections.size)
        val topMiddle = g.connections.single { it.at.distanceTo(Pt(60.0, 20.0)) < 1e-9 }
        assertPt(Pt(0.0, -1.0), topMiddle.normals.single())
        val corner = g.connections.single { it.at.distanceTo(Pt(110.0, 80.0)) < 1e-9 }
        assertEquals("a corner faces out of both its edges", 2, corner.normals.size)
        assertTrue(g.textRect.inside(box))
    }

    @Test fun anEllipseGluesOnItsCurveFacingOut() {
        val g = ShapeKind.ELLIPSE.build(box, ShapeParams())
        assertTrue(g.corners.isEmpty())
        assertEquals(8, g.connections.size)
        for (c in g.connections) {
            val e = ((c.at.x - 60.0) / 50.0).let { it * it } + ((c.at.y - 50.0) / 30.0).let { it * it }
            assertEquals(1.0, e, 1e-9)
            val n = c.normals.single()
            assertTrue("faces away from the centre", (c.at.x - 60.0) * n.x + (c.at.y - 50.0) * n.y > 0.0)
        }
        val t = g.textRect
        val e = ((t.right - 60.0) / 50.0).let { it * it } + ((t.bottom - 50.0) / 30.0).let { it * it }
        assertEquals("the label box touches the ellipse at its corners", 1.0, e, 1e-9)
    }

    @Test fun aTriangleFacesOutOfItsBase() {
        val g = ShapeKind.TRIANGLE.build(box, ShapeParams())
        assertEquals(3, g.corners.size)
        assertPt(Pt(60.0, 20.0), g.corners[0])
        val base = g.connections.single { it.at.distanceTo(Pt(60.0, 80.0)) < 1e-9 }
        assertPt(Pt(0.0, 1.0), base.normals.single())
    }

    @Test fun aFreeOutlineIsPlacedInItsBox() {
        val unit = listOf(FreePath.lines(listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(0.5, 1.0)), closed = true))
        val g = ShapeKind.POLYGON.build(box, ShapeParams(path = unit))
        assertEquals(listOf(Pt(10.0, 20.0), Pt(110.0, 20.0), Pt(60.0, 80.0)), g.corners)
        assertEquals(PathRole.OUTLINE, g.paths.single().role)
        assertEquals(PathRole.STROKE_ONLY, ShapeKind.POLYLINE.build(box, ShapeParams(path = unit)).paths.single().role)
    }

    @Test fun aTrianglesKnobSlidesItsApexAlongTheTop() {
        val k = ShapeKind.TRIANGLE.knobs.single()
        assertPt(Pt(60.0, 20.0), k.position(box, ShapeKind.TRIANGLE.defaultAdj, 0))
        val adj = k.drag(Pt(35.0, 70.0), box, ShapeKind.TRIANGLE.defaultAdj, 0)
        assertEquals(0.25, adj[0], 1e-12)
        assertPt(Pt(35.0, 20.0), ShapeKind.TRIANGLE.build(box, ShapeParams(adj)).corners[0])
        assertEquals("clamped to the top edge", 1.0, k.drag(Pt(500.0, 0.0), box, adj, 0)[0], 0.0)
    }

    @Test fun aMirrorFlipsTheApexAcross() {
        assertEquals(0.75, ShapeKind.TRIANGLE.mirroredAdj(doubleArrayOf(0.25))!![0], 1e-12)
        assertEquals("a missing value reads as the default", 0.5, ShapeKind.TRIANGLE.adjOf(DoubleArray(0), 0), 0.0)
    }
}
