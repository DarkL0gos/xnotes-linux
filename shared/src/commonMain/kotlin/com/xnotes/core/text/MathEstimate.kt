package com.xnotes.core.text

import com.xnotes.core.pal.MathBox
import kotlin.math.max

/**
 * How big a formula probably sets, read off its LaTeX alone: the room a layout holds open while
 * the real box is worked out elsewhere. It only has to be near, since the line reflows when the
 * real one lands, but the nearer it is the less moves then. The figures are ems, fitted to what
 * the renderer measures, and every formula reads as display style because that is how it sets.
 */
object MathEstimate {

    /** [latex] at [em] content px to the em. Never throws, whatever the source. */
    fun box(latex: String, em: Double): MathBox {
        val b = Reader(latex).all()
        return MathBox(
            width = (b.w + PAD_X) * em,
            ascent = (max(b.up, MIN_UP) + PAD_Y) * em,
            descent = (max(b.down, 0.0) + PAD_Y) * em,
        )
    }

    /** A box in ems: [w] wide, reaching [up] above the baseline and [down] below it. */
    private class Box(val w: Double, val up: Double, val down: Double, val limits: Boolean = false) {
        fun scaled(k: Double) = Box(w * k, up * k, down * k)

        fun then(b: Box) = Box(w + b.w, max(up, b.up), max(down, b.down))
    }

    private class Reader(private val s: String) {
        private var i = 0

        /** The whole source, read on past anything unbalanced that would end a [sequence] early. */
        fun all(): Box {
            var b = sequence(0)
            while (i < s.length) {
                i += if (s.startsWith("\\right", i)) "\\right".length else 1
                b = b.then(sequence(0))
            }
            return b
        }

        /** Atoms up to the end, a closing brace, or where an environment's cell or row ends. */
        fun sequence(depth: Int, inCell: Boolean = false): Box {
            var w = 0.0
            var up = 0.0
            var down = 0.0
            while (i < s.length) {
                val c = s[i]
                if (c == '}') break
                if (s.startsWith("\\right", i)) break
                if (inCell && (c == '&' || s.startsWith("\\\\", i) || s.startsWith("\\end", i))) break
                val start = i
                val b = atom(depth)?.let { scripts(it, depth) }
                if (i == start) i++
                if (b == null) continue
                w += b.w
                up = max(up, b.up)
                down = max(down, b.down)
            }
            return Box(w, up, down)
        }

        private fun atom(depth: Int): Box? {
            val c = s[i]
            return when {
                c.isWhitespace() || c == '&' -> {
                    i++
                    null
                }
                c == '{' -> group(depth)
                // A script with nothing in front of it attaches to an empty base.
                c == '^' || c == '_' -> Box(0.0, 0.0, 0.0)
                c == '\\' -> command(depth)
                else -> {
                    i++
                    glyph(c)
                }
            }
        }

        /** A braced group, or the single atom standing where one was expected. */
        private fun group(depth: Int): Box {
            skipSpaces()
            if (i >= s.length) return Box(0.0, 0.0, 0.0)
            if (s[i] != '{') return atom(depth) ?: Box(0.0, 0.0, 0.0)
            i++
            val b = sequence(depth)
            if (i < s.length && s[i] == '}') i++
            return b
        }

        /** Raw text in braces, as `\text` takes it. */
        private fun textGroup(): Box {
            skipSpaces()
            if (i >= s.length || s[i] != '{') return Box(0.0, 0.0, 0.0)
            var w = 0.0
            var nest = 0
            i++
            while (i < s.length) {
                val c = s[i++]
                if (c == '{') nest++
                if (c == '}' && nest-- == 0) break
                w += if (c == ' ') SPACE_TEXT else TEXT_GLYPH
            }
            return Box(w, LINE_UP, LINE_DOWN)
        }

        private fun scripts(base: Box, depth: Int): Box {
            var sup: Box? = null
            var sub: Box? = null
            while (true) {
                skipSpaces()
                if (i >= s.length) break
                when (s[i]) {
                    '^' -> {
                        i++
                        sup = group(depth + 1).scaled(SCRIPT)
                    }
                    '_' -> {
                        i++
                        sub = group(depth + 1).scaled(SCRIPT)
                    }
                    else -> break
                }
            }
            if (sup == null && sub == null) return base
            if (base.limits) {
                return Box(
                    w = max(base.w, max(sup?.w ?: 0.0, sub?.w ?: 0.0)),
                    up = base.up + (sup?.let { it.up + it.down + LIMIT_GAP } ?: 0.0),
                    down = base.down + (sub?.let { it.up + it.down + LIMIT_GAP } ?: 0.0),
                )
            }
            return Box(
                w = base.w + max(sup?.w ?: 0.0, sub?.w ?: 0.0) + SCRIPT_GAP,
                up = max(base.up, sup?.let { SUP_RISE + it.up } ?: 0.0),
                down = max(base.down, sub?.let { SUB_DROP + it.down + if (sup != null) BOTH_DROP else 0.0 } ?: 0.0),
            )
        }

        private fun command(depth: Int): Box? {
            i++
            if (i >= s.length) return null
            if (!s[i].isLetter()) {
                val c = s[i++]
                return when (c) {
                    ',' -> Box(0.17, 0.0, 0.0)
                    ':', '>' -> Box(0.22, 0.0, 0.0)
                    ';' -> Box(0.28, 0.0, 0.0)
                    '!' -> Box(-0.17, 0.0, 0.0)
                    ' ' -> Box(SPACE_TEXT, 0.0, 0.0)
                    '\\' -> null
                    else -> glyph(c)
                }
            }
            val from = i
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(from, i)
            return when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> fraction(depth, rule = true)
                "binom", "dbinom", "tbinom" -> fraction(depth, rule = false).let { Box(it.w + 2 * PAREN, it.up, it.down) }
                "sqrt" -> {
                    skipSpaces()
                    if (i < s.length && s[i] == '[') skipPast(']')
                    val arg = group(depth)
                    Box(arg.w + RADICAL, max(arg.up, X_HEIGHT) + RADICAL_RISE, max(arg.down, GLYPH_DOWN))
                }
                "left" -> delimited(depth)
                "begin" -> environment(depth)
                "end" -> {
                    group(depth)
                    null
                }
                "text", "textrm", "textit", "textbf", "textsf", "texttt", "mbox" -> textGroup()
                "operatorname" -> group(depth).let { Box(it.w + THIN, max(it.up, ASCENDER), it.down) }
                "color" -> {
                    group(depth)
                    null
                }
                "textcolor" -> {
                    group(depth)
                    group(depth)
                }
                "quad" -> Box(1.0, 0.0, 0.0)
                "qquad" -> Box(2.0, 0.0, 0.0)
                "enspace" -> Box(0.5, 0.0, 0.0)
                "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "big", "Big", "bigg", "Bigg",
                "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr" -> null
                // These faces are measured by their whole line box rather than their ink, so they stand taller.
                in LINE_FACES -> group(depth).let { Box(it.w, max(it.up, LINE_UP), max(it.down, LINE_DOWN)) }
                "mathbb" -> group(depth).let { Box(it.w, max(it.up, 0.95), max(it.down, 0.48)) }
                in STYLED -> group(depth)
                in ACCENTS -> group(depth).let { Box(it.w, max(it.up, X_HEIGHT) + ACCENT, it.down) }
                in BIG_OPS -> Box(1.44, 1.05, 0.55, limits = true)
                in INTEGRALS -> Box(0.95, 1.45, 0.9)
                in LIMIT_NAMES -> Box(name.length * NAME_GLYPH + THIN, ASCENDER, GLYPH_DOWN, limits = true)
                in NAMES -> Box(name.length * NAME_GLYPH + THIN, LINE_UP, LINE_DOWN)
                in RELATIONS -> Box(RELATION, OP_UP, OP_DOWN)
                "cdot", "circ", "bullet" -> Box(0.72, OP_UP, 0.0)
                "ldots", "cdots", "dots", "vdots", "ddots" -> Box(1.2, X_HEIGHT, 0.0)
                "infty" -> Box(1.0, X_HEIGHT, GLYPH_DOWN)
                else -> Box(GREEK, ASCENDER, GLYPH_DOWN)
            }
        }

        private fun fraction(depth: Int, rule: Boolean): Box {
            // At the top a fraction's parts set full size, as display style has them; deeper they shrink.
            val k = if (depth == 0) 1.0 else SCRIPT
            val num = group(depth + 1).scaled(k)
            val den = group(depth + 1).scaled(k)
            val gap = if (rule) FRAC_GAP else FRAC_GAP * 0.6
            return Box(max(num.w, den.w) + FRAC_PAD, AXIS + gap + num.down + num.up, den.up + den.down)
        }

        /** `\left` … `\right`: the content, with fences that grow to cover it about the axis. */
        private fun delimited(depth: Int): Box {
            skipDelimiter()
            val inner = sequence(depth)
            if (s.startsWith("\\right", i)) {
                i += "\\right".length
                skipDelimiter()
            }
            val half = max(max(inner.up - AXIS, inner.down + AXIS), FENCE_HALF)
            val fence = PAREN + FENCE_GROWTH * (half - FENCE_HALF)
            return Box(inner.w + 2 * fence, AXIS + half, half - AXIS)
        }

        /** A matrix, cases or alignment: rows of cells, stacked about the axis. */
        private fun environment(depth: Int): Box {
            val name = braced()
            if (name == "array" || name == "alignedat") braced()
            val cols = ArrayList<Double>()
            var rows = 0
            while (i < s.length) {
                var col = 0
                while (i < s.length) {
                    val cell = sequence(depth + 1, inCell = true)
                    while (cols.size <= col) cols.add(0.0)
                    cols[col] = max(cols[col], cell.w)
                    if (i < s.length && s[i] == '&') {
                        i++
                        col++
                        continue
                    }
                    break
                }
                rows++
                if (s.startsWith("\\\\", i)) {
                    i += 2
                    continue
                }
                break
            }
            if (s.startsWith("\\end", i)) {
                i += "\\end".length
                braced()
            }
            val h = (if (name.endsWith("cases")) CASES_ROW else ROW) * rows + ROWS_PAD
            val w = cols.sum() + COL_GAP * (cols.size - 1).coerceAtLeast(0) + ENV_PAD + if (name in FENCED) 2 * PAREN else 0.0
            return Box(w, AXIS + h / 2, h / 2 - AXIS)
        }

        private fun braced(): String {
            skipSpaces()
            if (i >= s.length || s[i] != '{') return ""
            val end = s.indexOf('}', i)
            if (end < 0) {
                val rest = s.substring(i + 1)
                i = s.length
                return rest
            }
            val name = s.substring(i + 1, end)
            i = end + 1
            return name
        }

        private fun skipDelimiter() {
            skipSpaces()
            if (i >= s.length) return
            if (s[i] != '\\') {
                i++
                return
            }
            i++
            if (i < s.length && !s[i].isLetter()) {
                i++
                return
            }
            while (i < s.length && s[i].isLetter()) i++
        }

        private fun skipPast(c: Char) {
            val end = s.indexOf(c, i)
            i = if (end < 0) s.length else end + 1
        }

        private fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun glyph(c: Char): Box = when {
            c == '+' || c == '-' || c == '=' || c == '<' || c == '>' -> Box(RELATION, OP_UP, OP_DOWN)
            c == ',' || c == ';' -> Box(0.45, 0.1, 0.19)
            c == ':' -> Box(0.5, X_HEIGHT, 0.0)
            c == '.' -> Box(0.28, 0.1, 0.0)
            c == '\'' -> Box(0.3, 0.75, 0.0)
            c in "()[]{}|/" -> Box(PAREN, 0.75, 0.25)
            c.isUpperCase() -> Box(CAPITAL, ASCENDER, GLYPH_DOWN)
            c.isDigit() || c in TALL_LOWER -> Box(GLYPH, ASCENDER, GLYPH_DOWN)
            c in DESCENDING -> Box(GLYPH, X_HEIGHT, DESCENDER)
            else -> Box(GLYPH, X_HEIGHT, GLYPH_DOWN)
        }
    }

    // The renderer's own padding around every formula.
    private const val PAD_X = 0.3
    private const val PAD_Y = 0.1

    private const val MIN_UP = 0.45
    private const val GLYPH = 0.55
    private const val CAPITAL = 0.72
    private const val GREEK = 0.62
    private const val TEXT_GLYPH = 0.48
    private const val NAME_GLYPH = 0.5
    private const val SPACE_TEXT = 0.33
    private const val THIN = 0.17
    private const val X_HEIGHT = 0.45
    private const val ASCENDER = 0.69
    private const val GLYPH_DOWN = 0.04
    private const val DESCENDER = 0.2
    private const val LINE_UP = 0.92
    private const val LINE_DOWN = 0.28
    private const val RELATION = 1.25
    private const val OP_UP = 0.58
    private const val OP_DOWN = 0.08
    private const val PAREN = 0.39

    private const val SCRIPT = 0.7
    private const val SCRIPT_GAP = 0.05
    private const val SUP_RISE = 0.41
    private const val SUB_DROP = 0.15
    private const val BOTH_DROP = 0.1
    private const val LIMIT_GAP = 0.15

    private const val AXIS = 0.25
    private const val FRAC_GAP = 0.42
    private const val FRAC_PAD = 0.24
    private const val RADICAL = 0.95
    private const val RADICAL_RISE = 0.6
    private const val ACCENT = 0.25
    private const val FENCE_HALF = 0.5
    private const val FENCE_GROWTH = 0.25

    private const val ROW = 0.72
    private const val CASES_ROW = 1.02
    private const val ROWS_PAD = 0.4
    private const val COL_GAP = 1.1
    private const val ENV_PAD = 0.5

    private const val TALL_LOWER = "bdfhklt"
    private const val DESCENDING = "gjpqy"

    private val LINE_FACES = setOf("mathbf", "boldsymbol", "bm", "mathsf", "mathtt")
    private val STYLED = setOf("mathrm", "mathit", "mathcal", "mathfrak", "mathscr", "mathop", "mathbin", "mathrel", "mathord")
    private val ACCENTS = setOf(
        "hat", "widehat", "bar", "overline", "underline", "vec", "tilde", "widetilde", "dot", "ddot",
        "acute", "grave", "breve", "check", "overrightarrow", "overleftarrow", "mathring",
    )
    private val BIG_OPS = setOf(
        "sum", "prod", "coprod", "bigcup", "bigcap", "bigsqcup", "bigvee", "bigwedge",
        "bigoplus", "bigotimes", "bigodot", "biguplus",
    )
    private val FENCED = setOf("pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix", "cases", "rcases", "dcases")
    private val INTEGRALS = setOf("int", "iint", "iiint", "oint", "oiint")
    private val LIMIT_NAMES = setOf("lim", "limsup", "liminf", "max", "min", "sup", "inf", "det", "gcd", "Pr", "argmax", "argmin")
    private val NAMES = setOf(
        "sin", "cos", "tan", "sec", "csc", "cot", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "log", "ln", "lg", "exp", "arg", "deg", "dim", "hom", "ker", "mod", "bmod",
    )
    private val RELATIONS = setOf(
        "times", "div", "pm", "mp", "to", "rightarrow", "leftarrow", "Rightarrow", "Leftarrow",
        "leftrightarrow", "Leftrightarrow", "longrightarrow", "implies", "iff", "mapsto", "gets",
        "le", "ge", "leq", "geq", "ne", "neq", "approx", "equiv", "sim", "simeq", "cong", "propto",
        "in", "notin", "ni", "subset", "subseteq", "supset", "supseteq", "cup", "cap", "wedge",
        "vee", "land", "lor", "mid", "parallel", "perp", "ll", "gg", "setminus", "oplus", "otimes",
    )
}
