package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import kotlin.math.abs

/**
 * Fits a single cubic Bézier to a freehand open stroke: the least-squares step of Schneider's
 * curve fit ("An Algorithm for Automatically Fitting Digitized Curves", Graphics Gems, 1990) with
 * the end tangents held to the stroke and no recursive splitting. One clean curve segment, the
 * smoothest possible snap, reparameterized a few times to tighten the fit.
 *
 * Returns null when one cubic can't follow the stroke within tolerance (a wide arc past a cubic's
 * reach, or a complex scribble), so it is left as ink rather than forced into a wrong curve.
 *
 * Pure and deterministic so it can be unit-tested on the plain JVM, like [StrokeEngine]. The curve
 * comes back as its four control points; [sample] walks it when a polyline is wanted.
 */
object CubicFit {

    /** Newton-Raphson reparameterization passes used to tighten the fit. */
    private const val REPARAM_ITERS = 4

    /** Fit one cubic to [points] and return it sampled to [samples]+1 points; null when the single
     *  cubic strays from the stroke by more than [maxError] (leave it as ink). */
    fun fitSampled(points: List<Pt>, maxError: Double, samples: Int): List<Pt>? =
        fit(points, maxError)?.let { sample(it, samples) }

    /** The cubic through [controls] (start, two handles, end) at [samples]+1 even steps of t. */
    fun sample(controls: List<Pt>, samples: Int): List<Pt> {
        val b = Cubic(controls[0], controls[1], controls[2], controls[3])
        return List(samples + 1) { eval(b, it.toDouble() / samples) }
    }

    /** Fit one cubic to [points]: its four control points, or null when it strays past [maxError]. */
    fun fit(points: List<Pt>, maxError: Double): List<Pt>? {
        val pts = dedupe(points)
        if (pts.size < 4) return null
        val tHat1 = (pts[1] - pts[0]).normalized()
        val tHat2 = (pts[pts.size - 2] - pts[pts.size - 1]).normalized()
        if (tHat1.length() < 1e-9 || tHat2.length() < 1e-9) return null
        var u = chordLengthParameterize(pts)
        var bez = generateBezier(pts, u, tHat1, tHat2)
        var err = maxDeviation(pts, bez, u)
        var iter = 0
        while (err > maxError && iter < REPARAM_ITERS) {
            u = DoubleArray(pts.size) { newton(bez, pts[it], u[it]) }
            bez = generateBezier(pts, u, tHat1, tHat2)
            err = maxDeviation(pts, bez, u)
            iter++
        }
        if (err > maxError) return null
        return listOf(bez.p0, bez.p1, bez.p2, bez.p3)
    }

    private class Cubic(val p0: Pt, val p1: Pt, val p2: Pt, val p3: Pt)

    /** Least-squares cubic with the two end tangents held fixed (Graphics Gems). */
    private fun generateBezier(pts: List<Pt>, u: DoubleArray, tHat1: Pt, tHat2: Pt): Cubic {
        val n = pts.size
        val p0 = pts.first()
        val p3 = pts.last()
        var c00 = 0.0
        var c01 = 0.0
        var c11 = 0.0
        var x0 = 0.0
        var x1 = 0.0
        for (i in 0 until n) {
            val ui = u[i]
            val a0 = tHat1 * b1(ui)
            val a1 = tHat2 * b2(ui)
            c00 += dot(a0, a0)
            c01 += dot(a0, a1)
            c11 += dot(a1, a1)
            val tmp = pts[i] - (p0 * (b0(ui) + b1(ui)) + p3 * (b2(ui) + b3(ui)))
            x0 += dot(a0, tmp)
            x1 += dot(a1, tmp)
        }
        val det = c00 * c11 - c01 * c01
        var alphaL = 0.0
        var alphaR = 0.0
        if (abs(det) > 1e-12) {
            alphaL = (x0 * c11 - x1 * c01) / det
            alphaR = (c00 * x1 - c01 * x0) / det
        }
        val segLen = p0.distanceTo(p3)
        if (alphaL < 1e-6 * segLen || alphaR < 1e-6 * segLen) {
            // Degenerate: fall back to the Wu/Barsky heuristic (handles a third of the chord out).
            val d = segLen / 3.0
            return Cubic(p0, p0 + tHat1 * d, p3 + tHat2 * d, p3)
        }
        return Cubic(p0, p0 + tHat1 * alphaL, p3 + tHat2 * alphaR, p3)
    }

    /** One Newton-Raphson step toward the curve parameter nearest [p]. */
    private fun newton(q: Cubic, p: Pt, u: Double): Double {
        val qu = eval(q, u)
        val q1 = arrayOf((q.p1 - q.p0) * 3.0, (q.p2 - q.p1) * 3.0, (q.p3 - q.p2) * 3.0)
        val q2 = arrayOf((q1[1] - q1[0]) * 2.0, (q1[2] - q1[1]) * 2.0)
        val d1 = q1[0] * ((1 - u) * (1 - u)) + q1[1] * (2 * (1 - u) * u) + q1[2] * (u * u)
        val d2 = q2[0] * (1 - u) + q2[1] * u
        val den = dot(d1, d1) + dot(qu - p, d2)
        if (den == 0.0) return u
        return (u - dot(qu - p, d1) / den).coerceIn(0.0, 1.0)
    }

    private fun eval(b: Cubic, t: Double): Pt {
        val mt = 1.0 - t
        return b.p0 * (mt * mt * mt) + b.p1 * (3.0 * mt * mt * t) + b.p2 * (3.0 * mt * t * t) + b.p3 * (t * t * t)
    }

    private fun maxDeviation(pts: List<Pt>, bez: Cubic, u: DoubleArray): Double {
        var max = 0.0
        for (i in pts.indices) {
            val d = eval(bez, u[i]).distanceTo(pts[i])
            if (d > max) max = d
        }
        return max
    }

    private fun chordLengthParameterize(pts: List<Pt>): DoubleArray {
        val u = DoubleArray(pts.size)
        for (i in 1 until pts.size) u[i] = u[i - 1] + pts[i].distanceTo(pts[i - 1])
        val total = u.last()
        if (total > 0.0) for (i in 1 until u.size) u[i] /= total
        return u
    }

    private fun dedupe(points: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>(points.size)
        for (p in points) if (out.isEmpty() || out.last().distanceTo(p) > 1e-9) out.add(p)
        return out
    }

    private fun dot(a: Pt, b: Pt): Double = a.x * b.x + a.y * b.y
    private fun b0(t: Double): Double = (1 - t) * (1 - t) * (1 - t)
    private fun b1(t: Double): Double = 3 * (1 - t) * (1 - t) * t
    private fun b2(t: Double): Double = 3 * (1 - t) * t * t
    private fun b3(t: Double): Double = t * t * t
}
