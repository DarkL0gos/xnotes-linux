package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.vector.PathFlattener
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorPathsTest {

    private fun near(want: Pt, got: Pt, tol: Double = 1e-9) = assertTrue("$want vs $got", want.distanceTo(got) <= tol)

    @Test fun aRoundedCornerMeetsBothSidesAlongThem() {
        val c = ConnectorPaths.rounded(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0)), 20.0)
        assertEquals(3, c.segments.size)
        near(Pt(80.0, 0.0), c.segments[0].end)
        val arc = c.segments[1] as VectorSeg.Cubic
        near(Pt(100.0, 20.0), arc.end)
        // A quarter arc's handles run along the two sides, a kappa of its radius long.
        near(Pt(80.0 + 20.0 * ShapePath.KAPPA, 0.0), arc.c1)
        near(Pt(100.0, 20.0 - 20.0 * ShapePath.KAPPA), arc.c2)
        near(Pt(100.0, 100.0), c.segments[2].end)
    }

    @Test fun aRoundedCornerNeverTakesMoreThanHalfASide() {
        val c = ConnectorPaths.rounded(listOf(Pt(0.0, 0.0), Pt(30.0, 0.0), Pt(30.0, 200.0)), 50.0)
        near(Pt(15.0, 0.0), c.segments[0].end)
        near(Pt(30.0, 15.0), c.segments[1].end)
    }

    @Test fun aCurveLeavesAndArrivesAlongItsEndSegmentsThroughEachMiddle() {
        val c = ConnectorPaths.curved(listOf(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(50.0, 100.0), Pt(100.0, 100.0)))
        assertEquals(2, c.segments.size)
        val first = c.segments[0] as VectorSeg.Cubic
        assertEquals(0.0, first.c1.y, 1e-12)
        near(Pt(50.0, 50.0), first.end)
        val last = c.segments[1] as VectorSeg.Cubic
        assertEquals(100.0, last.c2.y, 1e-12)
        near(Pt(100.0, 100.0), last.end)
    }

    @Test fun aLineIsCutAroundABox() {
        val pieces = ConnectorPaths.clipOut(ShapePath.lines(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)), closed = false), Rect(40.0, -10.0, 20.0, 20.0))
        assertEquals(2, pieces.size)
        near(Pt(0.0, 0.0), pieces[0].start)
        near(Pt(40.0, 0.0), pieces[0].segments.last().end)
        near(Pt(60.0, 0.0), pieces[1].start)
        near(Pt(100.0, 0.0), pieces[1].segments.last().end)
    }

    @Test fun aCurveIsCutAtTheBoxEdges() {
        val curve = VectorContour(Pt(0.0, 0.0), listOf(VectorSeg.Cubic(Pt(30.0, 40.0), Pt(70.0, 40.0), Pt(100.0, 0.0))), closed = false)
        val box = Rect(40.0, 0.0, 20.0, 50.0)
        val pieces = ConnectorPaths.clipOut(curve, box)
        assertEquals(2, pieces.size)
        assertEquals(40.0, pieces[0].segments.last().end.x, 1e-6)
        assertEquals(60.0, pieces[1].start.x, 1e-6)
        // The kept pieces still lie on the curve, and never inside the box.
        for (p in pieces.flatMap { PathFlattener.flatten(it, 0.05) }) {
            assertTrue(p.x <= 40.0 + 1e-6 || p.x >= 60.0 - 1e-6)
        }
    }

    @Test fun aLineClearOfTheBoxIsKeptWhole() {
        val line = ShapePath.lines(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 50.0)), closed = false)
        val pieces = ConnectorPaths.clipOut(line, Rect(200.0, 200.0, 10.0, 10.0))
        assertEquals(1, pieces.size)
        assertEquals(2, pieces[0].segments.size)
    }

    @Test fun aLineStartingInsideTheBoxKeepsOnlyWhatLeavesIt() {
        val pieces = ConnectorPaths.clipOut(ShapePath.lines(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)), closed = false), Rect(-10.0, -10.0, 30.0, 20.0))
        assertEquals(1, pieces.size)
        near(Pt(20.0, 0.0), pieces[0].start)
    }

    @Test fun placesAlongTheLineAreMeasuredByLength() {
        val l = ShapePath.lines(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0)), closed = false)
        near(Pt(100.0, 0.0), ConnectorPaths.pointAt(l, 0.5))
        near(Pt(50.0, 0.0), ConnectorPaths.pointAt(l, 0.25))
        assertEquals(0.75, ConnectorPaths.shareNear(l, Pt(130.0, 50.0)), 1e-9)
        assertEquals(0.0, ConnectorPaths.shareNear(l, Pt(-20.0, 5.0)), 1e-9)
    }
}
