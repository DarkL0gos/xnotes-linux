package com.xnotes.core.model

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.shapes.PathRole
import com.xnotes.core.shapes.ShapePainter
import com.xnotes.core.shapes.ShapePath
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.vector.PathFlattener
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What shapes and connectors share as drawn lines: hit tests against their paths, the neon tube,
 * and the area eraser's cut, which leaves what survives of them as ordinary ink.
 */
internal object Outline {

    /** Does [p] land within [tol] of a stroked path, or inside a filled head, or in a fill when [filled]? */
    fun hits(paths: List<ShapePath>, p: Pt, tol: Double, filled: Boolean): Boolean {
        for (path in paths) {
            val rings = path.contours.map { PathFlattener.flatten(it, HIT_FLATTEN) }
            if ((path.role == PathRole.HEAD_FILLED || (filled && path.role.fills)) && inRings(rings, p)) return true
            if (path.role.strokes) {
                for (i in rings.indices) if (nearPolyline(rings[i], p, tol, path.contours[i].closed)) return true
            }
        }
        return false
    }

    /** Inside an odd number of [rings]: in their fill, holes left out. */
    fun inRings(rings: List<List<Pt>>, p: Pt): Boolean {
        var inside = false
        for (ring in rings) if (ring.size >= 3 && Geometry.pointInPolygon(ring, p)) inside = !inside
        return inside
    }

    fun nearPolyline(verts: List<Pt>, p: Pt, tol: Double, closed: Boolean): Boolean {
        if (verts.isEmpty()) return false
        if (verts.size == 1) return verts[0].distanceTo(p) <= tol
        val edges = if (closed) verts.size else verts.size - 1
        for (i in 0 until edges) {
            if (Geometry.distancePointToSegment(p, verts[i], verts[(i + 1) % verts.size]) <= tol) return true
        }
        return false
    }

    /** Halo blur = width x (MIN + SPAN x strength), floored in page px. */
    fun glowRadius(width: Double, strength: Double): Double =
        (width * (GLOW_FACTOR_MIN + GLOW_FACTOR_SPAN * strength.coerceIn(0.0, 1.0))).coerceAtLeast(GLOW_MIN)

    /**
     * Neon: the lines as a glowing glass tube. A blurred halo in the line's colour (composited once
     * at a glow alpha so corners don't blow out), the opaque body, then a thinner white-hot core
     * down the middle so the colour reads at the tube's edges. [strength] scales the halo only.
     */
    fun paintNeon(r: Renderer, paths: List<ShapePath>, fill: Rgba?, pen: Pen, strength: Double, bounds: Rect) {
        val s = strength.coerceIn(0.0, 1.0)
        ShapePainter.fill(r, paths, fill, pen.color)
        r.saveLayerAlpha(bounds, GLOW_ALPHA_MIN + GLOW_ALPHA_SPAN * s)
        ShapePainter.stroke(r, paths, pen.copy(color = pen.color.withAlpha(255), glowRadius = glowRadius(pen.width, s)))
        r.restore()
        ShapePainter.stroke(r, paths, pen)
        ShapePainter.stroke(r, paths, pen.copy(color = Rgba(255, 255, 255, 255), width = (pen.width * CORE_WIDTH_FRAC).coerceAtLeast(CORE_WIDTH_MIN)))
    }

    /**
     * The area eraser's circle at [c] with [radius] cutting the stroked [paths]: whether it touched
     * any, and the runs left outside it, cut cleanly at its edge, as pen strokes in [pen]'s colour
     * and width, dashed where the path was and neon when [neon]. Null when nothing is stroked.
     */
    fun cut(paths: List<ShapePath>, pen: Pen, neon: Boolean, neonStrength: Double, c: Pt, radius: Double): Pair<Boolean, List<CanvasItem>>? {
        val outlines = paths
            .filter { it.role.strokes }
            .flatMap { p ->
                val dashes = ShapePainter.strokePen(p.role, pen)?.takeIf { it.dashed }
                p.contours.map { Triple(PathFlattener.flatten(it, ERASE_FLATTEN), it.closed, dashes) }
            }
            .filter { it.first.size >= 2 }
        if (outlines.isEmpty()) return null
        var touched = false
        val ink = ArrayList<CanvasItem>()
        for ((pts, closed, dashes) in outlines) {
            val (hit, survivors) = cutRun(pts, closed, c, radius)
            touched = touched || hit
            for (run in survivors) ink.add(inkOf(run, pen, dashes, neon, neonStrength))
        }
        return touched to ink
    }

    /** A run of a drawn line as a pen stroke: its colour and width, held constant, never smoothed. */
    private fun inkOf(run: List<Pt>, pen: Pen, dashes: Pen?, neon: Boolean, neonStrength: Double): Stroke {
        val tool = if (dashes != null) Tool.DASHED else Tool.PEN
        val config = ToolDefaults.configFor(tool).copy(
            baseWidth = pen.width,
            rgba = pen.color,
            pressureEnabled = false,
            taperEnabled = false,
            neon = neon,
            neonStrength = neonStrength,
            dashLength = dashes?.dashOn ?: pen.dashOn,
            dashGap = dashes?.dashGap ?: pen.dashGap,
        )
        // Close-spaced samples, so the eraser can go on cutting the ink anywhere along it.
        val samples = ArrayList<Sample>()
        for (i in run.indices) {
            val a = run[i]
            if (i == 0) {
                samples.add(Sample(a.x, a.y, 1.0))
                continue
            }
            val from = run[i - 1]
            val steps = kotlin.math.ceil(from.distanceTo(a) / INK_SPACING).toInt().coerceAtLeast(1)
            for (k in 1..steps) {
                val t = k.toDouble() / steps
                samples.add(Sample(from.x + (a.x - from.x) * t, from.y + (a.y - from.y) * t, 1.0))
            }
        }
        return Stroke(tool, config, samples, smoothScale = 0.0)
    }

    /** One line cut by the eraser circle: whether it was touched, and the runs left outside it. */
    private fun cutRun(verts: List<Pt>, closed: Boolean, c: Pt, radius: Double): Pair<Boolean, List<List<Pt>>> {
        val pts = if (closed) verts + verts.first() else verts
        var touched = false
        val runs = mutableListOf<MutableList<Pt>>()
        var current = mutableListOf<Pt>()
        fun flush() {
            if (current.size >= 2 && polylineLength(current) > 1e-6) runs.add(current)
            current = mutableListOf()
        }
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val outside = outsideSpans(a, b, c, radius)
            if (outside.size == 1 && outside[0].first == 0.0 && outside[0].second == 1.0) {
                if (current.isEmpty()) current.add(a)
                current.add(b)
                continue
            }
            touched = true
            if (outside.isEmpty()) { // the whole segment sits inside the circle
                flush()
                continue
            }
            for ((ta, tb) in outside) {
                if (ta == 0.0) {
                    if (current.isEmpty()) current.add(a)
                } else {
                    flush()
                    current.add(lerp(a, b, ta))
                }
                current.add(if (tb == 1.0) b else lerp(a, b, tb))
                if (tb < 1.0) flush()
            }
        }
        flush()

        // A closed outline cut away from the seam vertex leaves the seam intact in two runs;
        // join them so the survivor is one continuous polyline instead of splitting at v0.
        if (touched && closed && runs.size >= 2) {
            val first = runs.first()
            val last = runs.last()
            if (first.first().distanceTo(pts.first()) < 1e-9 && last.last().distanceTo(pts.first()) < 1e-9) {
                runs.removeAt(runs.size - 1)
                runs[0] = (last + first.drop(1)).toMutableList()
            }
        }
        return touched to runs
    }

    /** Sub-intervals of the segment [a]->[b] (as fractions) lying outside the circle at [c]. */
    private fun outsideSpans(a: Pt, b: Pt, c: Pt, r: Double): List<Pair<Double, Double>> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val fx = a.x - c.x
        val fy = a.y - c.y
        val qa = dx * dx + dy * dy
        if (qa < 1e-12) return if (fx * fx + fy * fy > r * r) listOf(0.0 to 1.0) else emptyList()
        val qb = 2.0 * (fx * dx + fy * dy)
        val qc = fx * fx + fy * fy - r * r
        val disc = qb * qb - 4.0 * qa * qc
        if (disc <= 0.0) return listOf(0.0 to 1.0) // misses (or grazes) the segment's line
        val sq = sqrt(disc)
        val t1 = (-qb - sq) / (2.0 * qa)
        val t2 = (-qb + sq) / (2.0 * qa)
        if (t2 <= 0.0 || t1 >= 1.0) return listOf(0.0 to 1.0) // chord lies beyond the segment span
        val spans = mutableListOf<Pair<Double, Double>>()
        if (t1 > 0.0) spans.add(0.0 to min(t1, 1.0))
        if (t2 < 1.0) spans.add(max(t2, 0.0) to 1.0)
        return spans
    }

    private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun polylineLength(pts: List<Pt>): Double {
        var len = 0.0
        for (i in 0 until pts.size - 1) len += pts[i].distanceTo(pts[i + 1])
        return len
    }

    /** Content px a curve may stray when flattened for hit tests and for erasing. */
    const val HIT_FLATTEN = 0.5
    private const val ERASE_FLATTEN = 0.25

    /** How far apart cut ink is sampled, in content px: a pen's own density. */
    private const val INK_SPACING = 1.5

    private const val GLOW_FACTOR_MIN = 1.2
    private const val GLOW_FACTOR_SPAN = 2.6
    private const val GLOW_MIN = 4.0

    /** Halo opacity = MIN + SPAN x strength. */
    private const val GLOW_ALPHA_MIN = 0.25
    private const val GLOW_ALPHA_SPAN = 0.55

    /** White-hot core width as a fraction of the line's, with a page-px floor. */
    private const val CORE_WIDTH_FRAC = 0.4
    private const val CORE_WIDTH_MIN = 1.0
}
