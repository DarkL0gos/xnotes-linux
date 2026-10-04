@file:OptIn(ExperimentalForeignApi::class)

package com.xnotes.capi

import com.xnotes.canvas.ChromePalette
import com.xnotes.capi.c.xn_font
import com.xnotes.capi.c.xn_host
import com.xnotes.capi.c.xn_palette
import com.xnotes.capi.c.xn_pen
import com.xnotes.capi.c.xn_renderer_vt
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.platform.IdCounter
import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.Runnable
import com.xnotes.core.platform.withLock
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import com.xnotes.core.platform.monotonicNanos
import com.xnotes.core.platform.pathString
import com.xnotes.input.FrameCallback
import com.xnotes.input.UiScheduler
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.cstr
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/*
 * The core's platform interfaces (Renderer, RasterSurface, TextMeasurer, ImageCodec, UiScheduler)
 * implemented over the host's C callback tables in xnotes.h.
 */

internal fun Rgba.packed(): UInt = (r.toUInt() shl 24) or ((g and 0xFF).toUInt() shl 16) or ((b and 0xFF).toUInt() shl 8) or (a and 0xFF).toUInt()

internal fun unpack(c: UInt): Rgba = Rgba((c shr 24).toInt() and 0xFF, (c shr 16).toInt() and 0xFF, (c shr 8).toInt() and 0xFF, c.toInt() and 0xFF)

private fun BlendMode.code(): Int = when (this) {
    BlendMode.SRC_OVER -> 0
    BlendMode.MULTIPLY -> 1
    BlendMode.SCREEN -> 2
}

/** Run [block] with [text] as a UTF-16 pointer and length (null for an empty string). */
internal inline fun <T> withUtf16(text: String, block: (CPointer<UShortVar>?, Int) -> T): T {
    if (text.isEmpty()) return block(null, 0)
    val chars = text.toCharArray()
    return chars.usePinned { block(it.addressOf(0).reinterpret(), chars.size) }
}

/** [font] as an xn_font in [scope]; the face's UTF-16 buffer is pinned for as long as the scope lives. */
private fun MemScope.cFont(font: FontSpec): CPointer<xn_font> {
    val f = alloc<xn_font>()
    f.point_size = font.pointSize
    f.bold = if (font.bold) 1 else 0
    f.italic = if (font.italic) 1 else 0
    val face = font.face.id
    f.face_len = face.length
    if (face.isNotEmpty()) {
        val buf = allocArray<UShortVar>(face.length)
        for (i in face.indices) buf[i] = face[i].code.toUShort()
        f.face = buf
    }
    return f.ptr
}

private fun MemScope.cPen(pen: Pen): CPointer<xn_pen> {
    val p = alloc<xn_pen>()
    p.color = pen.color.packed()
    p.width = pen.width
    p.cosmetic = if (pen.cosmetic) 1 else 0
    p.dashed = if (pen.dashed) 1 else 0
    p.dash_on = pen.dashOn
    p.dash_gap = pen.dashGap
    p.dash_phase = pen.dashPhase
    return p.ptr
}

/** A Renderer drawing through the host's vtable into the host target [r]. */
internal class CRenderer(private val vt: xn_renderer_vt, internal val r: COpaquePointer?) : Renderer {
    override fun save() = vt.save!!.invoke(r)
    override fun restore() = vt.restore!!.invoke(r)
    override fun rotate(degrees: Double) = vt.rotate!!.invoke(r, degrees)
    override fun saveLayerAlpha(bounds: Rect, alpha: Double) = saveLayerBlended(bounds, alpha, BlendMode.SRC_OVER)
    override fun saveLayerBlended(bounds: Rect, alpha: Double, blend: BlendMode) =
        vt.save_layer!!.invoke(r, bounds.x, bounds.y, bounds.w, bounds.h, alpha, blend.code())

    override fun translate(dx: Double, dy: Double) = vt.translate!!.invoke(r, dx, dy)
    override fun scale(sx: Double, sy: Double) = vt.scale!!.invoke(r, sx, sy)
    override fun clipRect(rect: Rect) = vt.clip_rect!!.invoke(r, rect.x, rect.y, rect.w, rect.h)
    override fun clear() = vt.clear!!.invoke(r)

    override fun fillBackground(rect: Rect, color: Rgba) = fillRect(rect, color)
    override fun fillRect(rect: Rect, color: Rgba) = vt.fill_rect!!.invoke(r, rect.x, rect.y, rect.w, rect.h, color.packed())

    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) {
        if (points.isEmpty()) return
        val xy = DoubleArray(points.size * 2)
        for ((i, p) in points.withIndex()) {
            xy[2 * i] = p.x
            xy[2 * i + 1] = p.y
        }
        xy.usePinned { vt.fill_polygon!!.invoke(r, it.addressOf(0), points.size, color.packed(), if (rule == FillRule.EVEN_ODD) 1 else 0) }
    }

    override fun fillCircle(center: Pt, radius: Double, color: Rgba) = fillEllipse(center, radius, radius, color)
    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) =
        vt.fill_ellipse!!.invoke(r, center.x, center.y, rx, ry, color.packed())

    override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {
        if (count <= 0) return
        centers.usePinned { c ->
            radii.usePinned { rd -> vt.fill_disk_ribbon!!.invoke(r, c.addressOf(0), rd.addressOf(0), from, count, color.packed()) }
        }
    }

    override fun strokeRect(rect: Rect, pen: Pen) = memScoped {
        vt.stroke_rect!!.invoke(r, rect.x, rect.y, rect.w, rect.h, cPen(pen))
    }

    override fun strokePolyline(points: List<Pt>, pen: Pen) = strokePath(points, false, pen)
    override fun strokePolygon(points: List<Pt>, pen: Pen) = strokePath(points, true, pen)

    private fun strokePath(points: List<Pt>, closed: Boolean, pen: Pen) {
        if (points.size < 2) return
        val xy = DoubleArray(points.size * 2)
        for ((i, p) in points.withIndex()) {
            xy[2 * i] = p.x
            xy[2 * i + 1] = p.y
        }
        memScoped {
            val cp = cPen(pen)
            xy.usePinned { vt.stroke_polyline!!.invoke(r, it.addressOf(0), points.size, if (closed) 1 else 0, cp) }
        }
    }

    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) = memScoped {
        vt.stroke_ellipse!!.invoke(r, center.x, center.y, rx, ry, cPen(pen))
    }

    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) =
        drawRasterBlended(raster, dest, 1.0, BlendMode.SRC_OVER, src)

    override fun drawRasterBlended(raster: RasterSurface, dest: Rect, alpha: Double, blend: BlendMode, src: Rect?) {
        val surface = (raster as? CSurface)?.handle ?: return
        memScoped {
            val d = allocArray<DoubleVar>(4)
            d[0] = dest.x; d[1] = dest.y; d[2] = dest.w; d[3] = dest.h
            val s = src?.let {
                allocArray<DoubleVar>(4).also { a -> a[0] = it.x; a[1] = it.y; a[2] = it.w; a[3] = it.h }
            }
            vt.draw_surface!!.invoke(r, surface, d, s, alpha, blend.code())
        }
    }

    override fun drawImage(image: ImageData, dest: Rect, orientation: Int, angle: Double) = memScoped {
        vt.draw_image!!.invoke(r, image.file.pathString.cstr.ptr, dest.x, dest.y, dest.w, dest.h, orientation, angle)
    }

    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) = memScoped {
        val f = cFont(font)
        withUtf16(text) { p, n ->
            vt.draw_text!!.invoke(r, p, n, rect.x, rect.y, rect.w, rect.h, f, color.packed(),
                if (flags.wordWrap) 1 else 0, if (flags.alignLeft) 1 else 0, if (flags.alignTop) 1 else 0)
        }
    }

    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) = memScoped {
        val f = cFont(font)
        withUtf16(text) { p, n -> vt.draw_text_run!!.invoke(r, p, n, x, baseline, f, color.packed()) }
    }
}

/** A page cache surface the host owns (e.g. a QImage). */
@OptIn(ExperimentalNativeApi::class)
internal class CSurface(
    private val host: xn_host,
    private val factory: CSurfaceFactory,
    val handle: COpaquePointer?,
    override val width: Int,
    override val height: Int,
    override val devicePixelRatio: Double,
) : RasterSurface {
    /**
     * This surface's own number. The host's handle is an address, which the host may hand out again
     * once the surface is released: a cleaner keyed by it would release whatever lives there by then.
     */
    private val id = factory.nextId()

    init {
        factory.register(id, handle)
    }

    /** Queues the host surface for release once this object is collected; see [CSurfaceFactory]. */
    @Suppress("unused")
    private val cleaner = createCleaner(factory to id) { (f, id) -> f.collected(id) }

    override fun fill(color: Rgba) = host.surface_fill!!.invoke(host.ctx, handle, color.packed())
    override fun renderer(): Renderer = CRenderer(host.renderer!!.pointed, host.surface_renderer!!.invoke(host.ctx, handle))
    override fun recycle() = factory.release(id)
}

/**
 * Hands out host surfaces and gets every one back. The core mostly just drops caches (on Android the
 * GC frees a Bitmap), so a collected surface is queued by its cleaner and released on the UI thread
 * at the next [drain]; an explicit recycle releases at once, and [dispose] releases what is left.
 */
internal class CSurfaceFactory(private val host: xn_host) : SurfaceFactory {
    private val lock = Lock()
    private val ids = IdCounter()

    /** Surface number → host handle, for every surface not yet released. */
    private val live = HashMap<Long, Long>()
    private val collected = ArrayList<Long>()
    private var disposed = false

    override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double): RasterSurface {
        val handle = host.surface_create!!.invoke(host.ctx, widthPx, heightPx, devicePixelRatio)
        return CSurface(host, this, handle, widthPx, heightPx, devicePixelRatio)
    }

    fun nextId(): Long = ids.next()

    fun register(id: Long, handle: COpaquePointer?) = withLock(lock) { live[id] = handle.toLong() }

    /** From a cleaner, on whatever thread the GC runs it: only queue. */
    fun collected(id: Long) = withLock(lock) {
        val raw = live.remove(id)
        if (!disposed && raw != null) collected.add(raw)
    }

    /** UI thread: release a surface the core is done with. */
    fun release(id: Long) {
        val raw = withLock(lock) { live.remove(id) } ?: return
        host.surface_release!!.invoke(host.ctx, raw.toCPointer())
    }

    /** UI thread: release what the GC collected since the last call. */
    fun drain() {
        val due = withLock(lock) { collected.toList().also { collected.clear() } }
        for (raw in due) host.surface_release!!.invoke(host.ctx, raw.toCPointer())
    }

    /** UI thread: release everything still out (the editor is going away). */
    fun dispose() {
        val due = withLock(lock) {
            disposed = true
            (live.values + collected).also {
                live.clear()
                collected.clear()
            }
        }
        for (raw in due) host.surface_release!!.invoke(host.ctx, raw.toCPointer())
    }
}

internal class CTextMeasurer(private val host: xn_host) : TextMeasurer {
    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect = memScoped {
        val out = allocArray<DoubleVar>(4)
        val f = cFont(font)
        withUtf16(text) { p, n -> host.text_measure!!.invoke(host.ctx, p, n, f, wrapWidth, if (flags.wordWrap) 1 else 0, out) }
        Rect(out[0], out[1], out[2], out[3])
    }

    override fun lineHeight(font: FontSpec): Double = metrics(font).height

    override fun metrics(font: FontSpec): LineMetrics = memScoped {
        val ascent = alloc<DoubleVar>()
        val descent = alloc<DoubleVar>()
        host.text_metrics!!.invoke(host.ctx, cFont(font), ascent.ptr, descent.ptr)
        LineMetrics(ascent.value, descent.value)
    }

    override fun advances(text: String, font: FontSpec): DoubleArray {
        if (text.isEmpty()) return DoubleArray(0)
        val out = DoubleArray(text.length)
        memScoped {
            val f = cFont(font)
            out.usePinned { o -> withUtf16(text) { p, n -> host.text_advances!!.invoke(host.ctx, p, n, f, o.addressOf(0)) } }
        }
        return out
    }
}

internal class CImageCodec(private val host: xn_host) : ImageCodec {
    override fun probeFile(path: String): ImageSize? = memScoped {
        val w = alloc<kotlinx.cinterop.IntVar>()
        val h = alloc<kotlinx.cinterop.IntVar>()
        if (host.image_probe!!.invoke(host.ctx, path.cstr.ptr, w.ptr, h.ptr) == 0) null else ImageSize(w.value, h.value)
    }
}

/**
 * Timers through the host: each posting gets a task id the host hands back to xn_editor_run_task
 * when it is due. Like Android's Handler, a task posted twice runs twice unless removed.
 */
internal class CScheduler(private val host: xn_host) : UiScheduler {
    private var nextId = 1uL
    private val delayed = HashMap<ULong, Runnable>()
    private val frames = HashMap<ULong, FrameCallback>()

    override fun postDelayed(task: Runnable, delayMs: Long) {
        val id = nextId++
        delayed[id] = task
        host.post_delayed!!.invoke(host.ctx, id, delayMs)
    }

    override fun removeCallbacks(task: Runnable) {
        val ids = delayed.filterValues { it === task }.keys.toList()
        for (id in ids) {
            delayed.remove(id)
            host.cancel_task!!.invoke(host.ctx, id)
        }
    }

    override fun postFrameCallback(callback: FrameCallback) {
        val id = nextId++
        frames[id] = callback
        host.post_frame!!.invoke(host.ctx, id)
    }

    /** Run the task the host says is due; unknown (cancelled) ids are ignored. */
    fun run(id: ULong) {
        delayed.remove(id)?.let {
            it.run()
            return
        }
        frames.remove(id)?.doFrame(monotonicNanos())
    }

    fun cancelAll() {
        for (id in delayed.keys + frames.keys) host.cancel_task!!.invoke(host.ctx, id)
        delayed.clear()
        frames.clear()
    }
}

internal class CPalette(p: xn_palette) : ChromePalette {
    override val bg = unpack(p.bg)
    override val paper = unpack(p.paper)
    override val paperBorder = unpack(p.paper_border)
    override val accent = unpack(p.accent)
    override val textDim = unpack(p.text_dim)
    override val panel = unpack(p.panel)
    override val border = unpack(p.border)
    override val text = unpack(p.text)
    override val menuBg = unpack(p.menu_bg)
    override val isDark = p.is_dark != 0
}

@Suppress("unused")
private val keepFontFace = FontFace.SANS
