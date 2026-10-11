package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.Axis
import com.xnotes.core.shapes.Elbow
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ConnectorEditTest {

    private val ink = Rgba(1, 2, 3, 255)

    private fun rect(l: Double, t: Double, r: Double, b: Double) = ShapeItem(ShapeKind.RECTANGLE, Pt(l, t), Pt(r, b), ink)

    private fun elbow() = ConnectorItem(Routing.ELBOW, ConnectorEnd(Pt(0.0, 0.0)), ConnectorEnd(Pt(200.0, 100.0)), ink)
        .apply { elbow = Elbow(Axis.H, doubleArrayOf(100.0)) }

    @Test fun theEndsAreGrabbedFirstThenTheMiddleSegment() {
        val c = elbow()
        assertEquals(ConnectorEdit.Grab.START, ConnectorEdit.grab(c, Pt(3.0, 2.0), 10.0)!!.grab)
        assertEquals(ConnectorEdit.Grab.END, ConnectorEdit.grab(c, Pt(198.0, 101.0), 10.0)!!.grab)
        val seg = ConnectorEdit.grab(c, Pt(101.0, 50.0), 10.0)!!
        assertEquals(ConnectorEdit.Grab.SEGMENT, seg.grab)
        assertEquals(1, seg.index)
        assertNull(ConnectorEdit.grab(c, Pt(50.0, 50.0), 10.0))
    }

    @Test fun aMiddleSegmentSlidesAcrossAndNothingElseMoves() {
        val c = elbow()
        val edit = ConnectorEdit.grab(c, Pt(100.0, 50.0), 10.0)!!
        edit.drag(Pt(140.0, 20.0), emptyList(), 10.0, 16.0)
        assertArrayEquals(doubleArrayOf(140.0), c.elbow.coords, 0.0)
        assertEquals(Pt(0.0, 0.0), c.start.at)
        assertEquals(Pt(200.0, 100.0), c.end.at)
        edit.restore()
        assertArrayEquals(doubleArrayOf(100.0), c.elbow.coords, 0.0)
    }

    @Test fun aStraightConnectorGainsAWaypointMidStretchAndItMoves() {
        val c = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(0.0, 0.0)), ConnectorEnd(Pt(200.0, 0.0)), ink)
        val add = ConnectorEdit.grab(c, Pt(100.0, 3.0), 10.0)!!
        assertEquals(ConnectorEdit.Grab.MIDPOINT, add.grab)
        add.drag(Pt(100.0, 60.0), emptyList(), 10.0, 16.0)
        add.drag(Pt(110.0, 70.0), emptyList(), 10.0, 16.0)
        assertEquals(listOf(Pt(110.0, 70.0)), c.waypoints)
        val move = ConnectorEdit.grab(c, Pt(108.0, 72.0), 10.0)!!
        assertEquals(ConnectorEdit.Grab.WAYPOINT, move.grab)
        move.drag(Pt(90.0, 40.0), emptyList(), 10.0, 16.0)
        assertEquals(listOf(Pt(90.0, 40.0)), c.waypoints)
    }

    @Test fun aDraggedEndGluesAgainWhereItLandsAndTheRouteIsPlannedAfresh() {
        val a = rect(-100.0, -30.0, 0.0, 30.0)
        val b = rect(300.0, 70.0, 400.0, 130.0)
        val c = elbow()
        c.start = ConnectorEnd(Pt(0.0, 0.0), a, 5)
        val edit = ConnectorEdit.grab(c, Pt(200.0, 100.0), 10.0)!!
        edit.drag(Pt(298.0, 99.0), listOf(a, b), 10.0, 16.0)
        assertSame(b, c.end.target)
        assertEquals(Pt(300.0, 100.0), c.end.at)
        assertSame(edit.hot!!.target, b)
        assertSame(a, c.start.target)
        edit.restore()
        assertNull(c.end.target)
    }

    @Test fun aStraightConnectorKeepsItsWaypointsWhenAnEndMoves() {
        val c = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(0.0, 0.0)), ConnectorEnd(Pt(200.0, 0.0)), ink)
        c.waypoints = listOf(Pt(100.0, 50.0))
        ConnectorEdit.grab(c, Pt(200.0, 0.0), 10.0)!!.drag(Pt(250.0, 20.0), emptyList(), 10.0, 16.0)
        assertEquals(listOf(Pt(100.0, 50.0)), c.waypoints)
        assertEquals(Pt(250.0, 20.0), c.end.at)
    }
}
