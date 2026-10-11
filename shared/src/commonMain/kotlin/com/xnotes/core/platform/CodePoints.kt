package com.xnotes.core.platform

/** Code point helpers for common code (the JVM's `Character` statics and `appendCodePoint`). */

/** Append [codePoint] as one or two UTF-16 units (a surrogate pair above the BMP); invalid ones are skipped. */
fun StringBuilder.appendCodePoint(codePoint: Int): StringBuilder {
    when {
        codePoint < 0 || codePoint > 0x10FFFF -> Unit
        codePoint < 0x10000 -> append(codePoint.toChar())
        else -> {
            val v = codePoint - 0x10000
            append((0xD800 + (v shr 10)).toChar())
            append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }
    return this
}

/** [codePoint] as a string, or null when it is no valid code point (`Character.toChars`). */
fun codePointString(codePoint: Int): String? =
    if (codePoint < 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) null else StringBuilder(2).appendCodePoint(codePoint).toString()

/** `Character.isWhitespace(int)` or `isSpaceChar`: blank of any kind. Above the BMP nothing is. */
fun isBlankCodePoint(codePoint: Int): Boolean =
    codePoint in 0..0xFFFF && codePoint.toChar().let { it.isWhitespace() || it.category == CharCategory.SPACE_SEPARATOR }

/** `Character.isLetterOrDigit(int)`: above the BMP, every assigned-looking code point counts as a letter. */
fun isLetterOrDigitCodePoint(codePoint: Int): Boolean =
    if (codePoint in 0..0xFFFF) codePoint.toChar().isLetterOrDigit() else codePoint in 0x10000..0x10FFFF
