package com.xnotes.core.shapes

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.pal.Pen
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapePainterTest {

    private val ink = Rgba(0, 0, 0)
    private val fill = Rgba(100, 150, 200, 64)
    private val pen = Pen(ink, 3.0, cosmetic = false)
    private val box = Rect(0.0, 0.0, 10.0, 10.0)
    private val line = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0))

    private fun paint(paths: List<ShapePath>, pen: Pen = this.pen): FakeRenderer {
        val r = FakeRenderer()
        ShapePainter.fill(r, paths, fill, ink)
        ShapePainter.stroke(r, paths, pen)
        return r
    }

    @Test fun anOutlineIsFilledThenStroked() {
        assertEquals(listOf("fillPath", "strokePath"), paint(listOf(ShapePath.rect(box))).ops)
    }

    @Test fun eachRolePaintsOnlyWhatItShould() {
        assertEquals(listOf("strokePath"), paint(listOf(ShapePath.polyline(line))).ops)
        assertEquals(listOf("fillPath"), paint(listOf(ShapePath.rect(box, PathRole.FILL_ONLY))).ops)
        assertEquals(listOf("strokePath"), paint(listOf(ShapePath.polyline(line, PathRole.HEAD))).ops)
        assertEquals(listOf("strokePath"), paint(listOf(ShapePath.polyline(line, PathRole.HIDDEN))).ops)
    }

    @Test fun aHeadStaysSolidOnADashedShape() {
        val r = paint(listOf(ShapePath.polyline(line, PathRole.HEAD)), pen.copy(dashed = true))
        assertFalse(r.pathPens.single().dashed)
    }

    @Test fun hiddenEdgesAreDashedOnASolidShape() {
        val r = paint(listOf(ShapePath.polyline(line, PathRole.HIDDEN)))
        assertTrue(r.pathPens.single().dashed)
    }

    @Test fun shadedFacesTintTheFillAndKeepItsAlpha() {
        val dark = ShapePainter.fillColor(PathRole.SHADE_DARK, fill, ink)!!
        val light = ShapePainter.fillColor(PathRole.SHADE_LIGHT, fill, ink)!!
        assertTrue(dark.r < fill.r && dark.g < fill.g && dark.b < fill.b)
        assertTrue(light.r > fill.r && light.g > fill.g && light.b > fill.b)
        assertEquals(64, dark.a)
        assertEquals(64, light.a)
        assertNull(ShapePainter.fillColor(PathRole.STROKE_ONLY, fill, ink))
    }

    @Test fun aDashedArrowDashesItsShaftButNotItsHead() {
        val arrow = ShapeItem(ShapeKind.ARROW, Pt(0.0, 0.0), Pt(100.0, 0.0), ink, 3.0, dashed = true)
        val r = FakeRenderer()
        arrow.paint(r)
        assertEquals(listOf(true, false), r.pathPens.map { it.dashed })
    }

    @Test fun anOpenShapeNeverFillsEvenWithAFillSet() {
        val line = ShapeItem.poly(ShapeKind.POLYLINE, listOf(Pt(0.0, 0.0), Pt(10.0, 10.0)), ink, 3.0, fill)
        val r = FakeRenderer()
        line.paint(r)
        assertEquals(listOf("strokePath"), r.ops)
    }
}
