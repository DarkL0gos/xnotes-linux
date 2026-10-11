package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ShapeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonDraftTest {

    private val reach = 10.0
    private val dbl = 300L

    @Test fun eachTapPutsDownACorner() {
        val d = PolygonDraft()
        assertEquals(PolygonDraft.Outcome.CONTINUE, d.tap(Pt(0.0, 0.0), 0, reach, dbl))
        assertEquals(PolygonDraft.Outcome.CONTINUE, d.tap(Pt(100.0, 0.0), 1000, reach, dbl))
        assertEquals(PolygonDraft.Outcome.CONTINUE, d.tap(Pt(100.0, 80.0), 2000, reach, dbl))
        assertEquals(3, d.points.size)
    }

    @Test fun aTapOnTheFirstCornerClosesIt() {
        val d = PolygonDraft()
        d.tap(Pt(0.0, 0.0), 0, reach, dbl)
        d.tap(Pt(100.0, 0.0), 1000, reach, dbl)
        assertEquals("two corners are not a polygon yet", PolygonDraft.Outcome.CONTINUE, d.tap(Pt(3.0, 2.0), 2000, reach, dbl))
        val e = PolygonDraft()
        e.tap(Pt(0.0, 0.0), 0, reach, dbl)
        e.tap(Pt(100.0, 0.0), 1000, reach, dbl)
        e.tap(Pt(100.0, 80.0), 2000, reach, dbl)
        assertEquals(PolygonDraft.Outcome.CLOSED, e.tap(Pt(4.0, -3.0), 3000, reach, dbl))
        assertEquals(3, e.points.size)
    }

    @Test fun aDoubleTapEndsItOpen() {
        val d = PolygonDraft()
        d.tap(Pt(0.0, 0.0), 0, reach, dbl)
        d.tap(Pt(100.0, 0.0), 1000, reach, dbl)
        assertEquals(PolygonDraft.Outcome.OPEN, d.tap(Pt(102.0, 1.0), 1200, reach, dbl))
        assertEquals("the second tap adds no corner", 2, d.points.size)
    }

    @Test fun aSlowSecondTapIsAnotherCorner() {
        val d = PolygonDraft()
        d.tap(Pt(0.0, 0.0), 0, reach, dbl)
        d.tap(Pt(100.0, 0.0), 1000, reach, dbl)
        assertEquals(PolygonDraft.Outcome.CONTINUE, d.tap(Pt(102.0, 1.0), 1600, reach, dbl))
        assertEquals(3, d.points.size)
    }

    @Test fun thePreviewRunsOutToThePen() {
        val d = PolygonDraft()
        d.tap(Pt(0.0, 0.0), 0, reach, dbl)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(5.0, 5.0)), d.preview(Pt(5.0, 5.0)))
        assertEquals(1, d.preview(null).size)
    }

    @Test fun aPlacedPolygonTakesTheToolsStyle() {
        val cfg = ShapeConfig(preset = ShapePresets.FREE_POLYGON, fill = true, headEnd = Head.OPEN)
        assertTrue(cfg.placesCorners)
        val pts = listOf(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(25.0, 40.0))
        val closed = cfg.placedShape(pts, true, Rgba(1, 2, 3), 2.0)
        assertEquals(ShapeKind.POLYGON, closed.shape)
        assertTrue(closed.fillRgba != null)
        assertEquals(Head.NONE, closed.headEnd)
        val open = cfg.placedShape(pts, false, Rgba(1, 2, 3), 2.0)
        assertEquals(ShapeKind.POLYLINE, open.shape)
        assertNull(open.fillRgba)
        assertEquals(Head.OPEN, open.headEnd)
    }
}
