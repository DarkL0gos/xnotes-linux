package com.xnotes.core.shapes

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FillRule
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg

/** What a [ShapePath] is to its shape, which decides how the shape's style paints it. */
enum class PathRole {
    /** Filled with the shape's fill, then stroked with its outline. */
    OUTLINE,

    /** Stroked only: an open outline, or a detail line inside the shape. */
    STROKE_ONLY,

    /** Filled only, with the shape's fill. */
    FILL_ONLY,

    /** Stroked dashed whatever the shape's own dash setting: a solid's hidden edges. */
    HIDDEN,

    /** Filled with a darker tint of the shape's fill: a solid's shaded face. */
    SHADE_DARK,

    /** Filled with a lighter tint of the shape's fill: a solid's lit face. */
    SHADE_LIGHT,

    /** Stroked solid even on a dashed shape: an arrowhead, whose barbs a dash gap would maim. */
    HEAD,

    /** A head with a solid body: filled in the outline's colour, whatever the shape's fill, and stroked solid. */
    HEAD_FILLED;

    val fills: Boolean get() = this == OUTLINE || this == FILL_ONLY || this == SHADE_DARK || this == SHADE_LIGHT || this == HEAD_FILLED
    val strokes: Boolean get() = this == OUTLINE || this == STROKE_ONLY || this == HIDDEN || this == HEAD || this == HEAD_FILLED
}

/**
 * One piece of a shape's geometry in page space: contours of lines and cubics, and the [role] that
 * says how the shape's fill and outline paint it. Every renderer draws shapes from these, so the
 * note, the canvas and the PDF cannot disagree about a shape's outline.
 */
class ShapePath(
    val contours: List<VectorContour>,
    val role: PathRole = PathRole.OUTLINE,
    val fillRule: FillRule = FillRule.NONZERO,
) {
    /** This path with every point, control points included, moved by [f]; exact for any affine. */
    fun mapped(f: (Pt) -> Pt): ShapePath = ShapePath(
        contours.map { c ->
            VectorContour(
                f(c.start),
                c.segments.map { seg ->
                    when (seg) {
                        is VectorSeg.Line -> VectorSeg.Line(f(seg.end))
                        is VectorSeg.Cubic -> VectorSeg.Cubic(f(seg.c1), f(seg.c2), f(seg.end))
                    }
                },
                c.closed,
            )
        },
        role,
        fillRule,
    )

    companion object {
        /** A closed run of straight edges through [points]. */
        fun polygon(points: List<Pt>, role: PathRole = PathRole.OUTLINE): ShapePath =
            ShapePath(listOf(lines(points, closed = true)), role)

        /** An open run of straight edges through [points]. */
        fun polyline(points: List<Pt>, role: PathRole = PathRole.STROKE_ONLY): ShapePath =
            ShapePath(listOf(lines(points, closed = false)), role)

        /** [r]'s outline, clockwise from its top left corner. */
        fun rect(r: Rect, role: PathRole = PathRole.OUTLINE): ShapePath = polygon(
            listOf(Pt(r.left, r.top), Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom)),
            role,
        )

        /** An axis-aligned ellipse as four cubics, clockwise from its rightmost point. */
        fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, role: PathRole = PathRole.OUTLINE): ShapePath =
            ShapePath(listOf(ellipseContour(cx, cy, rx, ry)), role)

        fun lines(points: List<Pt>, closed: Boolean): VectorContour {
            if (points.isEmpty()) return VectorContour(Pt.ZERO, emptyList(), closed)
            return VectorContour(points[0], points.drop(1).map { VectorSeg.Line(it) }, closed)
        }

        fun ellipseContour(cx: Double, cy: Double, rx: Double, ry: Double): VectorContour {
            val kx = rx * KAPPA
            val ky = ry * KAPPA
            return VectorContour(
                Pt(cx + rx, cy),
                listOf(
                    VectorSeg.Cubic(Pt(cx + rx, cy + ky), Pt(cx + kx, cy + ry), Pt(cx, cy + ry)),
                    VectorSeg.Cubic(Pt(cx - kx, cy + ry), Pt(cx - rx, cy + ky), Pt(cx - rx, cy)),
                    VectorSeg.Cubic(Pt(cx - rx, cy - ky), Pt(cx - kx, cy - ry), Pt(cx, cy - ry)),
                    VectorSeg.Cubic(Pt(cx + kx, cy - ry), Pt(cx + rx, cy - ky), Pt(cx + rx, cy)),
                ),
                closed = true,
            )
        }

        /** The cubic control distance that best fits a quarter circle of radius 1. */
        const val KAPPA = 0.5522847498307936
    }
}
