package com.xnotes.core.shapes

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.vector.PathFlattener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolidMathKindsTest {

    private val box = Rect(10.0, 20.0, 120.0, 100.0)
    private val ink = Rgba(10, 20, 30)

    private val solids = SolidMathKinds.all.filter { it.id.startsWith("solid_") }

    private fun geom(k: ShapeKind, n: Int = k.defaultN, adj: DoubleArray = k.defaultAdj, width: Double = 2.0) =
        k.build(box, ShapeParams(adj, n, null, width))

    @Test fun everySolidDashesItsHiddenEdges() {
        assertEquals(10, solids.size)
        for (k in solids) assertTrue("$k has no hidden edges", geom(k).paths.any { it.role == PathRole.HIDDEN })
    }

    @Test fun aFilledCubeShadesItsTopAndSideAndAnEmptyOneDoesNot() {
        val roles = geom(SolidMathKinds.CUBE).paths.map { it.role }
        assertTrue(PathRole.SHADE_LIGHT in roles && PathRole.SHADE_DARK in roles)
        val empty = FakeRenderer()
        ShapeItem(SolidMathKinds.CUBE, box.topLeft, Pt(box.right, box.bottom), ink, 2.0).paint(empty)
        assertTrue("nothing filled without a fill", empty.pathFills.isEmpty())
        val filled = FakeRenderer()
        ShapeItem(SolidMathKinds.CUBE, box.topLeft, Pt(box.right, box.bottom), ink, 2.0, Rgba(100, 150, 200, 255)).paint(filled)
        assertEquals("top, side and front each their own tint", 3, filled.pathFills.distinct().size)
        assertTrue("the hidden edges are dashed on a solid outline", filled.pathPens.any { it.dashed })
    }

    @Test fun eachSolidsKnobSitsWhereItsDragReadsIt() {
        for (k in solids) {
            val knob = k.knobs.single()
            val adj = doubleArrayOf(0.3)
            val back = knob.drag(knob.position(box, adj, 0), box, adj, 0)
            assertEquals("$k knob drifts", 0.3, back[0], 1e-9)
            assertEquals("$k clamps", 0.6, knob.drag(Pt(1e5, 1e5), box, adj, 0)[0].coerceAtLeast(knob.drag(Pt(-1e5, -1e5), box, adj, 0)[0]), 0.0)
        }
    }

    @Test fun axesCountTheirTicksAndCarryHeadsAsWideAsTheLine() {
        val g = geom(SolidMathKinds.AXES_2D, n = 4)
        assertEquals("two axes and four ticks on each", 2 + 8, g.paths.first { it.role == PathRole.STROKE_ONLY }.contours.size)
        assertEquals(2, g.paths.count { it.role == PathRole.HEAD })
        fun headSpan(width: Double): Double {
            val head = geom(SolidMathKinds.AXES_2D, width = width).paths.first { it.role == PathRole.HEAD }.contours.single()
            val pts = listOf(head.start) + head.segments.map { it.end }
            return pts.first().distanceTo(pts.last())
        }
        assertEquals("a head is never under 12 px long", 12.0, headSpan(2.0), 1e-9)
        assertEquals("then three and a half widths", 28.0, headSpan(8.0), 1e-9)
        assertEquals(CountKind.TICKS, SolidMathKinds.AXES_2D.countKind)
    }

    @Test fun aNumberLineTicksEndToEnd() {
        val g = geom(SolidMathKinds.NUMBER_LINE, n = 5)
        val stroked = g.paths.first { it.role == PathRole.STROKE_ONLY }.contours
        assertEquals("the line and its five ticks", 6, stroked.size)
        assertEquals(2, g.paths.count { it.role == PathRole.HEAD })
    }

    @Test fun aGridDrawsItsCellsAndMayFill() {
        val g = geom(SolidMathKinds.GRID, n = 3)
        assertEquals(PathRole.OUTLINE, g.paths[0].role)
        assertEquals("two lines across each way", 4, g.paths[1].contours.size)
        assertTrue(SolidMathKinds.GRID.isClosed)
        assertEquals(CountKind.CELLS, SolidMathKinds.GRID.countKind)
    }

    @Test fun anObtuseAngleStaysInItsBox() {
        for (deg in listOf(20.0, 90.0, 120.0, 170.0)) {
            val g = geom(SolidMathKinds.ANGLE, adj = doubleArrayOf(deg))
            val pts = g.paths.flatMap { p -> p.contours.flatMap { PathFlattener.flatten(it, 0.05) } }
            val b = Rect.bounding(pts)
            assertTrue("$deg degrees leaves the box: $b", b.left >= box.left - 1e-6 && b.right <= box.right + 1e-6 && b.top >= box.top - 1e-6)
            val knob = SolidMathKinds.ANGLE.knobs.single()
            val back = knob.drag(knob.position(box, doubleArrayOf(deg), 0), box, doubleArrayOf(deg), 0)
            assertEquals("$deg knob drifts", deg, back[0], 1e-6)
        }
    }

    @Test fun notationLinesNeverFill() {
        for (k in listOf(SolidMathKinds.AXES_2D, SolidMathKinds.AXES_3D, SolidMathKinds.NUMBER_LINE, SolidMathKinds.ANGLE,
            SolidMathKinds.RIGHT_ANGLE, SolidMathKinds.CURLY_BRACE, SolidMathKinds.SQUARE_BRACKET, SolidMathKinds.ROUND_BRACKET)) {
            assertFalse("$k fills", k.isClosed)
            val r = FakeRenderer()
            ShapeItem(k, box.topLeft, Pt(box.right, box.bottom), ink, 2.0, Rgba(1, 2, 3, 80)).paint(r)
            assertTrue("$k painted a fill", r.pathFills.isEmpty())
        }
    }

    @Test fun theMergedPageHoldsBothFamiliesInOrder() {
        val keys = ShapePresets.solidsMath.presets.map { it.key }
        assertEquals(19, keys.size)
        assertEquals("solid_cube", keys.first())
        assertEquals("solid_hemisphere", keys[9])
        assertEquals("math_axes_2d", keys[10])
        assertEquals("math_round_bracket", keys.last())
        assertEquals(ShapePresets.solidsMath, ShapePresets.pages[3])
    }
}
