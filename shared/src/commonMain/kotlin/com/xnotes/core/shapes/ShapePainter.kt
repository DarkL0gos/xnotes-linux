package com.xnotes.core.shapes

import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer

/**
 * How a shape's style paints its [ShapePath]s, role by role. The paged renderers draw through
 * [fill] and [stroke]; the canvas mesher asks [fillColor] and [strokePen] the same questions, so
 * both canvases tint, dash and skip exactly the same paths.
 */
object ShapePainter {

    /**
     * The colour a [role] path fills with, given the shape's [fill] (null when it has none) and its
     * [outline] colour; null when the path is left unfilled.
     */
    fun fillColor(role: PathRole, fill: Rgba?, outline: Rgba): Rgba? = when (role) {
        PathRole.OUTLINE, PathRole.FILL_ONLY -> fill
        PathRole.SHADE_DARK -> fill?.let { mix(it, 0, SHADE_DARK) }
        PathRole.SHADE_LIGHT -> fill?.let { mix(it, 255, SHADE_LIGHT) }
        PathRole.HEAD_FILLED -> outline
        else -> null
    }

    /** The pen a [role] path strokes with, given the shape's own [pen]; null when it is not stroked. */
    fun strokePen(role: PathRole, pen: Pen): Pen? = when (role) {
        PathRole.OUTLINE, PathRole.STROKE_ONLY -> pen
        PathRole.HIDDEN ->
            if (pen.dashed) pen
            else pen.copy(dashed = true, dashOn = HIDDEN_ON + pen.width * 2.0, dashGap = HIDDEN_GAP + pen.width * 1.5)
        PathRole.HEAD, PathRole.HEAD_FILLED -> pen.copy(dashed = false)
        else -> null
    }

    fun fill(r: Renderer, paths: List<ShapePath>, fill: Rgba?, outline: Rgba) {
        for (p in paths) fillColor(p.role, fill, outline)?.let { r.fillPath(p.contours, it, p.fillRule) }
    }

    fun stroke(r: Renderer, paths: List<ShapePath>, pen: Pen) {
        for (p in paths) strokePen(p.role, pen)?.let { r.strokePath(p.contours, it) }
    }

    private fun mix(c: Rgba, toward: Int, t: Double): Rgba = Rgba(
        (c.r + (toward - c.r) * t).toInt().coerceIn(0, 255),
        (c.g + (toward - c.g) * t).toInt().coerceIn(0, 255),
        (c.b + (toward - c.b) * t).toInt().coerceIn(0, 255),
        c.a,
    )

    private const val SHADE_DARK = 0.25
    private const val SHADE_LIGHT = 0.35
    private const val HIDDEN_ON = 4.0
    private const val HIDDEN_GAP = 3.0
}
