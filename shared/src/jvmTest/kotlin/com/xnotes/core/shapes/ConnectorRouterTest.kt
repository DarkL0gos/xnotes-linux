package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ConnectorRouterTest {

    private val margin = 16.0

    private val right = Pt(1.0, 0.0)
    private val left = Pt(-1.0, 0.0)
    private val up = Pt(0.0, -1.0)
    private val down = Pt(0.0, 1.0)

    private fun port(box: Rect, at: Pt, way: Pt) = ConnectorRouter.Port(at, listOf(way), box)

    /** Every segment runs along a page axis, from the start port to the end port. */
    private fun assertSquare(route: List<Pt>, from: Pt, to: Pt) {
        assertEquals(from, route.first())
        assertEquals(to, route.last())
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            assertTrue("segment $a-$b is not square", abs(a.x - b.x) < 1e-9 || abs(a.y - b.y) < 1e-9)
        }
    }

    /** True when some segment of [route] runs through [r]'s inside. */
    private fun crosses(route: List<Pt>, r: Rect): Boolean {
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            if (abs(a.y - b.y) < 1e-9) {
                if (a.y > r.top && a.y < r.bottom && max(a.x, b.x) > r.left && min(a.x, b.x) < r.right) return true
            } else {
                if (a.x > r.left && a.x < r.right && max(a.y, b.y) > r.top && min(a.y, b.y) < r.bottom) return true
            }
        }
        return false
    }

    @Test fun facingBoxesInLineAreJoinedStraight() {
        val a = Rect(0.0, 0.0, 100.0, 60.0)
        val b = Rect(300.0, 0.0, 100.0, 60.0)
        val route = ConnectorRouter.route(port(a, Pt(100.0, 30.0), right), port(b, Pt(300.0, 30.0), left), emptyList(), margin)
        assertEquals(listOf(Pt(100.0, 30.0), Pt(300.0, 30.0)), route)
    }

    @Test fun offsetBoxesAreJoinedByAStepHalfwayBetweenThem() {
        val a = Rect(0.0, 0.0, 100.0, 60.0)
        val b = Rect(300.0, 200.0, 100.0, 60.0)
        val route = ConnectorRouter.route(port(a, Pt(100.0, 30.0), right), port(b, Pt(300.0, 230.0), left), emptyList(), margin)
        assertSquare(route, Pt(100.0, 30.0), Pt(300.0, 230.0))
        assertEquals(4, route.size)
        assertEquals(200.0, route[1].x, 1e-9)
    }

    @Test fun aBoxInTheWayIsGoneRound() {
        val a = Rect(0.0, 0.0, 100.0, 60.0)
        val b = Rect(400.0, 0.0, 100.0, 60.0)
        val wall = Rect(200.0, -100.0, 60.0, 260.0)
        val route = ConnectorRouter.route(port(a, Pt(100.0, 30.0), right), port(b, Pt(400.0, 30.0), left), listOf(wall), margin)
        assertSquare(route, Pt(100.0, 30.0), Pt(400.0, 30.0))
        assertFalse(crosses(route, wall))
        assertFalse(crosses(route, wall.outset(margin - 1e-6)))
    }

    @Test fun endsFacingAwayGoRoundTheirOwnBoxes() {
        val a = Rect(0.0, 0.0, 100.0, 60.0)
        val b = Rect(300.0, 0.0, 100.0, 60.0)
        val route = ConnectorRouter.route(port(a, Pt(0.0, 30.0), left), port(b, Pt(400.0, 30.0), right), emptyList(), margin)
        assertSquare(route, Pt(0.0, 30.0), Pt(400.0, 30.0))
        assertFalse(crosses(route, a))
        assertFalse(crosses(route, b))
        // It leaves against its start's way out and arrives from beyond the end's.
        assertTrue(route[1].x < 0.0)
        assertTrue(route[route.size - 2].x > 400.0)
    }

    @Test fun aCornerPointTakesWhicheverOfItsWaysIsCheaper() {
        val a = Rect(0.0, 0.0, 100.0, 60.0)
        val b = Rect(100.0, 300.0, 100.0, 60.0)
        val from = ConnectorRouter.Port(Pt(100.0, 60.0), listOf(right, down), a)
        val route = ConnectorRouter.route(from, port(b, Pt(150.0, 300.0), up), emptyList(), margin)
        // Out along the bottom edge's line and straight down is one bend; out downward is two.
        assertEquals(listOf(Pt(100.0, 60.0), Pt(150.0, 60.0), Pt(150.0, 300.0)), route)
    }

    @Test fun aLooseEndMayArriveAnyWayEvenInsideABox() {
        val a = Rect(0.0, 0.0, 100.0, 60.0)
        val other = Rect(280.0, 180.0, 100.0, 100.0)
        val end = ConnectorRouter.Port(Pt(330.0, 230.0))
        val route = ConnectorRouter.route(port(a, Pt(100.0, 30.0), right), end, listOf(other), margin)
        assertSquare(route, Pt(100.0, 30.0), Pt(330.0, 230.0))
        assertTrue(route.size <= 3)
    }

    @Test fun aStartBoxedInOnEverySideStillGetsALine() {
        val a = Rect(0.0, 0.0, 100.0, 60.0)
        val walls = listOf(
            Rect(-60.0, -60.0, 220.0, 40.0),
            Rect(-60.0, 80.0, 220.0, 40.0),
            Rect(-60.0, -20.0, 40.0, 100.0),
            Rect(120.0, -20.0, 40.0, 100.0),
        )
        val b = Rect(500.0, 0.0, 100.0, 60.0)
        val route = ConnectorRouter.route(port(a, Pt(100.0, 30.0), right), port(b, Pt(500.0, 30.0), left), walls, margin)
        assertSquare(route, Pt(100.0, 30.0), Pt(500.0, 30.0))
    }

    @Test fun aSpentBudgetFallsBackToAPlainElbow() {
        val a = Rect(0.0, 0.0, 100.0, 60.0)
        val b = Rect(300.0, 200.0, 100.0, 60.0)
        val wall = Rect(180.0, -400.0, 40.0, 1000.0)
        val route = ConnectorRouter.route(port(a, Pt(100.0, 30.0), right), port(b, Pt(300.0, 230.0), left), listOf(wall), margin, budget = 1)
        assertSquare(route, Pt(100.0, 30.0), Pt(300.0, 230.0))
        assertTrue(crosses(route, wall))
    }

    @Test fun manyObstaclesRouteQuickly() {
        val boxes = (0 until 60).map { i -> Rect(150.0 + (i % 10) * 90.0, -300.0 + (i / 10) * 110.0, 50.0, 60.0) }
        val a = Rect(0.0, 0.0, 100.0, 60.0)
        val b = Rect(1200.0, 0.0, 100.0, 60.0)
        val started = System.nanoTime()
        val route = ConnectorRouter.route(port(a, Pt(100.0, 30.0), right), port(b, Pt(1200.0, 30.0), left), boxes, margin)
        val ms = (System.nanoTime() - started) / 1e6
        assertSquare(route, Pt(100.0, 30.0), Pt(1200.0, 30.0))
        for (box in boxes) assertFalse(crosses(route, box))
        assertTrue("took $ms ms", ms < 500.0)
    }
}
