package com.xnotes.core.model

import com.xnotes.core.platform.identitySet
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.vector.PathFlattener

/**
 * What closed shapes take along when they move, turn or resize: everything entirely inside their
 * outline, so a frame, a swimlane or a nested box keeps its contents. The contents take the very
 * transform the shapes do, as if selected with them: writing inside a box scales with it, line
 * widths included, while the shapes' own labels keep their type size. A text box only moves its
 * place through a turn and stays upright. Holds each item's gesture-start geometry, so every
 * frame is one move of the original.
 */
class Carry private constructor(private val entries: List<Entry>) {

    private class Entry(val item: CanvasItem, val holder: ShapeItem, val start: GeometrySnapshot)

    val items: List<CanvasItem> = entries.map { it.item }

    val isEmpty: Boolean get() = entries.isEmpty()

    /** The shape that carries [item], or null when it is not carried. */
    fun holderOf(item: CanvasItem): ShapeItem? = entries.firstOrNull { it.item === item }?.holder

    /** The carried items' geometry as the gesture found it, in [items] order. */
    val startSnapshots: List<GeometrySnapshot> get() = entries.map { it.start }

    /** Put every carried item back where the gesture began. */
    fun restore() {
        for (e in entries) e.item.restoreGeometry(e.start)
    }

    /**
     * Follow holders that this frame restored and then moved by [t], a [resize] or a turn: each item
     * from where the gesture began, by the same transform. Only the items in [only], when given.
     */
    fun follow(t: Affine, resize: Boolean, only: Set<CanvasItem>? = null) {
        for (e in entries) {
            val item = e.item
            if (only != null && item !in only) continue
            item.restoreGeometry(e.start)
            if (item is TextItem && !resize) {
                val c = item.bounds().center
                val to = t.apply(c)
                item.translate(to.x - c.x, to.y - c.y)
            } else {
                item.applyTransform(t)
            }
        }
    }

    /** Move every carried item by ([dx], [dy]) from where the gesture began. */
    fun translate(dx: Double, dy: Double) {
        for (e in entries) {
            e.item.restoreGeometry(e.start)
            e.item.translate(dx, dy)
        }
    }

    companion object {
        val NONE = Carry(emptyList())

        /**
         * The [candidates] entirely inside one of [holders]' outlines, each going with the
         * innermost holder that has it. Ink counts by all its points, a shape by its outline and
         * label, anything else by its bounds' corners. Locked items and [skip] (the selection
         * itself) stay where they are.
         */
        fun of(holders: List<CanvasItem>, candidates: List<CanvasItem>, skip: Collection<CanvasItem>): Carry {
            val shapes = holders.filterIsInstance<ShapeItem>().filter { it.shape.isClosed && it.shape != ShapeKind.GLYPHS }
            if (shapes.isEmpty()) return NONE
            val regions = shapes.map { Region(it) }.sortedBy { it.area }
            val skipped = identitySet<CanvasItem>().apply { addAll(skip) }
            val out = ArrayList<Entry>()
            for (item in candidates) {
                if (item.locked || item in skipped) continue
                val region = regions.firstOrNull { it.holds(item) } ?: continue
                out += Entry(item, region.shape, item.snapshotGeometry())
            }
            return if (out.isEmpty()) NONE else Carry(out)
        }

        /** How far a curve may stray when flattened to test it, in content px. */
        private const val OUTLINE_FLATTEN = 1.0

        /** The points an item has to have inside a holder to count as in it. */
        private fun pointsOf(item: CanvasItem): List<Pt> = when (item) {
            is Stroke -> item.samples.map { it.pos }
            is ShapeItem -> item.paths().flatMap { p -> p.contours.flatMap { PathFlattener.flatten(it, OUTLINE_FLATTEN) } } + item.labelCorners()
            else -> item.bounds().let { r -> listOf(r.topLeft, Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom)) }
        }

        private class Region(val shape: ShapeItem) {
            private val inside = shape.interior()
            private val bounds: Rect = shape.bounds()
            val area: Double = shape.box.w * shape.box.h

            fun holds(item: CanvasItem): Boolean {
                if (item === shape) return false
                val b = item.bounds()
                if (b.left < bounds.left || b.top < bounds.top || b.right > bounds.right || b.bottom > bounds.bottom) return false
                val pts = pointsOf(item)
                return pts.isNotEmpty() && pts.all(inside)
            }
        }
    }
}
