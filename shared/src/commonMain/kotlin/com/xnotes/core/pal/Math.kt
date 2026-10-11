package com.xnotes.core.pal

/** How big a formula sets, as far as that is known yet. */
sealed interface MathSize {
    /** Still being set somewhere else; asking again later tells. */
    data object Pending : MathSize

    /** The LaTeX will not set. */
    data object Refused : MathSize
}

/**
 * The room one typeset equation needs. [ascent] and [descent] straddle the text
 * baseline so an inline formula sits on the same baseline as the words it is set
 * among, rather than on its own bottom edge.
 */
data class MathBox(val width: Double, val ascent: Double, val descent: Double) : MathSize {
    val height: Double get() = ascent + descent

    /** The vertical room it asks of its line, as the line-height metrics. */
    fun metrics(): LineMetrics = LineMetrics(ascent, descent)
}

/**
 * Typesets LaTeX for the flow (spec: the TEXT tool's math runs). Laying maths
 * out needs font resolution the core has no access to, so the core only ever
 * asks how big an equation is and hands the same string back to be drawn.
 *
 * Whatever size it gives MUST agree with what is later drawn, for the same reason
 * [TextMeasurer] must: the flow breaks lines and paginates against these numbers,
 * so a size that differs from the paint leaves text wrapped around a box that is
 * not there. A typesetter that sets off the caller's thread answers [size] with
 * [MathSize.Pending] instead, and the flow holds a guess open until it lands.
 */
interface MathTypesetter {
    /**
     * [latex] set at [sizePt] in content px, or null when it will not parse.
     * [display] is the form an equation on its own line takes: sums and integrals
     * grow and put their limits above and below rather than beside.
     */
    fun measure(latex: String, sizePt: Double, display: Boolean = false): MathBox?

    /**
     * Like [measure], but free to answer [MathSize.Pending] rather than wait: then the
     * formula is being set elsewhere, and asking again says when it is done. One that
     * answers at once just measures.
     */
    fun size(latex: String, sizePt: Double, display: Boolean = false): MathSize =
        measure(latex, sizePt, display) ?: MathSize.Refused

    /**
     * A layout pass is starting. Formulas it asks after again rank ahead of those it
     * no longer shows, so a note just closed stops holding up the one now open.
     */
    fun beginLayout() {}

    /**
     * Whether this can set anything yet. A typesetter still waiting for the host
     * to hand it a renderer refuses everything, and that must not be mistaken for
     * every formula in the document being broken.
     */
    fun ready(): Boolean = true
}
