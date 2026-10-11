package com.xnotes.core.platform

/** [epochMillis] as ISO-8601 in UTC, exactly as `java.time.Instant.ofEpochMilli(ms).toString()` writes it. */
expect fun formatInstant(epochMillis: Long): String

/** An ISO-8601 instant (as `java.time.Instant.parse` accepts it) in epoch milliseconds, or null. */
expect fun parseInstant(text: String): Long?

/** Epoch milliseconds of a civil time at [offsetMinutes] from UTC, or null when the fields are out of range. */
@OptIn(kotlin.time.ExperimentalTime::class)
fun epochMillisOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, offsetMinutes: Int = 0): Long? {
    fun two(v: Int) = v.toString().padStart(2, '0')
    val sign = if (offsetMinutes < 0) '-' else '+'
    val off = kotlin.math.abs(offsetMinutes)
    val iso = "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}T${two(hour)}:${two(minute)}:${two(second)}" +
        "$sign${two(off / 60)}:${two(off % 60)}"
    return runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
}

/** [epochMillis] in UTC as year, month, day, hour, minute and second. */
@OptIn(kotlin.time.ExperimentalTime::class)
fun utcFieldsOf(epochMillis: Long): IntArray {
    // Instant's ISO form is "YYYY-MM-DDTHH:MM:SS[.fff]Z".
    val s = kotlin.time.Instant.fromEpochMilliseconds(epochMillis).toString()
    val m = Regex("""^(-?\d+)-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""").find(s) ?: return IntArray(6)
    return IntArray(6) { m.groupValues[it + 1].toInt() }
}
