package com.xnotes.core.model

import com.xnotes.core.geometry.Pt

/**
 * A connector being drawn, or one of its ends being dragged again. One end stays put; the other
 * ([movesStart] says which) follows the pen and glues to whatever connection point it comes near.
 * Each step routes the line afresh around the glue targets in its way: this is when a connector is
 * smart. Otherwise it only ever follows what it joins. A straight connector keeps its waypoints.
 */
class ConnectorDraft(val connector: ConnectorItem, private val movesStart: Boolean = false) {

    /** The point the far end is glued to now, for the chrome to light up; null while it is loose. */
    var hot: Glue.Hit? = null
        private set

    /**
     * Follow the pen to [at]: glue to the nearest connection point of [items] within [reach] (never
     * the one the other end is on), then route around [items] keeping [margin] clear.
     */
    fun drag(at: Pt, items: List<CanvasItem>, reach: Double, margin: Double) {
        val c = connector
        val other = if (movesStart) c.end else c.start
        val hit = Glue.nearest(items, at, reach, skip = c)?.takeUnless { it.target === other.target && it.index == other.point }
        hot = hit
        val moved = hit?.let { ConnectorEnd(it.point.at, it.target, it.index) } ?: ConnectorEnd(at)
        if (movesStart) c.start = moved else c.end = moved
        if (c.routing.orthogonal) Glue.reroute(c, items, margin)
    }

    /** True when what was drawn is worth keeping: it joins two points, or runs at least [minLength]. */
    fun worthKeeping(minLength: Double): Boolean {
        val c = connector
        val joins = c.start.glued && c.end.glued && !(c.start.target === c.end.target && c.start.point == c.end.point)
        return joins || c.start.at.distanceTo(c.end.at) >= minLength
    }

    companion object {
        /** Start a draft at [at]: glued to the nearest connection point of [items] within [reach], else loose. */
        fun startAt(at: Pt, items: List<CanvasItem>, reach: Double, make: (ConnectorEnd) -> ConnectorItem?): ConnectorDraft? {
            val hit = Glue.nearest(items, at, reach)
            val from = hit?.let { ConnectorEnd(it.point.at, it.target, it.index) } ?: ConnectorEnd(at)
            return make(from)?.let(::ConnectorDraft)
        }
    }
}
