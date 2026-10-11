package com.xnotes.core.shapes

import com.xnotes.core.geometry.toRadians
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlin.math.min
import kotlin.math.sqrt

// The Flowchart page's own symbols. Process, alternate process, decision, data, terminator,
// connector and extract are chips of Basic kinds; these are the rest.

/** A straight-edged symbol whose corners come from [corners]; asymmetric ones refuse a mirror. */
internal class FlowPolygonKind(
    id: String,
    private val symmetric: Boolean,
    override val iconAspect: Double,
    private val text: (Rect) -> Rect,
    private val corners: (Rect) -> List<Pt>,
) : ShapeKind(id) {
    override val isClosed get() = true
    override fun mirroredAdj(adj: DoubleArray): DoubleArray? = if (symmetric) adj else null
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val c = corners(box)
        return ShapeGeometry(listOf(ShapePath.polygon(c)), c, polygonConnections(c), text(box))
    }
}

/** Preparation: a hexagon pointed at both ends, the knob pulling the points in. */
internal class PreparationKind(id: String) : ShapeKind(id) {
    override val isClosed get() = true
    override val iconAspect get() = 1.5
    override val defaultAdj = doubleArrayOf(0.2)
    override val knobs = listOf(
        KnobDef(
            position = { box, adj, _ -> Pt(box.left + box.w * adjOf(adj, 0), box.top) },
            drag = { to, box, _, _ -> doubleArrayOf(if (box.w > 0.0) ((to.x - box.left) / box.w).coerceIn(0.0, 0.5) else 0.2) },
        ),
    )
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val i = box.w * adjOf(p.adj, 0).coerceIn(0.0, 0.5)
        val c = listOf(
            Pt(box.left + i, box.top), Pt(box.right - i, box.top), Pt(box.right, box.centerY),
            Pt(box.right - i, box.bottom), Pt(box.left + i, box.bottom), Pt(box.left, box.centerY),
        )
        val text = Rect(box.left + i / 2.0, box.top, (box.w - i).coerceAtLeast(0.0), box.h)
        return ShapeGeometry(listOf(ShapePath.polygon(c)), c, polygonConnections(c), ShapeKind.inset(text))
    }
}

/** An outline with stroked detail inside it: predefined process, internal storage, sort, summing junction, or. */
internal class FlowDetailKind(
    id: String,
    private val symmetric: Boolean,
    override val iconAspect: Double,
    private val text: (Rect) -> Rect,
    private val connections: (Rect) -> List<ConnectionPoint>,
    private val outline: (Rect) -> VectorContour,
    private val detail: (Rect) -> List<VectorContour>,
) : ShapeKind(id) {
    override val isClosed get() = true
    override fun mirroredAdj(adj: DoubleArray): DoubleArray? = if (symmetric) adj else null
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry = ShapeGeometry(
        listOf(ShapePath(listOf(outline(box))), ShapePath(detail(box), PathRole.STROKE_ONLY)),
        emptyList(),
        connections(box),
        text(box),
    )
}

/** A curved symbol drawn by [contours] (the first is the outline, any more are detail strokes). */
internal class FlowCurvedKind(
    id: String,
    private val symmetric: Boolean,
    override val iconAspect: Double,
    private val text: (Rect) -> Rect,
    private val contours: (Rect) -> List<VectorContour>,
    private val detail: (Rect) -> List<VectorContour> = { emptyList() },
    private val stacked: Boolean = false,
) : ShapeKind(id) {
    override val isClosed get() = true
    override fun mirroredAdj(adj: DoubleArray): DoubleArray? = if (symmetric) adj else null
    override fun build(box: Rect, p: ShapeParams): ShapeGeometry {
        val main = contours(box)
        val paths = ArrayList<ShapePath>(3)
        // A stack draws its back pages as bare edges, so they never show through the front page.
        paths += if (stacked) ShapePath(main.dropLast(1), PathRole.STROKE_ONLY) else ShapePath(main.take(1))
        if (stacked) paths += ShapePath(main.takeLast(1))
        detail(box).takeIf { it.isNotEmpty() }?.let { paths += ShapePath(it, PathRole.STROKE_ONLY) }
        return ShapeGeometry(paths, emptyList(), cardinalConnections(box), text(box))
    }
}

/** Glue points on the middles of the box's four sides, facing straight out. */
internal fun cardinalConnections(box: Rect): List<ConnectionPoint> = listOf(
    ConnectionPoint(Pt(box.centerX, box.top), listOf(Pt(0.0, -1.0))),
    ConnectionPoint(Pt(box.right, box.centerY), listOf(Pt(1.0, 0.0))),
    ConnectionPoint(Pt(box.centerX, box.bottom), listOf(Pt(0.0, 1.0))),
    ConnectionPoint(Pt(box.left, box.centerY), listOf(Pt(-1.0, 0.0))),
)

/** [box]'s four corners and four side middles, with their outward normals. */
internal fun boxConnections(box: Rect): List<ConnectionPoint> =
    ShapeKind.polygonConnections(listOf(box.topLeft, Pt(box.right, box.top), Pt(box.right, box.bottom), Pt(box.left, box.bottom)))

private fun rectContour(b: Rect) = ShapePath.lines(listOf(b.topLeft, Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom)), closed = true)

private fun segment(a: Pt, b: Pt) = ShapePath.lines(listOf(a, b), closed = false)

/** The document symbol: square top, a wave along the bottom that dips on the left and rises on the right. */
internal fun documentContour(b: Rect): VectorContour {
    val a = b.h * 0.08
    val base = b.bottom - a
    // Handles this far out make the wave's lowest point land exactly on the box's bottom edge.
    val reach = 2.0 * a * sqrt(3.0)
    return VectorContour(
        b.topLeft,
        listOf(
            VectorSeg.Line(Pt(b.right, b.top)),
            VectorSeg.Line(Pt(b.right, base)),
            VectorSeg.Cubic(Pt(b.right - b.w / 3.0, base - reach), Pt(b.left + b.w / 3.0, base + reach), Pt(b.left, base)),
        ),
        closed = true,
    )
}

private fun arcTo(c: Pt, rx: Double, ry: Double, startDeg: Double, sweepDeg: Double): List<VectorSeg> =
    arcSegments(c, rx, ry, toRadians(startDeg), toRadians(sweepDeg))

internal object FlowchartKinds {

    val PREPARATION: ShapeKind = PreparationKind("flow_preparation")

    val PREDEFINED: ShapeKind = FlowDetailKind(
        "flow_predefined", symmetric = true, iconAspect = 1.5,
        text = { b -> ShapeKind.inset(Rect(b.left + 0.1 * b.w, b.top, 0.8 * b.w, b.h)) },
        connections = ::boxConnections,
        outline = ::rectContour,
        detail = { b ->
            listOf(
                segment(Pt(b.left + 0.1 * b.w, b.top), Pt(b.left + 0.1 * b.w, b.bottom)),
                segment(Pt(b.right - 0.1 * b.w, b.top), Pt(b.right - 0.1 * b.w, b.bottom)),
            )
        },
    )

    val INTERNAL_STORAGE: ShapeKind = FlowDetailKind(
        "flow_internal_storage", symmetric = false, iconAspect = 1.25,
        text = { b -> ShapeKind.inset(Rect(b.left + 0.15 * b.w, b.top + 0.15 * b.h, 0.85 * b.w, 0.85 * b.h)) },
        connections = ::boxConnections,
        outline = ::rectContour,
        detail = { b ->
            listOf(
                segment(Pt(b.left + 0.15 * b.w, b.top), Pt(b.left + 0.15 * b.w, b.bottom)),
                segment(Pt(b.left, b.top + 0.15 * b.h), Pt(b.right, b.top + 0.15 * b.h)),
            )
        },
    )

    val DOCUMENT: ShapeKind = FlowCurvedKind(
        "flow_document", symmetric = false, iconAspect = 1.4,
        text = { b -> ShapeKind.inset(Rect(b.left, b.top, b.w, 0.82 * b.h)) },
        contours = { b -> listOf(documentContour(b)) },
    )

    val MULTIDOCUMENT: ShapeKind = FlowCurvedKind(
        "flow_multidocument", symmetric = false, iconAspect = 1.3,
        text = { b -> ShapeKind.inset(Rect(b.left, b.top + 0.2 * b.h, 0.8 * b.w, 0.65 * b.h)) },
        contours = { b ->
            val d = min(b.w, b.h) * 0.1
            val back = Rect(b.left + 2 * d, b.top, b.w - 2 * d, b.h - 2 * d)
            val middle = Rect(b.left + d, b.top + d, b.w - 2 * d, b.h - 2 * d)
            val front = Rect(b.left, b.top + 2 * d, b.w - 2 * d, b.h - 2 * d)
            val wave = 0.08 * front.h
            // Each page behind shows its top and its right edge, tucked under the page in front.
            listOf(
                ShapePath.lines(
                    listOf(Pt(back.left, middle.top), back.topLeft, Pt(back.right, back.top), Pt(back.right, back.bottom - wave), Pt(middle.right, back.bottom - wave)),
                    false,
                ),
                ShapePath.lines(
                    listOf(Pt(middle.left, front.top), middle.topLeft, Pt(middle.right, middle.top), Pt(middle.right, middle.bottom - wave), Pt(front.right, middle.bottom - wave)),
                    false,
                ),
                documentContour(front),
            )
        },
        stacked = true,
    )

    val MANUAL_INPUT: ShapeKind = FlowPolygonKind(
        "flow_manual_input", symmetric = false, iconAspect = 1.5,
        text = { b -> ShapeKind.inset(Rect(b.left, b.top + 0.25 * b.h, b.w, 0.75 * b.h)) },
    ) { b -> listOf(Pt(b.left, b.top + 0.25 * b.h), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom)) }

    val MANUAL_OPERATION: ShapeKind = FlowPolygonKind(
        "flow_manual_operation", symmetric = true, iconAspect = 1.5,
        text = { b -> ShapeKind.inset(Rect(b.left + 0.1 * b.w, b.top, 0.8 * b.w, b.h)) },
    ) { b -> listOf(b.topLeft, Pt(b.right, b.top), Pt(b.right - 0.2 * b.w, b.bottom), Pt(b.left + 0.2 * b.w, b.bottom)) }

    val OFF_PAGE: ShapeKind = FlowPolygonKind(
        "flow_off_page", symmetric = true, iconAspect = 0.9,
        text = { b -> ShapeKind.inset(Rect(b.left, b.top, b.w, 0.75 * b.h)) },
    ) { b -> listOf(b.topLeft, Pt(b.right, b.top), Pt(b.right, b.top + 0.75 * b.h), Pt(b.centerX, b.bottom), Pt(b.left, b.top + 0.75 * b.h)) }

    val MERGE: ShapeKind = FlowPolygonKind(
        "flow_merge", symmetric = true, iconAspect = 1.15,
        text = { b -> Rect(b.left + b.w / 4.0, b.top, b.w / 2.0, b.h / 2.0) },
    ) { b -> listOf(b.topLeft, Pt(b.right, b.top), Pt(b.centerX, b.bottom)) }

    val DATABASE: ShapeKind = FlowCurvedKind(
        "flow_database", symmetric = true, iconAspect = 0.8,
        text = { b -> ShapeKind.inset(Rect(b.left, b.top + cap(b), b.w, b.h - 1.5 * cap(b))) },
        contours = { b ->
            val c = cap(b)
            val top = Pt(b.centerX, b.top + c / 2.0)
            val bottom = Pt(b.centerX, b.bottom - c / 2.0)
            listOf(
                VectorContour(
                    Pt(b.left, top.y),
                    arcTo(top, b.w / 2.0, c / 2.0, 180.0, 180.0) + VectorSeg.Line(Pt(b.right, bottom.y)) +
                        arcTo(bottom, b.w / 2.0, c / 2.0, 0.0, 180.0),
                    closed = true,
                ),
            )
        },
        detail = { b ->
            val c = cap(b)
            val top = Pt(b.centerX, b.top + c / 2.0)
            listOf(VectorContour(Pt(b.right, top.y), arcTo(top, b.w / 2.0, c / 2.0, 0.0, 180.0), false))
        },
    )

    val DIRECT_ACCESS: ShapeKind = FlowCurvedKind(
        "flow_direct_access", symmetric = false, iconAspect = 1.6,
        text = { b -> ShapeKind.inset(Rect(b.left + side(b) / 2.0, b.top, b.w - 1.5 * side(b), b.h)) },
        contours = { b ->
            val c = side(b)
            val left = Pt(b.left + c / 2.0, b.centerY)
            val right = Pt(b.right - c / 2.0, b.centerY)
            listOf(
                VectorContour(
                    Pt(left.x, b.top),
                    listOf(VectorSeg.Line(Pt(right.x, b.top))) + arcTo(right, c / 2.0, b.h / 2.0, -90.0, 180.0) +
                        VectorSeg.Line(Pt(left.x, b.bottom)) + arcTo(left, c / 2.0, b.h / 2.0, 90.0, 180.0),
                    closed = true,
                ),
            )
        },
        detail = { b ->
            val c = side(b)
            val right = Pt(b.right - c / 2.0, b.centerY)
            listOf(VectorContour(Pt(right.x, b.top), arcTo(right, c / 2.0, b.h / 2.0, -90.0, -180.0), false))
        },
    )

    val DISPLAY: ShapeKind = FlowCurvedKind(
        "flow_display", symmetric = false, iconAspect = 1.5,
        text = { b -> ShapeKind.inset(Rect(b.left + 0.15 * b.w, b.top, 0.7 * b.w, b.h)) },
        contours = { b ->
            val r = 0.15 * b.w
            val c = Pt(b.right - r, b.centerY)
            listOf(
                VectorContour(
                    Pt(b.left, b.centerY),
                    listOf(VectorSeg.Line(Pt(b.left + 0.15 * b.w, b.top)), VectorSeg.Line(Pt(c.x, b.top))) +
                        arcTo(c, r, b.h / 2.0, -90.0, 180.0) + VectorSeg.Line(Pt(b.left + 0.15 * b.w, b.bottom)),
                    closed = true,
                ),
            )
        },
    )

    val DELAY: ShapeKind = FlowCurvedKind(
        "flow_delay", symmetric = false, iconAspect = 1.3,
        text = { b -> ShapeKind.inset(Rect(b.left, b.top, b.w - delayRadius(b) / 2.0, b.h)) },
        contours = { b ->
            val r = delayRadius(b)
            val c = Pt(b.right - r, b.centerY)
            listOf(
                VectorContour(
                    b.topLeft,
                    listOf(VectorSeg.Line(Pt(c.x, b.top))) + arcTo(c, r, b.h / 2.0, -90.0, 180.0) + VectorSeg.Line(Pt(b.left, b.bottom)),
                    closed = true,
                ),
            )
        },
    )

    val STORED_DATA: ShapeKind = FlowCurvedKind(
        "flow_stored_data", symmetric = false, iconAspect = 1.5,
        text = { b -> ShapeKind.inset(Rect(b.left + 0.12 * b.w, b.top, 0.76 * b.w, b.h)) },
        contours = { b ->
            val k = 0.12 * b.w
            listOf(
                VectorContour(
                    Pt(b.left + k, b.top),
                    listOf(VectorSeg.Line(Pt(b.right, b.top))) + arcTo(Pt(b.right, b.centerY), k, b.h / 2.0, -90.0, -180.0) +
                        VectorSeg.Line(Pt(b.left + k, b.bottom)) + arcTo(Pt(b.left + k, b.centerY), k, b.h / 2.0, 90.0, 180.0),
                    closed = true,
                ),
            )
        },
    )

    val SORT: ShapeKind = FlowDetailKind(
        "flow_sort", symmetric = true, iconAspect = 1.3,
        text = { b -> Rect(b.left + b.w / 4.0, b.top + b.h / 4.0, b.w / 2.0, b.h / 2.0) },
        connections = { b ->
            ShapeKind.polygonConnections(listOf(Pt(b.centerX, b.top), Pt(b.right, b.centerY), Pt(b.centerX, b.bottom), Pt(b.left, b.centerY)))
        },
        outline = { b -> ShapePath.lines(listOf(Pt(b.centerX, b.top), Pt(b.right, b.centerY), Pt(b.centerX, b.bottom), Pt(b.left, b.centerY)), true) },
        detail = { b -> listOf(segment(Pt(b.left, b.centerY), Pt(b.right, b.centerY))) },
    )

    val SUMMING: ShapeKind = FlowDetailKind(
        "flow_summing", symmetric = true, iconAspect = 1.0,
        text = { b -> ShapeKind.inset(b, 0.3) },
        connections = ::ellipseConnections,
        outline = { b -> ShapePath.ellipseContour(b.centerX, b.centerY, b.w / 2.0, b.h / 2.0) },
        detail = { b ->
            fun on(deg: Double) = Pt(b.centerX + b.w / 2.0 * kotlin.math.cos(toRadians(deg)), b.centerY + b.h / 2.0 * kotlin.math.sin(toRadians(deg)))
            listOf(segment(on(45.0), on(225.0)), segment(on(135.0), on(315.0)))
        },
    )

    val OR: ShapeKind = FlowDetailKind(
        "flow_or", symmetric = true, iconAspect = 1.0,
        text = { b -> ShapeKind.inset(b, 0.3) },
        connections = ::ellipseConnections,
        outline = { b -> ShapePath.ellipseContour(b.centerX, b.centerY, b.w / 2.0, b.h / 2.0) },
        detail = { b -> listOf(segment(Pt(b.centerX, b.top), Pt(b.centerX, b.bottom)), segment(Pt(b.left, b.centerY), Pt(b.right, b.centerY))) },
    )

    val all: List<ShapeKind> = listOf(
        PREPARATION, PREDEFINED, INTERNAL_STORAGE, DOCUMENT, MULTIDOCUMENT, MANUAL_INPUT, MANUAL_OPERATION,
        OFF_PAGE, MERGE, DATABASE, DIRECT_ACCESS, DISPLAY, DELAY, STORED_DATA, SORT, SUMMING, OR,
    )

    /** A database's cap: never more than a quarter of its height or a fifth of its width's worth. */
    private fun cap(b: Rect) = min(0.25 * b.h, 0.4 * b.w)

    /** A drum's end: never more than a quarter of its width or most of its height. */
    private fun side(b: Rect) = min(0.25 * b.w, 0.8 * b.h)

    private fun delayRadius(b: Rect) = min(b.h / 2.0, b.w)
}
