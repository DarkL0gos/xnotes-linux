package com.xnotes.core.shapes

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowchartKindsTest {

    private val box = Rect(0.0, 0.0, 150.0, 90.0)

    private fun roles(k: ShapeKind) = k.build(box, ShapeParams(k.defaultAdj, k.defaultN)).paths.map { it.role }

    @Test fun detailLinesAreStrokedOverTheOutline() {
        assertEquals(listOf(PathRole.OUTLINE, PathRole.STROKE_ONLY), roles(FlowchartKinds.PREDEFINED))
        assertEquals(2, FlowchartKinds.PREDEFINED.build(box, ShapeParams()).paths[1].contours.size)
        assertEquals(listOf(PathRole.OUTLINE, PathRole.STROKE_ONLY), roles(FlowchartKinds.DATABASE))
        assertEquals(listOf(PathRole.OUTLINE, PathRole.STROKE_ONLY), roles(FlowchartKinds.SUMMING))
    }

    @Test fun aStackShowsItsBackPagesAsEdgesBehindTheFrontOne() {
        val paths = FlowchartKinds.MULTIDOCUMENT.build(box, ShapeParams()).paths
        assertEquals(listOf(PathRole.STROKE_ONLY, PathRole.OUTLINE), paths.map { it.role })
        assertEquals(2, paths[0].contours.size)
        assertTrue(paths[0].contours.none { it.closed })
    }

    @Test fun aDocumentsWaveTouchesTheBottomEdge() {
        val b = FreePath.bounds(FlowchartKinds.DOCUMENT.build(box, ShapeParams()).paths.single().contours)!!
        assertEquals(box.bottom, b.bottom, 1e-9)
    }

    @Test fun straightSymbolsShowTheirCorners() {
        assertEquals(6, FlowchartKinds.PREPARATION.build(box, ShapeParams(FlowchartKinds.PREPARATION.defaultAdj)).corners.size)
        assertEquals(5, FlowchartKinds.OFF_PAGE.build(box, ShapeParams()).corners.size)
        assertEquals(3, FlowchartKinds.MERGE.build(box, ShapeParams()).corners.size)
        assertTrue("curved ones show none", FlowchartKinds.DELAY.build(box, ShapeParams()).corners.isEmpty())
    }

    @Test fun aFlippedOneSidedSymbolBecomesFree() {
        val s = ShapeItem(FlowchartKinds.INTERNAL_STORAGE, box.topLeft, Pt(box.right, box.bottom), Rgba(0, 0, 0), 2.0)
        s.applyTransform(Affine.scaleAbout(box.center, -1.0, 1.0))
        assertEquals(ShapeKind.POLYGON, s.shape)
        val sort = ShapeItem(FlowchartKinds.SORT, box.topLeft, Pt(box.right, box.bottom), Rgba(0, 0, 0), 2.0)
        sort.applyTransform(Affine.scaleAbout(box.center, -1.0, 1.0))
        assertEquals(FlowchartKinds.SORT, sort.shape)
    }

    @Test fun theFlowchartPageHasTwentyFourChips() {
        val keys = ShapePresets.flowchart.presets.map { it.key }
        assertEquals(24, keys.size)
        assertEquals(listOf("flow_process", "flow_alt_process", "flow_decision", "flow_data", "flow_terminator", "flow_preparation"), keys.take(6))
        assertEquals(ShapeKind.CIRCLE, ShapePresets.byKey("flow_connector")!!.kind)
        assertEquals("the second page", ShapePresets.flowchart, ShapePresets.pages[1])
    }
}
