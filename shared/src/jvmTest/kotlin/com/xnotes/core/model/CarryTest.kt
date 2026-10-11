package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class CarryTest {

    private val ink = Rgba(10, 20, 30, 255)

    private fun box(x: Double, y: Double, w: Double, h: Double, kind: ShapeKind = ShapeKind.RECTANGLE) =
        ShapeItem(kind, Pt(x, y), Pt(x + w, y + h), ink, 2.0)

    private fun ink(vararg pts: Pt) = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), pts.map { Sample(it.x, it.y, 1.0) }.toMutableList())

    @Test fun onlyWhatIsEntirelyInsideIsCarried() {
        val frame = box(0.0, 0.0, 200.0, 100.0)
        val inside = ink(Pt(20.0, 20.0), Pt(80.0, 60.0))
        val across = ink(Pt(150.0, 50.0), Pt(260.0, 50.0))
        val outside = ink(Pt(300.0, 50.0), Pt(320.0, 60.0))
        val locked = ink(Pt(30.0, 30.0), Pt(40.0, 40.0)).apply { this.locked = true }
        val carry = Carry.of(listOf(frame), listOf(frame, inside, across, outside, locked), skip = listOf(frame))
        assertEquals(listOf<CanvasItem>(inside), carry.items)
    }

    @Test fun anEmptyMiddleStillHoldsItsWritingButAnOpenShapeHoldsNothing() {
        val ring = box(0.0, 0.0, 200.0, 200.0, ShapeKind.RING)
        val inHole = ink(Pt(95.0, 95.0), Pt(105.0, 105.0))
        assertTrue("the hole is outside a ring", Carry.of(listOf(ring), listOf(inHole), listOf(ring)).isEmpty)
        val line = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(200.0, 200.0), ink)
        assertTrue(Carry.of(listOf(line), listOf(inHole), listOf(line)).isEmpty)
    }

    @Test fun theInnermostHolderTakesWhatItHolds() {
        val outer = box(0.0, 0.0, 400.0, 400.0)
        val inner = box(100.0, 100.0, 100.0, 100.0)
        val note = ink(Pt(140.0, 140.0), Pt(160.0, 160.0))
        val carry = Carry.of(listOf(outer, inner), listOf(outer, inner, note), skip = listOf(outer, inner))
        assertEquals(listOf<CanvasItem>(note), carry.items)
        assertSame(inner, carry.holderOf(note))
        // A shape inside an unselected-but-carried box goes along too, and its writing with it.
        val nested = Carry.of(listOf(outer), listOf(outer, inner, note), skip = listOf(outer))
        assertEquals(listOf<CanvasItem>(inner, note), nested.items)
        assertSame(outer, nested.holderOf(note))
    }

    @Test fun aMoveOrTurnCarriesRigidlyAndATextBoxStaysUpright() {
        val frame = box(0.0, 0.0, 200.0, 100.0)
        val line = ink(Pt(20.0, 50.0), Pt(60.0, 50.0))
        val text = TextItem(Pt(120.0, 40.0), width = 40.0, text = "x", measurer = FakeTextMeasurer())
        val carry = Carry.of(listOf(frame), listOf(line, text), skip = listOf(frame))
        assertEquals(2, carry.items.size)
        val turn = Affine.rotateAbout(Pt(100.0, 50.0), PI / 2)
        val textCentre = turn.apply(text.bounds().center)
        carry.follow(turn, resize = false)
        val s = line.samples
        assertEquals(100.0, s.first().pos.x, 1e-3)
        assertEquals(-30.0, s.first().pos.y, 1e-3)
        assertEquals(textCentre.x, text.bounds().centerX, 1e-9)
        assertEquals(textCentre.y, text.bounds().centerY, 1e-9)
        assertEquals("upright, its own width", 40.0, text.width, 0.0)
    }

    @Test fun aResizeScalesWritingWithTheShapeLineWidthsIncluded() {
        val frame = box(0.0, 0.0, 200.0, 100.0)
        val word = ink(Pt(90.0, 45.0), Pt(110.0, 55.0))
        val width0 = word.config.baseWidth
        val carry = Carry.of(listOf(frame), listOf(word), skip = listOf(frame))
        val grow = Affine.scaleAbout(Pt(0.0, 0.0), 2.0, 2.0)
        frame.applyTransform(grow)
        carry.follow(grow, resize = true)
        val s = word.samples
        assertEquals(180.0, s.first().pos.x, 1e-3)
        assertEquals(90.0, s.first().pos.y, 1e-3)
        assertEquals(220.0, s.last().pos.x, 1e-3)
        assertEquals("the line grows with it", width0 * 2.0, word.config.baseWidth, 1e-9)
        carry.restore()
        assertEquals(90.0, word.samples.first().pos.x, 1e-3)
        assertEquals(width0, word.config.baseWidth, 1e-9)
    }

    @Test fun aTextBoxInsideScalesAsALassoedOneDoes() {
        val frame = box(0.0, 0.0, 200.0, 100.0)
        val text = TextItem(Pt(40.0, 40.0), width = 60.0, text = "x", pointSize = 10.0, measurer = FakeTextMeasurer())
        val carry = Carry.of(listOf(frame), listOf(text), skip = listOf(frame))
        carry.follow(Affine.scaleAbout(Pt(0.0, 0.0), 2.0, 2.0), resize = true)
        assertEquals(Pt(80.0, 80.0), text.pos)
        assertEquals(120.0, text.width, 1e-9)
        assertEquals("a corner drag grows its type", 20.0, text.pointSize, 1e-9)
    }

    @Test fun aTurnedHoldersWritingScalesAlongTheHoldersOwnAxes() {
        val frame = box(0.0, 0.0, 200.0, 100.0)
        frame.applyTransform(Affine.rotateAbout(frame.box.center, 0.5))
        val at = frame.toPage(Pt(150.0, 50.0))
        val dot = ink(at, Pt(at.x + 1.0, at.y))
        val carry = Carry.of(listOf(frame), listOf(dot), skip = listOf(frame))
        assertSame(dot, carry.items.single())
        val c = frame.box.center
        val stretch = Affine.rotateAbout(c, 0.5).compose(Affine.scaleAbout(c, 2.0, 1.0).compose(Affine.rotateAbout(c, -0.5)))
        val was = dot.samples.map { it.pos }
        frame.applyTransform(stretch)
        carry.follow(stretch, resize = true)
        for ((w, now) in was.zip(dot.samples.map { it.pos })) assertEquals(0.0, stretch.apply(w).distanceTo(now), 1e-3)
        val local = frame.toLocal(dot.samples.first().pos)
        val b = frame.box
        assertEquals("still three quarters across the stretched frame", 0.75, (local.x - b.left) / b.w, 1e-3)
    }
}
