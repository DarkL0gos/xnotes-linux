package com.xnotes.editor

import com.xnotes.canvas.CanvasPointerMode
import com.xnotes.canvas.ChromePalette
import com.xnotes.canvas.InfiniteFramePainter
import com.xnotes.canvas.InfiniteInteraction
import com.xnotes.canvas.ResizeMath
import com.xnotes.canvas.WetInkCache
import com.xnotes.canvas.isHighlighterInk
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.history.History
import com.xnotes.core.history.LockItems
import com.xnotes.core.history.RestyleItems
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.AddCanvasItems
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.CanvasSelection
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.EraseCanvasItems
import com.xnotes.core.infinite.EraseSession
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.LiftTransform
import com.xnotes.core.infinite.OnCanvas
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.core.infinite.ReplaceCanvasItems
import com.xnotes.core.infinite.bringToFrontOrder
import com.xnotes.core.infinite.sameOrder
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.deepCopy
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.platform.File
import com.xnotes.core.platform.identityMap
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.input.PointerEvent
import com.xnotes.input.UiScheduler
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/** What the infinite canvas editor needs from its host. */
interface InfiniteCanvasHost {
    /** Something on screen changed: schedule a frame. */
    fun requestRender()

    /** The document or the undo stack changed. */
    fun contentChanged()

    /** Zoom or scroll changed. */
    fun viewChanged()

    /** The editor armed a tool by itself (a long-press grab, and back). */
    fun toolChanged(tool: Tool) {}

    /** The settled selection's viewport rect, for its menu, or null to hide it. */
    fun selectionMenu(rect: Rect?) {}

    /** A held press asked for a context menu at a viewport point; [locked] is the locked item under it. */
    fun contextMenu(at: Pt, locked: CanvasItem?) {}
}

/**
 * The infinite canvas (`.xcanvas`) as a host-independent editor: the document and its undo stack,
 * the view, the shared [InfiniteInteraction] gestures, selection actions, and drawing through any
 * [Renderer] ([InfiniteFramePainter] for the content, the overlays here).
 *
 * The Android app keeps its own editor over a GPU scene; this one carries the same editing rules
 * (commits, erasing, selection, history) for hosts that draw through the renderer, such as the
 * Qt app through the C API.
 */
class InfiniteCanvasEditor(
    val document: InfiniteDocument,
    surfaces: SurfaceFactory,
    private val scheduler: UiScheduler,
    private val textMeasurer: TextMeasurer,
    private val host: InfiniteCanvasHost,
) {
    val history = History()
    val viewport = CanvasViewport()
    val selection = CanvasSelection(document)
    var palette: ChromePalette = ChromePalette.DARK
        set(value) {
            field = value
            painter.invalidateBase()
            invalidateFrame()
        }
    var devicePxPerDp = 1.0

    private val painter = InfiniteFramePainter(surfaces)
    private val wetCache = WetInkCache(surfaces)

    // --- tools ---

    private val toolConfigs = HashMap<Tool, ToolConfig>()
    var inkColor: Rgba = InkPalette.DEFAULT
    var shapeConfig: ShapeConfig = ShapeConfig()
    var detectShapes: Boolean = false

    // --- what the overlays draw ---

    private var wetStroke: Stroke? = null
    private var pendingShape: ShapeItem? = null
    private var lifted: List<CanvasItem> = emptyList()
    private var lift: LiftTransform = LiftTransform.NONE
    private var eraserAt: Pt? = null
    private var eraserRadiusPx = 0.0

    /** The locked item the last context menu was opened on. */
    var pressedLocked: CanvasItem? = null
        private set

    private val clipboard = ArrayList<CanvasItem>()
    private var clipboardFromCut = false
    private var appliedInitialView = false

    // --- frame damage: writing repaints only around the pen (see frameDamage) ---

    /** The next frame must repaint everything. */
    var fullFrame = true

    private var damage: Rect? = null
    private var lastInk: Rect? = null
    private var inkJustGrew = false

    val interaction: InfiniteInteraction = InfiniteInteraction(
        viewport = viewport,
        requestRender = {
            if (inkJustGrew) inkJustGrew = false else fullFrame = true
            host.requestRender()
        },
        scheduler = scheduler,
        onViewChanged = { onViewChanged() },
        configFor = { configFor(it) },
        onWetStroke = { wet(it) },
        onCommitStroke = {
            wet(null)
            commitItem(it)
        },
        onEraseBegin = { EraseSession(document) },
        onEraseEnd = { session ->
            session.buildCommand()?.let {
                history.push(it)
                markDirty()
            }
        },
        onEraserCursor = { at, radius -> eraserCursor(at, radius) },
        onPendingShape = { shape ->
            pendingShape = shape
            fullFrame = true
        },
        onCommitShape = { commitItem(it) },
        shapeConfig = { shapeConfig },
        inkColor = { inkColor },
        detectShapes = { detectShapes },
        selection = { selection },
        itemsIn = { document.itemsIn(it) },
        onSelectionChanged = {
            fullFrame = true
            host.selectionMenu(selectionMenuRect())
        },
        onCommitSelection = { command -> command?.let { push(it) } },
        onLiftSelection = { items, at -> setLift(items, at) },
        devicePxPerDp = { devicePxPerDp },
        onContextMenu = { at, _, locked ->
            pressedLocked = locked
            host.contextMenu(at, locked)
        },
        onToolChanged = { host.toolChanged(it) },
    )

    /**
     * Keeps the tiles in step with the model. Every change, history's included, goes through the
     * document, so this is the one place a tile learns its ink changed. An item that moved leaves
     * stale ink where it was, so the bounds it was last drawn with are kept to drop those tiles too.
     */
    private val drawnBounds: MutableMap<CanvasItem, Rect> = identityMap()

    init {
        for (item in document.items) drawnBounds[item] = item.paintBounds()
        document.listener = object : InfiniteDocument.Listener {
            override fun onItemAdded(item: CanvasItem) = touched(item)
            override fun onItemChanged(item: CanvasItem) = touched(item)
            override fun onItemRemoved(item: CanvasItem) {
                val was = drawnBounds.remove(item) ?: item.paintBounds()
                if (item.isHighlighterInk()) painter.invalidateBase() else painter.invalidate(was)
                fullFrame = true
            }

            override fun onOrderChanged() {
                painter.invalidateAll()
                fullFrame = true
            }

            override fun onReset() {
                drawnBounds.clear()
                for (item in document.items) drawnBounds[item] = item.paintBounds()
                painter.invalidateAll()
                fullFrame = true
            }
        }
    }

    private fun touched(item: CanvasItem) {
        val was = drawnBounds[item]
        val now = item.paintBounds()
        drawnBounds[item] = now
        fullFrame = true
        // Highlighters are not in the tiles (they are drawn over the composed base): no tile to drop.
        if (item.isHighlighterInk()) return painter.invalidateBase()
        was?.let { painter.invalidate(it) }
        painter.invalidate(now)
    }

    // --- input ---

    fun onTouch(e: PointerEvent) = interaction.onTouch(e)

    fun onHover(e: PointerEvent) = interaction.onGenericMotion(e)

    // --- view ---

    fun setViewport(width: Int, height: Int, pxPerDp: Double) {
        viewport.widthPx = width
        viewport.heightPx = height
        devicePxPerDp = pxPerDp
        fullFrame = true
        applyInitialView()
    }

    /** Where the canvas was left, or on its content, or at the origin; once the view has a size. */
    private fun applyInitialView() {
        if (appliedInitialView || viewport.widthPx <= 0 || viewport.heightPx <= 0) return
        appliedInitialView = true
        val saved = document.lastView
        when {
            saved != null -> viewport.apply(saved)
            else -> document.contentBounds()?.let { viewport.fit(it) } ?: viewport.centerOn(0.0, 0.0)
        }
        onViewChanged()
    }

    fun zoomAt(vx: Double, vy: Double, factor: Double) {
        viewport.zoomAround(vx, vy, viewport.zoom * factor)
        onViewChanged()
    }

    fun zoomBy(factor: Double) {
        viewport.zoomAroundCenter(viewport.zoom * factor)
        onViewChanged()
    }

    /** Move the view by viewport pixels (the content slides the other way). */
    fun scrollBy(dx: Double, dy: Double) {
        viewport.scrollX += dx / viewport.zoom
        viewport.scrollY += dy / viewport.zoom
        onViewChanged()
    }

    /** Frame every item, or the origin at 100% on an empty canvas. */
    fun zoomToFit() {
        val bounds = document.contentBounds()
        if (bounds == null) {
            viewport.zoom = 1.0
            viewport.centerOn(0.0, 0.0)
        } else {
            viewport.fit(bounds)
        }
        onViewChanged()
    }

    /**
     * The scrollable extent for scroll bars, in content px: what is drawn plus the view, with a
     * view's worth of margin on every side, since the canvas itself goes on forever.
     */
    fun scrollExtent(): Rect {
        val view = viewport.visibleContentRect()
        val content = document.contentBounds()?.union(view) ?: view
        return content.outsetXY(view.w, view.h)
    }

    private fun Rect.outsetXY(mx: Double, my: Double) = Rect(x - mx, y - my, w + 2 * mx, h + 2 * my)

    private fun onViewChanged() {
        document.lastView = viewport.toWaypoint()
        fullFrame = true
        host.viewChanged()
        host.selectionMenu(selectionMenuRect())
        host.requestRender()
    }

    // --- tools ---

    var tool: Tool
        get() = interaction.tool
        set(next) {
            // Leaving the selection tools drops the selection, so its chrome cannot linger over ink.
            if (interaction.tool != next && (interaction.tool == Tool.SELECT || interaction.tool == Tool.LASSO)) {
                interaction.clearSelection()
            }
            interaction.tool = next
            invalidateFrame()
        }

    /** The stored style of [t], without the ink colour folded in. */
    fun toolConfig(t: Tool): ToolConfig = toolConfigs.getOrPut(t) { ToolDefaults.configFor(t) }

    fun setToolConfig(t: Tool, config: ToolConfig) {
        toolConfigs[t] = config
    }

    /** What [t] draws with: its style, in its own colour if it has one, else the active ink. */
    fun configFor(t: Tool): ToolConfig {
        val base = toolConfig(t)
        return base.copy(rgba = base.colorOverride ?: inkColor)
    }

    // --- edits ---

    private fun markDirty() {
        document.dirty = true
        fullFrame = true
        host.contentChanged()
        host.requestRender()
    }

    private fun push(command: Command) {
        history.push(command)
        markDirty()
    }

    /** Add a finished item and record the edit, the common tail of every creating tool. */
    private fun commitItem(item: CanvasItem) {
        document.add(item)
        push(AddCanvasItem(document, item))
    }

    /** An image file of [width]×[height] px, at most 60% of the view, centred on [at] or the view. */
    fun insertImage(file: File, width: Int, height: Int, at: Pt? = null) {
        val visible = viewport.visibleContentRect()
        val scale = minOf(1.0, visible.w * 0.6 / width, visible.h * 0.6 / height)
        val w = width * scale
        val h = height * scale
        val centre = at ?: viewport.centerContent
        commitItem(ImageItem(ImageData(file, width, height), Rect(centre.x - w / 2.0, centre.y - h / 2.0, w, h)))
    }

    fun setBackground(background: CanvasBackground) {
        document.background = background
        painter.invalidateBase()
        markDirty()
    }

    // History moves geometry the selection box cannot follow, so it drops the selection, as the
    // paged editor does.
    fun undo() {
        if (!history.canUndo) return
        history.undo()
        interaction.clearSelection()
        markDirty()
    }

    fun redo() {
        if (!history.canRedo) return
        history.redo()
        interaction.clearSelection()
        markDirty()
    }

    fun escape() {
        interaction.clearSelection()
        invalidateFrame()
    }

    // --- selection ---

    val hasSelection: Boolean get() = !selection.isEmpty

    /** Where the selection's menu anchors (viewport px), or null while there is none or a gesture runs. */
    fun selectionMenuRect(): Rect? {
        val box = selection.box ?: return null
        if (interaction.mode != CanvasPointerMode.IDLE) return null
        val bounds = Rect.bounding(box.corners().map { viewport.contentToViewport(it) })
        // Clear of the rotate grip above the box, as on the paged canvas.
        val clearance = OverlayTessellator.GRIP_ARM_PX + OverlayTessellator.GRIP_PX / 2.0
        return Rect(bounds.x, bounds.y - clearance, bounds.w, bounds.h + clearance)
    }

    fun selectAll() {
        if (document.isEmpty) return
        selection.select(document.items.filter { !it.locked })
        tool = Tool.SELECT
        selectionChanged()
    }

    private fun selectionChanged() {
        fullFrame = true
        host.selectionMenu(selectionMenuRect())
        host.requestRender()
    }

    fun deleteSelection() {
        val items = selection.items
        if (items.isEmpty()) return
        val command = EraseCanvasItems.capture(document, items)
        document.removeAll(items)
        history.push(command)
        interaction.clearSelection()
        markDirty()
    }

    fun copySelection() {
        if (selection.isEmpty) return
        clipboard.clear()
        clipboardFromCut = false
        selection.items.mapTo(clipboard) { it.deepCopy(textMeasurer) }
    }

    fun cutSelection() {
        if (selection.isEmpty) return
        copySelection()
        clipboardFromCut = true
        deleteSelection()
    }

    fun duplicateSelection() {
        if (selection.isEmpty) return
        val clones = selection.items.map { it.deepCopy(textMeasurer) }
        for (clone in clones) clone.translate(DUPLICATE_NUDGE, DUPLICATE_NUDGE)
        document.addAll(clones)
        history.push(AddCanvasItems(document, clones))
        selection.select(clones)
        markDirty()
        selectionChanged()
    }

    val canPaste: Boolean get() = clipboard.isNotEmpty()

    /** Paste with the copied items' top-left at [at] (content px), and select them. */
    fun pasteAt(at: Pt) {
        if (clipboard.isEmpty()) return
        val clones = clipboard.map { it.deepCopy(textMeasurer) }
        var box: Rect? = null
        for (clone in clones) box = box?.union(clone.bounds()) ?: clone.bounds()
        val bounds = box ?: return
        for (clone in clones) clone.translate(at.x - bounds.left, at.y - bounds.top)
        document.addAll(clones)
        history.push(AddCanvasItems(document, clones))
        if (clipboardFromCut) {
            clipboard.clear()
            clipboardFromCut = false
        }
        selection.select(clones)
        tool = Tool.SELECT
        markDirty()
        selectionChanged()
    }

    fun bringToFront() {
        if (selection.isEmpty) return
        val before = document.items.toList()
        val after = bringToFrontOrder(before, selection.items)
        if (sameOrder(before, after)) return
        document.replaceAll(after)
        push(ReplaceCanvasItems(document, before, after))
    }

    /** Pin the selection in place and put it away; a locked item cannot stay selected. */
    fun lockSelection() {
        if (selection.isEmpty) return
        val items = selection.items.toList()
        for (item in items) item.locked = true
        history.push(LockItems(items, true))
        interaction.clearSelection()
        markDirty()
    }

    /** Release the locked item the last context menu was opened on. */
    fun unlockPressed() {
        val item = pressedLocked ?: return
        pressedLocked = null
        if (!item.locked) return
        item.locked = false
        push(LockItems(listOf(item), false))
    }

    fun selectionStyles(): List<DrawStyle> = selection.items.mapNotNull { DrawStyle.of(it) }

    private var restyleBaseline: MutableMap<CanvasItem, DrawStyle>? = null

    /** Recolour and/or re-thicken the selection, with the paged editor's preview/commit contract. */
    fun restyleSelection(color: Rgba?, width: Double?, preview: Boolean) {
        if (selection.isEmpty) return
        val baseline = restyleBaseline ?: HashMap<CanvasItem, DrawStyle>().also { map ->
            for (item in selection.items) DrawStyle.of(item)?.let { map[item] = it }
        }
        restyleBaseline = if (preview) baseline else null
        if (color != null || width != null) {
            for (item in selection.items) {
                val current = DrawStyle.of(item) ?: continue
                DrawStyle(color ?: current.color, width ?: current.width).applyTo(item)
            }
            document.itemsChanged(selection.items)
        }
        if (!preview) {
            val entries = baseline.mapNotNull { (item, before) ->
                DrawStyle.of(item)?.takeIf { it != before }?.let { RestyleItems.Entry(item, before, it) }
            }
            if (entries.isNotEmpty()) history.push(OnCanvas(document, RestyleItems(entries), entries.map { it.item }))
        }
        markDirty()
    }

    // --- live drawing state, and the damage it does ---

    private fun wet(stroke: Stroke?) {
        val rect = stroke?.let { inkRect(it) }
        if (stroke == null || rect == null) {
            if (wetStroke != null) fullFrame = true
            wetStroke = null
            lastInk = null
            return
        }
        wetStroke = stroke
        damage = listOfNotNull(damage, lastInk, rect).reduce { a, b -> a.union(b) }
        lastInk = rect
        inkJustGrew = true
    }

    /** The viewport rect a live stroke's next frame can differ in: its unsettled tail, else all of it. */
    private fun inkRect(stroke: Stroke): Rect? {
        val ribbon = stroke.wetRibbon
        val content = if (ribbon != null && !stroke.config.neon && ribbon.pointCount > 0) {
            var l = Double.MAX_VALUE
            var t = Double.MAX_VALUE
            var r = -Double.MAX_VALUE
            var b = -Double.MAX_VALUE
            for (i in max(0, ribbon.settledCount - 2) until ribbon.pointCount) {
                val hw = ribbon.hw(i)
                l = minOf(l, ribbon.cx(i) - hw)
                t = minOf(t, ribbon.cy(i) - hw)
                r = maxOf(r, ribbon.cx(i) + hw)
                b = maxOf(b, ribbon.cy(i) + hw)
            }
            Rect(l, t, r - l, b - t)
        } else {
            stroke.paintBounds()
        }
        if (!content.w.isFinite() || !content.h.isFinite()) return null
        return viewport.contentToViewport(content).outset(INK_DAMAGE_PAD)
    }

    private fun eraserCursor(at: Pt?, radiusPx: Double) {
        fun ring(p: Pt?, r: Double) = p?.let { Rect(it.x - r, it.y - r, 2 * r, 2 * r).outset(3.0) }
        damage = listOfNotNull(damage, ring(eraserAt, eraserRadiusPx), ring(at, radiusPx)).reduceOrNull { a, b -> a.union(b) }
        eraserAt = at
        eraserRadiusPx = radiusPx
        inkJustGrew = true
    }

    private fun setLift(items: List<CanvasItem>, at: LiftTransform) {
        // The tiles leave dragged items out, so they redraw without them, and with them once dropped.
        val changed = if (items.isEmpty()) lifted else items.filter { i -> lifted.none { it === i } }
        for (item in changed) painter.invalidate(drawnBounds[item] ?: item.paintBounds())
        lifted = items
        lift = at
        fullFrame = true
    }

    /** The next frame repaints everything (the selection chrome moved, the palette changed, ...). */
    fun invalidateFrame() {
        fullFrame = true
        host.requestRender()
    }

    /** The rect the next frame may limit itself to (viewport px), or null for a full frame. */
    fun frameDamage(): Rect? = if (fullFrame) null else damage

    // --- painting ---

    fun paint(r: Renderer) {
        damage = null
        fullFrame = false
        val paper = document.background.paperColor ?: palette.paper
        val result = painter.paint(r, document, viewport, paper, skip = { item -> lifted.any { it === item } })

        r.save()
        r.scale(viewport.zoom, viewport.zoom)
        r.translate(-viewport.scrollX, -viewport.scrollY)
        if (lifted.isNotEmpty()) {
            r.save()
            applyLift(r, lift)
            for (item in lifted) item.paint(r)
            r.restore()
        }
        pendingShape?.paint(r)
        wetStroke?.let { stroke ->
            val maxPixels = 4L * viewport.widthPx * viewport.heightPx
            if (!wetCache.paint(r, stroke, viewport.zoom, maxPixels)) stroke.paint(r)
        }
        r.restore()
        paintOverlays(r)
        when (result) {
            // Tiles still to render: keep frames coming until the view is whole.
            InfiniteFramePainter.Result.RENDERING -> invalidateFrame()
            // The zoom is moving: render its tiles once it has held still a moment.
            InfiniteFramePainter.Result.ZOOMING -> {
                scheduler.removeCallbacks(zoomSettle)
                scheduler.postDelayed(zoomSettle, ZOOM_SETTLE_MS)
            }
            InfiniteFramePainter.Result.COMPLETE -> Unit
        }
    }

    /** The drag so far, as the renderer's translate, rotate and scale (M = R(t1) S R(t2), by SVD). */
    private fun applyLift(r: Renderer, l: LiftTransform) {
        r.translate(l.pivot.x + l.dx, l.pivot.y + l.dy)
        if (!l.isLinearIdentity) {
            val e = (l.a + l.d) / 2
            val f = (l.a - l.d) / 2
            val g = (l.b + l.c) / 2
            val h = (l.b - l.c) / 2
            val q = sqrt(e * e + h * h)
            val rr = sqrt(f * f + g * g)
            val a1 = atan2(g, f)
            val a2 = atan2(h, e)
            r.rotate((a2 + a1) / 2 * 180.0 / PI)
            r.scale(q + rr, q - rr)
            r.rotate((a2 - a1) / 2 * 180.0 / PI)
        }
        r.translate(-l.pivot.x, -l.pivot.y)
    }

    /** Band, lasso, selection box with its handles and grip, and the eraser ring, in viewport px. */
    private fun paintOverlays(r: Renderer) {
        val accent = palette.accent
        val dashOn = OverlayTessellator.DASH_ON_DP * devicePxPerDp
        val dashGap = OverlayTessellator.DASH_GAP_DP * devicePxPerDp
        val marquee = Pen(accent, OverlayTessellator.MARQUEE_PX, cosmetic = true, dashed = true, dashOn = dashOn, dashGap = dashGap)
        interaction.bandRect?.let { r.strokeRect(viewport.contentToViewport(it), marquee) }
        val lasso = interaction.lassoPoints
        if (lasso.size >= 2) r.strokePolyline(lasso.map { viewport.contentToViewport(it) }, marquee)
        selection.box?.let { box ->
            val zoom = viewport.zoom
            val outline = Pen(accent, OverlayTessellator.OUTLINE_PX, cosmetic = true, dashed = true, dashOn = dashOn, dashGap = dashGap)
            r.strokePolygon(box.corners().map { viewport.contentToViewport(it) }, outline)
            val top = viewport.contentToViewport(ResizeMath.obbTopMid(box))
            val grip = viewport.contentToViewport(ResizeMath.obbRotateGrip(box, OverlayTessellator.GRIP_ARM_PX / zoom))
            r.strokePolyline(listOf(top, grip), Pen(accent, OverlayTessellator.OUTLINE_PX))
            val half = OverlayTessellator.HANDLE_PX / 2.0
            for (handle in ResizeMath.obbHandles(box)) {
                val p = viewport.contentToViewport(handle.content)
                r.fillRect(Rect(p.x - half, p.y - half, half * 2, half * 2), accent)
            }
            r.fillCircle(grip, OverlayTessellator.GRIP_PX / 2.0, accent)
        }
        eraserAt?.let { at ->
            r.strokeEllipse(at, eraserRadiusPx, eraserRadiusPx, Pen(Rgba(255, 255, 255, 200), 3.0))
            r.strokeEllipse(at, eraserRadiusPx, eraserRadiusPx, Pen(Rgba(80, 80, 80, 230), 1.4))
        }
    }

    private val zoomSettle = com.xnotes.core.platform.Runnable { invalidateFrame() }

    /** Let go of every cached surface (the editor is going away). */
    fun dispose() {
        scheduler.removeCallbacks(zoomSettle)
        interaction.resetGestureState()
        painter.dispose()
        wetCache.clear()
        document.listener = null
    }

    companion object {
        /** Content px a duplicate lands from its original, so the copy is visibly a copy. */
        const val DUPLICATE_NUDGE = 24.0

        private const val INK_DAMAGE_PAD = 4.0

        /** How long the zoom has to hold still before its tiles are rendered. */
        private const val ZOOM_SETTLE_MS = 80L
    }
}
