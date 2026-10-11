package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.Axis
import com.xnotes.core.shapes.Elbow
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class GlueFollowTest {

    private val ink = Rgba(1, 2, 3, 255)

    private fun rect(l: Double, t: Double, r: Double, b: Double) = ShapeItem(ShapeKind.RECTANGLE, Pt(l, t), Pt(r, b), ink)

    /** A right-middle to left-middle elbow from [a] to [b], stepping at x = [mid]. */
    private fun joining(a: ShapeItem, b: ShapeItem, mid: Double) = ConnectorItem(
        Routing.ELBOW, ConnectorEnd(Glue.point(a, 5)!!.at, a, 5), ConnectorEnd(Glue.point(b, 7)!!.at, b, 7), ink, 2.0,
    ).apply { elbow = Elbow(Axis.H, doubleArrayOf(mid)) }

    private fun near(want: Pt, got: Pt) = assertTrue("$want vs $got", want.distanceTo(got) < 1e-9)

    @Test fun aConnectorFollowsWhatItIsGluedToAndKeepsItsMiddle() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b, 200.0)
        val all = listOf(a, b, c)
        val follow = GlueFollow.of(listOf(b), all, { it in all })
        b.translate(50.0, 40.0)
        follow.follow(Affine.translateBy(50.0, 40.0))
        near(Pt(350.0, 170.0), c.end.at)
        assertSame(b, c.end.target)
        assertEquals(200.0, c.elbow.coords[0], 0.0)
        near(Pt(100.0, 30.0), c.start.at)
        assertEquals(listOf<CanvasItem>(c), follow.items)
    }

    @Test fun aConnectorBetweenTwoMovingItemsRidesAlongWhole() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b, 200.0)
        val all = listOf(a, b, c)
        val before = c.route()
        val follow = GlueFollow.of(listOf(a, b), all, { it in all })
        a.translate(10.0, 20.0)
        b.translate(10.0, 20.0)
        follow.follow(Affine.translateBy(10.0, 20.0))
        assertEquals(before.map { it + Pt(10.0, 20.0) }, c.route())
    }

    @Test fun aMovingConnectorKeepsItsEndOnWhatStays() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b, 200.0)
        val all = listOf(a, b, c)
        val follow = GlueFollow.of(listOf(a, c), all, { it in all })
        a.translate(0.0, -50.0)
        c.translate(0.0, -50.0)
        follow.follow(Affine.translateBy(0.0, -50.0))
        near(Pt(100.0, -20.0), c.start.at)
        near(Pt(300.0, 130.0), c.end.at)
        assertSame(b, c.end.target)
    }

    @Test fun unpinnedAMovingConnectorMovesWhole() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b, 200.0)
        val all = listOf(a, b, c)
        val follow = GlueFollow.of(listOf(c), all, { it in all }, pin = false)
        assertTrue(follow.items.isEmpty())
        assertTrue(!follow.places(c))
        c.translate(5.0, 5.0)
        follow.follow(Affine.translateBy(5.0, 5.0))
        near(Pt(105.0, 35.0), c.start.at)
    }

    @Test fun aQuarterTurnedTargetKeepsItsEndsOnTheSamePlaceAfterItFramesItselfAnew() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b, 200.0)
        val all = listOf(a, b, c)
        val turn = com.xnotes.core.geometry.Affine.rotateAbout(Pt(200.0, 80.0), PI / 2.0)
        val follow = GlueFollow.of(listOf(a, b, c), all, { it in all })
        a.applyTransform(turn)
        b.applyTransform(turn)
        c.applyTransform(turn)
        follow.follow(turn)
        follow.settle()
        near(Glue.point(b, c.end.point)!!.at, c.end.at)
        near(Glue.point(a, c.start.point)!!.at, c.start.at)
        assertTrue(c.end.point != 7)
    }

    @Test fun aConnectorGluedToAFollowingConnectorFollowsInTurn() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b, 200.0)
        val mid = Glue.points(c).indexOfFirst { it.at.distanceTo(Pt(200.0, 80.0)) < 1e-9 }
        assertTrue(mid >= 0)
        val tee = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(260.0, 400.0)), ConnectorEnd(Pt(200.0, 80.0), c, mid), ink)
        val all = listOf(a, b, c, tee)
        val follow = GlueFollow.of(listOf(a), all, { it in all })
        assertEquals(listOf<CanvasItem>(c, tee), follow.items)
        a.translate(0.0, 20.0)
        follow.follow(Affine.translateBy(0.0, 20.0))
        near(Glue.point(c, mid)!!.at, tee.end.at)
    }

    @Test fun aTargetThatIsGoneHoldsNothing() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b, 200.0)
        val all = listOf(a, c)
        assertTrue(GlueFollow.of(listOf(b), all, { it in all }).isEmpty)
    }

    @Test fun anEndThatNowLeavesAnotherWayGetsItsRouteMended() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b, 200.0)
        val all = listOf(a, b, c)
        val follow = GlueFollow.of(listOf(b), all, { it in all })
        val turn = Affine.rotateAbout(b.box.center, PI / 2.0)
        b.applyTransform(turn)
        follow.follow(turn)
        // The left middle now faces up, so the route arrives from above.
        val pts = c.elbow.points(c.start.at, c.end.at)
        val last = pts[pts.size - 2]
        assertEquals(c.end.at.x, last.x, 1e-9)
        assertTrue(last.y < c.end.at.y)
    }

    @Test fun aDragTheRendererIsOffsettingIsFollowedWhereItIsDrawn() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b, 200.0)
        val all = listOf(a, b, c)
        val follow = GlueFollow.of(listOf(b), all, { it in all })
        val shift = Affine.translateBy(-30.0, 0.0)
        follow.follow(shift) { if (it === b) shift else null }
        near(Pt(270.0, 130.0), c.end.at)
        follow.restore()
        near(Pt(300.0, 130.0), c.end.at)
    }

    @Test fun aTeeWhosePointIsGoneTakesTheNearestOne() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b, 200.0)
        val last = Glue.points(c).lastIndex
        val tee = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(350.0, 400.0)), ConnectorEnd(Glue.points(c)[last].at, c, last), ink)
        val all = listOf(a, b, c, tee)
        val follow = GlueFollow.of(listOf(c), all, { it in all }, rigid = false, pin = false)
        // The step flattens away, so the connector keeps fewer points than the tee's number.
        c.elbow = Elbow(Axis.H, DoubleArray(0))
        b.translate(0.0, -100.0)
        c.end = c.end.at(Glue.point(b, 7)!!.at)
        follow.follow(Affine.IDENTITY)
        assertTrue(Glue.points(c).size <= last)
        assertSame(c, tee.end.target)
        assertTrue(tee.end.point in Glue.points(c).indices)
        near(Glue.point(c, tee.end.point)!!.at, tee.end.at)
    }

    @Test fun aCutConnectorsLabelBecomesATextBoxWhereItWas() {
        val c = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(0.0, 0.0)), ConnectorEnd(Pt(200.0, 0.0)), ink)
        c.label = ShapeLabel("ok", TextStyle(ink, 10.0, com.xnotes.core.pal.FontFace.SANS), com.xnotes.core.FakeTextMeasurer())
        val box = c.labelAsTextBox()!!
        assertEquals("ok", box.text)
        assertEquals(c.labelRect()!!.center.x, box.pos.x + box.width / 2.0, 1e-9)
    }
}

