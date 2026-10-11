package com.xnotes.core.pdf

import com.xnotes.core.platform.appendCodePoint

/** Word boundaries in a page's text: runs of letters and digits, with the marks and apostrophes inside words. */
object TextWords {

    /**
     * The word around character [index] as character indices, or the character alone when it is
     * no part of a word (a space, a line break, punctuation). A word broken over two lines by a
     * hyphen is one word.
     */
    fun wordAt(text: PageText, index: Int): IntRange {
        if (text.isLineBreak(index)) return index..index
        var from = index
        while (from > 0 && !text.isLineBreak(from - 1)) from--
        var to = index + 1
        while (to < text.length && !text.isLineBreak(to)) to++
        // Where each character's UTF-16 units start, then where the last ends. A line-end hyphen
        // and a glyph without text add none, so the word around them stays whole.
        val starts = IntArray(to - from + 1)
        val sb = StringBuilder(to - from)
        for (k in from until to) {
            starts[k - from] = sb.length
            val cp = text.codepoint(k)
            if (cp != 0 && !text.isHyphen(k)) sb.appendCodePoint(cp)
        }
        starts[to - from] = sb.length
        val at = starts[index - from]
        if (at == sb.length) return index..index
        val str = sb.toString()
        if (!isWordChar(str[at])) return index..index
        var start = at
        while (start > 0 && isWordChar(str[start - 1])) start--
        var end = at + 1
        while (end < str.length && isWordChar(str[end])) end++
        val first = starts.indexOfFirst { it >= start }
        var last = starts.indexOfFirst { it >= end }
        // Glyphs without units right after the word belong to it, as those before it already do.
        while (last < to - from && starts[last + 1] == starts[last]) last++
        return from + first until from + last
    }

    /** Part of a word: a letter, a digit, a combining mark, an apostrophe, or half of a pair above the BMP. */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '\u2019' || c.isSurrogate() ||
        c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.COMBINING_SPACING_MARK
}
