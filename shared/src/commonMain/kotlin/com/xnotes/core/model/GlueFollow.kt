package com.xnotes.core.model

import com.xnotes.core.platform.identitySet
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect

/**
 * Keeps connectors joined to what a gesture moves. Built as the gesture starts; every frame puts
 * each connector back where the gesture began and places it again. A connector whose glued ends are
 * all on what moves rides along whole, as if it were selected. Any other glued to what moves keeps
 * its route and only its moved ends go along, the route mended where an end now leaves the wrong way
 * ([com.xnotes.core.shapes.Elbow.fitStart]), with no thought for what it crosses. A moving connector
 * glued to something that stays keeps that end where it is and bends. Connectors glued to connectors
 * follow in turn, so T-junctions hold.
 */
class GlueFollow private constructor(
    private val moving: Set<CanvasItem>,
    private val entries: List<Entry>,
    private val placed: Set<CanvasItem>,
    private val alive: (CanvasItem) -> Boolean,
    private val rigid: Boolean,
) {

    /** RIGID: a moving connector glued only to what moves, which the gesture itself carries. */
    private enum class Role { RIDES, FOLLOWS, BENDS, RIGID }

    private class Entry(val c: ConnectorItem, val start: GeometrySnapshot, val role: Role)

    /** Every connector this gesture places, the moving ones that bend included, in the order it places them. */
    val items: List<CanvasItem> = entries.filter { it.role != Role.RIGID }.map { it.c }

    /** Their geometry as the gesture found it, in [items] order. */
    val startSnapshots: List<GeometrySnapshot> get() = entries.filter { it.role != Role.RIGID }.map { it.start }

    /** True when nothing is glued to what moves. */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** True when [item] is one of the connectors this places, which a renderer must then draw from the model. */
    fun places(item: CanvasItem): Boolean = item in placed

    /** Put every connector back where the gesture began. */
    fun restore() {
        for (e in entries) if (e.role != Role.RIGID) e.c.restoreGeometry(e.start)
    }

    /**
     * At the end of a rigid gesture, glue each moved end to the point of its target that is where
     * it now sits. A shape turned a quarter frames itself upright again, so the point that went
     * there is no longer the one its number named.
     */
    fun settle() {
        if (!rigid) return
        for (e in entries) {
            val c = e.c
            c.start = reindexed(c.start)
            c.end = reindexed(c.end)
        }
    }

    private fun reindexed(end: ConnectorEnd): ConnectorEnd {
        val t = end.target ?: return end
        if (!alive(t)) return end
        val points = Glue.points(t)
        if (points.getOrNull(end.point)?.let { it.at.distanceTo(end.at) <= SAME_SPOT } == true) return end
        val best = points.withIndex().minByOrNull { it.value.at.distanceTo(end.at) } ?: return end
        return if (best.value.at.distanceTo(end.at) <= SAME_SPOT) ConnectorEnd(end.at, t, best.index) else end
    }

    /**
     * Place every connector for a frame in which what moves has been moved by [moved] since the
     * gesture began. [shown] says how a moving item is drawn displaced past its model geometry, for
     * a drag the renderer is offsetting; null means the model has it.
     */
    fun follow(moved: Affine, shown: (CanvasItem) -> Affine? = { null }) {
        for (e in entries) {
            if (e.role == Role.RIGID) continue
            val c = e.c
            c.restoreGeometry(e.start)
            if (e.role != Role.FOLLOWS) c.applyTransform(moved)
            if (e.role != Role.RIDES) reattach(c, e.role, moved, shown)
        }
    }

    /** Put [c]'s ends back on their targets as they now are, and mend its route where an end moved. */
    private fun reattach(c: ConnectorItem, role: Role, moved: Affine, shown: (CanvasItem) -> Affine?) {
        val s = place(c.start, role, moved, shown)
        val e = place(c.end, role, moved, shown)
        if (s == null && e == null) return
        if (s != null) c.start = s.end
        if (e != null) c.end = e.end
        if (!c.routing.orthogonal) return
        var route = c.elbow
        if (s != null) route = route.fitStart(c.start.at, c.end.at, s.ways, c.clearance, s.box)
        if (e != null) route = route.fitEnd(c.start.at, c.end.at, e.ways, c.clearance, e.box)
        c.elbow = route
    }

    private class Placed(val end: ConnectorEnd, val ways: List<Pt>, val box: Rect?)

    /**
     * Where glued [end] goes this frame, or null when it stays as the route has it: a following
     * connector moves the ends on what moved, a bending one puts back the ends on what stayed.
     */
    private fun place(end: ConnectorEnd, role: Role, moved: Affine, shown: (CanvasItem) -> Affine?): Placed? {
        val t = end.target ?: return null
        if (!alive(t)) return null
        val moves = t in moving || t in placed
        if (role == Role.FOLLOWS && !moves) return null
        if (role == Role.BENDS && t in moving && t !in placed) return null
        val carried = t in moving && t !in placed
        val m = if (carried) shown(t) else null
        val points = Glue.points(t)
        fun drawnAt(i: Int) = points[i].at.let { m?.apply(it) ?: it }
        var index = end.point
        // Moved whole, the point is wherever its old place went; a target that framed itself anew numbers it otherwise.
        if (rigid && carried && points.isNotEmpty()) {
            val want = moved.apply(end.at)
            if (index !in points.indices || drawnAt(index).distanceTo(want) > SAME_SPOT) {
                index = points.indices.minByOrNull { drawnAt(it).distanceTo(want) } ?: index
            }
        } else if (index !in points.indices) {
            // The point is gone (a connector lost a bend): the nearest one takes the end.
            index = points.indices.minByOrNull { drawnAt(it).distanceTo(end.at) } ?: return null
        }
        val cp = points.getOrNull(index) ?: return null
        val glued = ConnectorEnd(end.at, t, index)
        if (m == null) return Placed(glued.at(cp.at), cp.normals, boxOf(t))
        val ways = cp.normals.map { n -> Pt(m.a * n.x + m.c * n.y, m.b * n.x + m.d * n.y).normalized() }
        val box = boxOf(t)?.let { b -> Rect.bounding(listOf(b.topLeft, Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom)).map(m::apply)) }
        return Placed(glued.at(m.apply(cp.at)), ways, box)
    }

    private fun boxOf(t: CanvasItem): Rect? = if (t is ConnectorItem) null else t.bounds()

    companion object {
        val NONE = GlueFollow(emptySet(), emptyList(), emptySet(), { false }, rigid = false)

        /** Two points closer than this, in content px, are one. */
        private const val SAME_SPOT = 1e-4

        /**
         * What the connectors among [candidates] (a page's items, or a canvas's) do while [moving]
         * moves. [alive] says whether a target is still there; a gone one counts as no target at
         * all. [rigid] says the gesture moves everything by one transform, so a connector glued
         * only to what moves can ride along; a point edit is not rigid. Unless [pin], a moving
         * connector lets go rather than bend (a lone connector dragged by its line).
         */
        fun of(
            moving: Collection<CanvasItem>,
            candidates: List<CanvasItem>,
            alive: (CanvasItem) -> Boolean,
            rigid: Boolean = true,
            pin: Boolean = true,
        ): GlueFollow {
            val m = identitySet(moving)
            val placed = identitySet(emptyList())
            val entries = ArrayList<Entry>()
            fun targets(c: ConnectorItem) = listOfNotNull(c.start.target, c.end.target).filter(alive)
            for (item in moving) {
                if (item !is ConnectorItem) continue
                val ts = targets(item)
                if (pin && ts.any { it !in m }) {
                    entries += Entry(item, item.snapshotGeometry(), Role.BENDS)
                    placed += item
                } else if (rigid && ts.isNotEmpty()) {
                    entries += Entry(item, item.snapshotGeometry(), Role.RIGID)
                }
            }
            // Rings outward from what moves, so a connector is placed after whatever it is glued to.
            val connectors = candidates.filterIsInstance<ConnectorItem>().filter { it !in m }
            var grew = true
            while (grew) {
                grew = false
                for (c in connectors) {
                    if (c in placed) continue
                    val ts = targets(c)
                    if (ts.none { it in m || it in placed }) continue
                    val rides = rigid && c.start.target?.takeIf(alive) != null && c.end.target?.takeIf(alive) != null &&
                        c.start.target in m && c.end.target in m
                    entries += Entry(c, c.snapshotGeometry(), if (rides) Role.RIDES else Role.FOLLOWS)
                    placed += c
                    grew = true
                }
            }
            if (entries.isEmpty()) return NONE
            return GlueFollow(m, entries, placed, alive, rigid)
        }

        private fun identitySet(of: Collection<CanvasItem>): MutableSet<CanvasItem> =
            identitySet<CanvasItem>().apply { addAll(of) }
    }
}
