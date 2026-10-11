package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The Block arrows & callouts page. Arrows that come in several directions are one drawing made
// pointing right in a canonical box and turned or mirrored onto the real one, so each direction is
// its own kind (and a label inside stays upright) without a second copy of the drawing.

/** Which way a canonical, right-pointing drawing is laid onto its box. */
internal enum class Orient {
    RIGHT, LEFT, UP, DOWN;

    /** The canonical box's width and height for a real box [b]: turned ones swap them. */
    fun canonical(b: Rect): Pair<Double, Double> = if (this == UP || this == DOWN) b.h to b.w else b.w to b.h

    fun map(p: Pt, b: Rect): Pt = when (this) {
        RIGHT -> Pt(b.left + p.x, b.top + p.y)
        LEFT -> Pt(b.right - p.x, b.top + p.y)
        UP -> Pt(b.left + p.y, b.bottom - p.x)
        DOWN -> Pt(b.right - p.y, b.top + p.x)
    }

    fun unmap(p: Pt, b: Rect): Pt = when (this) {
        RIGHT -> Pt(p.x - b.left, p.y - b.top)
        LEFT -> Pt(b.right - p.x, p.y - b.top)
        UP -> Pt(b.bottom - p.y, p.x - b.left)
        DOWN -> Pt(p.y - b.top, b.right - p.x)
    }

    fun direction(v: Pt): Pt = when (this) {
        RIGHT -> v
        LEFT -> Pt(-v.x, v.y)
        UP -> Pt(v.y, -v.x)
        DOWN -> Pt(-v.y, v.x)
    }
}

/** A drawing made in the canonical box (0, 0)-([w], [h]), pointing right, and its knobs there. */
internal interface Canonical {
    val closed: Boolean get() = true
    val defaultAdj: DoubleArray
    val knobs: List<KnobDef> get() = emptyList()
    val iconAspect: Double get() = 1.5
    val perfectAspect: Double get() = 1.0
    fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry
}

/** A [Canonical] drawing laid onto its box by [orient]. */
internal class OrientedKind(id: String, private val orient: Orient, private val drawing: Canonical) : ShapeKind(id) {
    override val isClosed get() = drawing.closed
    override val defaultAdj = drawing.defaultAdj
    override val iconAspect get() = if (orient == Orient.UP || orient == Orient.DOWN) 1.0 / drawing.iconAspect else drawing.iconAspect
    override fun perfectAspect(n: Int) = if (orient == Orient.UP || orient == Orient.DOWN) 1.0 / drawing.perfectAspect else drawing.perfectAspect

    // A flip turns one direction into another kind, so the shape goes free instead.
    override fun mirroredAdj(adj: DoubleArray): DoubleArray? = null

    override val knobs: List<KnobDef> = drawing.knobs.map { k ->
        KnobDef(
            position = { box, adj, n -> orient.map(k.position(canonicalBox(box), adj, n), box) },
            drag = { to, box, adj, n -> k.drag(orient.unmap(to, box), canonicalBox(box), adj, n) },
            reframe = k.reframe?.let { f ->
                { to, box ->
                    val c = f(orient.unmap(to, box), canonicalBox(box))
                    Rect.fromPoints(orient.map(c.topLeft, box), orient.map(Pt(c.right, c.bottom), box))
                }
            },
        )
    }

    private fun canonicalBox(box: Rect): Rect {
        val (w, h) = orient.canonical(box)
        return Rect(0.0, 0.0, w, h)
    }

    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val (w, h) = orient.canonical(box)
        val g = drawing.build(w, h) { adjOf(p.adj, it) }
        val f = { q: Pt -> orient.map(q, box) }
        val text = g.textRect.let { t -> Rect.fromPoints(f(t.topLeft), f(Pt(t.right, t.bottom))) }
        return ShapeGeometry(
            g.paths.map { it.mapped(f) },
            g.corners.map(f),
            g.connections.map { c -> ConnectionPoint(f(c.at), c.normals.map(orient::direction)) },
            text,
        )
    }
}

/** A kind drawn straight into its box, for the arrows with no direction and for the callouts. */
internal class BoxedKind(
    id: String,
    private val drawing: Canonical,
    private val mirror: ((DoubleArray) -> DoubleArray?)? = null,
    override val staysInBox: Boolean = true,
) : ShapeKind(id) {
    override val isClosed get() = drawing.closed
    override val defaultAdj = drawing.defaultAdj
    override val iconAspect get() = drawing.iconAspect
    override fun perfectAspect(n: Int) = drawing.perfectAspect
    override fun mirroredAdj(adj: DoubleArray): DoubleArray? = mirror?.invoke(adj)
    override val knobs: List<KnobDef> = drawing.knobs.map { k ->
        KnobDef(
            position = { box, adj, n -> k.position(Rect(0.0, 0.0, box.w, box.h), adj, n) + box.topLeft },
            drag = { to, box, adj, n -> k.drag(to - box.topLeft, Rect(0.0, 0.0, box.w, box.h), adj, n) },
            reframe = k.reframe?.let { f ->
                { to, box -> f(to - box.topLeft, Rect(0.0, 0.0, box.w, box.h)).let { Rect(it.x + box.left, it.y + box.top, it.w, it.h) } }
            },
        )
    }

    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val g = drawing.build(box.w, box.h) { adjOf(p.adj, it) }
        val f = { q: Pt -> q + box.topLeft }
        return ShapeGeometry(
            g.paths.map { it.mapped(f) },
            g.corners.map(f),
            g.connections.map { c -> ConnectionPoint(f(c.at), c.normals) },
            Rect(g.textRect.x + box.left, g.textRect.y + box.top, g.textRect.w, g.textRect.h),
        )
    }
}

// --- the one knob every arrow has, in the canonical frame ---

// Every head is twice as wide as its shaft is thick, and as long as the shaft is thick, so the
// thickness knob sizes the head with the shaft and nothing can change the one against the other.

/** The least a shaft spanning arrow's box may be, in content px. */
private const val LEAST_SPAN = 2.0

/**
 * The knob of an arrow whose head spans its box, which makes the shaft half the box's height: on
 * the middle of the head's top edge, level with the shaft's, it deepens or flattens the box about
 * the centre line, the head included, up to [deepest] for a box that long. [head] is the head's
 * length in a box.
 */
private fun spanKnob(head: (w: Double, h: Double) -> Double, deepest: (w: Double) -> Double) = KnobDef(
    position = { b, _, _ -> Pt(b.w - head(b.w, b.h) / 2.0, b.h / 4.0) },
    drag = { _, _, adj, _ -> adj },
    reframe = { to, b ->
        val h = (4.0 * abs(to.y - b.centerY)).coerceIn(LEAST_SPAN, max(LEAST_SPAN, deepest(b.w)))
        Rect(b.left, b.centerY - h / 2.0, b.w, h)
    },
)

/** A shaft's thickness from its stored [share] of the shorter side, held to [range]. */
private fun thickness(share: Double, w: Double, h: Double, range: ClosedFloatingPointRange<Double>) =
    share.coerceIn(range) * min(w, h)

/**
 * The knob of an arrow whose arms reach its box at any thickness, stored as a share of the shorter
 * side within [range]: [at] places it for a thickness, [reach] reads the thickness a drag asks for.
 */
private fun thicknessKnob(
    default: Double,
    range: ClosedFloatingPointRange<Double>,
    at: (w: Double, h: Double, t: Double) -> Pt,
    reach: (to: Pt, w: Double, h: Double) -> Double,
) = KnobDef(
    position = { b, adj, _ -> at(b.w, b.h, thickness(adj.getOrNull(0)?.takeIf { it.isFinite() } ?: default, b.w, b.h, range)) },
    drag = { to, b, _, _ ->
        val side = min(b.w, b.h)
        doubleArrayOf(if (side > 0.0) (reach(to, b.w, b.h) / side).coerceIn(range) else default)
    },
)

/** One head on the right of a straight shaft. Notched: a V cut into the tail. */
internal class SingleArrow(private val notched: Boolean = false) : Canonical {
    override val defaultAdj = DoubleArray(0)
    override val knobs = listOf(spanKnob({ w, h -> head(w, h) }, { w -> 2.0 * w }))

    private fun head(w: Double, h: Double) = min(h / 2.0, w)

    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val l = head(w, h)
        val half = h / 4.0
        val cy = h / 2.0
        val notch = if (notched) min(half, w - l) else 0.0
        val corners = listOf(
            Pt(0.0, cy - half), Pt(w - l, cy - half), Pt(w - l, 0.0), Pt(w, cy),
            Pt(w - l, h), Pt(w - l, cy + half), Pt(0.0, cy + half),
        ) + (if (notched) listOf(Pt(notch, cy)) else emptyList())
        val ends = listOf(
            ConnectionPoint(Pt(w, cy), listOf(Pt(1.0, 0.0))),
            ConnectionPoint(Pt(notch, cy), listOf(Pt(-1.0, 0.0))),
            ConnectionPoint(Pt((w - l) / 2.0, cy - half), listOf(Pt(0.0, -1.0))),
            ConnectionPoint(Pt((w - l) / 2.0, cy + half), listOf(Pt(0.0, 1.0))),
        )
        val text = Rect(notch, cy - half, (w - l - notch).coerceAtLeast(0.0), 2 * half)
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, ends, text)
    }
}

/** Heads at both ends of a straight shaft. */
internal class DoubleArrow : Canonical {
    override val defaultAdj = DoubleArray(0)
    override val iconAspect get() = 2.0
    // Squared up, the heads would meet with no shaft between them.
    override val perfectAspect get() = 2.0
    // On the head at the right end.
    override val knobs = listOf(spanKnob({ w, h -> head(w, h) }, { w -> w }))

    private fun head(w: Double, h: Double) = min(h / 2.0, w / 2.0)

    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val l = head(w, h)
        val half = h / 4.0
        val cy = h / 2.0
        val corners = listOf(
            Pt(0.0, cy), Pt(l, 0.0), Pt(l, cy - half), Pt(w - l, cy - half), Pt(w - l, 0.0), Pt(w, cy),
            Pt(w - l, h), Pt(w - l, cy + half), Pt(l, cy + half), Pt(l, h),
        )
        val ends = listOf(
            ConnectionPoint(Pt(0.0, cy), listOf(Pt(-1.0, 0.0))),
            ConnectionPoint(Pt(w, cy), listOf(Pt(1.0, 0.0))),
            ConnectionPoint(Pt(w / 2.0, cy - half), listOf(Pt(0.0, -1.0))),
            ConnectionPoint(Pt(w / 2.0, cy + half), listOf(Pt(0.0, 1.0))),
        )
        return ShapeGeometry(listOf(ShapePath.polygon(corners)), corners, ends, Rect(l, cy - half, (w - 2 * l).coerceAtLeast(0.0), 2 * half))
    }
}

/** Four heads, one on each side, their shafts crossing in the middle. */
internal class QuadArrow : Canonical {
    override val defaultAdj = doubleArrayOf(0.22)
    override val iconAspect get() = 1.0
    override val knobs = listOf(
        // Halfway along the right head's top edge.
        thicknessKnob(0.22, RANGE, at = { w, h, t -> Pt(w - t / 2.0, h / 2.0 - t / 2.0) }) { to, _, h -> 2.0 * abs(to.y - h / 2.0) },
    )

    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val t = thickness(adj(0), w, h, RANGE)
        val a = t / 2.0
        val b = t
        val l = t
        val cx = w / 2.0
        val cy = h / 2.0
        val c = listOf(
            Pt(cx, 0.0), Pt(cx + b, l), Pt(cx + a, l), Pt(cx + a, cy - a), Pt(w - l, cy - a), Pt(w - l, cy - b),
            Pt(w, cy), Pt(w - l, cy + b), Pt(w - l, cy + a), Pt(cx + a, cy + a), Pt(cx + a, h - l), Pt(cx + b, h - l),
            Pt(cx, h), Pt(cx - b, h - l), Pt(cx - a, h - l), Pt(cx - a, cy + a), Pt(l, cy + a), Pt(l, cy + b),
            Pt(0.0, cy), Pt(l, cy - b), Pt(l, cy - a), Pt(cx - a, cy - a), Pt(cx - a, l), Pt(cx - b, l),
        )
        val tips = listOf(Pt(cx, 0.0) to Pt(0.0, -1.0), Pt(w, cy) to Pt(1.0, 0.0), Pt(cx, h) to Pt(0.0, 1.0), Pt(0.0, cy) to Pt(-1.0, 0.0))
        return ShapeGeometry(listOf(ShapePath.polygon(c)), c, tips.map { ConnectionPoint(it.first, listOf(it.second)) }, Rect(cx - a, cy - a, 2 * a, 2 * a))
    }

    private companion object {
        // At a third of the shorter side the heads' bases meet the crossing.
        val RANGE = 0.05..(1.0 / 3.0)
    }
}

/** A chevron (notched both ends) or a pentagon arrow (square tail), the knob setting how far the point reaches back. */
internal class PointedBand(private val notchedTail: Boolean) : Canonical {
    override val defaultAdj = doubleArrayOf(0.5)
    override val knobs = listOf(
        KnobDef(
            position = { b, adj, _ -> Pt(b.w - depth(b.w, b.h, adj.getOrNull(0) ?: 0.5), 0.0) },
            drag = { to, b, _, _ -> doubleArrayOf(((b.w - to.x) / min(b.w, b.h).coerceAtLeast(1e-9)).coerceIn(0.0, b.w / min(b.w, b.h).coerceAtLeast(1e-9))) },
        ),
    )

    private fun depth(w: Double, h: Double, k: Double) = (k * min(w, h)).coerceIn(0.0, if (notchedTail) w / 2.0 else w)

    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val d = depth(w, h, adj(0))
        val cy = h / 2.0
        val c = listOf(Pt(0.0, 0.0), Pt(w - d, 0.0), Pt(w, cy), Pt(w - d, h), Pt(0.0, h)) +
            (if (notchedTail) listOf(Pt(d, cy)) else emptyList())
        return ShapeGeometry(listOf(ShapePath.polygon(c)), c, ShapeKind.polygonConnections(c), ShapeKind.inset(Rect(if (notchedTail) d else 0.0, 0.0, w - d - (if (notchedTail) d else 0.0), h)))
    }
}

/** An L: a shaft up the left side turning right along the top into a head. Bent-up: along the bottom, turning up at the right. */
internal class BentArrow(private val up: Boolean) : Canonical {
    override val defaultAdj = doubleArrayOf(0.25)
    override val iconAspect get() = 1.2
    override val knobs = listOf(
        if (!up) {
            // Halfway along the head's lower edge, which sits a thickness and a half down.
            thicknessKnob(0.25, RANGE, at = { w, h, t -> Pt(w - head(w, h, t) / 2.0, 1.5 * t) }) { to, _, _ -> to.y / 1.5 }
        } else {
            // Halfway along the head's left edge, a thickness and a half in from the right.
            thicknessKnob(0.25, RANGE, at = { w, h, t -> Pt(w - 1.5 * t, head(w, h, t) / 2.0) }) { to, w, _ -> (w - to.x) / 1.5 }
        },
    )

    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val t = thickness(adj(0), w, h, RANGE)
        val b = t
        val l = head(w, h, t)
        val c = if (!up) {
            listOf(
                Pt(0.0, h), Pt(0.0, b - t / 2.0), Pt(w - l, b - t / 2.0), Pt(w - l, 0.0), Pt(w, b),
                Pt(w - l, 2 * b), Pt(w - l, b + t / 2.0), Pt(t, b + t / 2.0), Pt(t, h),
            )
        } else {
            val x = w - b
            listOf(
                Pt(0.0, h - t), Pt(x - t / 2.0, h - t), Pt(x - t / 2.0, l), Pt(w - 2 * b, l), Pt(x, 0.0),
                Pt(w, l), Pt(x + t / 2.0, l), Pt(x + t / 2.0, h), Pt(0.0, h),
            )
        }
        val text = if (!up) Rect(t, b + t / 2.0, w - t, h - b - t / 2.0) else Rect(0.0, 0.0, w - 2 * b, h - t)
        return ShapeGeometry(listOf(ShapePath.polygon(c)), c, ShapeKind.polygonConnections(c).filterIndexed { i, _ -> i < c.size }, ShapeKind.inset(text))
    }

    private fun head(w: Double, h: Double, t: Double) = t.coerceIn(0.0, max(0.0, (if (up) h else w) - t))

    private companion object {
        val RANGE = 0.05..0.45
    }
}

/** Two heads joined by an L: one pointing left at the bottom, one pointing up at the right. */
internal class LeftUpArrow : Canonical {
    override val defaultAdj = doubleArrayOf(0.2)
    override val iconAspect get() = 1.0
    override val knobs = listOf(
        // Halfway along the upper head's left edge, a thickness and a half in from the right.
        thicknessKnob(0.2, RANGE, at = { w, h, t -> Pt(w - 1.5 * t, head(w, h, t) / 2.0) }) { to, w, _ -> (w - to.x) / 1.5 },
    )

    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val t = thickness(adj(0), w, h, RANGE)
        val b = t
        val l = head(w, h, t)
        val x = w - b
        val y = h - b
        val c = listOf(
            Pt(0.0, y), Pt(l, y - b), Pt(l, y - t / 2.0), Pt(x - t / 2.0, y - t / 2.0), Pt(x - t / 2.0, l),
            Pt(x - b, l), Pt(x, 0.0), Pt(w, l), Pt(x + t / 2.0, l), Pt(x + t / 2.0, y + t / 2.0),
            Pt(l, y + t / 2.0), Pt(l, h),
        )
        return ShapeGeometry(listOf(ShapePath.polygon(c)), c, ShapeKind.polygonConnections(c).take(c.size), ShapeKind.inset(Rect(0.0, 0.0, x - t / 2.0, y - t / 2.0)))
    }

    private fun head(w: Double, h: Double, t: Double) = t.coerceIn(0.0, min(w, h) / 2.0)

    private companion object {
        val RANGE = 0.05..0.3
    }
}

/** A U: up the left, over the top in a half circle, down the right into a head pointing down. */
internal class UTurnArrow : Canonical {
    override val defaultAdj = doubleArrayOf(0.25)
    override val iconAspect get() = 1.0
    // Halfway along the head's left edge, a thickness and a half in from the right.
    override val knobs = listOf(thicknessKnob(0.25, RANGE, at = { w, h, t -> Pt(w - 1.5 * t, h - head(h, t) / 2.0) }) { to, w, _ -> (w - to.x) / 1.5 })

    private fun head(h: Double, t: Double) = t.coerceIn(0.0, h / 2.0)

    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val t = thickness(adj(0), w, h, RANGE)
        val b = t
        val l = head(h, t)
        val leftX = t / 2.0
        val rightX = w - b
        val rc = (rightX - leftX) / 2.0
        val outer = rc + t / 2.0
        val inner = (rc - t / 2.0).coerceAtLeast(1e-6)
        // A shallow box flattens the bend into a half ellipse rather than overrunning the legs.
        val outerY = min(outer, (h - l) * 0.7).coerceAtLeast(t)
        val innerY = (outerY - t).coerceAtLeast(1e-6)
        val centre = Pt((leftX + rightX) / 2.0, outerY)
        val segs = ArrayList<VectorSeg>()
        segs += VectorSeg.Line(Pt(0.0, centre.y))
        segs += arcSegments(centre, outer, outerY, PI, PI)
        segs += VectorSeg.Line(Pt(rightX + t / 2.0, h - l))
        segs += VectorSeg.Line(Pt(w, h - l))
        segs += VectorSeg.Line(Pt(rightX, h))
        segs += VectorSeg.Line(Pt(w - 2 * b, h - l))
        segs += VectorSeg.Line(Pt(rightX - t / 2.0, h - l))
        segs += VectorSeg.Line(Pt(rightX - t / 2.0, centre.y))
        segs += arcSegments(centre, inner, innerY, 0.0, -PI)
        segs += VectorSeg.Line(Pt(t, h))
        val contour = VectorContour(Pt(0.0, h), segs, closed = true)
        val tips = listOf(ConnectionPoint(Pt(rightX, h), listOf(Pt(0.0, 1.0))), ConnectionPoint(Pt(t / 2.0, h), listOf(Pt(0.0, 1.0))))
        return ShapeGeometry(listOf(ShapePath(listOf(contour))), emptyList(), tips, ShapeKind.inset(Rect(t, centre.y, w - 2 * b - t, h - centre.y - l)))
    }

    private companion object {
        val RANGE = 0.05..0.3
    }
}

/** A band sweeping up from the bottom left in a quarter curve into a head pointing right at the top. */
internal class CurvedArrow : Canonical {
    override val defaultAdj = doubleArrayOf(0.25)
    override val iconAspect get() = 1.0
    // Halfway along the head's lower edge, which sits a thickness and a half down.
    override val knobs = listOf(thicknessKnob(0.25, RANGE, at = { w, _, t -> Pt(w - head(w, t) / 2.0, 1.5 * t) }) { to, _, _ -> to.y / 1.5 })

    private fun head(w: Double, t: Double) = t.coerceIn(0.0, w / 2.0)

    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val t = thickness(adj(0), w, h, RANGE)
        val b = t
        val l = head(w, t)
        val centre = Pt(w - l, h)
        val rx = (w - l - t / 2.0).coerceAtLeast(t)
        val ry = (h - b).coerceAtLeast(t)
        val segs = ArrayList<VectorSeg>()
        segs += arcSegments(centre, rx + t / 2.0, ry + t / 2.0, PI, PI / 2.0)
        segs += VectorSeg.Line(Pt(w - l, 0.0))
        segs += VectorSeg.Line(Pt(w, b))
        segs += VectorSeg.Line(Pt(w - l, 2 * b))
        segs += VectorSeg.Line(Pt(w - l, b + t / 2.0))
        segs += arcSegments(centre, rx - t / 2.0, ry - t / 2.0, 1.5 * PI, -PI / 2.0)
        val contour = VectorContour(Pt(centre.x - rx - t / 2.0, h), segs, closed = true)
        val tips = listOf(ConnectionPoint(Pt(w, b), listOf(Pt(1.0, 0.0))), ConnectionPoint(Pt(t / 2.0, h), listOf(Pt(0.0, 1.0))))
        return ShapeGeometry(listOf(ShapePath(listOf(contour))), emptyList(), tips, ShapeKind.inset(Rect(t, 2 * b, w - l - t, h - 2 * b)))
    }

    private companion object {
        val RANGE = 0.05..0.4
    }
}

/**
 * Three quarters of a ring, clockwise from the bottom left, ending in a head. Radii are shares of
 * the box's half size, and the ring sits a thickness in from the rim so the head just reaches it.
 */
internal class CircularArrow : Canonical {
    override val defaultAdj = doubleArrayOf(0.25)
    override val iconAspect get() = 1.0
    override val knobs = listOf(
        KnobDef(
            // On the head's inner corner, two thicknesses in from the rim.
            position = { b, adj, _ -> onUnit(b, START + SWEEP, 1.0 - 2.0 * thick(adj.getOrNull(0)?.takeIf { it.isFinite() } ?: 0.25)) },
            drag = { to, b, _, _ ->
                val u = Pt((to.x - b.w / 2.0) / (b.w / 2.0).coerceAtLeast(1e-9), (to.y - b.h / 2.0) / (b.h / 2.0).coerceAtLeast(1e-9))
                doubleArrayOf(thick((1.0 - u.length()) / 2.0))
            },
        ),
    )

    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val b = Rect(0.0, 0.0, w, h)
        val t = thick(adj(0))
        val mid = 1.0 - t
        val half = t / 2.0
        val end = START + SWEEP
        val tipAngle = end + t / mid
        val segs = ArrayList<VectorSeg>()
        segs += arcSegments(b.center, w / 2.0 * (mid + half), h / 2.0 * (mid + half), START, SWEEP)
        segs += VectorSeg.Line(onUnit(b, end, mid + t))
        segs += VectorSeg.Line(onUnit(b, tipAngle, mid))
        segs += VectorSeg.Line(onUnit(b, end, mid - t))
        segs += VectorSeg.Line(onUnit(b, end, mid - half))
        segs += arcSegments(b.center, w / 2.0 * (mid - half), h / 2.0 * (mid - half), end, -SWEEP)
        val contour = VectorContour(onUnit(b, START, mid + half), segs, closed = true)
        return ShapeGeometry(listOf(ShapePath(listOf(contour))), emptyList(), ellipseConnections(b), ShapeKind.inset(b, 0.32))
    }

    private fun thick(share: Double) = share.coerceIn(0.05, 0.4)

    private fun onUnit(b: Rect, a: Double, r: Double) = Pt(b.centerX + b.w / 2.0 * r * cos(a), b.centerY + b.h / 2.0 * r * sin(a))

    private companion object {
        const val START = 0.75 * PI
        const val SWEEP = 1.5 * PI
    }
}

/** A right arrow whose tail is broken into two stripes. */
internal class StripedArrow : Canonical {
    override val defaultAdj = DoubleArray(0)
    override val knobs = listOf(spanKnob({ w, h -> head(w, h) }, { w -> 1.4 * w }))

    private fun head(w: Double, h: Double) = min(h / 2.0, w * 0.7)

    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val l = head(w, h)
        val half = h / 4.0
        val cy = h / 2.0
        val s = w * 0.05
        fun bar(x: Double, width: Double) = ShapePath.lines(listOf(Pt(x, cy - half), Pt(x + width, cy - half), Pt(x + width, cy + half), Pt(x, cy + half)), true)
        val body = listOf(
            Pt(4 * s, cy - half), Pt(w - l, cy - half), Pt(w - l, 0.0), Pt(w, cy),
            Pt(w - l, h), Pt(w - l, cy + half), Pt(4 * s, cy + half),
        )
        val path = ShapePath(listOf(bar(0.0, s), bar(2 * s, s), ShapePath.lines(body, true)))
        val ends = listOf(ConnectionPoint(Pt(w, cy), listOf(Pt(1.0, 0.0))), ConnectionPoint(Pt(0.0, cy), listOf(Pt(-1.0, 0.0))))
        return ShapeGeometry(listOf(path), emptyList(), ends, Rect(4 * s, cy - half, (w - l - 4 * s).coerceAtLeast(0.0), 2 * half))
    }
}

// --- callouts: a body and a tail whose tip is a knob, free to sit outside the box ---

/** The tail's tip as fractions of the box from its top left: below the left quarter by default. */
private val TAIL_ADJ = doubleArrayOf(0.25, 1.35)

private fun tailKnob() = KnobDef(
    position = { b, adj, _ -> Pt(b.w * (adj.getOrNull(0) ?: TAIL_ADJ[0]), b.h * (adj.getOrNull(1) ?: TAIL_ADJ[1])) },
    drag = { to, b, _, _ ->
        doubleArrayOf(
            if (b.w > 0.0) (to.x / b.w).coerceIn(-3.0, 4.0) else 0.25,
            if (b.h > 0.0) (to.y / b.h).coerceIn(-3.0, 4.0) else 1.35,
        )
    },
)

private fun mirrorTail(adj: DoubleArray) = doubleArrayOf(1.0 - (adj.getOrNull(0) ?: TAIL_ADJ[0]), adj.getOrNull(1) ?: TAIL_ADJ[1])

/** A rectangle (rounded or not) with a wedge out of the side that faces its tip. */
internal class BoxCallout(private val rounded: Boolean) : Canonical {
    override val defaultAdj = TAIL_ADJ
    override val knobs = listOf(tailKnob())
    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val tip = Pt(w * adj(0), h * adj(1))
        val r = if (rounded) min(w, h) / 6.0 else 0.0
        // Walk round the box clockwise; the side facing the tip gets the wedge.
        val d = Pt((tip.x - w / 2.0) / (w / 2.0).coerceAtLeast(1e-9), (tip.y - h / 2.0) / (h / 2.0).coerceAtLeast(1e-9))
        val side = if (abs(d.x) > abs(d.y)) (if (d.x > 0) 1 else 3) else (if (d.y > 0) 2 else 0)
        val corners = listOf(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, h), Pt(0.0, h))
        val segs = ArrayList<VectorSeg>()
        val k = r * ShapePath.KAPPA
        var start: Pt? = null
        for (i in 0 until 4) {
            val a = corners[i]
            val bpt = corners[(i + 1) % 4]
            val dir = (bpt - a).normalized()
            val from = a + dir * r
            val to = bpt - dir * r
            if (start == null) start = from
            if (i == side) {
                val len = from.distanceTo(to)
                val base = len * 0.18
                val along = ((tip - from).let { it.x * dir.x + it.y * dir.y }).coerceIn(base, len - base)
                segs += VectorSeg.Line(from + dir * (along - base / 2.0))
                segs += VectorSeg.Line(tip)
                segs += VectorSeg.Line(from + dir * (along + base / 2.0))
            }
            segs += VectorSeg.Line(to)
            if (r > 0.0) {
                val next = corners[(i + 2) % 4]
                val ndir = (next - bpt).normalized()
                segs += VectorSeg.Cubic(to + dir * k, bpt + ndir * (r - k), bpt + ndir * r)
            }
        }
        val contour = VectorContour(start!!, segs, closed = true)
        val body = Rect(0.0, 0.0, w, h)
        return ShapeGeometry(listOf(ShapePath(listOf(contour))), emptyList(), cardinalConnections(body) + ConnectionPoint(tip, emptyList()), ShapeKind.inset(body))
    }
}

/** An ellipse with a wedge out toward its tip. */
internal class OvalCallout : Canonical {
    override val defaultAdj = TAIL_ADJ
    override val knobs = listOf(tailKnob())
    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val tip = Pt(w * adj(0), h * adj(1))
        val c = Pt(w / 2.0, h / 2.0)
        val toward = atan2((tip.y - c.y) / (h / 2.0).coerceAtLeast(1e-9), (tip.x - c.x) / (w / 2.0).coerceAtLeast(1e-9))
        val spread = 0.25
        fun rim(a: Double) = Pt(c.x + w / 2.0 * cos(a), c.y + h / 2.0 * sin(a))
        val segs = arcSegments(c, w / 2.0, h / 2.0, toward + spread, 2 * PI - 2 * spread) + VectorSeg.Line(tip)
        val contour = VectorContour(rim(toward + spread), segs, closed = true)
        val body = Rect(0.0, 0.0, w, h)
        val k = 1.0 / kotlin.math.sqrt(2.0)
        val text = Rect(c.x - w / 2.0 * k, c.y - h / 2.0 * k, w * k, h * k)
        return ShapeGeometry(listOf(ShapePath(listOf(contour))), emptyList(), ellipseConnections(body) + ConnectionPoint(tip, emptyList()), text)
    }
}

/** A cloud with three bubbles shrinking away toward its tip. */
internal class ThoughtCallout : Canonical {
    override val defaultAdj = TAIL_ADJ
    override val knobs = listOf(tailKnob())
    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val body = Rect(0.0, 0.0, w, h)
        val cloud = ShapeKind.CLOUD.build(body, ShapeParams()).paths.single().contours.single()
        val tip = Pt(w * adj(0), h * adj(1))
        val c = body.center
        // The bubbles start just past the cloud's rim on the way to the tip and shrink as they go.
        val a = atan2((tip.y - c.y) / (h / 2.0).coerceAtLeast(1e-9), (tip.x - c.x) / (w / 2.0).coerceAtLeast(1e-9))
        val edge = Pt(c.x + w / 2.0 * 1.08 * cos(a), c.y + h / 2.0 * 1.08 * sin(a))
        val r0 = min(w, h) * 0.09
        val bubbles = listOf(0.15 to 1.0, 0.55 to 0.66, 0.92 to 0.4).map { (f, s) ->
            val at = edge + (tip - edge) * f
            ShapePath.ellipseContour(at.x, at.y, r0 * s, r0 * s)
        }
        return ShapeGeometry(listOf(ShapePath(listOf(cloud) + bubbles)), emptyList(), ellipseConnections(body), ShapeKind.inset(body, 0.2))
    }
}

/** A plain box with a leader line from its nearest side out to the tip. */
internal class LineCallout : Canonical {
    override val defaultAdj = TAIL_ADJ
    override val knobs = listOf(tailKnob())
    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val body = Rect(0.0, 0.0, w, h)
        val tip = Pt(w * adj(0), h * adj(1))
        val anchor = listOf(Pt(w / 2.0, 0.0), Pt(w, h / 2.0), Pt(w / 2.0, h), Pt(0.0, h / 2.0)).minByOrNull { it.distanceTo(tip) }!!
        val box = ShapePath.lines(listOf(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, h), Pt(0.0, h)), true)
        val leader = ShapePath.lines(listOf(anchor, tip), false)
        return ShapeGeometry(
            listOf(ShapePath(listOf(box)), ShapePath(listOf(leader), PathRole.STROKE_ONLY)),
            emptyList(),
            boxConnections(body) + ConnectionPoint(tip, emptyList()),
            ShapeKind.inset(body),
        )
    }
}

/** An explosion: a jagged star of uneven spikes. */
internal class Burst : Canonical {
    override val defaultAdj = DoubleArray(0)
    override val iconAspect get() = 1.2
    override fun build(w: Double, h: Double, adj: (Int) -> Double): ShapeGeometry {
        val radii = doubleArrayOf(1.0, 0.55, 0.9, 0.5, 0.97, 0.58, 0.82, 0.5, 1.0, 0.6, 0.86, 0.48, 0.95, 0.56, 0.88, 0.52)
        val unit = radii.indices.map { i ->
            val a = -PI / 2.0 + 2.0 * PI * i / radii.size + (if (i % 2 == 0) 0.05 else -0.04)
            Pt(radii[i] * cos(a), radii[i] * sin(a))
        }
        val bb = Rect.bounding(unit)
        val pts = unit.map { Pt((it.x - bb.left) / bb.w * w, (it.y - bb.top) / bb.h * h) }
        val body = Rect(0.0, 0.0, w, h)
        return ShapeGeometry(listOf(ShapePath.polygon(pts)), pts, ellipseConnections(body), ShapeKind.inset(body, 0.28))
    }
}

internal object ArrowKinds {

    private val single = SingleArrow()
    private val double = DoubleArrow()
    private val curved = CurvedArrow()

    val RIGHT: ShapeKind = OrientedKind("arrow_right", Orient.RIGHT, single)
    val LEFT: ShapeKind = OrientedKind("arrow_left", Orient.LEFT, single)
    val UP: ShapeKind = OrientedKind("arrow_up", Orient.UP, single)
    val DOWN: ShapeKind = OrientedKind("arrow_down", Orient.DOWN, single)
    val LEFT_RIGHT: ShapeKind = OrientedKind("arrow_left_right", Orient.RIGHT, double)
    val UP_DOWN: ShapeKind = OrientedKind("arrow_up_down", Orient.UP, double)
    val QUAD: ShapeKind = BoxedKind("arrow_quad", QuadArrow(), mirror = { it })
    val BENT: ShapeKind = BoxedKind("arrow_bent", BentArrow(up = false))
    val U_TURN: ShapeKind = BoxedKind("arrow_u_turn", UTurnArrow())
    val CHEVRON: ShapeKind = OrientedKind("arrow_chevron", Orient.RIGHT, PointedBand(notchedTail = true))
    val PENTAGON: ShapeKind = OrientedKind("arrow_pentagon", Orient.RIGHT, PointedBand(notchedTail = false))
    val NOTCHED: ShapeKind = OrientedKind("arrow_notched", Orient.RIGHT, SingleArrow(notched = true))
    val CURVED_RIGHT: ShapeKind = OrientedKind("arrow_curved_right", Orient.RIGHT, curved)
    val CURVED_LEFT: ShapeKind = OrientedKind("arrow_curved_left", Orient.LEFT, curved)
    val CIRCULAR: ShapeKind = BoxedKind("arrow_circular", CircularArrow())
    val STRIPED: ShapeKind = OrientedKind("arrow_striped", Orient.RIGHT, StripedArrow())
    val BENT_UP: ShapeKind = BoxedKind("arrow_bent_up", BentArrow(up = true))
    val LEFT_UP: ShapeKind = BoxedKind("arrow_left_up", LeftUpArrow())

    val RECT_CALLOUT: ShapeKind = BoxedKind("callout_rect", BoxCallout(rounded = false), ::mirrorTail, staysInBox = false)
    val ROUND_CALLOUT: ShapeKind = BoxedKind("callout_round", BoxCallout(rounded = true), ::mirrorTail, staysInBox = false)
    val OVAL_CALLOUT: ShapeKind = BoxedKind("callout_oval", OvalCallout(), ::mirrorTail, staysInBox = false)
    val THOUGHT: ShapeKind = BoxedKind("callout_thought", ThoughtCallout(), ::mirrorTail, staysInBox = false)
    val LINE_CALLOUT: ShapeKind = BoxedKind("callout_line", LineCallout(), ::mirrorTail, staysInBox = false)
    val BURST: ShapeKind = BoxedKind("burst", Burst())

    val all: List<ShapeKind> = listOf(
        RIGHT, LEFT, UP, DOWN, LEFT_RIGHT, UP_DOWN, QUAD, BENT, U_TURN, CHEVRON, PENTAGON, NOTCHED,
        CURVED_RIGHT, CURVED_LEFT, CIRCULAR, STRIPED, BENT_UP, LEFT_UP,
        RECT_CALLOUT, ROUND_CALLOUT, OVAL_CALLOUT, THOUGHT, LINE_CALLOUT, BURST,
    )
}
