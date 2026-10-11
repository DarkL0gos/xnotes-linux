package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ConnectorDraftTest {

    private val ink = Rgba(1, 2, 3, 255)
    private val reach = 10.0
    private val margin = 16.0

    private fun rect(l: Double, t: Double, r: Double, b: Double) = ShapeItem(ShapeKind.RECTANGLE, Pt(l, t), Pt(r, b), ink)

    private fun start(at: Pt, items: List<CanvasItem>, routing: Routing = Routing.ELBOW) =
        ConnectorDraft.startAt(at, items, reach) { ConnectorItem(routing, it, ConnectorEnd(it.at), ink, 2.0) }!!

    @Test fun aPressNearAPointGluesTheStartAndElsewhereLeavesItLoose() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val glued = start(Pt(104.0, 33.0), listOf(a))
        assertSame(a, glued.connector.start.target)
        assertEquals(Pt(100.0, 30.0), glued.connector.start.at)
        val loose = start(Pt(300.0, 300.0), listOf(a))
        assertNull(loose.connector.start.target)
        assertEquals(Pt(300.0, 300.0), loose.connector.start.at)
    }

    @Test fun theFarEndGluesNearAPointAndRoutesRoundWhatIsInTheWay() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(400.0, 0.0, 500.0, 60.0)
        val wall = rect(200.0, -100.0, 260.0, 160.0)
        val items = listOf(a, wall, b)
        val draft = start(Pt(100.0, 30.0), items)
        draft.drag(Pt(397.0, 31.0), items, reach, margin)
        val c = draft.connector
        assertSame(b, c.end.target)
        assertEquals(Pt(400.0, 30.0), c.end.at)
        assertSame(b, draft.hot!!.target)
        // Round the wall, never through it.
        val w = wall.box
        val route = c.route()
        for (i in 1 until route.size) {
            val p = route[i - 1]
            val q = route[i]
            val through = if (abs(p.y - q.y) < 1e-9) p.y > w.top && p.y < w.bottom && maxOf(p.x, q.x) > w.left && minOf(p.x, q.x) < w.right
            else p.x > w.left && p.x < w.right && maxOf(p.y, q.y) > w.top && minOf(p.y, q.y) < w.bottom
            assertFalse("segment $p-$q runs through the wall", through)
        }
    }

    @Test fun theFarEndGoesLooseAwayFromEveryPoint() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val draft = start(Pt(100.0, 30.0), listOf(a))
        draft.drag(Pt(300.0, 200.0), listOf(a), reach, margin)
        assertNull(draft.connector.end.target)
        assertNull(draft.hot)
        assertEquals(Pt(300.0, 200.0), draft.connector.end.at)
    }

    @Test fun theFarEndNeverGluesBackOntoItsOwnStart() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val draft = start(Pt(100.0, 30.0), listOf(a))
        draft.drag(Pt(101.0, 31.0), listOf(a), reach, margin)
        assertNull(draft.connector.end.target)
        draft.drag(Pt(100.0, 2.0), listOf(a), reach, margin)
        assertSame(a, draft.connector.end.target)
        assertEquals(Pt(100.0, 0.0), draft.connector.end.at)
    }

    @Test fun aStraightDraftStaysStraight() {
        val draft = start(Pt(0.0, 0.0), emptyList(), Routing.STRAIGHT)
        draft.drag(Pt(80.0, 50.0), emptyList(), reach, margin)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(80.0, 50.0)), draft.connector.route())
    }

    @Test fun onlyADraftThatJoinsOrRunsFarEnoughIsKept() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val draft = start(Pt(100.0, 30.0), listOf(a))
        draft.drag(Pt(101.0, 30.0), listOf(a), reach, margin)
        assertFalse(draft.worthKeeping(3.0))
        draft.drag(Pt(100.0, 59.0), listOf(a), reach, margin)
        assertTrue("two points of one shape joined", draft.worthKeeping(3.0))
    }
}
