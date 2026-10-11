package com.xnotes.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * An oriented bounding box: an axis-aligned box of half-extents [halfW] x [halfH] centred at
 * [center] and turned by [angle] radians. Backs the tilting selection box so a rotated selection
 * shows a box that turns with its content instead of an upright one. Items stay baked in content
 * space; this rides alongside as the selection's tracked frame.
 */
data class Obb(
    val center: Pt,
    val halfW: Double,
    val halfH: Double,
    val angle: Double,
) {
    /** Map a point from the box's local frame (origin = centre, axis-aligned) into world space. */
    fun localToWorld(p: Pt): Pt {
        val cs = cos(angle)
        val sn = sin(angle)
        return Pt(center.x + p.x * cs - p.y * sn, center.y + p.x * sn + p.y * cs)
    }

    /** Map a world-space point into the box's local frame. */
    fun worldToLocal(p: Pt): Pt {
        val cs = cos(angle)
        val sn = sin(angle)
        val dx = p.x - center.x
        val dy = p.y - center.y
        return Pt(dx * cs + dy * sn, -dx * sn + dy * cs)
    }

    /** Corners in world space, clockwise from top-left: TL, TR, BR, BL. */
    fun corners(): List<Pt> = listOf(
        localToWorld(Pt(-halfW, -halfH)),
        localToWorld(Pt(halfW, -halfH)),
        localToWorld(Pt(halfW, halfH)),
        localToWorld(Pt(-halfW, halfH)),
    )

    fun contains(p: Pt): Boolean {
        val l = worldToLocal(p)
        return abs(l.x) <= halfW && abs(l.y) <= halfH
    }

    fun translate(dx: Double, dy: Double): Obb = copy(center = Pt(center.x + dx, center.y + dy))

    /** The same box pushed [d] further out on every side. */
    fun grow(d: Double): Obb = if (d == 0.0) this else copy(halfW = halfW + d, halfH = halfH + d)

    companion object {
        fun fromAabb(r: Rect): Obb = Obb(r.center, r.w / 2.0, r.h / 2.0, 0.0)
    }
}
