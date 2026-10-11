package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.CubicFit
import com.xnotes.core.stroke.RecognizedShape
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeShapeTest {

    private val ink = Rgba(0, 0, 0)
    private val controls = listOf(Pt(0.0, 0.0), Pt(30.0, 120.0), Pt(140.0, 110.0), Pt(160.0, 10.0))

    private fun curve() = ShapeItem.free(
        ShapeKind.CURVE,
        listOf(VectorContour(controls[0], listOf(VectorSeg.Cubic(controls[1], controls[2], controls[3])), false)),
        ink, 3.0,
    )

    /** The shape's curve on the page, sampled. */
    private fun onPage(s: ShapeItem): List<Pt> {
        val c = s.paths().single().contours.single()
        val seg = c.segments.single() as VectorSeg.Cubic
        return CubicFit.sample(listOf(c.start, seg.c1, seg.c2, seg.end), 16)
    }

    @Test fun aCurveKeepsItsControlPointsAndHugsItsBox() {
        val s = curve()
        assertTrue(s.paths().single().contours.single().segments.single() is VectorSeg.Cubic)
        val samples = onPage(s)
        assertEquals(samples.minOf { it.y }, s.box.top, 0.1)
        assertEquals(samples.maxOf { it.y }, s.box.bottom, 0.1)
        assertTrue("the box stops short of the handles", s.box.bottom < 110.0)
    }

    @Test fun anyAffineMapsTheCurveExactly() {
        val s = curve()
        val before = onPage(s)
        val t = Affine.rotateAbout(Pt(40.0, 40.0), 0.8).compose(Affine.scaleAbout(Pt.ZERO, 1.7, 0.6))
        s.applyTransform(t)
        assertEquals(ShapeKind.CURVE, s.shape)
        val after = onPage(s)
        for (i in before.indices) assertEquals(0.0, t.apply(before[i]).distanceTo(after[i]), 1e-6)
    }

    @Test fun aCurveIsHitAlongItsLengthNotAcrossItsHandles() {
        val s = curve()
        val mid = onPage(s)[8]
        assertTrue(s.contains(mid))
        assertFalse("the handle sits off the curve", s.contains(Pt(30.0, 120.0)))
    }

    @Test fun anAreaCutOfACurveLeavesTwoRuns() {
        val s = curve()
        val mid = onPage(s)[8]
        val frags = s.erasedBy(mid.x, mid.y, 6.0)!!.map { it as Stroke }
        assertEquals(2, frags.size)
        assertEquals(0.0, frags[0].samples.first().pos.distanceTo(controls[0]), 1e-4)
        assertEquals(0.0, frags[1].samples.last().pos.distanceTo(controls[3]), 1e-4)
    }

    @Test fun aCurvesStandInIsItsFlattenedOutline() {
        val s = curve()
        val standIn = s.legacyOutline()
        assertNotNull(standIn)
        assertTrue(standIn!!.size > 8)
        assertEquals(0.0, standIn.first().distanceTo(controls[0]), 1e-9)
        assertEquals(0.0, standIn.last().distanceTo(controls[3]), 1e-9)
        assertEquals("curve", s.legacyId())
    }

    @Test fun aPlainPolygonNeedsNoStandIn() {
        val s = ShapeItem.poly(ShapeKind.POLYGON, listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(5.0, 8.0)), ink)
        assertNull(s.legacyOutline())
        assertEquals("polygon", s.legacyId())
    }

    @Test fun aShearedTriangleBecomesAPolygonOfItsCorners() {
        val s = ShapeItem(ShapeKind.TRIANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), ink, 2.0)
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), 0.5))
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 1.0))
        assertEquals(ShapeKind.POLYGON, s.shape)
        assertEquals(3, s.vertices()!!.size)
    }

    @Test fun aRecognizedCurveBecomesACubic() {
        val rec = RecognizedShape(ShapeKind.CURVE, Pt(0.0, 0.0), Pt(160.0, 90.0), controls = controls)
        val s = rec.toShape(ink, 2.0, null, neon = true, neonStrength = 0.4, dashed = false, dashLength = 10.0, dashGap = 8.0)
        assertEquals(ShapeKind.CURVE, s.shape)
        assertTrue(s.neon)
        assertTrue(s.paths().single().contours.single().segments.single() is VectorSeg.Cubic)
    }
}
