// PDF pages for the canvas, rendered in the background.
//
// Rendering the visible part of a page at a new zoom takes QtPdf 70-90 ms at 3456x2304, far too
// long for the UI thread, so a request that is not cached yet is answered at once with the best
// render there is (an earlier one, scaled) and the exact one is rendered on a worker thread. When
// it lands, ready() runs and the canvas rebuilds its page caches from it.
#pragma once

#include <QHash>
#include <QImage>
#include <QList>
#include <QMutex>
#include <QObject>
#include <QRect>
#include <QSize>
#include <QString>

#include <functional>
#include <list>
#include <memory>

class QPainter;
class QPdfDocument;
class QThreadPool;

class PdfPages {
public:
    /* With ready unset every render is synchronous (export); otherwise it runs on the UI thread. */
    explicit PdfPages(std::function<void()> ready = {});
    ~PdfPages();

    /*
     * Draw region (rx, ry, rw, rh) of page rendered at full size into target (logical rect, the
     * painter's current transform). Sharp when rendered before; else as well as possible now, and
     * sharp once ready() has run. Returns false when it drew a stand-in.
     */
    bool draw(QPainter* p, const QString& path, int page, QSize full, QRect region, const QRectF& target);

    /* Renders waiting or running on the worker. */
    int pending() const;

private:
    struct Render {
        QString path;
        int page;
        QSize full;
        QRect region;  // in full's pixels
        QImage image;
    };

    std::function<void()> ready_;
    std::list<Render> renders_;  // most recent first
    qint64 cachedBytes_ = 0;
    std::unique_ptr<QThreadPool> pool_;
    std::unique_ptr<QObject> receiver_;  // delivers finished renders on the UI thread

    /* Shared with the worker: what is queued, and the size each page is wanted at now. */
    mutable QMutex mutex_;
    QList<QString> queued_;
    QHash<QString, QSize> wanted_;

    static QImage render(QPdfDocument* doc, int page, QSize full, QRect region);
    QPdfDocument* document(const QString& path);
    QHash<QString, QPdfDocument*> documents_;

    const Render* exact(const QString& path, int page, QSize full, QRect region) const;
    const Render* best(const QString& path, int page, QSize full, QRect region) const;
    void store(Render r);
    void request(const QString& path, int page, QSize full, QRect region);
};
