package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.BackgroundPattern
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.platform.monotonicNanos
import kotlin.concurrent.Volatile
import kotlin.math.floor

/**
 * The infinite canvas drawn through a [Renderer], for hosts without the Android GPU scene.
 *
 * Painting every visible item each frame costs what the view holds, far more than a frame on a
 * full canvas, so committed ink is rasterized into square tiles at the exact zoom, which the
 * renderer then copies pixel for pixel: a pan reuses every tile still in view and renders only
 * the strip it uncovers, and an edit drops just the tiles the changed item touches.
 *
 * A zoom invalidates every tile at once, so while it is changing nothing is rendered: the frame
 * shows the last complete view scaled (one image, not a hundred tiles), and the tiles for the new
 * zoom are rendered once it holds still, a budget per frame, over that stand-in.
 *
 * With [runAsync] set, the tiles a pan or a zoom uncovers are rendered on the host's cache threads
 * instead, the stand-in showing where they are missing until [onTileReady] says one landed, and the
 * ring of tiles around the view is rendered ahead. Tiles over an edit are still rendered within the
 * frame, so the changed ink never blinks out (the stand-in predates it).
 *
 * The frame's base (paper, ruling, ink, highlighters) is composed into a view-sized [base] surface
 * and copied out, so the last complete one is at hand as that stand-in. Highlighters are not
 * baked into tiles: they multiply with everything beneath, the paper included, so they are drawn
 * over the composed base, as on the paged canvas.
 */
class InfiniteFramePainter(private val surfaces: SurfaceFactory) {

    /** What [paint] managed. */
    enum class Result {
        /** Every tile in view is in the frame. */
        COMPLETE,

        /** Tiles are missing and being rendered: paint again soon. */
        RENDERING,

        /** The zoom is changing: tiles wait until it holds still; paint again once it has. */
        ZOOMING,

        /** Tiles are missing and being rendered off the UI thread: [onTileReady] asks for the next frame. */
        WAITING,
    }

    /**
     * Off-thread tile rendering: [runAsync] runs a render on a cache thread, which hands the tile
     * back through [postToMain] (to the UI thread); [onTileReady] then asks for a frame. Unset,
     * tiles are rendered within the frame, a budget at a time.
     */
    var runAsync: ((() -> Unit) -> Unit)? = null
    var postToMain: ((() -> Unit) -> Unit)? = null
    var onTileReady: (() -> Unit)? = null

    /** A tile render handed to a cache thread; dropped when its result is no longer wanted. */
    private class Request {
        /** Set on the UI thread, read by the cache thread before it starts drawing. */
        @Volatile
        var cancelled = false
    }

    /** Tiles being rendered off the UI thread, each with the request its result must still match. */
    private val pending = HashMap<Key, Request>()

    /** Forget [key]'s render under way, and spare the cache thread the work if it has not begun. */
    private fun cancel(key: Key) {
        pending.remove(key)?.cancelled = true
    }

    /** Content changed since the view was last complete: its tiles are rendered within the frame. */
    private val edited = ArrayList<Rect>()
    private var editedAll = false

    private data class Key(val zoom: Double, val ix: Long, val iy: Long)

    private class Tile(val surface: RasterSurface, var used: Long)

    /** A composed base and the view it shows. */
    private class Base(val surface: RasterSurface, var zoom: Double, var scrollX: Double, var scrollY: Double)

    private val tiles = HashMap<Key, Tile>()
    private var frameNo = 0L

    /** The base being composed, and the last complete one (the stand-in while zooming). */
    private var work: Base? = null
    private var stable: Base? = null

    /** The zoom of the previous frame, to tell a zoom in progress from one that has settled. */
    private var lastZoom = Double.NaN

    /** Bumped by every content or background change, so a base knows it is stale. */
    private var generation = 0L
    private var stableGeneration = -1L

    /** Tiles kept at most, oldest dropped first; ~1 MB each. */
    var maxTiles = 320

    /** Tiles rendered by the last frame. */
    var renderedLastFrame = 0
        private set

    /** Drop the tiles that show any part of [content] (an item was added, removed or changed there). */
    fun invalidate(content: Rect) {
        generation++
        // Renders under way there predate the change: their tiles are turned away when they land.
        for (key in pending.keys.filter { rectOf(it).intersects(content) }) cancel(key)
        edited.add(content)
        val it = tiles.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (rectOf(e.key).intersects(content)) {
                e.value.surface.recycle()
                it.remove()
            }
        }
    }

    /** Everything is stale (the order changed, a reset, a new background). */
    fun invalidateAll() {
        generation++
        for (r in pending.values) r.cancelled = true
        pending.clear()
        editedAll = true
        for (t in tiles.values) t.surface.recycle()
        tiles.clear()
    }

    /** Only the composed base is stale (paper or ruling changed; the ink did not). */
    fun invalidateBase() {
        generation++
    }

    /**
     * Paint the canvas under the overlays: paper, ruling, the committed ink (except what [skip]
     * excludes, such as items being dragged) and the highlighters, into [r] in viewport pixels.
     */
    fun paint(
        r: Renderer,
        doc: InfiniteDocument,
        viewport: CanvasViewport,
        paper: Rgba,
        skip: (CanvasItem) -> Boolean,
        budgetNanos: Long = DEFAULT_BUDGET_NANOS,
    ): Result {
        frameNo++
        val w = viewport.widthPx
        val h = viewport.heightPx
        if (w <= 0 || h <= 0) return Result.COMPLETE
        val zoom = viewport.zoom
        val zooming = !lastZoom.isNaN() && zoom != lastZoom && stable != null
        lastZoom = zoom

        val view = Rect(0.0, 0.0, w.toDouble(), h.toDouble())
        val s = stable
        // The last complete base still shows exactly this: copy it.
        if (s != null && stableGeneration == generation && s.zoom == zoom && s.scrollX == viewport.scrollX &&
            s.scrollY == viewport.scrollY && s.surface.width == w && s.surface.height == h
        ) {
            r.drawRaster(s.surface, view)
            renderedLastFrame = 0
            return Result.COMPLETE
        }

        val base = baseSurface(w, h)
        val br = base.surface.renderer()
        val cs = TILE / zoom
        val visible = viewport.visibleContentRect()
        val ix0 = floor(visible.left / cs).toLong()
        val iy0 = floor(visible.top / cs).toLong()
        val ix1 = floor(visible.right / cs).toLong()
        val iy1 = floor(visible.bottom / cs).toLong()

        // Render what may be rendered now (nothing while the zoom is moving), then see what is there.
        // With the view where it was, the missing tiles are ones an edit dropped: a handful, and
        // all rendered now, or the stand-in (which predates the edit) would show ink blink out.
        val sameView = s != null && s.zoom == zoom && s.scrollX == viewport.scrollX && s.scrollY == viewport.scrollY &&
            s.surface.width == w && s.surface.height == h
        val deadline = if (sameView) Long.MAX_VALUE else monotonicNanos() + budgetNanos
        val async = runAsync != null && postToMain != null
        renderedLastFrame = 0
        val ready = ArrayList<Pair<Key, Tile>>()
        val absent = ArrayList<Key>()
        for (iy in iy0..iy1) for (ix in ix0..ix1) {
            val key = Key(zoom, ix, iy)
            var tile = tiles[key]
            if (tile == null && !zooming) {
                val overEdit = editedAll || edited.any { rectOf(key).intersects(it) }
                if (async && !overEdit && !sameView) {
                    requestTile(key, doc, skip)
                } else if (overEdit || renderedLastFrame == 0 || monotonicNanos() < deadline) {
                    cancel(key) // drawn here and now; a render under way is outdated
                    tile = renderTile(key, doc, skip)
                    renderedLastFrame++
                }
            }
            if (tile == null) absent.add(key) else ready.add(key to tile.also { it.used = frameNo })
        }
        // Renders queued for views since left (a pan or a zoom away) would hold up this one's.
        if (async && pending.isNotEmpty()) {
            val stale = pending.keys.filter { it.zoom != zoom || it.ix !in ix0 - 1..ix1 + 1 || it.iy !in iy0 - 1..iy1 + 1 }
            for (key in stale) cancel(key)
        }
        // Ahead of a pan: the ring around the view, once the view itself is asked for.
        if (async && !zooming) {
            for (iy in iy0 - 1..iy1 + 1) for (ix in ix0 - 1..ix1 + 1) {
                if (iy in iy0..iy1 && ix in ix0..ix1) continue
                val key = Key(zoom, ix, iy)
                if (key !in tiles) requestTile(key, doc, skip)
            }
        }

        // The last complete base, moved to this view, for what is not rendered yet.
        val standIn = s?.takeIf { it.surface.width == w && it.surface.height == h }
        fun standInRect(b: Base) = (zoom / b.zoom).let { k ->
            Rect((b.scrollX - viewport.scrollX) * zoom, (b.scrollY - viewport.scrollY) * zoom, w * k, h * k)
        }
        if (ready.isEmpty() && standIn != null) {
            // A zoom in progress: one scaled image, not a tile at a time.
            br.fillBackground(view, paper)
            br.drawRaster(standIn.surface, standInRect(standIn))
        } else {
            br.fillBackground(view, paper)
            paintPattern(br, doc.background, viewport)
            br.save()
            br.scale(zoom, zoom)
            br.translate(-viewport.scrollX, -viewport.scrollY)
            for ((key, tile) in ready) br.drawRaster(tile.surface, rectOf(key))
            for (item in doc.itemsIn(visible)) {
                if (item.isHighlighterInk() && !skip(item)) item.paint(br)
            }
            // Last, so the stand-in's own highlighters are not multiplied a second time.
            if (standIn != null) {
                for (key in absent) {
                    br.save()
                    br.clipRect(rectOf(key))
                    br.translate(viewport.scrollX, viewport.scrollY)
                    br.scale(1.0 / zoom, 1.0 / zoom)
                    br.drawRaster(standIn.surface, standInRect(standIn))
                    br.restore()
                }
            }
            br.restore()
        }
        val missing = absent.isNotEmpty()
        base.zoom = zoom
        base.scrollX = viewport.scrollX
        base.scrollY = viewport.scrollY
        r.drawRaster(base.surface, view)

        if (!missing) {
            edited.clear()
            editedAll = false
            // The new complete base: keep it as the stand-in, and compose the next into the old one.
            work = stable
            stable = base
            stableGeneration = generation
        }
        evict(zoom)
        return when {
            !missing -> Result.COMPLETE
            zooming -> Result.ZOOMING
            absent.all { it in pending } -> Result.WAITING
            else -> Result.RENDERING
        }
    }

    /** The surface the next base is composed in (never the stable one). */
    private fun baseSurface(w: Int, h: Int): Base {
        val b = work
        if (b != null && b.surface.width == w && b.surface.height == h && b !== stable) return b
        b?.takeIf { it !== stable }?.surface?.recycle()
        return Base(surfaces.create(w, h, 1.0), 0.0, 0.0, 0.0).also { work = it }
    }

    /** Ink for [key]'s square at its zoom, on a transparent tile. */
    private fun renderTile(key: Key, doc: InfiniteDocument, skip: (CanvasItem) -> Boolean): Tile =
        Tile(drawTile(key, tileItems(key, doc, skip)), frameNo).also { tiles[key] = it }

    /** What goes into [key]'s tile, read on the UI thread. */
    private fun tileItems(key: Key, doc: InfiniteDocument, skip: (CanvasItem) -> Boolean): List<CanvasItem> =
        doc.itemsIn(rectOf(key)).filter { !skip(it) && !it.isHighlighterInk() }

    /** [items] drawn for [key], on any thread. */
    private fun drawTile(key: Key, items: List<CanvasItem>): RasterSurface {
        val rect = rectOf(key)
        val surface = surfaces.create(TILE.toInt(), TILE.toInt(), 1.0)
        surface.fill(TRANSPARENT)
        val tr = surface.renderer()
        tr.scale(key.zoom, key.zoom)
        tr.translate(-rect.left, -rect.top)
        for (item in items) item.paint(tr)
        return surface
    }

    /** Render [key]'s tile on a cache thread, unless one is under way; it joins [tiles] when it lands. */
    private fun requestTile(key: Key, doc: InfiniteDocument, skip: (CanvasItem) -> Boolean) {
        if (key in pending) return
        val async = runAsync ?: return
        val post = postToMain ?: return
        val request = Request()
        pending[key] = request
        val items = tileItems(key, doc, skip)
        async {
            if (request.cancelled) return@async
            val surface = drawTile(key, items)
            post {
                if (pending[key] === request) {
                    pending.remove(key)
                    tiles.put(key, Tile(surface, frameNo))?.surface?.recycle()
                    onTileReady?.invoke()
                } else {
                    surface.recycle() // the ink there changed while it was drawn
                }
            }
        }
    }

    /** Over the budget, tiles of other zooms go first, then the least recently shown. */
    private fun evict(zoom: Double) {
        if (tiles.size <= maxTiles) return
        val old = tiles.entries.filter { it.value.used < frameNo }
            .sortedWith(compareBy({ it.key.zoom == zoom }, { it.value.used }))
        for (e in old.take(tiles.size - maxTiles)) {
            e.value.surface.recycle()
            tiles.remove(e.key)
        }
    }

    /** Let every surface go. */
    fun dispose() {
        invalidateAll()
        onTileReady = null
        work?.surface?.recycle()
        stable?.surface?.recycle()
        work = null
        stable = null
    }

    // --- ruling ---

    /** The ruling in device pixels: lines for LINES, both directions for GRID, intersections for DOTS. */
    private fun paintPattern(r: Renderer, bg: CanvasBackground, viewport: CanvasViewport) {
        if (bg.pattern == PagePattern.NONE) return
        val res = BackgroundPattern.resolve(bg, viewport)
        val w = viewport.widthPx.toDouble()
        val h = viewport.heightPx.toDouble()
        val color = bg.patternColor
        val sub = color.withAlpha((color.a * res.subdivisionAlpha).toInt().coerceIn(0, 255))
        fun lines(periodPx: Double, phaseX: Double, phaseY: Double, c: Rgba) {
            if (c.a == 0 || periodPx < 2.0) return
            val lw = BackgroundPattern.LINE_WIDTH_PX
            when (bg.pattern) {
                PagePattern.LINES, PagePattern.GRID -> {
                    var y = -phaseY
                    while (y < h) {
                        if (y > -lw) r.fillRect(Rect(0.0, y - lw / 2, w, lw), c)
                        y += periodPx
                    }
                    if (bg.pattern == PagePattern.GRID) {
                        var x = -phaseX
                        while (x < w) {
                            if (x > -lw) r.fillRect(Rect(x - lw / 2, 0.0, lw, h), c)
                            x += periodPx
                        }
                    }
                }
                PagePattern.DOTS -> {
                    val radius = BackgroundPattern.DOT_RADIUS_PX
                    var y = -phaseY
                    while (y < h + radius) {
                        var x = -phaseX
                        while (x < w + radius) {
                            r.fillCircle(Pt(x, y), radius, c)
                            x += periodPx
                        }
                        y += periodPx
                    }
                }
                PagePattern.NONE -> Unit
            }
        }
        // The finer level fades in under the base one as it becomes legible.
        if (res.subdivisionAlpha > 0.0) lines(res.subPeriodPx, res.subPhaseXPx, res.subPhaseYPx, sub)
        lines(res.periodPx, res.phaseXPx, res.phaseYPx, color)
    }

    companion object {
        /** Tile edge in device pixels. */
        const val TILE = 512.0

        /** Missing tiles rendered per frame until this is spent; the rest come next frame. */
        const val DEFAULT_BUDGET_NANOS = 12_000_000L

        private val TRANSPARENT = Rgba(0, 0, 0, 0)

        private fun rectOf(k: Key): Rect {
            val cs = TILE / k.zoom
            return Rect(k.ix * cs, k.iy * cs, cs, cs)
        }
    }
}
