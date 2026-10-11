package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapeRotationTest {

    private val ink = Rgba(0, 0, 0)

    private fun rect(kind: ShapeKind = ShapeKind.RECTANGLE) =
        ShapeItem(kind, Pt(0.0, 0.0), Pt(100.0, 50.0), ink, 3.0)

    private fun assertPt(want: Pt, got: Pt, eps: Double = 1e-6) {
        assertEquals("x of $got", want.x, got.x, eps)
        assertEquals("y of $got", want.y, got.y, eps)
    }

    /** Every vertex of [want] matches one of [got], in any order. */
    private fun assertSameCorners(want: List<Pt>, got: List<Pt>) {
        assertEquals(want.size, got.size)
        for (w in want) assertTrue("missing $w in $got", got.any { it.distanceTo(w) < 1e-6 })
    }

    private fun corners(s: ShapeItem): List<Pt> {
        val b = s.box
        return listOf(Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom)).map(s::toPage)
    }

    @Test fun aTurnKeepsTheKindAndTheFrame() {
        val s = rect()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 6))
        assertEquals(ShapeKind.RECTANGLE, s.shape)
        assertEquals(PI / 6, s.rotation, 1e-12)
        assertEquals(100.0, s.box.w, 1e-9)
        assertEquals(50.0, s.box.h, 1e-9)
        assertPt(Pt(50.0, 25.0), s.box.center)
    }

    @Test fun aTurnAboutAnotherPointMovesTheCentre() {
        val s = rect()
        val t = Affine.rotateAbout(Pt.ZERO, PI / 3)
        s.applyTransform(t)
        assertPt(t.apply(Pt(50.0, 25.0)), s.box.center)
        assertSameCorners(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 50.0), Pt(0.0, 50.0)).map(t::apply), corners(s))
    }

    @Test fun aStretchAlongItsOwnAxesStaysARectangle() {
        val s = rect()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 5))
        val before = corners(s)
        val t = Affine.scaleAlongAxes(s.toPage(Pt(0.0, 0.0)), PI / 5, 2.0, 1.0)
        s.applyTransform(t)
        assertEquals(ShapeKind.RECTANGLE, s.shape)
        assertEquals(PI / 5, s.rotation, 1e-9)
        assertEquals(200.0, s.box.w, 1e-6)
        assertEquals(50.0, s.box.h, 1e-6)
        assertSameCorners(before.map(t::apply), corners(s))
    }

    @Test fun aShearMakesATurnedRectangleAPolygon() {
        val s = rect()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 5))
        val before = corners(s)
        val t = Affine.scaleAbout(Pt.ZERO, 2.0, 1.0)
        s.applyTransform(t)
        assertEquals(ShapeKind.POLYGON, s.shape)
        assertSameCorners(before.map(t::apply), s.vertices()!!)
    }

    @Test fun aShearedEllipseIsStillAnExactEllipse() {
        val s = rect(ShapeKind.ELLIPSE)
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 7))
        val c = s.box.center
        val onCurve = (0 until 12).map { k ->
            val a = 2 * PI * k / 12
            s.toPage(Pt(c.x + 50.0 * cos(a), c.y + 25.0 * sin(a)))
        }
        val t = Affine.scaleAbout(Pt(10.0, 10.0), 1.8, 0.7)
        s.applyTransform(t)
        assertEquals(ShapeKind.ELLIPSE, s.shape)
        val rx = s.box.w / 2.0
        val ry = s.box.h / 2.0
        for (p in onCurve.map(t::apply)) {
            val q = s.toLocal(p)
            val e = ((q.x - s.box.centerX) / rx).let { it * it } + ((q.y - s.box.centerY) / ry).let { it * it }
            assertEquals("off the new ellipse: $p", 1.0, e, 1e-9)
        }
    }

    @Test fun aStretchedCircleBecomesAnEllipse() {
        val s = ShapeItem(ShapeKind.CIRCLE, Pt(0.0, 0.0), Pt(50.0, 50.0), ink, 3.0)
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 1.0))
        assertEquals(ShapeKind.ELLIPSE, s.shape)
        val round = ShapeItem(ShapeKind.CIRCLE, Pt(0.0, 0.0), Pt(50.0, 50.0), ink, 3.0)
        round.applyTransform(Affine.rotateAbout(Pt.ZERO, 1.0))
        assertEquals(ShapeKind.CIRCLE, round.shape)
    }

    @Test fun aMirroredTriangleKeepsItsApex() {
        val s = rect(ShapeKind.TRIANGLE)
        val before = s.paths().single().contours.single().let { c -> listOf(c.start) + c.segments.map { it.end } }
        val t = Affine.scaleAbout(Pt(50.0, 25.0), 1.0, -1.0)
        s.applyTransform(t)
        assertEquals(ShapeKind.TRIANGLE, s.shape)
        val after = s.paths().single().contours.single().let { c -> listOf(c.start) + c.segments.map { it.end } }
        assertSameCorners(before.map(t::apply), after)
    }

    @Test fun aTurnedPolygonMapsItsPointsExactly() {
        val verts = listOf(Pt(0.0, 0.0), Pt(80.0, 10.0), Pt(60.0, 70.0), Pt(-10.0, 40.0))
        val s = ShapeItem.poly(ShapeKind.POLYGON, verts, ink)
        val t = Affine.rotateAbout(Pt(20.0, 20.0), 0.7).compose(Affine.scaleAbout(Pt.ZERO, 1.5, 0.5))
        s.applyTransform(t)
        assertEquals(ShapeKind.POLYGON, s.shape)
        val got = s.vertices()!!
        for (i in verts.indices) assertPt(t.apply(verts[i]), got[i])
    }

    @Test fun anUprightScaleStaysUpright() {
        val s = rect()
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 3.0))
        assertEquals(0.0, s.rotation, 0.0)
        assertEquals(200.0, s.box.w, 1e-9)
        assertEquals(150.0, s.box.h, 1e-9)
    }

    @Test fun aTurnedShapeIsHitWhereItIsDrawn() {
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 40.0), Pt(100.0, 60.0), ink, 2.0, fillRgba = Rgba(0, 0, 0, 60))
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 50.0), PI / 2 - 0.01))
        assertTrue("inside the turned bar", s.contains(Pt(50.0, 5.0)))
        assertFalse("where the upright bar was", s.contains(Pt(5.0, 50.0)))
        assertTrue(s.intersectsCircle(50.0, 95.0, 2.0))
        assertFalse(s.intersectsCircle(95.0, 50.0, 2.0))
    }

    @Test fun aTurnedEllipseBoundsItsCurveTightly() {
        val s = ShapeItem(ShapeKind.ELLIPSE, Pt(0.0, 40.0), Pt(100.0, 60.0), ink, 2.0)
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 50.0), PI / 4))
        val b = s.bounds()
        val reach = kotlin.math.hypot(50.0 * cos(PI / 4), 10.0 * sin(PI / 4))
        val pad = 2.0 / 2.0 + 1.0
        assertEquals(50.0 - reach - pad, b.left, 1e-9)
        assertEquals(2 * (reach + pad), b.w, 1e-9)
    }

    @Test fun anAreaCutOfATurnedShapeLeavesPageSpaceFragments() {
        val s = rect()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 6))
        val corner = s.toPage(Pt(100.0, 0.0))
        val frags = s.erasedBy(corner.x, corner.y, 10.0)
        assertNotNull(frags)
        val pts = (frags!!.single() as Stroke).samples.map { it.pos }
        val far = s.toPage(Pt(0.0, 50.0))
        assertTrue("the far corner survives as ink", pts.any { it.distanceTo(far) < 1e-4 })
    }

    @Test fun aSnapshotCarriesTheTurn() {
        val s = rect()
        val snap = s.snapshotGeometry()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), 0.4))
        s.restoreGeometry(snap)
        assertEquals(0.0, s.rotation, 0.0)
        val copy = s.also { it.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), 0.4)) }.deepCopy(com.xnotes.core.FakeTextMeasurer())
        assertEquals(0.4, (copy as ShapeItem).rotation, 1e-12)
    }
}
