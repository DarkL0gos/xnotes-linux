package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlin.math.max

/** What the end of an open shape or a connector carries: nothing, a chevron, or a UML head. */
enum class Head(val id: String) {
    NONE("none"),
    OPEN("open"),
    FILLED("filled"),
    HOLLOW_TRIANGLE("hollow_triangle"),
    FILLED_DIAMOND("filled_diamond"),
    HOLLOW_DIAMOND("hollow_diamond");

    /** How far short of the end the line stops, so it never shows through a head with a body. */
    fun inset(width: Double): Double = when (this) {
        NONE, OPEN -> 0.0
        FILLED, HOLLOW_TRIANGLE -> length(width)
        FILLED_DIAMOND, HOLLOW_DIAMOND -> length(width) * DIAMOND_STRETCH
    }

    /**
     * The head at the line's end [end], pointing along unit [dir], for a line [width] wide. The
     * chevron's tip sits half a width past the end so it clears the line's round cap; a closed
     * head puts its tip on the end and the line stops at its back.
     */
    fun paths(end: Pt, dir: Pt, width: Double): List<ShapePath> {
        if (this == NONE || dir.length() < 1e-9) return emptyList()
        val len = length(width)
        val perp = dir.perp()
        return when (this) {
            NONE -> emptyList()
            OPEN -> {
                val tip = end + dir * (width * 0.5)
                val back = tip - dir * len
                listOf(ShapePath.polyline(listOf(back + perp * (len * 0.5), tip, back - perp * (len * 0.5)), PathRole.HEAD))
            }
            FILLED, HOLLOW_TRIANGLE -> {
                val back = end - dir * len
                val tri = listOf(end, back + perp * (len * 0.5), back - perp * (len * 0.5))
                listOf(ShapePath.polygon(tri, if (this == FILLED) PathRole.HEAD_FILLED else PathRole.HEAD))
            }
            FILLED_DIAMOND, HOLLOW_DIAMOND -> {
                val d = len * DIAMOND_STRETCH
                val mid = end - dir * (d / 2.0)
                val side = perp * (len * 0.4)
                val diamond = listOf(end, mid + side, end - dir * d, mid - side)
                listOf(ShapePath.polygon(diamond, if (this == FILLED_DIAMOND) PathRole.HEAD_FILLED else PathRole.HEAD))
            }
        }
    }

    companion object {
        fun fromId(id: String?): Head = entries.firstOrNull { it.id == id } ?: NONE

        /** A head's length along the line: never under 12 px, else three and a half widths. */
        fun length(width: Double): Double = max(12.0, width * 3.5)

        private const val DIAMOND_STRETCH = 1.3

        /**
         * [body]'s open ends with [start] and [end] heads: the line pulled back by each head's
         * inset and the heads added. Nothing changes for a closed body or for no heads.
         */
        fun attach(body: List<ShapePath>, start: Head, end: Head, width: Double): List<ShapePath> {
            if (start == NONE && end == NONE) return body
            val pathIndex = body.indexOfFirst { p -> p.role == PathRole.STROKE_ONLY && p.contours.any { !it.closed } }
            if (pathIndex < 0) return body
            val path = body[pathIndex]
            val first = path.contours.indexOfFirst { !it.closed }
            val last = path.contours.indexOfLast { !it.closed }
            val contours = path.contours.toMutableList()
            val heads = ArrayList<ShapePath>(2)
            endOf(contours[last])?.let { (at, dir) ->
                heads += end.paths(at, dir, width)
                contours[last] = trimEnd(contours[last], end.inset(width))
            }
            startOf(contours[first])?.let { (at, dir) ->
                heads += start.paths(at, dir, width)
                contours[first] = reverse(trimEnd(reverse(contours[first]), start.inset(width)))
            }
            val out = body.toMutableList()
            out[pathIndex] = ShapePath(contours, path.role, path.fillRule)
            return out + heads
        }

        /** Where a contour ends and the way it is heading there. */
        private fun endOf(c: VectorContour): Pair<Pt, Pt>? {
            val s = c.segments.lastOrNull() ?: return null
            val from = if (c.segments.size >= 2) c.segments[c.segments.size - 2].end else c.start
            val toward = when (s) {
                is VectorSeg.Line -> from
                is VectorSeg.Cubic -> listOf(s.c2, s.c1, from).firstOrNull { it.distanceTo(s.end) > 1e-9 } ?: from
            }
            val d = s.end - toward
            return if (d.length() < 1e-9) null else s.end to d.normalized()
        }

        private fun startOf(c: VectorContour): Pair<Pt, Pt>? = endOf(reverse(c))

        /** [c] traced the other way round. */
        private fun reverse(c: VectorContour): VectorContour {
            if (c.segments.isEmpty()) return c
            val pts = listOf(c.start) + c.segments.map { it.end }
            val segs = ArrayList<VectorSeg>(c.segments.size)
            for (i in c.segments.indices.reversed()) {
                val to = pts[i]
                segs += when (val s = c.segments[i]) {
                    is VectorSeg.Line -> VectorSeg.Line(to)
                    is VectorSeg.Cubic -> VectorSeg.Cubic(s.c2, s.c1, to)
                }
            }
            return VectorContour(pts.last(), segs, c.closed)
        }

        /** [c] with its last [distance] cut off (most of its last segment at most). */
        private fun trimEnd(c: VectorContour, distance: Double): VectorContour {
            if (distance <= 0.0 || c.segments.isEmpty()) return c
            val s = c.segments.last()
            val from = if (c.segments.size >= 2) c.segments[c.segments.size - 2].end else c.start
            val cut: VectorSeg = when (s) {
                is VectorSeg.Line -> {
                    val len = from.distanceTo(s.end)
                    if (len < 1e-9) return c
                    val keep = (len - distance).coerceAtLeast(len * MIN_KEEP) / len
                    VectorSeg.Line(from + (s.end - from) * keep)
                }
                is VectorSeg.Cubic -> {
                    // Walk back along the curve to where it is [distance] from its end, then split there.
                    var lo = 0.0
                    var hi = 1.0
                    repeat(30) {
                        val t = (lo + hi) / 2.0
                        if (cubicAt(from, s.c1, s.c2, s.end, t).distanceTo(s.end) > distance) lo = t else hi = t
                    }
                    val t = lo.coerceAtLeast(MIN_KEEP)
                    val a = from + (s.c1 - from) * t
                    val b = s.c1 + (s.c2 - s.c1) * t
                    val e = s.c2 + (s.end - s.c2) * t
                    val ab = a + (b - a) * t
                    val be = b + (e - b) * t
                    VectorSeg.Cubic(a, ab, ab + (be - ab) * t)
                }
            }
            return VectorContour(c.start, c.segments.dropLast(1) + cut, c.closed)
        }

        private fun cubicAt(p0: Pt, p1: Pt, p2: Pt, p3: Pt, t: Double): Pt {
            val u = 1.0 - t
            return p0 * (u * u * u) + p1 * (3 * u * u * t) + p2 * (3 * u * t * t) + p3 * (t * t * t)
        }

        /** A head never eats more of the last segment than this much of it. */
        private const val MIN_KEEP = 0.1
    }
}
