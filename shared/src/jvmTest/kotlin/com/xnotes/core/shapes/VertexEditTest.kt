package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.stroke.CubicFit
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VertexEditTest {

    private val square = FreePath.lines(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0)), closed = true)
    private val arch = VectorContour(Pt(0.0, 0.0), listOf(VectorSeg.Cubic(Pt(0.0, 10.0), Pt(10.0, 10.0), Pt(10.0, 0.0))), false)

    private fun verts(c: VectorContour) = VertexEdit.vertices(VertexEdit.canonical(c))

    @Test fun aClosedContourGetsAnExplicitClosingEdge() {
        val c = VertexEdit.canonical(square)
        assertEquals(4, c.segments.size)
        assertEquals(Pt(0.0, 0.0), c.segments.last().end)
        assertEquals(4, VertexEdit.vertices(c).size)
        assertEquals(c, VertexEdit.canonical(c).let { if (it.segments.size == 4) c else it })
    }

    @Test fun movingAVertexMovesOnlyThatCorner() {
        val out = VertexEdit.moveVertex(listOf(square), PathSpot(0, 2), Pt(20.0, 15.0)).single()
        assertEquals(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(20.0, 15.0), Pt(0.0, 10.0)), verts(out))
    }

    @Test fun movingTheStartOfAClosedContourMovesItsClosingEdgeToo() {
        val out = VertexEdit.moveVertex(listOf(square), PathSpot(0, 0), Pt(-5.0, -5.0)).single()
        assertEquals(Pt(-5.0, -5.0), out.start)
        assertEquals(Pt(-5.0, -5.0), out.segments.last().end)
    }

    @Test fun aCubicsHandleRidesAlongWithItsVertex() {
        val out = VertexEdit.moveVertex(listOf(arch), PathSpot(0, 1), Pt(20.0, 0.0)).single()
        val seg = out.segments.single() as VectorSeg.Cubic
        assertEquals(Pt(20.0, 10.0), seg.c2)
        assertEquals(Pt(0.0, 10.0), seg.c1)
    }

    @Test fun splittingAnEdgeAddsItsMidpoint() {
        val (out, added) = VertexEdit.split(listOf(square), PathSpot(0, 1))
        assertEquals(PathSpot(0, 2), added)
        assertEquals(Pt(10.0, 5.0), verts(out.single())[2])
        assertEquals(5, verts(out.single()).size)
    }

    @Test fun splittingACubicKeepsItsCurve() {
        val (out, added) = VertexEdit.split(listOf(arch), PathSpot(0, 0))
        val c = out.single()
        assertEquals(2, c.segments.size)
        val before = CubicFit.sample(listOf(Pt(0.0, 0.0), Pt(0.0, 10.0), Pt(10.0, 10.0), Pt(10.0, 0.0)), 8)
        assertEquals(0.0, VertexEdit.vertexAt(c, added.index).distanceTo(before[4]), 1e-9)
        val a = c.segments[0] as VectorSeg.Cubic
        val firstHalf = CubicFit.sample(listOf(c.start, a.c1, a.c2, a.end), 4)
        for (i in 0..4) assertEquals(0.0, firstHalf[i].distanceTo(before[i]), 1e-9)
    }

    @Test fun deletingACornerJoinsItsNeighbours() {
        val out = VertexEdit.delete(listOf(square), PathSpot(0, 2))!!.single()
        assertEquals(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(0.0, 10.0)), verts(out))
        assertTrue(out.segments.all { it is VectorSeg.Line })
    }

    @Test fun deletingTheStartReopensAtTheNextVertex() {
        val out = VertexEdit.delete(listOf(square), PathSpot(0, 0))!!.single()
        assertEquals(listOf(Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0)), verts(out))
        assertEquals(out.start, out.segments.last().end)
    }

    @Test fun aTriangleKeepsItsThreeCornersAndALineItsTwoEnds() {
        val tri = FreePath.lines(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(5.0, 8.0)), closed = true)
        assertNull(VertexEdit.delete(listOf(tri), PathSpot(0, 1)))
        assertNull(VertexEdit.delete(listOf(arch), PathSpot(0, 0)))
    }

    @Test fun deletingBesideACurveKeepsTheOuterHandles() {
        val zig = VectorContour(
            Pt(0.0, 0.0),
            listOf(VectorSeg.Cubic(Pt(0.0, 5.0), Pt(5.0, 5.0), Pt(5.0, 0.0)), VectorSeg.Line(Pt(10.0, 0.0))),
            false,
        )
        val out = VertexEdit.delete(listOf(zig), PathSpot(0, 1))!!.single()
        val seg = out.segments.single() as VectorSeg.Cubic
        assertEquals(Pt(0.0, 5.0), seg.c1)
        assertEquals(Pt(10.0, 0.0), seg.end)
    }

    @Test fun anOpenContoursEndsCanGo() {
        val line = FreePath.lines(listOf(Pt(0.0, 0.0), Pt(5.0, 5.0), Pt(10.0, 0.0)), closed = false)
        assertEquals(listOf(Pt(5.0, 5.0), Pt(10.0, 0.0)), verts(VertexEdit.delete(listOf(line), PathSpot(0, 0))!!.single()))
        assertEquals(listOf(Pt(0.0, 0.0), Pt(5.0, 5.0)), verts(VertexEdit.delete(listOf(line), PathSpot(0, 2))!!.single()))
    }
}
