package com.xnotes.core.text

import com.xnotes.core.pal.MathBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathEstimateTest {

    private fun box(latex: String) = MathEstimate.box(latex, 1.0)

    private fun MathBox.sane() = width > 0.0 && ascent > 0.0 && descent >= 0.0 &&
        width.isFinite() && ascent.isFinite() && descent.isFinite()

    @Test
    fun brokenLatexStillGetsABoxAndNeverThrows() {
        val broken = listOf(
            "", " ", "\\", "{", "}", "a}b", "x^", "x_", "^_^", "\\frac{", "\\frac{a}", "\\sqrt[", "\\left(",
            "\\right)", "\\left( x", "\\begin{pmatrix} a & b", "\\begin{", "\\end{pmatrix}", "\\text{abc",
            "\\\\\\\\", "&&", "\\color{red", "\\textcolor{red}{", "x^{2", "\\mathbb{",
        )
        for (latex in broken) assertTrue(latex, box(latex).sane())
    }

    @Test
    fun theBoxGrowsWithWhatIsWritten() {
        assertTrue(box("x").width < box("x + y").width)
        assertTrue(box("x + y").width < box("x + y + z").width)
    }

    @Test
    fun scriptsRaiseAndLowerTheBox() {
        assertTrue(box("x^2").ascent > box("x").ascent)
        assertTrue(box("x_i").descent > box("x").descent)
    }

    @Test
    fun aFractionStandsTallButOnlyAsWideAsItsWiderPart() {
        val frac = box("\\frac{abc}{d}")
        assertTrue(frac.height > box("abc").height * 2)
        assertTrue(frac.width < box("abcd").width)
    }

    @Test
    fun moreRowsMakeATallerMatrix() {
        val two = box("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}")
        val three = box("\\begin{pmatrix} a & b \\\\ c & d \\\\ e & f \\end{pmatrix}")
        assertTrue(three.height > two.height)
        assertEquals(two.width, three.width, 1e-9)
    }

    @Test
    fun limitsStackOnABigOperatorRatherThanWidenIt() {
        val bare = box("\\sum")
        val limited = box("\\sum_{i=1}^{n}")
        assertTrue(limited.height > bare.height)
        assertTrue(limited.width < bare.width + box("i=1").width)
    }

    @Test
    fun scalesWithTheEm() {
        val one = MathEstimate.box("\\frac{a}{b}", 1.0)
        val ten = MathEstimate.box("\\frac{a}{b}", 10.0)
        assertEquals(one.width * 10, ten.width, 1e-9)
        assertEquals(one.ascent * 10, ten.ascent, 1e-9)
    }

    /** Boxes the renderer measured on a tablet, in ems; the guess has to stay near them. */
    @Test
    fun staysNearWhatTheRendererMeasures() {
        val measured = mapOf(
            "x" to doubleArrayOf(0.86, 0.58, 0.14),
            "a + b = c" to doubleArrayOf(4.22, 0.82, 0.22),
            "\\frac{a}{b}" to doubleArrayOf(1.06, 1.257, 0.826),
            "\\sum_{i=1}^{n} i" to doubleArrayOf(2.35, 1.58, 1.266),
            "\\begin{bmatrix} 1 & 0 & 0 \\\\ 0 & 1 & 0 \\\\ 0 & 0 & 1 \\end{bmatrix}" to doubleArrayOf(5.4, 1.63, 1.13),
            "f(x) = \\sum_{k=0}^{n} \\binom{n}{k} x^k (1-x)^{n-k}" to doubleArrayOf(12.446, 1.58, 1.266),
            "\\sin x + \\cos y" to doubleArrayOf(6.068, 1.02, 0.38),
        )
        for ((latex, m) in measured) {
            val b = box(latex)
            assertEquals(latex, 1.0, b.width / m[0], 0.15)
            assertEquals(latex, 1.0, b.height / (m[1] + m[2]), 0.15)
        }
    }
}
