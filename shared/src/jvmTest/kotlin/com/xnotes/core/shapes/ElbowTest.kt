package com.xnotes.core.shapes

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ElbowTest {

    private val a = Pt(0.0, 0.0)
    private val b = Pt(100.0, 60.0)

    private fun assertSamePoints(want: List<Pt>, got: List<Pt>) {
        assertEquals(want.size, got.size)
        for (i in want.indices) assertTrue("point $i: ${want[i]} vs ${got[i]}", want[i].distanceTo(got[i]) < 1e-9)
    }

    @Test fun aPlainElbowBendsOnceAlongItsFirstAxis() {
        assertEquals(listOf(a, Pt(100.0, 0.0), b), Elbow(Axis.H, DoubleArray(0)).points(a, b))
        assertEquals(listOf(a, Pt(0.0, 60.0), b), Elbow(Axis.V, DoubleArray(0)).points(a, b))
    }

    @Test fun eachCoordinatePlacesTheNextSegmentAcrossItsAxis() {
        assertEquals(listOf(a, Pt(40.0, 0.0), Pt(40.0, 60.0), b), Elbow(Axis.H, doubleArrayOf(40.0)).points(a, b))
        val e = Elbow(Axis.H, doubleArrayOf(40.0, 80.0))
        assertEquals(listOf(a, Pt(40.0, 0.0), Pt(40.0, 80.0), Pt(100.0, 80.0), b), e.points(a, b))
        assertEquals(4, e.segments)
        assertEquals(Axis.V, e.last)
        assertEquals(Axis.H, Elbow(Axis.H, doubleArrayOf(40.0)).last)
    }

    @Test fun movingAnEndMovesOnlyTheSegmentsItSits() {
        val e = Elbow(Axis.H, doubleArrayOf(40.0))
        val moved = e.points(Pt(0.0, -30.0), Pt(140.0, 90.0))
        assertEquals(listOf(Pt(0.0, -30.0), Pt(40.0, -30.0), Pt(40.0, 90.0), Pt(140.0, 90.0)), moved)
    }

    @Test fun reversedTracesTheSameRouteBackwards() {
        for (e in listOf(Elbow(Axis.H, DoubleArray(0)), Elbow(Axis.V, doubleArrayOf(30.0)), Elbow(Axis.H, doubleArrayOf(40.0, 80.0)))) {
            assertEquals(e.points(a, b).reversed(), e.reversed().points(b, a))
        }
    }

    @Test fun aMoveShiftsEachSegmentByThePartAcrossIt() {
        val e = Elbow(Axis.H, doubleArrayOf(40.0, 80.0))
        val d = Pt(5.0, 7.0)
        assertSamePoints(e.points(a, b).map { it + d }, e.translated(d.x, d.y).points(a + d, b + d))
    }

    @Test fun withCoordSlidesOneMiddleSegment() {
        val e = Elbow(Axis.H, doubleArrayOf(40.0, 80.0)).withCoord(2, 20.0)
        assertArrayEquals(doubleArrayOf(40.0, 20.0), e.coords, 0.0)
        assertEquals(e, e.withCoord(0, 5.0))
        assertEquals(e, e.withCoord(3, 5.0))
    }

    @Test fun aQuarterTurnTurnsTheRouteExactly() {
        val e = Elbow(Axis.H, doubleArrayOf(40.0, 80.0))
        val t = Affine.rotateAbout(Pt(50.0, 30.0), PI / 2.0)
        val turned = e.transformed(t, a, b)
        assertEquals(Axis.V, turned.first)
        assertSamePoints(e.points(a, b).map(t::apply), turned.points(t.apply(a), t.apply(b)))
    }

    @Test fun aStretchKeepsTheRouteSquare() {
        val e = Elbow(Axis.V, doubleArrayOf(30.0))
        val t = Affine.scaleAbout(Pt.ZERO, 2.0, 3.0)
        assertSamePoints(e.points(a, b).map(t::apply), e.transformed(t, a, b).points(t.apply(a), t.apply(b)))
    }

    @Test fun aSmallTurnKeepsTheAxesAndMovesEachMiddleSegmentWithItsMidpoint() {
        val e = Elbow(Axis.H, doubleArrayOf(40.0))
        val t = Affine.rotateAbout(Pt.ZERO, 0.2)
        val turned = e.transformed(t, a, b)
        assertEquals(Axis.H, turned.first)
        assertEquals(t.apply(Pt(40.0, 30.0)).x, turned.coords[0], 1e-9)
    }

    @Test fun ofReadsASquarePathBack() {
        for (e in listOf(Elbow(Axis.H, DoubleArray(0)), Elbow(Axis.V, doubleArrayOf(30.0)), Elbow(Axis.H, doubleArrayOf(40.0, 80.0)))) {
            assertEquals(e, Elbow.of(e.points(a, b)))
        }
    }

    @Test fun aPathWithNoBendKeepsAStepOfNoHeightHalfway() {
        val e = Elbow.of(listOf(Pt(0.0, 5.0), Pt(100.0, 5.0)))
        assertEquals(Axis.H, e.first)
        assertArrayEquals(doubleArrayOf(50.0), e.coords, 1e-12)
        assertEquals(listOf(Pt(0.0, 5.0), Pt(50.0, 5.0), Pt(50.0, 25.0), Pt(100.0, 25.0)), e.points(Pt(0.0, 5.0), Pt(100.0, 25.0)))
    }

    @Test fun cornersDropsRepeatsStraightRunsAndBacktracks() {
        val path = listOf(a, a, Pt(20.0, 0.0), Pt(50.0, 0.0), Pt(50.0, 40.0), Pt(50.0, 40.0), Pt(50.0, 60.0), b)
        assertEquals(listOf(a, Pt(50.0, 0.0), Pt(50.0, 60.0), b), Elbow.corners(path))
        assertEquals(listOf(a, Pt(10.0, 0.0)), Elbow.corners(listOf(a, Pt(30.0, 0.0), Pt(10.0, 0.0))))
    }

    // --- mending a route an end has moved ---

    private val right = Pt(1.0, 0.0)
    private val up = Pt(0.0, -1.0)

    @Test fun aRouteThatAlreadyLeavesWellIsLeftAlone() {
        val e = Elbow(Axis.H, doubleArrayOf(40.0))
        assertEquals(e, e.fitStart(a, b, listOf(right), 10.0, null))
    }

    @Test fun aRouteOnTheWrongAxisDropsACoordinateWhenThatLeavesWell() {
        // Out up, across at x = 50, up again: from a point whose way is right, the drop leaves along it.
        val e = Elbow(Axis.V, doubleArrayOf(-20.0, 50.0))
        val fitted = e.fitStart(Pt(0.0, 0.0), Pt(100.0, -60.0), listOf(right), 10.0, null)
        assertEquals(Axis.H, fitted.first)
        assertArrayEquals(doubleArrayOf(50.0), fitted.coords, 0.0)
    }

    @Test fun aRouteOnTheWrongAxisOtherwiseGainsATurnOut() {
        val e = Elbow(Axis.H, doubleArrayOf(40.0))
        val fitted = e.fitStart(Pt(0.0, 0.0), Pt(100.0, 60.0), listOf(up), 10.0, null)
        assertEquals(Axis.V, fitted.first)
        assertArrayEquals(doubleArrayOf(-10.0, 40.0), fitted.coords, 0.0)
        val pts = fitted.points(Pt(0.0, 0.0), Pt(100.0, 60.0))
        assertEquals(Pt(0.0, -10.0), pts[1])
    }

    @Test fun aRouteHeadingBackIntoItsBoxGoesOutAndRound() {
        // Leaving the box's right side but turning back left above the box towards a point below.
        val box = Rect(-100.0, -30.0, 100.0, 60.0)
        val e = Elbow(Axis.H, doubleArrayOf(-50.0))
        val fitted = e.fitStart(Pt(0.0, 0.0), Pt(-200.0, 200.0), listOf(right), 10.0, box)
        assertArrayEquals(doubleArrayOf(10.0, 40.0, -50.0), fitted.coords, 0.0)
        val pts = fitted.points(Pt(0.0, 0.0), Pt(-200.0, 200.0))
        assertEquals(Pt(10.0, 0.0), pts[1])
        assertEquals(Pt(10.0, 40.0), pts[2])
        assertEquals(Pt(-50.0, 40.0), pts[3])
    }

    @Test fun theEndIsMendedTheSameWayFromItsSide() {
        val e = Elbow(Axis.H, doubleArrayOf(40.0))
        // The end now sits on a top edge: the route must come down onto it.
        val fitted = e.fitEnd(Pt(0.0, 0.0), Pt(100.0, 60.0), listOf(up), 10.0, null)
        val pts = fitted.points(Pt(0.0, 0.0), Pt(100.0, 60.0))
        val last = pts[pts.size - 2]
        assertEquals(100.0, last.x, 1e-9)
        assertTrue(last.y < 60.0)
    }
}

