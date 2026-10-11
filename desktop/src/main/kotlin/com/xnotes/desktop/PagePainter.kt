package com.xnotes.desktop

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.insets
import com.xnotes.core.model.paintPagePattern
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.resolvedTemplate
import com.xnotes.core.model.resolvedPatternColor
import com.xnotes.core.model.resolvedSpacing
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowPainter
import java.awt.Graphics2D

/** Renders one page straight into Java2D with the shared model paint commands (tests, thumbnails, export). */
internal object PagePainter {
    fun paint(graphics: Graphics2D, document: Document, pageIndex: Int, frame: FlowFrame?, darkPaper: Boolean) {
        val page = document.pages[pageIndex]
        val inset = page.insets(document)
        val cover = Rect(-inset.left, -inset.top,
            page.width + inset.left + inset.right, page.height + inset.top + inset.bottom)
        val visible = graphics.clipBounds?.let {
            Rect(it.x.toDouble(), it.y.toDouble(), it.width.toDouble(), it.height.toDouble())
        } ?: cover
        DesktopRenderer(graphics.create() as Graphics2D).use { painter ->
            painter.clipRect(cover)
            painter.fillBackground(cover, page.resolvedPageColor(document,
                if (darkPaper) Rgba(22, 22, 22, 255) else Rgba(255, 255, 255, 255))!!)
            // The built-in templates are the classic rulings.
            PagePattern.fromId(page.resolvedTemplate(document))?.let { pattern ->
                paintPagePattern(painter, pattern, page.resolvedPatternColor(document),
                    page.resolvedSpacing(document) ?: PageStyle.DEFAULT_SPACING, cover, visible)
            }
            if (page.pdfPage != null && document.pdfFile != null) {
                // The PDF source will be painted by the next platform layer step.
                painter.fillRect(Rect(0.0, 0.0, page.width, 30.0), Rgba(220, 225, 235, 255))
                painter.drawTextRun("Фон PDF пока недоступен", 8.0, 21.0,
                    com.xnotes.core.pal.FontSpec(13.0), Rgba(60, 65, 75, 255))
            }
            for (item in page.items) if (item.paintBounds().intersects(visible)) item.paint(painter)
            if (frame != null) FlowPainter.paintPage(painter, frame, pageIndex,
                visible)
        }
    }
}
