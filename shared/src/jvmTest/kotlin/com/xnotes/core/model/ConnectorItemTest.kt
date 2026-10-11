package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.pal.FontFace
import com.xnotes.core.shapes.Axis
import com.xnotes.core.shapes.Elbow
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.PathRole
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.vector.VectorSeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ConnectorItemTest {

    private val ink = Rgba(10, 20, 30, 255)
    private val measurer = FakeTextMeasurer()

    private fun elbow(from: Pt, to: Pt, vararg coords: Double, first: Axis = Axis.H) =
        ConnectorItem(Routing.ELBOW, ConnectorEnd(from), ConnectorEnd(to), ink, 2.0).apply { elbow = Elbow(first, coords) }

    private fun near(want: Pt, got: Pt, tol: Double = 1e-9) = assertTrue("$want vs $got", want.distanceTo(got) <= tol)

    @Test fun aStraightConnectorRunsThroughItsWaypoints() {
        val c = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(0.0, 0.0)), ConnectorEnd(Pt(100.0, 0.0)), ink)
        c.waypoints = listOf(Pt(50.0, 40.0))
        assertEquals(listOf(Pt(0.0, 0.0), Pt(50.0, 40.0), Pt(100.0, 0.0)), c.route())
        assertEquals(2, c.body().segments.size)
    }

    @Test fun anElbowRunsSquareAndDropsBendsOfNoLength() {
        assertEquals(listOf(Pt(0.0, 0.0), Pt(40.0, 0.0), Pt(40.0, 60.0), Pt(100.0, 60.0)), elbow(Pt(0.0, 0.0), Pt(100.0, 60.0), 40.0).route())
        assertEquals(listOf(Pt(0.0, 10.0), Pt(100.0, 10.0)), elbow(Pt(0.0, 10.0), Pt(100.0, 10.0), 40.0).route())
    }

    @Test fun roundedAndCurvedConnectorsDrawCurves() {
        val c = elbow(Pt(0.0, 0.0), Pt(100.0, 60.0), 40.0)
        c.routing = Routing.ROUNDED
        assertTrue(c.body().segments.any { it is VectorSeg.Cubic })
        c.routing = Routing.CURVED
        assertTrue(c.body().segments.all { it is VectorSeg.Cubic })
        near(Pt(100.0, 60.0), c.body().segments.last().end)
    }

    @Test fun aHeadSitsOnTheEndPointingAlongTheLastSegment() {
        val c = elbow(Pt(0.0, 0.0), Pt(100.0, 60.0), 40.0)
        c.headEnd = Head.FILLED
        val head = c.paths().single { it.role == PathRole.HEAD_FILLED }.contours.single()
        near(Pt(100.0, 60.0), head.start)
        val body = c.paths().first { it.role == PathRole.STROKE_ONLY }.contours.single()
        near(Pt(100.0 - Head.length(2.0), 60.0), body.segments.last().end)
    }

    @Test fun aLabelSitsOnTheLineWhichBreaksAroundIt() {
        val c = elbow(Pt(0.0, 0.0), Pt(200.0, 0.0))
        c.routing = Routing.STRAIGHT
        c.label = ShapeLabel("Hi", TextStyle(ink, 10.0, FontFace.SANS), measurer)
        val r = c.labelRect()!!
        near(Pt(100.0, 0.0), r.center)
        assertEquals(2, c.paths().single { it.role == PathRole.STROKE_ONLY }.contours.size)
        assertTrue(c.bounds().contains(Pt(r.right, r.bottom)))
        assertTrue(c.contains(r.center))
        c.labelAt = 0.25
        near(Pt(50.0, 0.0), c.labelRect()!!.center)
    }

    @Test fun aLongLabelWrapsAtItsWidest() {
        val c = elbow(Pt(0.0, 0.0), Pt(2000.0, 0.0))
        c.label = ShapeLabel("x".repeat(400), TextStyle(ink, 10.0, FontFace.SANS), measurer)
        assertEquals(c.label!!.lineHeight * ConnectorItem.MAX_WIDTH_LINES, c.labelRect()!!.w, 1e-9)
    }

    @Test fun anEmptyLabelHasAFrameToTypeInButNoRect() {
        val c = elbow(Pt(0.0, 0.0), Pt(200.0, 0.0))
        c.label = ShapeLabel("", TextStyle(ink, 10.0, FontFace.SANS), measurer)
        assertNull(c.labelRect())
        assertNotNull(c.labelFrame())
    }

    @Test fun itIsHitOnItsLineAndNotBesideIt() {
        val c = elbow(Pt(0.0, 0.0), Pt(100.0, 60.0), 40.0)
        assertTrue(c.contains(Pt(40.0, 30.0)))
        assertFalse(c.contains(Pt(70.0, 30.0)))
        assertTrue(c.intersectsCircle(70.0, 30.0, 31.0))
        assertFalse(c.intersectsCircle(70.0, 30.0, 20.0))
    }

    @Test fun aMoveCarriesTheRouteAndKeepsTheGlue() {
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(-50.0, -20.0), Pt(0.0, 20.0), ink)
        val c = elbow(Pt(0.0, 0.0), Pt(100.0, 60.0), 40.0)
        c.start = ConnectorEnd(Pt(0.0, 0.0), shape, 5)
        val before = c.route()
        c.translate(10.0, 5.0)
        assertSame(shape, c.start.target)
        assertEquals(5, c.start.point)
        assertEquals(before.map { it + Pt(10.0, 5.0) }, c.route())
    }

    @Test fun aQuarterTurnTurnsItAndAWidthStaysAWidth() {
        val c = elbow(Pt(0.0, 0.0), Pt(100.0, 60.0), 40.0)
        val t = Affine.rotateAbout(Pt(50.0, 30.0), PI / 2.0)
        val want = c.route().map(t::apply)
        c.applyTransform(t)
        val got = c.route()
        assertEquals(want.size, got.size)
        for (i in want.indices) near(want[i], got[i], 1e-9)
        assertEquals(2.0, c.strokeWidth, 0.0)
    }

    @Test fun aSnapshotPutsBackTheEndsTheRouteAndTheLabelsPlace() {
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(-50.0, -20.0), Pt(0.0, 20.0), ink)
        val c = elbow(Pt(0.0, 0.0), Pt(100.0, 60.0), 40.0)
        c.start = ConnectorEnd(Pt(0.0, 0.0), shape, 5)
        val snap = c.snapshotGeometry()
        assertEquals(snap, c.snapshotGeometry())
        c.start = c.start.loose()
        c.elbow = c.elbow.withCoord(1, 70.0)
        c.labelAt = 0.2
        assertNotEquals(snap, c.snapshotGeometry())
        c.restoreGeometry(snap)
        assertSame(shape, c.start.target)
        assertEquals(40.0, c.elbow.coords[0], 0.0)
        assertEquals(0.5, c.labelAt, 0.0)
    }

    @Test fun theAreaEraserCutsItIntoInk() {
        val c = elbow(Pt(0.0, 0.0), Pt(100.0, 0.0))
        c.routing = Routing.STRAIGHT
        val pieces = c.erasedBy(50.0, 0.0, 10.0)!!
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it is Stroke })
        assertNull(c.erasedBy(50.0, 40.0, 10.0))
    }

    @Test fun itOffersItsBendsAndTheMiddleOfEachStretch() {
        val points = elbow(Pt(0.0, 0.0), Pt(100.0, 60.0), 40.0).connectionPoints()
        val at = points.map { it.at }
        assertEquals(listOf(Pt(20.0, 0.0), Pt(40.0, 0.0), Pt(40.0, 30.0), Pt(40.0, 60.0), Pt(70.0, 60.0)), at)
        // A bend offers the ways out clear of both stretches it joins.
        val bend = points[1]
        assertTrue(bend.normals.any { it.distanceTo(Pt(1.0, 0.0)) < 1e-9 })
        assertTrue(bend.normals.any { it.distanceTo(Pt(0.0, -1.0)) < 1e-9 })
    }

    @Test fun aRestyleRecoloursTheLineAndALabelInItsColour() {
        val c = elbow(Pt(0.0, 0.0), Pt(100.0, 0.0))
        c.label = ShapeLabel("a", TextStyle(ink, 10.0, FontFace.SANS), measurer)
        DrawStyle(Rgba(200, 0, 0, 255), 5.0).applyTo(c)
        assertEquals(Rgba(200, 0, 0, 255), c.strokeRgba)
        assertEquals(5.0, c.strokeWidth, 0.0)
        assertEquals(Rgba(200, 0, 0, 255), c.label!!.style.rgba)
    }

    @Test fun aHandleTheLabelCoversMovesToWhereItsSegmentShows() {
        val c = elbow(Pt(0.0, 0.0), Pt(200.0, 100.0), 100.0)
        val bare = c.segmentHandles().single()
        assertEquals(Pt(100.0, 50.0), bare)
        c.label = ShapeLabel("label", TextStyle(ink, 10.0, FontFace.SANS), measurer)
        val r = c.labelRect()!!
        assertTrue("the label sits on the middle segment", r.contains(Pt(100.0, 50.0)))
        val moved = c.segmentHandles().single()
        assertFalse(r.contains(moved))
        assertEquals(100.0, moved.x, 1e-9)
        assertEquals(ConnectorEdit.Grab.SEGMENT, ConnectorEdit.grab(c, moved, 3.0)!!.grab)
    }

    @Test fun aConnectorsLabelSlidesAlongItsLine() {
        val c = elbow(Pt(0.0, 0.0), Pt(200.0, 0.0))
        c.routing = Routing.STRAIGHT
        c.label = ShapeLabel("a", TextStyle(ink, 10.0, FontFace.SANS), measurer)
        c.slideLabel(Pt(150.0, -40.0))
        assertEquals(0.75, c.labelAt, 1e-9)
    }
}

