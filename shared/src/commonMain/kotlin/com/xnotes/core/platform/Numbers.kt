package com.xnotes.core.platform

import kotlin.math.roundToLong

/**
 * `java.lang.Math.round(double)` on every target: nearest integer with ties towards positive
 * infinity, clamped to the Long range, and 0 for NaN (where [roundToLong] would throw).
 */
fun roundHalfUp(x: Double): Long = if (x.isNaN()) 0L else x.roundToLong()

/**
 * [v] as `java.lang.Double.toString` writes it (JDK 19+): the shortest decimal of at least two
 * digits that reads back as [v], plain for 1e-3 ≤ |v| < 1e7 and `d.dddE±n` otherwise. Manifests
 * carry doubles in this form, so every platform must produce the same text.
 */
expect fun javaDoubleToString(v: Double): String

/**
 * [v] with exactly [places] fraction digits and a '.' whatever the locale, as
 * `String.format(Locale.ROOT, "%.Nf", v)` writes it (ties rounding away from zero).
 */
fun fixedDecimal(v: Double, places: Int): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    var scale = 1L
    repeat(places) { scale *= 10 }
    val n = roundHalfUp(kotlin.math.abs(v) * scale)
    val digits = n.toString().padStart(places + 1, '0')
    val whole = digits.dropLast(places)
    val frac = digits.takeLast(places)
    return (if (v < 0) "-" else "") + whole + (if (places > 0) ".$frac" else "")
}
