package com.xnotes.core.model

import com.xnotes.canvas.HandleId
import com.xnotes.canvas.ResizeMath
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.PathSpot
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapePointEditTest {

    private val ink = Rgba(10, 20, 30)
    private val fill = Rgba(10, 20, 30, 64)

    private fun rect() = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), ink, 3.0, fill, dashed = true)

    @Test fun aRectangleShowsItsCornersAndEdgeMiddles() {
        val s = rect()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), 0.4))
        val dots = s.vertexDots()
        assertEquals(4, dots.size)
        assertEquals(0.0, dots[2].at.distanceTo(s.toPage(Pt(100.0, 50.0))), 1e-9)
        assertEquals(4, s.edgeDots().size)
        assertEquals(0.0, s.edgeDots()[0].at.distanceTo(s.toPage(Pt(50.0, 0.0))), 1e-9)
    }

    @Test fun smoothShapesAndLinesShowNoDots() {
        assertTrue(ShapeItem(ShapeKind.ELLIPSE, Pt(0.0, 0.0), Pt(100.0, 50.0), ink).vertexDots().isEmpty())
        assertTrue(ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(100.0, 50.0), ink).vertexDots().isEmpty())
        assertTrue(ShapeItem(ShapeKind.ELLIPSE, Pt(0.0, 0.0), Pt(100.0, 50.0), ink).edgeDots().isEmpty())
    }

    @Test fun shortEdgesHideTheirMiddleDot() {
        assertEquals("only the two long edges", 2, rect().edgeDots(minLength = 60.0).size)
    }

    @Test fun becomingFreeKeepsTheLookAndTheFrame() {
        val s = rect()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), 0.4))
        val corners = s.vertexDots().map { it.at }
        assertTrue(s.toFree())
        assertEquals(ShapeKind.POLYGON, s.shape)
        assertEquals(0.4, s.rotation, 1e-12)
        assertEquals(fill, s.fillRgba)
        assertTrue(s.dashed)
        val after = s.vertexDots().map { it.at }
        for (i in corners.indices) assertEquals(0.0, corners[i].distanceTo(after[i]), 1e-9)
    }

    @Test fun draggingACornerMovesOnlyThatCorner() {
        val s = rect()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), 0.4))
        val corners = s.vertexDots().map { it.at }
        s.toFree()
        val to = corners[2] + Pt(30.0, 12.0)
        s.moveVertex(PathSpot(0, 2), to)
        val after = s.vertexDots().map { it.at }
        assertEquals(0.0, after[2].distanceTo(to), 1e-9)
        for (i in listOf(0, 1, 3)) assertEquals("corner $i stayed", 0.0, after[i].distanceTo(corners[i]), 1e-9)
        assertEquals("the frame keeps its turn", 0.4, s.rotation, 1e-12)
    }

    @Test fun splittingAnEdgeThenDraggingAddsACorner() {
        val s = rect()
        s.toFree()
        val added = s.splitEdge(PathSpot(0, 0))!!
        s.moveVertex(added, Pt(50.0, -20.0))
        assertEquals(5, s.vertexDots().size)
        assertEquals(-20.0, s.box.top, 1e-9)
    }

    @Test fun deletingACornerOfARectangleLeavesATriangle() {
        val s = rect()
        s.toFree()
        assertTrue(s.deleteVertex(PathSpot(0, 1)))
        assertEquals(3, s.vertexDots().size)
        assertFalse("a triangle keeps its last three", s.deleteVertex(PathSpot(0, 0)))
    }

    @Test fun aSnapshotUndoesTheWholeEdit() {
        val s = rect()
        val before = s.snapshotGeometry()
        s.toFree()
        s.moveVertex(PathSpot(0, 2), Pt(140.0, 90.0))
        s.restoreGeometry(before)
        assertEquals(ShapeKind.RECTANGLE, s.shape)
        assertEquals(Pt(100.0, 50.0), s.end)
    }

    @Test fun theFrameIsTheBoxAndThePadSitsOutside() {
        val s = rect()
        val f = s.frameObb()
        assertNotNull(f)
        assertEquals(50.0, f!!.halfW, 1e-9)
        assertEquals(3.0 / 2.0 + 1.0, s.framePad, 1e-9)
    }

    @Test fun aHandleOutsideTheFrameDragsTheFrameEdgeWithIt() {
        val frame = Obb(Pt(50.0, 25.0), 50.0, 25.0, 0.0)
        val margin = 12.0
        val r = ResizeMath.obbResize(frame, HandleId.R, Pt(100.0 + margin + 40.0, 25.0), margin)
        assertEquals("the right edge followed the pointer, the gap kept", 140.0, r.obb.center.x + r.obb.halfW, 1e-9)
        assertEquals(0.0, r.obb.center.x - r.obb.halfW, 1e-9)
    }

    @Test fun anUnfilledShapesMiddleIsNotTheShape() {
        val empty = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), ink, 3.0)
        assertTrue("on the outline", empty.touches(Pt(50.0, 2.0), 6.0))
        assertFalse("in the empty middle, where a new shape can start", empty.touches(Pt(50.0, 25.0), 6.0))
        assertFalse("off the outline past the tolerance", empty.touches(Pt(50.0, 12.0), 6.0))
        val filled = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), ink, 3.0, fill)
        assertTrue("a fill is the shape", filled.touches(Pt(50.0, 25.0), 6.0))
    }

    @Test fun aTurnedShapeIsTouchedWhereItIsDrawn() {
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 40.0), Pt(100.0, 60.0), ink, 2.0, fill)
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 50.0), PI_HALF_MINUS))
        assertTrue(s.touches(Pt(50.0, 5.0), 4.0))
        assertFalse(s.touches(Pt(5.0, 50.0), 4.0))
    }

    private companion object {
        const val PI_HALF_MINUS = Math.PI / 2 - 0.01
    }
}
