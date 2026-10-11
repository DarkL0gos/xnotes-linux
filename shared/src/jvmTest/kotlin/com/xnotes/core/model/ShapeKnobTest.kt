package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeKnobTest {

    private val ink = Rgba(0, 0, 0)

    private fun triangle() = ShapeItem(ShapeKind.TRIANGLE, Pt(0.0, 0.0), Pt(100.0, 60.0), ink, 2.0)

    private fun corners(s: ShapeItem) = s.vertexDots().map { it.at }

    @Test fun aKnobSitsWhereItsKindSays() {
        val s = triangle()
        assertEquals(listOf(Pt(50.0, 0.0)), s.knobDots())
        assertTrue(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(1.0, 1.0), ink).knobDots().isEmpty())
    }

    @Test fun draggingATurnedKnobFollowsTheFrame() {
        val s = triangle()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 30.0), 0.6))
        s.dragKnob(0, s.toPage(Pt(20.0, -40.0)))
        assertEquals(0.2, s.adj[0], 1e-9)
        assertEquals(0.0, s.knobDots().single().distanceTo(s.toPage(Pt(20.0, 0.0))), 1e-9)
    }

    @Test fun aFlipMirrorsAnOffCentreApex() {
        val s = triangle()
        s.dragKnob(0, Pt(20.0, 0.0))
        val before = corners(s)
        val flip = Affine.scaleAbout(Pt(50.0, 30.0), -1.0, 1.0)
        s.applyTransform(flip)
        assertEquals(ShapeKind.TRIANGLE, s.shape)
        val after = corners(s)
        for (p in before.map(flip::apply)) assertTrue("missing $p", after.any { it.distanceTo(p) < 1e-9 })
    }

    @Test fun anOffCentreApexNeedsAStandIn() {
        val s = triangle()
        assertNull(s.legacyOutline())
        s.dragKnob(0, Pt(20.0, 0.0))
        assertNotNull(s.legacyOutline())
        assertEquals("polygon", s.legacyId())
    }

    @Test fun aKnobDragUndoesFromItsSnapshot() {
        val s = triangle()
        val snap = s.snapshotGeometry()
        s.dragKnob(0, Pt(90.0, 0.0))
        s.restoreGeometry(snap)
        assertEquals(0.5, s.adj[0], 0.0)
    }

    @Test fun becomingFreeDropsTheKnobs() {
        val s = triangle()
        s.dragKnob(0, Pt(20.0, 0.0))
        s.toFree()
        assertTrue(s.knobDots().isEmpty())
        assertEquals(Pt(20.0, 0.0), corners(s)[0])
    }
}
