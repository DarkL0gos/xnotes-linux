package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowKindsTest {

    private val box = Rect(10.0, 20.0, 160.0, 80.0)
    private val ink = Rgba(0, 0, 0)

    private fun corners(k: ShapeKind) = k.build(box, ShapeParams(k.defaultAdj, k.defaultN)).corners

    @Test fun eachDirectionPointsItsOwnWay() {
        assertTrue(corners(ArrowKinds.RIGHT).any { it.distanceTo(Pt(box.right, box.centerY)) < 1e-9 })
        assertTrue(corners(ArrowKinds.LEFT).any { it.distanceTo(Pt(box.left, box.centerY)) < 1e-9 })
        assertTrue(corners(ArrowKinds.UP).any { it.distanceTo(Pt(box.centerX, box.top)) < 1e-9 })
        assertTrue(corners(ArrowKinds.DOWN).any { it.distanceTo(Pt(box.centerX, box.bottom)) < 1e-9 })
        val both = corners(ArrowKinds.LEFT_RIGHT)
        assertTrue(both.any { it.distanceTo(Pt(box.left, box.centerY)) < 1e-9 } && both.any { it.distanceTo(Pt(box.right, box.centerY)) < 1e-9 })
    }

    private val arrows = listOf(
        ArrowKinds.RIGHT, ArrowKinds.LEFT, ArrowKinds.UP, ArrowKinds.DOWN, ArrowKinds.LEFT_RIGHT, ArrowKinds.UP_DOWN,
        ArrowKinds.QUAD, ArrowKinds.BENT, ArrowKinds.U_TURN, ArrowKinds.CHEVRON, ArrowKinds.PENTAGON, ArrowKinds.NOTCHED,
        ArrowKinds.CURVED_RIGHT, ArrowKinds.CURVED_LEFT, ArrowKinds.CIRCULAR, ArrowKinds.STRIPED, ArrowKinds.BENT_UP, ArrowKinds.LEFT_UP,
    )

    private fun shape(k: ShapeKind, b: Rect = box) = ShapeItem(k, b.topLeft, Pt(b.right, b.bottom), ink, 2.0)

    @Test fun everyArrowHasOneKnob() {
        assertEquals(18, arrows.size)
        for (k in arrows) assertEquals("$k", 1, k.knobs.size)
    }

    @Test fun aKnobDroppedWhereItIsChangesNothing() {
        // Square, so no arrow is deeper than its knob can make it.
        val sq = Rect(10.0, 20.0, 100.0, 100.0)
        for (k in arrows) {
            val s = shape(k, sq)
            val adj = s.adj.copyOf()
            s.dragKnob(0, s.knobDots().single())
            assertEquals("$k box", 0.0, s.box.topLeft.distanceTo(sq.topLeft) + Pt(s.box.right, s.box.bottom).distanceTo(Pt(sq.right, sq.bottom)), 1e-9)
            for (i in adj.indices) assertEquals("$k knob", adj[i], s.adj[i], 1e-9)
        }
    }

    @Test fun halvingAStraightArrowHalvesItsHead() {
        val s = shape(ArrowKinds.RIGHT)
        val knob = s.knobDots().single()
        assertEquals("halfway along the head's top edge", 0.0, knob.distanceTo(Pt(box.right - 20.0, box.top + 20.0)), 1e-9)
        // The shaft is 40 thick and the knob level with its top edge: half that puts it 10 above the centre line.
        s.dragKnob(0, Pt(knob.x + 7.0, box.centerY - 10.0))
        assertEquals("the box keeps its length", box.w, s.box.w, 1e-9)
        assertEquals("and its centre line", box.centerY, s.box.centerY, 1e-9)
        assertEquals("the head spans the box, half as high", 40.0, s.box.h, 1e-9)
        val c = s.localGeometry().corners
        assertEquals("the head's base is half as far from the tip", box.right - 20.0, c[1].x, 1e-9)
        assertEquals("the shaft is half as thick", 20.0, c[5].y - c[0].y, 1e-9)
        assertEquals("the knob stays under the pen", box.centerY - 10.0, s.knobDots().single().y, 1e-9)
    }

    @Test fun aTurnedStraightArrowThinsAcrossItsOwnShaft() {
        val tall = Rect(10.0, 20.0, 80.0, 160.0)
        val up = shape(ArrowKinds.UP, tall)
        val knob = up.knobDots().single()
        assertEquals("halfway along the head's left edge", 0.0, knob.distanceTo(Pt(tall.left + 20.0, tall.top + 20.0)), 1e-9)
        up.dragKnob(0, Pt(tall.centerX - 10.0, knob.y))
        assertEquals("narrower about the centre line", 40.0, up.box.w, 1e-9)
        assertEquals(tall.centerX, up.box.centerX, 1e-9)
        assertEquals("as long as it was", tall.top, up.box.top, 1e-9)
        assertEquals(tall.bottom, up.box.bottom, 1e-9)
    }

    @Test fun aRotatedArrowThinsAboutItsOwnCentre() {
        val s = shape(ArrowKinds.LEFT_RIGHT)
        s.rotation = 0.6
        val centre = s.box.center
        val knob = s.toLocal(s.knobDots().single())
        s.dragKnob(0, s.toPage(Pt(knob.x, box.centerY - 5.0)))
        assertEquals(0.0, s.box.center.distanceTo(centre), 1e-9)
        assertEquals(20.0, s.box.h, 1e-9)
        assertEquals(0.6, s.rotation, 0.0)
    }

    @Test fun aKnobKeepsWhichCornerTheShapeStartedFrom() {
        val s = ShapeItem(ArrowKinds.RIGHT, Pt(box.right, box.bottom), box.topLeft, ink, 2.0)
        s.dragKnob(0, Pt(s.knobDots().single().x, box.centerY - 10.0))
        assertTrue(s.start.x > s.end.x && s.start.y > s.end.y)
    }

    @Test fun aStraightArrowNeverOutgrowsItsHead() {
        val s = shape(ArrowKinds.RIGHT)
        s.dragKnob(0, Pt(box.centerX, box.centerY - 1000.0))
        assertEquals("deep enough for the head to take the whole length", 2.0 * box.w, s.box.h, 1e-9)
        s.dragKnob(0, Pt(box.centerX, box.centerY))
        assertTrue("never flat", s.box.h > 0.0)
    }

    @Test fun halvingABentArrowHalvesItsHead() {
        val b = Rect(0.0, 0.0, 120.0, 100.0)
        val s = shape(ArrowKinds.BENT, b)
        val knob = s.knobDots().single()
        assertEquals("halfway along the head's lower edge", 0.0, knob.distanceTo(Pt(107.5, 37.5)), 1e-9)
        s.dragKnob(0, Pt(100.0, 18.75))
        val c = s.localGeometry().corners
        assertEquals("the upright is half as thick", 12.5, c[8].x, 1e-9)
        assertEquals("the head is half as long", 12.5, b.right - c[2].x, 1e-9)
        assertEquals("and half as wide", 25.0, c[5].y - c[3].y, 1e-9)
        assertEquals("the box stays", b.w, s.box.w, 0.0)
    }

    @Test fun aQuadArrowsHeadsFollowItsShafts() {
        val b = Rect(0.0, 0.0, 100.0, 100.0)
        val s = shape(ArrowKinds.QUAD, b)
        assertEquals("halfway along the right head's top edge", 0.0, s.knobDots().single().distanceTo(Pt(89.0, 39.0)), 1e-9)
        s.dragKnob(0, Pt(95.0, 45.0))
        val c = s.localGeometry().corners
        assertEquals("shafts 10 thick", 10.0, c[2].x - c[22].x, 1e-9)
        assertEquals("heads twice as wide", 20.0, c[1].x - c[23].x, 1e-9)
        assertEquals("and as long as the shaft is thick", 10.0, c[1].y, 1e-9)
    }

    @Test fun aCircularArrowsHeadFollowsItsRing() {
        val b = Rect(0.0, 0.0, 200.0, 200.0)
        fun headAcross(s: ShapeItem): Double {
            // The straight runs after the outer arc: the head's outer corner, its tip, its inner corner.
            val lines = s.localGeometry().paths.single().contours.single().segments.filterIsInstance<com.xnotes.core.vector.VectorSeg.Line>()
            return lines[0].end.distanceTo(lines[2].end)
        }
        val s = shape(ArrowKinds.CIRCULAR, b)
        val before = headAcross(s)
        assertEquals("twice the ring's 25", 50.0, before, 1e-9)
        val knob = s.knobDots().single()
        val centre = b.center
        assertEquals("on the head's inner corner, two thicknesses in from the rim", 50.0, knob.distanceTo(centre), 1e-9)
        val out = (knob - centre).normalized()
        s.dragKnob(0, centre + out * (100.0 * (1.0 - 2.0 * 0.125)))
        assertEquals(0.125, s.adj[0], 1e-9)
        assertEquals("half as wide", before / 2.0, headAcross(s), 1e-9)
    }

    @Test fun aCalloutsTailReachesItsKnob() {
        val c = ShapeItem(ArrowKinds.RECT_CALLOUT, box.topLeft, Pt(box.right, box.bottom), ink, 2.0)
        val tip = c.knobDots().single()
        assertTrue("the tail starts below the box", tip.y > box.bottom)
        assertTrue("the bounds reach the tip", c.bounds().bottom >= tip.y)
        assertTrue(c.contains(tip))
        c.dragKnob(0, Pt(box.right + 60.0, box.centerY))
        val moved = c.knobDots().single()
        assertEquals(box.right + 60.0, moved.x, 1e-9)
        assertTrue(c.bounds().right >= moved.x)
    }

    @Test fun aThoughtBubbleTrailsBubblesToItsTip() {
        val g = ArrowKinds.THOUGHT.build(box, ShapeParams(ArrowKinds.THOUGHT.defaultAdj))
        assertEquals("the cloud and three bubbles", 4, g.paths.single().contours.size)
    }

    @Test fun aLineCalloutsLeaderIsAStrokeOnly() {
        val g = ArrowKinds.LINE_CALLOUT.build(box, ShapeParams(ArrowKinds.LINE_CALLOUT.defaultAdj))
        assertEquals(listOf(PathRole.OUTLINE, PathRole.STROKE_ONLY), g.paths.map { it.role })
    }

    @Test fun theArrowsPageHasTwentyFourChips() {
        val keys = ShapePresets.arrows.presets.map { it.key }
        assertEquals(24, keys.size)
        assertEquals("arrow_right", keys.first())
        assertEquals("burst", keys.last())
        assertEquals("the third page", ShapePresets.arrows, ShapePresets.pages[2])
    }
}
