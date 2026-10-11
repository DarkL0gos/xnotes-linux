package com.xnotes.core.model

import com.xnotes.core.geometry.Pt

/**
 * One drag of a lone connector's own handles: an end, which lets go and glues again wherever it is
 * dropped, routed as it is when drawn; a middle segment of a square route, slid across; or a
 * straight connector's waypoint, moved, or added halfway along a stretch. Every frame starts from
 * the connector as the drag found it, so nothing compounds.
 */
class ConnectorEdit private constructor(val connector: ConnectorItem, val grab: Grab, val index: Int) {

    enum class Grab { START, END, SEGMENT, WAYPOINT, MIDPOINT }

    /** The connector as the drag found it. */
    val before: GeometrySnapshot = connector.snapshotGeometry()

    private val draft = when (grab) {
        Grab.START -> ConnectorDraft(connector, movesStart = true)
        Grab.END -> ConnectorDraft(connector)
        else -> null
    }

    /** The point a dragged end would glue to now, for the chrome to light. */
    val hot: Glue.Hit? get() = draft?.hot

    /** Drag the handle to [to]; an end glues near a point of [items] within [reach] and routes [margin] clear. */
    fun drag(to: Pt, items: List<CanvasItem>, reach: Double, margin: Double) {
        connector.restoreGeometry(before)
        when (grab) {
            Grab.START, Grab.END -> draft?.drag(to, items, reach, margin)
            Grab.SEGMENT -> connector.slideSegment(index, to)
            Grab.WAYPOINT -> connector.moveWaypoint(index, to)
            Grab.MIDPOINT -> connector.insertWaypoint(index, to)
        }
    }

    /** Put the connector back as the drag found it. */
    fun restore() = connector.restoreGeometry(before)

    companion object {
        /**
         * The handle of [c] within [reach] of [p], or null: its ends first (the nearer), then the
         * middle segments, the waypoints, and the stretch middles that add one.
         */
        fun grab(c: ConnectorItem, p: Pt, reach: Double): ConnectorEdit? {
            val toStart = c.start.at.distanceTo(p)
            val toEnd = c.end.at.distanceTo(p)
            if (minOf(toStart, toEnd) <= reach) return ConnectorEdit(c, if (toStart <= toEnd) Grab.START else Grab.END, 0)
            nearestIndex(c.segmentHandles(), p, reach)?.let { return ConnectorEdit(c, Grab.SEGMENT, it + 1) }
            nearestIndex(c.waypoints, p, reach)?.let { return ConnectorEdit(c, Grab.WAYPOINT, it) }
            nearestIndex(c.midpointDots(), p, reach)?.let { return ConnectorEdit(c, Grab.MIDPOINT, it) }
            return null
        }

        private fun nearestIndex(points: List<Pt>, p: Pt, reach: Double): Int? =
            points.withIndex().minByOrNull { it.value.distanceTo(p) }?.takeIf { it.value.distanceTo(p) <= reach }?.index
    }
}
