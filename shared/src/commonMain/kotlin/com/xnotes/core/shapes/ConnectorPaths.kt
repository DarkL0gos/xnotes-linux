package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.vector.PathFlattener
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.tan

/** The line a connector draws along its route, the gap its label sits in, and places along it. */
object ConnectorPaths {

    /** Straight lines through [pts]. */
    fun straight(pts: List<Pt>): VectorContour = ShapePath.lines(pts, closed = false)

    /** [pts] with each corner turned on an arc of up to [radius], taking at most half of either side. */
    fun rounded(pts: List<Pt>, radius: Double): VectorContour {
        if (pts.size < 3) return straight(pts)
        val segs = ArrayList<VectorSeg>(2 * pts.size)
        for (i in 1 until pts.size - 1) {
            val a = pts[i - 1]
            val c = pts[i]
            val b = pts[i + 1]
            val la = a.distanceTo(c)
            val lb = c.distanceTo(b)
            val r = min(radius, min(la, lb) / 2.0)
            if (r < 1e-9) {
                segs += VectorSeg.Line(c)
                continue
            }
            val u = (c - a) / la
            val v = (b - c) / lb
            // A cubic fits an arc of any turn with handles 4/3 tan(turn / 4) of its radius long.
            val k = 4.0 / 3.0 * tan(acos((u.x * v.x + u.y * v.y).coerceIn(-1.0, 1.0)) / 4.0) * r
            val p1 = c - u * r
            val p2 = c + v * r
            segs += VectorSeg.Line(p1)
            segs += VectorSeg.Cubic(p1 + u * k, p2 - v * k, p2)
        }
        segs += VectorSeg.Line(pts.last())
        return VectorContour(pts[0], segs, closed = false)
    }

    /**
     * A smooth curve guided by [pts]: it leaves along the first segment, passes through the middle
     * of each inner one and arrives along the last, drawn toward every corner on the way.
     */
    fun curved(pts: List<Pt>): VectorContour {
        if (pts.size < 3) return straight(pts)
        val segs = ArrayList<VectorSeg>(pts.size)
        var a = pts[0]
        for (i in 1 until pts.size - 1) {
            val c = pts[i]
            val b = if (i == pts.size - 2) pts.last() else (pts[i] + pts[i + 1]) * 0.5
            segs += VectorSeg.Cubic(a + (c - a) * (2.0 / 3.0), b + (c - b) * (2.0 / 3.0), b)
            a = b
        }
        return VectorContour(pts[0], segs, closed = false)
    }

    /** [c] with every part inside [r] cut away: the gap a label sits in, so the line never runs through it. */
    fun clipOut(c: VectorContour, r: Rect): List<VectorContour> {
        val out = ArrayList<VectorContour>()
        var start: Pt? = null
        var segs = ArrayList<VectorSeg>()
        fun flush() {
            val s = start
            if (s != null && segs.isNotEmpty()) out += VectorContour(s, segs, closed = false)
            start = null
            segs = ArrayList()
        }
        var from = c.start
        for (seg in c.segments) {
            for ((ta, tb) in outside(from, seg, r)) {
                if (ta > 0.0) flush()
                when (seg) {
                    is VectorSeg.Line -> {
                        if (start == null) start = lerp(from, seg.end, ta)
                        segs += VectorSeg.Line(lerp(from, seg.end, tb))
                    }
                    is VectorSeg.Cubic -> {
                        val (s, part) = cubicPart(from, seg, ta, tb)
                        if (start == null) start = s
                        segs += part
                    }
                }
                if (tb < 1.0) flush()
            }
            from = seg.end
        }
        flush()
        return out
    }

    /**
     * Where a handle for the segment [a]-[b] goes when [at], its usual place, sits under the label in
     * [gap]: halfway along the longer stretch of the segment left showing, or [at] when none is.
     */
    fun clearOf(a: Pt, b: Pt, at: Pt, gap: Rect): Pt {
        if (!gap.contains(at)) return at
        val (t0, t1) = lineInside(a, b, gap) ?: return at
        val before = t0
        val after = 1.0 - t1
        if (maxOf(before, after) <= SPAN_EPS) return at
        return if (before >= after) lerp(a, b, t0 / 2.0) else lerp(a, b, (t1 + 1.0) / 2.0)
    }

    /** The point [share] of the way along [c], by length. */
    fun pointAt(c: VectorContour, share: Double): Pt {
        val pts = PathFlattener.flatten(c, MEASURE_FLATTEN)
        if (pts.size < 2) return pts.firstOrNull() ?: c.start
        var want = share.coerceIn(0.0, 1.0) * length(pts)
        for (i in 1 until pts.size) {
            val d = pts[i - 1].distanceTo(pts[i])
            if (want <= d && d > 0.0) return lerp(pts[i - 1], pts[i], want / d)
            want -= d
        }
        return pts.last()
    }

    /** How far along [c], as a share of its length, lies the point of it nearest [p]. */
    fun shareNear(c: VectorContour, p: Pt): Double {
        val pts = PathFlattener.flatten(c, MEASURE_FLATTEN)
        val total = length(pts)
        if (pts.size < 2 || total <= 0.0) return 0.5
        var best = Double.MAX_VALUE
        var bestAt = 0.0
        var walked = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val d = a.distanceTo(b)
            val t = if (d > 0.0) (((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / (d * d)).coerceIn(0.0, 1.0) else 0.0
            val dist = lerp(a, b, t).distanceTo(p)
            if (dist < best) {
                best = dist
                bestAt = walked + t * d
            }
            walked += d
        }
        return bestAt / total
    }

    /** The parts of [seg] from [from] outside [r], as parameter spans in order. */
    private fun outside(from: Pt, seg: VectorSeg, r: Rect): List<Pair<Double, Double>> {
        val inside = when (seg) {
            is VectorSeg.Line -> listOfNotNull(lineInside(from, seg.end, r))
            is VectorSeg.Cubic -> cubicInside(from, seg, r)
        }
        val out = ArrayList<Pair<Double, Double>>(inside.size + 1)
        var t = 0.0
        for ((a, b) in inside) {
            if (a - t > SPAN_EPS) out += t to a
            t = b
        }
        if (1.0 - t > SPAN_EPS) out += t to 1.0
        return out
    }

    /** The span of the segment [a]-[b] inside [r], clipped Liang-Barsky style; null when it misses. */
    private fun lineInside(a: Pt, b: Pt, r: Rect): Pair<Double, Double>? {
        var t0 = 0.0
        var t1 = 1.0
        val dx = b.x - a.x
        val dy = b.y - a.y
        val ps = doubleArrayOf(-dx, dx, -dy, dy)
        val qs = doubleArrayOf(a.x - r.left, r.right - a.x, a.y - r.top, r.bottom - a.y)
        for (i in 0..3) {
            val p = ps[i]
            val q = qs[i]
            if (abs(p) < 1e-12) {
                if (q < 0.0) return null
                continue
            }
            val t = q / p
            if (p < 0.0) {
                if (t > t1) return null
                if (t > t0) t0 = t
            } else {
                if (t < t0) return null
                if (t < t1) t1 = t
            }
        }
        return if (t1 - t0 > SPAN_EPS) t0 to t1 else null
    }

    /** The spans of a cubic inside [r]: sampled, then each crossing found by halving. */
    private fun cubicInside(from: Pt, s: VectorSeg.Cubic, r: Rect): List<Pair<Double, Double>> {
        val inside = BooleanArray(CUBIC_SAMPLES + 1) { r.contains(cubicAt(from, s, it.toDouble() / CUBIC_SAMPLES)) }
        val out = ArrayList<Pair<Double, Double>>()
        var enter = if (inside[0]) 0.0 else -1.0
        for (i in 1..CUBIC_SAMPLES) {
            if (inside[i] == inside[i - 1]) continue
            var lo = (i - 1).toDouble() / CUBIC_SAMPLES
            var hi = i.toDouble() / CUBIC_SAMPLES
            repeat(24) {
                val mid = (lo + hi) / 2.0
                if (r.contains(cubicAt(from, s, mid)) == inside[i - 1]) lo = mid else hi = mid
            }
            val t = (lo + hi) / 2.0
            if (inside[i]) {
                enter = t
            } else {
                if (t - enter > SPAN_EPS) out += enter to t
                enter = -1.0
            }
        }
        if (enter >= 0.0 && 1.0 - enter > SPAN_EPS) out += enter to 1.0
        return out
    }

    /** The piece of a cubic between parameters [ta] and [tb]: where it starts, and the segment. */
    private fun cubicPart(from: Pt, s: VectorSeg.Cubic, ta: Double, tb: Double): Pair<Pt, VectorSeg.Cubic> {
        val left = split(from, s.c1, s.c2, s.end, tb).first
        val local = if (tb > 0.0) ta / tb else 0.0
        val right = split(left[0], left[1], left[2], left[3], local).second
        return right[0] to VectorSeg.Cubic(right[1], right[2], right[3])
    }

    /** de Casteljau: the cubic's halves either side of [t]. */
    private fun split(p0: Pt, p1: Pt, p2: Pt, p3: Pt, t: Double): Pair<List<Pt>, List<Pt>> {
        val a = lerp(p0, p1, t)
        val b = lerp(p1, p2, t)
        val c = lerp(p2, p3, t)
        val d = lerp(a, b, t)
        val e = lerp(b, c, t)
        val f = lerp(d, e, t)
        return listOf(p0, a, d, f) to listOf(f, e, c, p3)
    }

    private fun cubicAt(from: Pt, s: VectorSeg.Cubic, t: Double): Pt {
        val u = 1.0 - t
        return from * (u * u * u) + s.c1 * (3 * u * u * t) + s.c2 * (3 * u * t * t) + s.end * (t * t * t)
    }

    private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun length(pts: List<Pt>): Double {
        var len = 0.0
        for (i in 1 until pts.size) len += pts[i - 1].distanceTo(pts[i])
        return len
    }

    /** Spans shorter than this, as a share of their segment, are dropped rather than drawn. */
    private const val SPAN_EPS = 1e-6
    private const val CUBIC_SAMPLES = 48

    /** How closely a route is followed when measuring along it, in content px. */
    private const val MEASURE_FLATTEN = 0.25
}
