#include "PdfPages.h"

#include <QHash>
#include <QPainter>
#include <QPdfDocument>
#include <QPdfDocumentRenderOptions>
#include <QPointer>
#include <QThreadPool>

#include <cmath>

namespace {

/* Rendered pixels kept for reuse. */
constexpr qint64 kCacheBytes = 384LL * 1024 * 1024;

/* A first look at a page is rendered at most this wide, quickly, while the sharp one is made. */
constexpr int kPreviewWidth = 1000;

/* Renders cover a little more than asked, so small scrolls stay inside them. */
constexpr double kRegionSlack = 0.25;

QString pageKey(const QString& path, int page) { return path + QLatin1Char('#') + QString::number(page); }

QString renderKey(const QString& path, int page, QSize full, QRect region) {
    return pageKey(path, page) + QStringLiteral("@%1x%2:%3,%4,%5,%6")
        .arg(full.width()).arg(full.height()).arg(region.x()).arg(region.y()).arg(region.width()).arg(region.height());
}

/* region grown by kRegionSlack on each side, inside the page. */
QRect withSlack(QRect region, QSize full) {
    const int dx = int(region.width() * kRegionSlack), dy = int(region.height() * kRegionSlack);
    return region.adjusted(-dx, -dy, dx, dy) & QRect(QPoint(0, 0), full);
}

}  // namespace

PdfPages::PdfPages(std::function<void()> ready) : ready_(std::move(ready)) {
    if (!ready_) return;
    pool_ = std::make_unique<QThreadPool>();
    pool_->setMaxThreadCount(1);
    receiver_ = std::make_unique<QObject>();
}

PdfPages::~PdfPages() {
    if (pool_) {
        {
            QMutexLocker lock(&mutex_);
            wanted_.clear();  // queued renders find themselves unwanted and stop
        }
        pool_->waitForDone();
    }
    qDeleteAll(documents_);
}

int PdfPages::pending() const {
    QMutexLocker lock(&mutex_);
    return int(queued_.size());
}

QPdfDocument* PdfPages::document(const QString& path) {
    if (QPdfDocument* doc = documents_.value(path)) return doc;
    auto* doc = new QPdfDocument;
    if (doc->load(path) != QPdfDocument::Error::None) {
        delete doc;
        return nullptr;
    }
    documents_.insert(path, doc);
    return doc;
}

QImage PdfPages::render(QPdfDocument* doc, int page, QSize full, QRect region) {
    QPdfDocumentRenderOptions options;
    options.setScaledSize(full);
    options.setScaledClipRect(region);
    options.setRenderFlags(QPdfDocumentRenderOptions::RenderFlag::Annotations);
    QImage image = doc->render(page, region.size(), options);
    if (image.isNull()) return image;
    // A PDF page is white paper; QtPdf leaves what it does not paint transparent.
    QImage paper(image.size(), QImage::Format_RGB32);
    paper.fill(Qt::white);
    QPainter(&paper).drawImage(0, 0, image);
    return paper;
}

const PdfPages::Render* PdfPages::exact(const QString& path, int page, QSize full, QRect region) const {
    for (const Render& r : renders_)
        if (r.page == page && r.full == full && r.path == path && r.region.contains(region)) return &r;
    return nullptr;
}

/* The sharpest render of the page that covers region once scaled to full. */
const PdfPages::Render* PdfPages::best(const QString& path, int page, QSize full, QRect region) const {
    const Render* found = nullptr;
    for (const Render& r : renders_) {
        if (r.page != page || r.path != path) continue;
        const double s = double(r.full.width()) / full.width();
        const QRectF needed(region.x() * s, region.y() * s, region.width() * s, region.height() * s);
        if (!QRectF(r.region).adjusted(-1, -1, 1, 1).contains(needed)) continue;
        if (!found || r.full.width() > found->full.width()) found = &r;
    }
    return found;
}

void PdfPages::store(Render r) {
    cachedBytes_ += r.image.sizeInBytes();
    renders_.push_front(std::move(r));
    while (cachedBytes_ > kCacheBytes && renders_.size() > 1) {
        cachedBytes_ -= renders_.back().image.sizeInBytes();
        renders_.pop_back();
    }
}

bool PdfPages::draw(QPainter* p, const QString& path, int page, QSize full, QRect region, const QRectF& target) {
    auto blit = [&](const Render& r) {
        const double s = double(r.full.width()) / full.width();
        const QRectF source(region.x() * s - r.region.x(), region.y() * s - r.region.y(), region.width() * s, region.height() * s);
        p->save();
        p->setRenderHint(QPainter::SmoothPixmapTransform, true);
        p->drawImage(target, r.image, source);
        p->restore();
    };
    if (const Render* r = exact(path, page, full, region)) {
        // Touch it: most recent first.
        for (auto it = renders_.begin(); it != renders_.end(); ++it)
            if (&*it == r) {
                renders_.splice(renders_.begin(), renders_, it);
                break;
            }
        blit(renders_.front());
        return true;
    }

    QPdfDocument* doc = document(path);
    if (!doc || page < 0 || page >= doc->pageCount()) return true;  // nothing better will come
    if (!ready_) {  // synchronous: exactly what was asked
        Render r{path, page, full, region, render(doc, page, full, region)};
        if (r.image.isNull()) return true;
        blit(r);
        store(std::move(r));
        return true;
    }

    const Render* fallback = best(path, page, full, region);
    if (!fallback) {
        // Never seen at any size: a quick whole-page preview now.
        const double s = std::min(1.0, double(kPreviewWidth) / full.width());
        const QSize small = (QSizeF(full) * s).toSize().expandedTo(QSize(1, 1));
        Render preview{path, page, small, QRect(QPoint(0, 0), small), render(doc, page, small, QRect(QPoint(0, 0), small))};
        if (preview.image.isNull()) return true;
        store(std::move(preview));
        fallback = &renders_.front();
    }
    blit(*fallback);
    request(path, page, full, withSlack(region, full));
    return false;
}

/* Render on the worker; skipped when, by the time it runs, the page is wanted at another size. */
void PdfPages::request(const QString& path, int page, QSize full, QRect region) {
    const QString key = renderKey(path, page, full, region);
    {
        QMutexLocker lock(&mutex_);
        wanted_.insert(pageKey(path, page), full);
        if (queued_.contains(key)) return;
        queued_.append(key);
    }
    QPointer<QObject> receiver = receiver_.get();
    pool_->start([this, receiver, key, path, page, full, region] {
        {
            QMutexLocker lock(&mutex_);
            if (wanted_.value(pageKey(path, page)) != full) {
                queued_.removeAll(key);
                return;
            }
        }
        // The worker's own documents: QtPdf serializes PDFium, but a QPdfDocument is not shared.
        thread_local QHash<QString, std::shared_ptr<QPdfDocument>> docs;
        std::shared_ptr<QPdfDocument>& doc = docs[path];
        if (!doc) {
            doc = std::make_shared<QPdfDocument>();
            if (doc->load(path) != QPdfDocument::Error::None) doc.reset();
        }
        QImage image = doc ? render(doc.get(), page, full, region) : QImage();
        QMetaObject::invokeMethod(receiver, [this, receiver, key, path, page, full, region, image] {
            if (!receiver) return;
            {
                QMutexLocker lock(&mutex_);
                queued_.removeAll(key);
            }
            if (image.isNull()) return;
            store(Render{path, page, full, region, image});
            ready_();
        }, Qt::QueuedConnection);
    });
}
