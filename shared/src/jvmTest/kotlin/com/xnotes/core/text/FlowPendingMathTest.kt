package com.xnotes.core.text

import com.xnotes.core.FakeAsyncTypesetter
import com.xnotes.core.FakeMathTypesetter
import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.MathBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formulas a typesetter is still setting elsewhere: the room held for them, and what lands after. */
class FlowPendingMathTest {

    private val ts = FakeAsyncTypesetter()
    private val l = FlowLayout(FakeTextMeasurer(), ts)
    private val page = Rect(0.0, 0.0, 400.0, 400.0)

    private fun mathPara(latex: String, display: Boolean = false) =
        Paragraph(mutableListOf(Run(latex, CharStyle(math = true, mathDisplay = display))))

    private fun flowWith(vararg paras: Paragraph) = TextFlow().apply {
        paragraphs.addAll(paras)
        margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
    }

    private fun lay(layout: FlowLayout, flow: TextFlow, width: Double = 400.0) =
        layout.layout(flow, listOf(PageBox(width, 400.0)), 150)

    private fun mathSegs(frame: FlowFrame) = frame.pages.flatMap { p -> p.lines.flatMap { it.segs } }.filter { it.math }

    @Test
    fun aFormulaStillBeingSetHoldsTheLineOpenWithAGuess() {
        val frame = lay(l, flowWith(mathPara("\\frac{a}{b}")))
        val seg = mathSegs(frame).single()
        assertTrue(seg.pending)
        val guess = seg.box!!
        assertTrue(guess.width > 0.0)
        // The caret steps over it whole, as it will over the formula.
        val line = frame.pages[0].lines.single()
        assertEquals(guess.width, line.xs.last() - line.xs.first(), 1e-9)
    }

    @Test
    fun itPaintsAStandInRatherThanTheFormula() {
        val frame = lay(l, flowWith(mathPara("x^2")))
        val r = FakeRenderer()
        FlowPainter.paintPage(r, frame, 0, page)
        assertTrue(r.ops.none { it.startsWith("drawMath") })
        assertEquals(1, r.ops.count { it.startsWith("mathPending:x^2@") })
    }

    @Test
    fun theRealBoxTakesOverOnTheNextPassWithoutAnEdit() {
        val flow = flowWith(mathPara("x^2"))
        lay(l, flow)
        val real = MathBox(120.0, 30.0, 8.0)
        ts.land("x^2", real)
        val frame = lay(l, flow)
        val seg = mathSegs(frame).single()
        assertFalse(seg.pending)
        assertEquals(real, seg.box)
        val line = frame.pages[0].lines.single()
        assertEquals(120.0, line.xs.last() - line.xs.first(), 1e-9)
        assertEquals(30.0, line.baseline - line.top, 1e-9)
    }

    @Test
    fun aRefusalThatLandsShowsTheSourceAsBroken() {
        val flow = flowWith(mathPara("\\frac{"))
        lay(l, flow)
        ts.land("\\frac{", null)
        val frame = lay(l, flow)
        assertEquals(MathShow.ERROR, frame.pages[0].lines.single().decos.single().math)
        assertTrue(mathSegs(frame).isEmpty())
    }

    @Test
    fun aGuessIsOneBoxThatNeverBreaksAcrossLines() {
        val p = Paragraph(mutableListOf(Run("a b ", CharStyle.DEFAULT), Run("x + y + z + w + v", CharStyle(math = true))))
        val frame = lay(l, flowWith(p), width = 60.0)
        assertEquals("x + y + z + w + v", mathSegs(frame).single().text)
    }

    @Test
    fun aLonePendingDisplayFormulaSitsCentred() {
        val frame = lay(l, flowWith(mathPara("x", display = true)))
        val line = frame.pages[0].lines.single()
        val w = line.xs.last() - line.xs.first()
        assertEquals((400.0 - w) / 2.0, line.xs.first(), 1e-9)
    }

    @Test
    fun eachPassAsksAfterAPendingFormulaOnceAndStopsWhenItLands() {
        val flow = flowWith(mathPara("x^2"))
        repeat(3) { lay(l, flow) }
        assertEquals(3, ts.passes)
        assertEquals(3, ts.asked.count { it == "x^2" })
        ts.land("x^2", MathBox(10.0, 10.0, 2.0))
        lay(l, flow)
        ts.asked.clear()
        repeat(2) { lay(l, flow) }
        assertTrue(ts.asked.isEmpty())
    }

    @Test
    fun withoutMathAPageLeavesItsFormulasToTheirOwnPass() {
        val layout = FlowLayout(FakeTextMeasurer(), FakeMathTypesetter())
        val p = Paragraph(mutableListOf(Run("hi ", CharStyle.DEFAULT), Run("x^2", CharStyle(math = true))))
        val frame = lay(layout, flowWith(p))
        val text = FakeRenderer().also { FlowPainter.paintPage(it, frame, 0, page, math = false) }
        assertTrue(text.ops.none { it.startsWith("drawMath") || it.startsWith("mathPending") })
        assertTrue(text.ops.any { it.startsWith("drawTextRun:hi@") })
        val maths = FakeRenderer().also { FlowPainter.paintMath(it, frame, 0, page) }
        assertEquals(1, maths.ops.size)
        assertTrue(maths.ops.single().startsWith("drawMath:x^2@"))
    }

    @Test
    fun aBackendStillSettingAFormulaGetsAStandInAtItsRealBox() {
        val layout = FlowLayout(FakeTextMeasurer(), FakeMathTypesetter())
        val frame = lay(layout, flowWith(mathPara("ab")))
        val seg = mathSegs(frame).single()
        val box = seg.box!!
        val line = frame.pages[0].lines.single()
        val r = FakeRenderer().apply { mathReady = false }
        FlowPainter.paintMath(r, frame, 0, page)
        assertEquals(
            listOf(
                "drawMath:ab@${seg.x},${line.baseline}",
                "mathPending:ab@${seg.x},${line.baseline - box.ascent},${box.width},${box.height}",
            ),
            r.ops,
        )
    }

    @Test
    fun aLayoutThroughOnePageMatchesItAndSetsNothingFurther() {
        val math = object : com.xnotes.core.pal.MathTypesetter {
            val asked = ArrayList<String>()
            override fun measure(latex: String, sizePt: Double, display: Boolean): MathBox? {
                asked += latex
                return MathBox(40.0, 12.0, 4.0)
            }
        }
        val flow = flowWith(*Array(40) { mathPara("f_{$it}") })
        val boxes = listOf(PageBox(400.0, 120.0), PageBox(400.0, 120.0), PageBox(400.0, 120.0), PageBox(400.0, 120.0))
        val whole = FlowLayout(FakeTextMeasurer(), math).layout(flow, boxes, 150)
        math.asked.clear()
        val first = FlowLayout(FakeTextMeasurer(), math).layout(flow, boxes, 150, throughPage = 0)
        assertEquals(whole.pages[0].lines.map { it.top to it.paraIndex }, first.pages[0].lines.map { it.top to it.paraIndex })
        assertTrue(math.asked.size < 40)
        assertTrue(first.pages[2].lines.isEmpty())
    }
}
