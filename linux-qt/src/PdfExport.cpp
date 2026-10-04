#include "PdfExport.h"

#include "CoreHost.h"

#include <QFile>
#include <QPageLayout>
#include <QPageSize>
#include <QPainter>
#include <QPdfWriter>
#include <QSaveFile>

namespace {

/* PDF background pixels per note pixel: 2 × 150 dpi = 300 dpi. */
constexpr double kPdfScale = 2.0;

}  // namespace

QString writePdf(xn_note* note, const QString& path, xn_rgba paper, const std::function<void(int, int)>& progress) {
    // A host of its own, whose PDF backgrounds render synchronously and exactly (the canvas's
    // render them in the background and draws what it has meanwhile).
    xn::CoreHost exportHost;
    const xn_host* host = exportHost.host();
    const int pages = xn_note_page_count(note);
    const int dpi = xn_note_dpi(note);
    if (pages <= 0 || dpi <= 0) return QStringLiteral("В заметке нет страниц");

    // Written beside the target and moved over it only once complete.
    QSaveFile file(path);
    if (!file.open(QIODevice::WriteOnly)) return QStringLiteral("Не удалось открыть %1 для записи").arg(path);
    {
        QPdfWriter writer(&file);
        writer.setCreator(QStringLiteral("xnotes"));
        writer.setResolution(dpi);  // one device unit = one note pixel
        QPainter painter;
        for (int i = 0; i < pages; i++) {
            double box[4];
            if (!xn_note_page_box(note, i, box)) continue;
            const QPageSize size(QSizeF(box[2] * 72.0 / dpi, box[3] * 72.0 / dpi), QPageSize::Point, QString(),
                                 QPageSize::ExactMatch);
            writer.setPageLayout(QPageLayout(size, QPageLayout::Portrait, QMarginsF(0, 0, 0, 0)));
            if (i == 0) {
                if (!painter.begin(&writer)) return QStringLiteral("Не удалось начать PDF");
            } else {
                writer.newPage();
            }
            painter.save();
            painter.setRenderHints(QPainter::Antialiasing | QPainter::SmoothPixmapTransform | QPainter::TextAntialiasing);
            painter.translate(-box[0], -box[1]);
            xn::PainterTarget target(&painter);
            xn_note_paint_page(note, i, &target, host, paper, kPdfScale);
            painter.restore();
            if (progress) progress(i + 1, pages);
        }
        painter.end();
    }
    if (!file.commit()) return QStringLiteral("Не удалось записать %1").arg(path);
    return {};
}
