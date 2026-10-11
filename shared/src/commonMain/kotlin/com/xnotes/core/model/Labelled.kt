package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect

/**
 * An item that carries a typed label: a closed shape sets it in its text area and turns it with
 * itself; a line, an arc or a connector sets it upright on the line, where a drag slides it along.
 * The editors, search and renderers reach a label through this, whatever carries it.
 */
interface Labelled : CanvasItem {

    var label: ShapeLabel?

    /** True while the label is being typed: the editor shows it, so the item draws without. Never saved. */
    var labelHidden: Boolean

    /** Whether the item takes a label at all. */
    val takesLabel: Boolean

    /** Where the label's lines set, in the label's own frame ([labelToPage] places it); null without words. */
    fun labelRect(): Rect?

    /** [labelRect] even while the label is empty: where the editor types it. */
    fun labelFrame(): Rect?

    /** A point of the label's frame, on the page. */
    fun labelToPage(p: Pt): Pt

    /** How far the label is turned on the page, in radians clockwise. */
    val labelTurn: Double

    /** True when page point [p] lands on the label's lines. */
    fun labelContains(p: Pt): Boolean

    /** True when the label rides a line, so a drag slides it along. */
    val labelSlides: Boolean

    /** Slide the label along its line to where it comes nearest page point [p]. */
    fun slideLabel(p: Pt)

    /** The colour a new label takes, and the fill it must read against (null when there is none). */
    val labelInk: Rgba
    val labelBackdrop: Rgba?
}
