package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreePathTest {

    private val curve = VectorContour(
        Pt(0.0, 0.0),
        listOf(VectorSeg.Cubic(Pt(0.0, 1.0), Pt(1.0, 1.0), Pt(1.0, 0.0)), VectorSeg.Line(Pt(0.5, -0.25))),
        closed = true,
    )
    private val open = FreePath.lines(listOf(Pt(0.125, 0.5), Pt(0.75, 0.875)), closed = false)

    private fun points(c: VectorContour): List<Pt> = listOf(c.start) + c.segments.flatMap {
        when (it) {
            is VectorSeg.Line -> listOf(it.end)
            is VectorSeg.Cubic -> listOf(it.c1, it.c2, it.end)
        }
    }

    @Test fun svgPathDataRoundTrips() {
        val back = FreePath.fromSvg(FreePath.toSvg(listOf(curve, open)))!!
        assertEquals(2, back.size)
        assertTrue(back[0].closed)
        assertFalse(back[1].closed)
        assertTrue(back[0].segments[0] is VectorSeg.Cubic)
        assertEquals(points(curve), points(back[0]))
        assertEquals(points(open), points(back[1]))
    }

    @Test fun svgPathDataIsCompact() {
        assertEquals("M0.125,0.5 L0.75,0.875", FreePath.toSvg(listOf(open)))
        assertEquals("M0,0 L1,0 L1,1 Z", FreePath.toSvg(listOf(FreePath.lines(listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0)), true))))
    }

    @Test fun badPathDataReadsAsNothing() {
        assertNull(FreePath.fromSvg(""))
        assertNull(FreePath.fromSvg("L1,1"))
        assertNull(FreePath.fromSvg("M1,1 Q2,2 3,3"))
        assertNull(FreePath.fromSvg("M1"))
    }

    @Test fun normalizeAndPlaceAreInverses() {
        val box = Rect(10.0, 20.0, 200.0, 50.0)
        val placed = FreePath.place(listOf(curve), box)
        val back = FreePath.normalize(placed, box)
        val want = points(curve)
        val got = points(back[0])
        for (i in want.indices) assertEquals(0.0, want[i].distanceTo(got[i]), 1e-12)
    }

    @Test fun boundsHugTheCurveNotItsHandles() {
        val arch = VectorContour(Pt(0.0, 0.0), listOf(VectorSeg.Cubic(Pt(0.0, 100.0), Pt(100.0, 100.0), Pt(100.0, 0.0))), false)
        val b = FreePath.bounds(listOf(arch))!!
        assertEquals("the arch peaks at three quarters of its handles' height", 75.0, b.bottom, 0.1)
        assertEquals(0.0, b.top, 1e-9)
    }

    @Test fun onlyOneStraightContourIsAVertexList() {
        assertTrue(FreePath.isVertexList(listOf(open)))
        assertFalse(FreePath.isVertexList(listOf(curve)))
        assertFalse(FreePath.isVertexList(listOf(open, open)))
    }
}
