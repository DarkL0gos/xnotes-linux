package com.xnotes.core.shapes

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.stroke.CubicFit
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadTest {

    private val ink = Rgba(10, 20, 30)

    private fun pts(c: VectorContour) = listOf(c.start) + c.segments.map { it.end }

    @Test fun theOpenHeadIsTodaysChevron() {
        val head = Head.OPEN.paths(Pt(100.0, 0.0), Pt(1.0, 0.0), 4.0).single()
        assertEquals(PathRole.HEAD, head.role)
        // The tip half a width past the end, the barbs 14 back and 7 out (3.5 widths long).
        assertEquals(listOf(Pt(88.0, 7.0), Pt(102.0, 0.0), Pt(88.0, -7.0)), pts(head.contours.single()))
    }

    @Test fun aClosedHeadStopsTheLineAtItsBack() {
        val line = listOf(ShapePath.polyline(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0))))
        val out = Head.attach(line, Head.NONE, Head.HOLLOW_TRIANGLE, 2.0)
        assertEquals(Pt(88.0, 0.0), pts(out[0].contours.single()).last())
        assertEquals(PathRole.HEAD, out[1].role)
        assertEquals(Pt(100.0, 0.0), out[1].contours.single().start)
    }

    @Test fun aStartHeadPointsBackwards() {
        val line = listOf(ShapePath.polyline(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0))))
        val out = Head.attach(line, Head.FILLED_DIAMOND, Head.NONE, 2.0)
        val shaft = pts(out[0].contours.single())
        assertEquals("the line starts behind the diamond", 12.0 * 1.3, shaft.first().x, 1e-9)
        assertEquals(PathRole.HEAD_FILLED, out[1].role)
        assertEquals(Pt(0.0, 0.0), out[1].contours.single().start)
    }

    @Test fun aCurvedEndIsTrimmedAlongTheCurve() {
        val curve = VectorContour(Pt(0.0, 0.0), listOf(VectorSeg.Cubic(Pt(0.0, 100.0), Pt(100.0, 100.0), Pt(100.0, 0.0))), false)
        val out = Head.attach(listOf(ShapePath(listOf(curve), PathRole.STROKE_ONLY)), Head.NONE, Head.FILLED, 4.0)
        val seg = out[0].contours.single().segments.single() as VectorSeg.Cubic
        assertEquals("cut 14 short of the end", 14.0, seg.end.distanceTo(Pt(100.0, 0.0)), 0.01)
        val onCurve = CubicFit.sample(listOf(Pt(0.0, 0.0), Pt(0.0, 100.0), Pt(100.0, 100.0), Pt(100.0, 0.0)), 400)
        assertTrue("and still on it", onCurve.any { it.distanceTo(seg.end) < 0.5 })
    }

    @Test fun anArrowEndsInAChevronByDefault() {
        val arrow = ShapeItem(ShapeKind.ARROW, Pt(0.0, 0.0), Pt(100.0, 0.0), ink, 4.0)
        assertEquals(Head.OPEN, arrow.headEnd)
        assertEquals(Head.NONE, arrow.headStart)
        assertEquals(PathRole.HEAD, arrow.paths().last().role)
        assertEquals(Head.NONE, ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(1.0, 0.0), ink).headEnd)
    }

    @Test fun aFilledHeadTakesTheOutlineColourWithoutAFill() {
        val line = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(100.0, 0.0), ink, 2.0, headEnd = Head.FILLED)
        val r = FakeRenderer()
        line.paint(r)
        assertEquals(listOf(ink), r.pathFills)
        assertTrue("the head's body is hit like its outline", line.contains(Pt(95.0, 0.0)))
        assertTrue("the bounds reach the head's barbs", line.bounds().bottom >= 6.0)
    }

    @Test fun bendingALineKeepsItsHeads() {
        val arrow = ShapeItem(ShapeKind.ARROW, Pt(0.0, 0.0), Pt(100.0, 0.0), ink, 2.0, headStart = Head.HOLLOW_DIAMOND)
        assertTrue(arrow.vertexDots().isEmpty())
        val middle = arrow.edgeDots().single()
        assertEquals(Pt(50.0, 0.0), middle.at)
        assertTrue(arrow.toFree())
        assertEquals(ShapeKind.POLYLINE, arrow.shape)
        val bend = arrow.splitEdge(middle.spot)!!
        arrow.moveVertex(bend, Pt(50.0, 40.0))
        assertEquals(3, arrow.vertexDots().size)
        assertEquals(Head.OPEN, arrow.headEnd)
        assertEquals(Head.HOLLOW_DIAMOND, arrow.headStart)
        assertEquals(PathRole.HEAD, arrow.paths().last().role)
    }

    @Test fun headsReadBackByTheirIds() {
        for (h in Head.entries) assertEquals(h, Head.fromId(h.id))
        assertEquals(Head.NONE, Head.fromId("trident"))
    }
}
