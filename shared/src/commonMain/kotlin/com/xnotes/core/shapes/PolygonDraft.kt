package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt

/**
 * A polygon being placed corner by corner, the Free polygon chip's way of drawing: every tap puts
 * down a corner, a tap back on the first corner closes it, and a double tap ends it open.
 */
class PolygonDraft {

    enum class Outcome { CONTINUE, CLOSED, OPEN }

    val points: MutableList<Pt> = ArrayList()
    private var lastTapAt = Long.MIN_VALUE / 2

    /** A tap at [p] at [time] (ms): [reach] is how near a corner counts as on it. */
    fun tap(p: Pt, time: Long, reach: Double, doubleTapMs: Long): Outcome {
        if (points.size >= 3 && p.distanceTo(points.first()) <= reach) return Outcome.CLOSED
        val again = points.isNotEmpty() && time - lastTapAt <= doubleTapMs && p.distanceTo(points.last()) <= reach
        // A double tap ends the line; with a single corner down there is no line to end yet.
        if (again) return if (points.size >= 2) Outcome.OPEN else Outcome.CONTINUE
        points += p
        lastTapAt = time
        return Outcome.CONTINUE
    }

    /** What to show: the corners so far, and a band out to [pointer] while a press is down. */
    fun preview(pointer: Pt?): List<Pt> = if (pointer == null) points.toList() else points + pointer
}
