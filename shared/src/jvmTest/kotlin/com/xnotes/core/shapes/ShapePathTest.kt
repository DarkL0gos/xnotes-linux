package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.vector.PathFlattener
import com.xnotes.core.vector.VectorSeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapePathTest {

    @Test fun anEllipseStaysOnItsCurveWhenFlattened() {
        val c = ShapePath.ellipse(50.0, 30.0, 40.0, 20.0).contours.single()
        assertTrue(c.closed)
        assertEquals("starts at the rightmost point", Pt(90.0, 30.0), c.start)
        for (p in PathFlattener.flatten(c, 0.01)) {
            val e = ((p.x - 50.0) / 40.0).let { it * it } + ((p.y - 30.0) / 20.0).let { it * it }
            assertEquals(1.0, e, 0.002)
        }
    }

    @Test fun anEllipseRunsClockwiseOnScreen() {
        val c = ShapePath.ellipse(0.0, 0.0, 10.0, 10.0).contours.single()
        assertEquals("right, then down to the bottom", Pt(0.0, 10.0), c.segments[0].end)
    }

    @Test fun aRectRunsClockwiseFromTheTopLeft() {
        val c = ShapePath.rect(Rect(0.0, 0.0, 10.0, 5.0)).contours.single()
        assertEquals(Pt(0.0, 0.0), c.start)
        assertEquals(listOf(Pt(10.0, 0.0), Pt(10.0, 5.0), Pt(0.0, 5.0)), c.segments.map { it.end })
        assertTrue(c.segments.all { it is VectorSeg.Line })
        assertTrue(c.closed)
    }

    @Test fun aPolylineStaysOpenAndAPolygonCloses() {
        val pts = listOf(Pt(0.0, 0.0), Pt(5.0, 5.0), Pt(10.0, 0.0))
        assertFalse(ShapePath.polyline(pts).contours.single().closed)
        assertTrue(ShapePath.polygon(pts).contours.single().closed)
        assertEquals(PathRole.STROKE_ONLY, ShapePath.polyline(pts).role)
        assertEquals(PathRole.OUTLINE, ShapePath.polygon(pts).role)
    }

    @Test fun rolesSayWhatTheyPaint() {
        assertTrue(PathRole.OUTLINE.fills && PathRole.OUTLINE.strokes)
        assertTrue(!PathRole.STROKE_ONLY.fills && PathRole.STROKE_ONLY.strokes)
        assertTrue(PathRole.FILL_ONLY.fills && !PathRole.FILL_ONLY.strokes)
        assertTrue(!PathRole.HIDDEN.fills && PathRole.HIDDEN.strokes)
        assertTrue(!PathRole.HEAD.fills && PathRole.HEAD.strokes)
        assertTrue(PathRole.SHADE_DARK.fills && !PathRole.SHADE_LIGHT.strokes)
    }
}
