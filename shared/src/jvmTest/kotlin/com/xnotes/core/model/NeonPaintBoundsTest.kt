package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.stroke.Sample
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeonPaintBoundsTest {

    private fun neonStroke(strength: Double = 0.6): Stroke {
        val s = Stroke(Tool.PEN, ToolConfig(baseWidth = 4.0, neon = true, neonStrength = strength))
        s.addSample(Sample(10.0, 10.0, 1.0))
        s.addSample(Sample(50.0, 10.0, 1.0))
        s.addSample(Sample(90.0, 10.0, 1.0))
        return s
    }

    private fun plainStroke(): Stroke {
        val s = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN))
        s.addSample(Sample(10.0, 10.0, 1.0))
        s.addSample(Sample(90.0, 10.0, 1.0))
        return s
    }

    @Test fun neonStrokePaintBoundsLargerThanBounds() {
        val s = neonStroke()
        val b = s.bounds()
        val pb = s.paintBounds()
        assertTrue("paintBounds left should be smaller than bounds left", pb.left < b.left)
        assertTrue("paintBounds top should be smaller than bounds top", pb.top < b.top)
        assertTrue("paintBounds right should be larger than bounds right", pb.right > b.right)
        assertTrue("paintBounds bottom should be larger than bounds bottom", pb.bottom > b.bottom)
        // wide glowR = max(4.0 * (1.8 + 5.0 * 0.6), 6.0) = max(19.2, 6.0) = 19.2; expansion = 19.2*2+4 = 42.4
        val expectedExpansion = 42.4
        assertEquals(expectedExpansion, b.left - pb.left, 0.01)
        assertEquals(expectedExpansion, pb.right - b.right, 0.01)
    }

    @Test fun plainStrokePaintBoundsEqualsBounds() {
        val s = plainStroke()
        assertEquals(s.bounds(), s.paintBounds())
    }

    @Test fun highlighterNeonFlagIgnored() {
        // Highlighter never glows even when neon=true
        val s = Stroke(Tool.HIGHLIGHTER, ToolConfig(baseWidth = 16.0, neon = true, neonStrength = 1.0))
        s.addSample(Sample(0.0, 0.0, 1.0))
        s.addSample(Sample(50.0, 0.0, 1.0))
        assertEquals(s.bounds(), s.paintBounds())
    }

    @Test fun neonShapePaintBoundsLargerThanBounds() {
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(10.0, 10.0), Pt(90.0, 60.0), Rgba(255, 0, 0, 255),
            strokeWidth = 3.0, neon = true, neonStrength = 0.6)
        val b = s.bounds()
        val pb = s.paintBounds()
        assertTrue(pb.left < b.left)
        assertTrue(pb.top < b.top)
        assertTrue(pb.right > b.right)
        assertTrue(pb.bottom > b.bottom)
        // glowR = max(3.0 * (1.2 + 2.6 * 0.6), 4.0) = max(8.28, 4.0) = 8.28; expansion = 8.28*2+4 = 20.56
        val expectedExpansion = 20.56
        assertEquals(expectedExpansion, b.left - pb.left, 0.01)
    }

    @Test fun plainShapePaintBoundsEqualsBounds() {
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(10.0, 10.0), Pt(90.0, 60.0), Rgba(0, 0, 0, 255), 3.0)
        assertEquals(s.bounds(), s.paintBounds())
    }
}
