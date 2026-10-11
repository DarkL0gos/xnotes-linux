package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg

/** A vertex (or, for an edge edit, a segment) of a free outline: which contour, and which one in it. */
data class PathSpot(val contour: Int, val index: Int)

/** A dot the editor draws over a shape: where it is on the page, and what it grabs. */
class EditDot(val at: Pt, val spot: PathSpot)

/**
 * Point edits on a free outline: move a vertex, split an edge, delete a vertex. Contours are
 * worked in canonical form, where a closed contour ends with an explicit segment back to its start,
 * so vertex i always owns segment i (from vertex i to the next one) and a closed contour has as
 * many segments as vertices.
 */
object VertexEdit {

    /** [c] with a closed contour's implicit straight closing edge made an explicit segment. */
    fun canonical(c: VectorContour): VectorContour {
        if (!c.closed) return c
        val last = c.segments.lastOrNull()?.end ?: return c
        if (last.distanceTo(c.start) < 1e-12) return c
        return VectorContour(c.start, c.segments + VectorSeg.Line(c.start), true)
    }

    /** The vertices of a canonical contour; a closed one's return to its start is not counted twice. */
    fun vertices(c: VectorContour): List<Pt> {
        val out = ArrayList<Pt>(c.segments.size + 1)
        out.add(c.start)
        for (s in c.segments) out.add(s.end)
        if (c.closed) out.removeAt(out.size - 1)
        return out
    }

    /** The point halfway along segment [i] of a canonical contour. */
    fun midpoint(c: VectorContour, i: Int): Pt {
        val from = if (i == 0) c.start else c.segments[i - 1].end
        return when (val s = c.segments[i]) {
            is VectorSeg.Line -> lerp(from, s.end, 0.5)
            is VectorSeg.Cubic -> cubicAt(from, s.c1, s.c2, s.end, 0.5)
        }
    }

    /**
     * Move vertex [spot] to [to]. A cubic's handle attached to that vertex rides along, so the curve
     * keeps the direction it leaves the vertex in.
     */
    fun moveVertex(contours: List<VectorContour>, spot: PathSpot, to: Pt): List<VectorContour> =
        contours.mapIndexed { ci, raw ->
            if (ci != spot.contour) return@mapIndexed raw
            val c = canonical(raw)
            val n = c.segments.size
            val from = vertexAt(c, spot.index)
            val d = to - from
            var start = c.start
            val segs = c.segments.toMutableList()
            // The segment ending at the vertex: for vertex 0 of a closed contour, the last one.
            val inIdx = if (spot.index > 0) spot.index - 1 else if (c.closed) n - 1 else -1
            if (spot.index == 0) start = to
            if (inIdx >= 0) segs[inIdx] = withEnd(segs[inIdx], to, moveC2 = d)
            if (spot.index < n) segs[spot.index] = shiftC1(segs[spot.index], d)
            VectorContour(start, segs, c.closed)
        }

    /** Split segment [spot] at its middle (de Casteljau for a cubic); the new vertex's spot comes back too. */
    fun split(contours: List<VectorContour>, spot: PathSpot): Pair<List<VectorContour>, PathSpot> {
        val out = contours.mapIndexed { ci, raw ->
            if (ci != spot.contour) return@mapIndexed raw
            val c = canonical(raw)
            val i = spot.index
            val from = if (i == 0) c.start else c.segments[i - 1].end
            val segs = c.segments.toMutableList()
            val halves = when (val s = segs[i]) {
                is VectorSeg.Line -> {
                    val m = lerp(from, s.end, 0.5)
                    listOf(VectorSeg.Line(m), VectorSeg.Line(s.end))
                }
                is VectorSeg.Cubic -> {
                    val a = lerp(from, s.c1, 0.5)
                    val b = lerp(s.c1, s.c2, 0.5)
                    val e = lerp(s.c2, s.end, 0.5)
                    val ab = lerp(a, b, 0.5)
                    val be = lerp(b, e, 0.5)
                    val m = lerp(ab, be, 0.5)
                    listOf(VectorSeg.Cubic(a, ab, m), VectorSeg.Cubic(be, e, s.end))
                }
            }
            segs.removeAt(i)
            segs.addAll(i, halves)
            VectorContour(c.start, segs, c.closed)
        }
        return out to PathSpot(spot.contour, spot.index + 1)
    }

    /**
     * Delete vertex [spot]; null when that would leave a closed contour under three vertices or an
     * open one under two. The neighbours join with a line when both sides were straight, else
     * with one cubic that keeps the outer handles.
     */
    fun delete(contours: List<VectorContour>, spot: PathSpot): List<VectorContour>? {
        val raw = contours.getOrNull(spot.contour) ?: return null
        val c = canonical(raw)
        val verts = vertices(c)
        val min = if (c.closed) 3 else 2
        if (verts.size <= min) return null
        val n = c.segments.size
        val i = spot.index
        val replaced: VectorContour = when {
            !c.closed && i == 0 -> VectorContour(c.segments[0].end, c.segments.drop(1), false)
            !c.closed && i == verts.size - 1 -> VectorContour(c.start, c.segments.dropLast(1), false)
            else -> {
                val inIdx = if (i > 0) i - 1 else n - 1
                val outIdx = i % n
                val prev = vertexAt(c, (i - 1 + verts.size) % verts.size)
                val next = c.segments[outIdx].end
                val incoming = c.segments[inIdx]
                val outgoing = c.segments[outIdx]
                val joined: VectorSeg = if (incoming is VectorSeg.Line && outgoing is VectorSeg.Line) {
                    VectorSeg.Line(next)
                } else {
                    VectorSeg.Cubic(
                        (incoming as? VectorSeg.Cubic)?.c1 ?: prev,
                        (outgoing as? VectorSeg.Cubic)?.c2 ?: next,
                        next,
                    )
                }
                val segs = c.segments.toMutableList()
                if (i == 0) {
                    // The start goes: the contour now opens at the next vertex and closes back to it.
                    segs.removeAt(outIdx)
                    segs[segs.size - 1] = withEnd(joined, next, Pt.ZERO)
                    VectorContour(next, segs, true)
                } else {
                    segs[inIdx] = joined
                    segs.removeAt(outIdx)
                    VectorContour(c.start, segs, c.closed)
                }
            }
        }
        return contours.mapIndexed { ci, it -> if (ci == spot.contour) replaced else it }
    }

    fun vertexAt(c: VectorContour, i: Int): Pt = if (i == 0) c.start else c.segments[i - 1].end

    private fun withEnd(s: VectorSeg, end: Pt, moveC2: Pt): VectorSeg = when (s) {
        is VectorSeg.Line -> VectorSeg.Line(end)
        is VectorSeg.Cubic -> VectorSeg.Cubic(s.c1, s.c2 + moveC2, end)
    }

    private fun shiftC1(s: VectorSeg, d: Pt): VectorSeg = when (s) {
        is VectorSeg.Line -> s
        is VectorSeg.Cubic -> VectorSeg.Cubic(s.c1 + d, s.c2, s.end)
    }

    private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun cubicAt(p0: Pt, p1: Pt, p2: Pt, p3: Pt, t: Double): Pt {
        val u = 1.0 - t
        val a = u * u * u
        val b = 3 * u * u * t
        val c = 3 * u * t * t
        val d = t * t * t
        return Pt(a * p0.x + b * p1.x + c * p2.x + d * p3.x, a * p0.y + b * p1.y + c * p2.y + d * p3.y)
    }
}
