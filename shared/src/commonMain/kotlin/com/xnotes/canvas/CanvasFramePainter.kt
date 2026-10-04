package com.xnotes.canvas

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer

/**
 * One frame of the paged canvas, host-independent: paper, page backgrounds, cached ink, the sharp
 * viewport past the resolution cap, and the live highlighter composite. Hosts paint their own
 * chrome (overlay, scrollbars, badges) on top, then call [finish].
 *
 * Page caches are built on the state's cache thread and blitted when ready, so a frame never waits
 * on rasterization.
 */
object CanvasFramePainter {

    /** What the host must do after a frame. */
    class Frame internal constructor(
        internal val cachedPages: Set<Page>,
        /**
         * True when the sharp viewport should be re-rendered once the view has been still for a
         * moment: the host (re)arms its settle timer and then calls [CanvasState.requestSharpViewport].
         * False means cancel any pending settle.
         */
        val sharpSettleNeeded: Boolean,
    )

    /** Paint the document (everything below the interaction overlay) into [r], in viewport pixels. */
    fun paint(r: Renderer, st: CanvasState): Frame {
        // Past the resolution cap a sharp render of the view may cover all of it: it is opaque
        // (background, paper, page backgrounds), so nothing under it needs drawing. Scaling the
        // soft page caches up only to hide them was most of a frame's cost on a large screen.
        val pastCap = st.isPastResolutionCap()
        val sharp = if (pastCap) st.sharpViewportBlit() else null
        val covered = sharp != null && sharp.dx <= 0.0 && sharp.dy <= 0.0 &&
            sharp.dx + sharp.base.width * sharp.scale >= st.viewportW &&
            sharp.dy + sharp.base.height * sharp.scale >= st.viewportH

        if (!covered) r.fillBackground(Rect(0.0, 0.0, st.viewportW.toDouble(), st.viewportH.toDouble()), st.palette.bg)

        val origin = st.origin()
        r.save()
        r.translate(origin.x, origin.y)
        r.scale(st.zoom, st.zoom)

        val visible = st.visibleContentRect()
        val border = Pen(st.palette.paperBorder, 1.0, cosmetic = true)
        val cachedPages = HashSet<Page>()
        // Paginated mode shows only the current row; neighbours never draw.
        val drawable = st.drawablePageRange()

        for (i in st.document.pages.indices) {
            if (i !in drawable) continue
            val pr = st.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = st.document.pages[i]
            cachedPages.add(page)

            if (covered) {
                // Hidden under the sharp render; still keep the caches coming for when it moves.
                st.backgroundForOrSchedule(page)
                st.cacheForOrSchedule(page)
                continue
            }
            r.fillRect(pr, st.paperColor(page))
            if (st.pageBorders) r.strokeRect(pr, border)
            st.backgroundForOrSchedule(page)?.let { blitPageSurface(r, st, page, pr, it.surface) }
            // A live caret session lifts the flow out of the ink cache; paint it
            // immediate-mode here (under the ink, over the background) so every
            // keystroke shows without waiting for a cache rebuild.
            if (st.flowLifted) {
                r.withSave {
                    r.clipRect(pr)
                    r.translate(pr.left, pr.top)
                    st.applyPageTransform(r, page)
                    st.paintFlow?.invoke(page, r, st.displayRectToPage(page, visible.translate(-pr.left, -pr.top)))
                }
            }
            st.cacheForOrSchedule(page)?.let { blitPageSurface(r, st, page, pr, it.surface) }
        }
        r.restore()

        // Prefetch the N pages before and after the visible band (N = visible count) into the
        // page cache so scrolling lands on already-rasterized pages. Scheduled after the on-screen
        // pages above so visible content always builds first on the single cache thread, nearest
        // pages first, and skipped during a pinch so the settle-rebuild isn't starved.
        if (!st.zoomingInProgress) {
            st.visiblePageRange()?.let { vis ->
                val n = vis.last - vis.first + 1
                for (d in 1..n) for (j in intArrayOf(vis.last + d, vis.first - d)) {
                    val page = st.document.pages.getOrNull(j) ?: continue
                    if (!cachedPages.add(page)) continue
                    st.backgroundForOrSchedule(page)
                    st.cacheForOrSchedule(page)
                }
            }
        }

        // Past the resolution cap, cover the (soft, capped) page caches with a razor-sharp,
        // full-resolution render of just the viewport. While panning we slide the previous sharp
        // render with the content (so short pans stay sharp) and let the soft cache show only in
        // the strip panning into view; once the view settles we re-render the sharp viewport for
        // the new area. A zoom change drops back to the soft caches until the settle re-render.
        val sharpSettle: Boolean
        if (pastCap) {
            val blit = sharp
            if (blit != null) {
                val dw = blit.base.width * blit.scale
                val dh = blit.base.height * blit.scale
                r.drawRaster(blit.base, Rect(blit.dx, blit.dy, dw, dh))
                // A lifted flow is absent from the sharp ink layer too: draw it live
                // between the sharp base and sharp ink so the stack order holds.
                // Clipped to the blit's own rect: outside it the page loop's live pass
                // already painted the flow, and a second (translucent) chip on top
                // reads as a lighter band while the slid sharp frame settles.
                if (st.flowLifted) {
                    r.withSave {
                        r.clipRect(Rect(blit.dx, blit.dy, dw, dh))
                        r.translate(origin.x, origin.y)
                        r.scale(st.zoom, st.zoom)
                        for (i in st.document.pages.indices) {
                            if (i !in drawable) continue
                            val pr = st.pageRects.getOrNull(i) ?: continue
                            if (!pr.intersects(visible)) continue
                            val page = st.document.pages[i]
                            r.withSave {
                                r.clipRect(pr)
                                r.translate(pr.left, pr.top)
                                st.applyPageTransform(r, page)
                                st.paintFlow?.invoke(page, r, st.displayRectToPage(page, visible.translate(-pr.left, -pr.top)))
                            }
                        }
                    }
                }
                r.drawRaster(blit.ink, Rect(blit.dx, blit.dy, dw, dh))
                // Off the exact rendered view (panned or zoomed): re-render for where we settle.
                sharpSettle = !(blit.scale == 1.0 && blit.dx == 0.0 && blit.dy == 0.0)
            } else {
                sharpSettle = true
            }
        } else {
            sharpSettle = false
            st.clearSharpViewport()
        }

        // Highlighters composite here, over the finished page (paper + background + ink), so
        // their MULTIPLY blend darkens against everything beneath instead of washing it out —
        // matching the live preview. They're few and drawn at screen resolution (so crisp at
        // any zoom); pen/calligraphy ink stays cached underneath.
        r.withSave {
            r.translate(origin.x, origin.y)
            r.scale(st.zoom, st.zoom)
            for (i in st.document.pages.indices) {
                if (i !in drawable) continue
                val pr = st.pageRects.getOrNull(i) ?: continue
                if (!pr.intersects(visible)) continue
                val page = st.document.pages[i]
                // Page-space visible rect, so off-band highlighters on a tall page skip the composite.
                val visLocal = st.displayRectToPage(page, visible.translate(-pr.left, -pr.top))
                r.withSave {
                    r.clipRect(pr)
                    r.translate(pr.left, pr.top)
                    st.applyPageTransform(r, page)
                    for (item in page.items) {
                        if (item is Stroke && item.isHighlighterInk() && !st.isLiftedItem(item) &&
                            item.bounds().intersects(visLocal)
                        ) {
                            // Blit the pre-rendered opaque ribbon at the ink's alpha and blend,
                            // instead of re-tessellating the ribbon every frame.
                            val hc = st.highlighterCacheFor(item, page)
                            r.drawRasterBlended(hc.surface, hc.cover, item.renderColor.a / 255.0, item.blendMode)
                        }
                    }
                }
            }
        }
        return Frame(cachedPages, sharpSettle)
    }

    /** End the frame once the host's own layers are drawn: release caches of pages now out of view. */
    fun finish(st: CanvasState, frame: Frame) = st.dropCachesExcept(frame.cachedPages)

    /** Blit a page-space cache surface into the page's display rect, rotated per the view. The
     *  surface covers the page's whole footprint (margins included), so it blits at [CanvasState.footprint]. */
    private fun blitPageSurface(r: Renderer, st: CanvasState, page: Page, pr: Rect, surface: RasterSurface) {
        if (st.rotationDeg == 0) {
            r.drawRaster(surface, pr)
            return
        }
        r.withSave {
            r.translate(pr.left, pr.top)
            st.applyPageTransform(r, page)
            r.drawRaster(surface, st.footprint(page))
        }
    }
}
