#include "CoreHost.h"

#include "PdfPages.h"

#include <QFontMetricsF>
#include <QPdfDocument>
#include <QImageReader>
#include <QMutex>
#include <QPainterPath>
#include <QTextLayout>
#include <QTimer>

#include <cmath>

namespace xn {

// --- layers ------------------------------------------------------------------------------------

namespace {
constexpr QPainter::RenderHints kHints = QPainter::Antialiasing | QPainter::SmoothPixmapTransform | QPainter::TextAntialiasing;
}

QPainter* PainterTarget::painter() {
    if (!layers_.empty()) return layers_.back().painter.get();
    if (image_ && !base_->isActive()) {
        // Resumed after the surface was read: the core's save/transform state does not span that,
        // since it only keeps a renderer between whole drawing passes.
        saveIsLayer_.clear();
        base_->begin(image_);
        base_->setRenderHints(kHints);
    }
    return base_;
}

void PainterTarget::save() {
    painter()->save();
    saveIsLayer_.push_back(false);
}

void PainterTarget::saveLayer(const QRectF& bounds, double alpha, int blend) {
    QPainter* parent = painter();
    // The layer covers the bounds in the parent's logical space, clipped to what the parent can
    // show, at the device's pixel ratio so it is as sharp as the parent (a GL view is logical).
    QRect device = parent->transform().mapRect(bounds).toAlignedRect();
    if (parent->hasClipping()) device &= parent->transform().mapRect(parent->clipBoundingRect()).toAlignedRect();
    device &= QRect(0, 0, parent->device()->width(), parent->device()->height());
    if (device.isEmpty()) device = QRect(0, 0, 1, 1);
    const double dpr = parent->device()->devicePixelRatioF();
    Layer layer;
    layer.image = std::make_unique<QImage>((QSizeF(device.size()) * dpr).toSize(), QImage::Format_ARGB32_Premultiplied);
    layer.image->setDevicePixelRatio(dpr);
    layer.image->fill(Qt::transparent);
    layer.painter = std::make_unique<QPainter>(layer.image.get());
    layer.painter->setRenderHints(parent->renderHints());
    QTransform t = parent->transform();
    t *= QTransform::fromTranslate(-device.x(), -device.y());
    layer.painter->setTransform(t);
    layer.deviceRect = device;
    layer.alpha = alpha;
    layer.blend = blend;
    layers_.push_back(std::move(layer));
    saveIsLayer_.push_back(true);
}

void PainterTarget::restore() {
    if (saveIsLayer_.empty()) return;
    bool isLayer = saveIsLayer_.back();
    saveIsLayer_.pop_back();
    if (!isLayer) {
        painter()->restore();
        return;
    }
    Layer layer = std::move(layers_.back());
    layers_.pop_back();
    layer.painter->end();
    QPainter* parent = painter();
    parent->save();
    parent->resetTransform();
    parent->setOpacity(layer.alpha);
    parent->setCompositionMode(layer.blend == XN_BLEND_MULTIPLY ? QPainter::CompositionMode_Multiply
                               : layer.blend == XN_BLEND_SCREEN ? QPainter::CompositionMode_Screen
                                                                : QPainter::CompositionMode_SourceOver);
    parent->drawImage(layer.deviceRect.topLeft(), *layer.image);
    parent->restore();
}

void Surface::endPainting() {
    // Painting gave the image a new cache key, so the OpenGL engine uploads it again.
    if (painter.isActive()) painter.end();
}

PainterTarget* Surface::restart() {
    endPainting();
    target = PainterTarget(&painter, &image);
    return &target;
}

// --- the renderer vtable -------------------------------------------------------------------------

namespace {

PainterTarget* T(void* r) { return static_cast<PainterTarget*>(r); }
QPainter* P(void* r) { return T(r)->painter(); }

QPen toPen(const xn_pen* p) {
    QPen pen(toColor(p->color));
    pen.setWidthF(p->width);
    pen.setCosmetic(p->cosmetic != 0);
    pen.setCapStyle(Qt::RoundCap);
    pen.setJoinStyle(Qt::RoundJoin);
    if (p->dashed && p->width > 0) {
        // Qt measures dashes in pen widths.
        pen.setDashPattern({p->dash_on / p->width, p->dash_gap / p->width});
        pen.setDashOffset(p->dash_phase / p->width);
        pen.setCapStyle(Qt::FlatCap);
    }
    return pen;
}

QString fromUtf16(const uint16_t* text, int len) {
    return len > 0 ? QString::fromUtf16(reinterpret_cast<const char16_t*>(text), len) : QString();
}

void rSave(void* r) { T(r)->save(); }
void rRestore(void* r) { T(r)->restore(); }
void rSaveLayer(void* r, double x, double y, double w, double h, double alpha, int blend) {
    T(r)->saveLayer(QRectF(x, y, w, h), alpha, blend);
}
void rTranslate(void* r, double dx, double dy) { P(r)->translate(dx, dy); }
void rScale(void* r, double sx, double sy) { P(r)->scale(sx, sy); }
void rRotate(void* r, double degrees) { P(r)->rotate(degrees); }
void rClipRect(void* r, double x, double y, double w, double h) { P(r)->setClipRect(QRectF(x, y, w, h), Qt::IntersectClip); }
void rClear(void* r) {
    QPainter* p = P(r);
    p->save();
    p->setCompositionMode(QPainter::CompositionMode_Clear);
    QRectF area = p->hasClipping() ? p->clipBoundingRect() : p->transform().inverted().mapRect(QRectF(0, 0, p->device()->width(), p->device()->height()));
    p->fillRect(area, Qt::transparent);
    p->restore();
}
/*
 * Runs block(device rect) with the painter set to device pixels (identity world transform on top
 * of the device's own pixel ratio), when the current transform only scales and translates.
 * Returns what block returned, or false when the transform does more.
 */
template <typename F>
bool inDevicePixels(QPainter* p, const QRectF& rect, F block) {
    const QTransform t = p->deviceTransform();
    if (t.type() > QTransform::TxScale) return false;
    const QRectF device = t.mapRect(rect);
    const double dpr = p->device()->devicePixelRatioF();
    p->save();
    p->setWorldTransform(QTransform::fromScale(1 / dpr, 1 / dpr));
    const bool done = block(device);
    p->restore();
    return done;
}

/*
 * Axis-aligned fills (the gap, the paper) snap to whole device pixels and skip antialiasing,
 * which on a fractional full-screen rect cost several milliseconds a frame.
 */
void rFillRect(void* r, double x, double y, double w, double h, xn_rgba c) {
    QPainter* p = P(r);
    const QColor color = toColor(c);
    const bool snapped = inDevicePixels(p, QRectF(x, y, w, h), [&](const QRectF& d) {
        p->setRenderHint(QPainter::Antialiasing, false);  // whole pixels; Qt's coverage path is slow
        const QRect whole(QPoint(int(std::lround(d.left())), int(std::lround(d.top()))),
                          QPoint(int(std::lround(d.right())) - 1, int(std::lround(d.bottom())) - 1));
        if (!whole.isEmpty()) p->fillRect(whole, color);
        return true;
    });
    if (!snapped) p->fillRect(QRectF(x, y, w, h), color);
}
void rFillPolygon(void* r, const double* xy, int n, xn_rgba c, int rule) {
    QPolygonF poly;
    poly.reserve(n);
    for (int i = 0; i < n; i++) poly << QPointF(xy[2 * i], xy[2 * i + 1]);
    QPainterPath path;
    path.setFillRule(rule == XN_FILL_EVEN_ODD ? Qt::OddEvenFill : Qt::WindingFill);
    path.addPolygon(poly);
    path.closeSubpath();
    P(r)->fillPath(path, toColor(c));
}
void rFillEllipse(void* r, double cx, double cy, double rx, double ry, xn_rgba c) {
    QPainterPath path;
    path.addEllipse(QPointF(cx, cy), rx, ry);
    P(r)->fillPath(path, toColor(c));
}

/*
 * The ink ribbon: every disc plus the quad bridging each consecutive pair, filled as one nonzero
 * path so the pieces merge without seams. Every piece winds the same way, or the overlaps would
 * cancel into holes.
 *
 * Filling it is most of what drawing a page costs, so the path is kept lean: discs are polygons
 * fine enough for their size on the device (Qt flattens an ellipse's curves on every fill), and a
 * disc inside the stroke is left out where the quads on either side already cover it, which is
 * where the stroke barely turns and its width barely changes.
 */
void rFillDiskRibbon(void* r, const float* centers, const float* radii, int from, int count, xn_rgba c) {
    if (count <= 0) return;
    QPainter* p = P(r);
    const QTransform& t = p->deviceTransform();
    const double scale = std::sqrt(std::abs(t.m11() * t.m22() - t.m12() * t.m21()));
    const int end = from + count;
    auto x = [&](int i) { return double(centers[2 * i]); };
    auto y = [&](int i) { return double(centers[2 * i + 1]); };

    QPainterPath path;
    path.setFillRule(Qt::WindingFill);
    QPolygonF quad(4);
    for (int i = from; i + 1 < end; i++) {
        const double x0 = x(i), y0 = y(i), x1 = x(i + 1), y1 = y(i + 1);
        const double len = std::hypot(x1 - x0, y1 - y0);
        if (len < 1e-9) continue;
        const double nx = -(y1 - y0) / len, ny = (x1 - x0) / len;
        const double r0 = radii[i], r1 = radii[i + 1];
        const QPointF a(x0 + nx * r0, y0 + ny * r0), b(x1 + nx * r1, y1 + ny * r1);
        const QPointF cc(x1 - nx * r1, y1 - ny * r1), d(x0 - nx * r0, y0 - ny * r0);
        const double cross = (b.x() - a.x()) * (cc.y() - a.y()) - (b.y() - a.y()) * (cc.x() - a.x());
        if (cross >= 0) { quad[0] = a; quad[1] = b; quad[2] = cc; quad[3] = d; }
        else { quad[0] = d; quad[1] = cc; quad[2] = b; quad[3] = a; }
        path.addPolygon(quad);
        path.closeSubpath();
    }

    /* Whether the quads around point i leave a visible gap that only its disc fills. */
    auto discNeeded = [&](int i) {
        if (i == from || i == end - 1) return true;  // the round ends
        const double ax = x(i) - x(i - 1), ay = y(i) - y(i - 1), bx = x(i + 1) - x(i), by = y(i + 1) - y(i);
        const double la = std::hypot(ax, ay), lb = std::hypot(bx, by);
        const double rad = radii[i];
        // A short segment against a change of width: the quad is no longer the discs' hull.
        if (la < 1e-9 || lb < 1e-9 || std::abs(radii[i - 1] - rad) * 4 > la || std::abs(radii[i + 1] - rad) * 4 > lb) return true;
        // The wedge on the outside of the turn is rad * (1 - cos(turn / 2)) deep.
        const double cosTurn = (ax * bx + ay * by) / (la * lb);
        const double depth = rad * scale * (1 - std::sqrt(std::max(0.0, (1 + cosTurn) / 2)));
        return depth > 0.02;
    };
    QPolygonF disc;
    for (int i = from; i < end; i++) {
        const double rad = radii[i];
        if (rad <= 0 || !discNeeded(i)) continue;
        // Sides enough to stay within a twentieth of a device pixel of the circle.
        const double dev = rad * scale;
        const int n = dev <= 0.05 ? 4 : std::clamp(int(std::ceil(M_PI / std::acos(std::max(-1.0, 1 - 0.05 / dev)))), 4, 96);
        disc.resize(n);
        const double cx = x(i), cy = y(i);
        for (int k = 0; k < n; k++) {
            const double a = 2 * M_PI * k / n;  // increasing angle winds as the quads do
            disc[k] = QPointF(cx + rad * std::cos(a), cy + rad * std::sin(a));
        }
        path.addPolygon(disc);
        path.closeSubpath();
    }
    p->fillPath(path, toColor(c));
}

void rStrokePolyline(void* r, const double* xy, int n, int closed, const xn_pen* pen) {
    QPolygonF poly;
    poly.reserve(n);
    for (int i = 0; i < n; i++) poly << QPointF(xy[2 * i], xy[2 * i + 1]);
    QPainter* p = P(r);
    p->save();
    p->setPen(toPen(pen));
    p->setBrush(Qt::NoBrush);
    if (closed) p->drawPolygon(poly); else p->drawPolyline(poly);
    p->restore();
}
void rStrokeRect(void* r, double x, double y, double w, double h, const xn_pen* pen) {
    QPainter* p = P(r);
    p->save();
    QPen qp = toPen(pen);
    qp.setJoinStyle(Qt::MiterJoin);
    p->setPen(qp);
    p->setBrush(Qt::NoBrush);
    p->drawRect(QRectF(x, y, w, h));
    p->restore();
}
void rStrokeEllipse(void* r, double cx, double cy, double rx, double ry, const xn_pen* pen) {
    QPainter* p = P(r);
    p->save();
    p->setPen(toPen(pen));
    p->setBrush(Qt::NoBrush);
    p->drawEllipse(QPointF(cx, cy), rx, ry);
    p->restore();
}
void rDrawSurface(void* r, void* surface, const double* dest, const double* src, double alpha, int blend) {
    auto* s = static_cast<Surface*>(surface);
    if (!s) return;
    s->endPainting();
    QPainter* p = P(r);
    p->save();
    p->setOpacity(alpha);
    if (blend == XN_BLEND_MULTIPLY) p->setCompositionMode(QPainter::CompositionMode_Multiply);
    else if (blend == XN_BLEND_SCREEN) p->setCompositionMode(QPainter::CompositionMode_Screen);
    const QRectF target(dest[0], dest[1], dest[2], dest[3]);
    const QRectF source = src ? QRectF(src[0], src[1], src[2], src[3]) : QRectF(s->image.rect());
    // A cache made for this zoom is drawn unscaled, at its exact (fractional) position: Qt's
    // translate-only path. Fitted into a rect a fraction of a pixel off its size it went through
    // the filtered scaling path instead, six times slower at 3456x2304.
    const bool copied = inDevicePixels(p, target, [&](const QRectF& d) {
        if (std::abs(d.width() - source.width()) > 1 || std::abs(d.height() - source.height()) > 1) return false;
        p->drawImage(d.topLeft(), s->image, source);
        return true;
    });
    if (!copied) p->drawImage(target, s->image, source);
    p->restore();
}

/* Decoded images at the size they were last drawn, so a frame does not decode again. Shared by
   the UI thread and the core's cache threads, under imageCacheLock(). */
QCache<QString, QImage>& imageCache() {
    static QCache<QString, QImage> cache(64 * 1024 * 1024);  // bytes, via the cost below
    return cache;
}
QMutex& imageCacheLock() {
    static QMutex lock;
    return lock;
}

void rDrawImage(void* r, const char* path, double x, double y, double w, double h, int orientation, double angle) {
    QPainter* p = P(r);
    const QRectF dest(x, y, w, h);
    const bool turned = ((orientation % 180) + 180) % 180 == 90;
    const double scale = std::max(1.0, std::hypot(p->transform().m11(), p->transform().m12()));
    const QSize want(std::ceil((turned ? h : w) * scale), std::ceil((turned ? w : h) * scale));
    const QString key = QString::fromUtf8(path) + QStringLiteral("@%1x%2").arg(want.width()).arg(want.height());
    QImage image;  // a shared copy: the cache may drop its own while another thread draws
    {
        QMutexLocker lock(&imageCacheLock());
        if (QImage* cached = imageCache().object(key)) image = *cached;
    }
    if (image.isNull()) {
        QImageReader reader(QString::fromUtf8(path));
        reader.setAutoTransform(false);
        if (reader.size().isValid()) reader.setScaledSize(reader.size().scaled(want, Qt::KeepAspectRatioByExpanding).boundedTo(reader.size()));
        image = reader.read();
        if (image.isNull()) return;
        QMutexLocker lock(&imageCacheLock());
        imageCache().insert(key, new QImage(image), int(std::min<qsizetype>(image.sizeInBytes(), 64 * 1024 * 1024)));
    }
    p->save();
    p->setRenderHint(QPainter::SmoothPixmapTransform, true);
    p->translate(dest.center());
    p->rotate(orientation + angle * 180.0 / M_PI);
    const double iw = turned ? h : w, ih = turned ? w : h;
    p->drawImage(QRectF(-iw / 2, -ih / 2, iw, ih), image);
    p->restore();
}

void rDrawText(void* r, const uint16_t* text, int len, double x, double y, double w, double h, const xn_font* font,
               xn_rgba c, int wordWrap, int alignLeft, int alignTop) {
    QPainter* p = P(r);
    p->save();
    p->setFont(CoreHost::fontFor(font));
    p->setPen(toColor(c));
    int flags = (wordWrap ? Qt::TextWordWrap : 0) | (alignLeft ? Qt::AlignLeft : Qt::AlignHCenter) |
                (alignTop ? Qt::AlignTop : Qt::AlignVCenter);
    p->drawText(QRectF(x, y, w, h), flags, fromUtf16(text, len));
    p->restore();
}

void rDrawTextRun(void* r, const uint16_t* text, int len, double x, double baseline, const xn_font* font, xn_rgba c) {
    QPainter* p = P(r);
    p->save();
    p->setFont(CoreHost::fontFor(font));
    p->setPen(toColor(c));
    p->drawText(QPointF(x, baseline), fromUtf16(text, len));
    p->restore();
}

const xn_renderer_vt kVtable = {
    rSave, rRestore, rSaveLayer, rTranslate, rScale, rRotate, rClipRect, rClear,
    rFillRect, rFillPolygon, rFillEllipse, rFillDiskRibbon,
    rStrokePolyline, rStrokeRect, rStrokeEllipse,
    rDrawSurface, rDrawImage, rDrawText, rDrawTextRun,
};

CoreHost* H(void* ctx) { return static_cast<CoreHost*>(ctx); }

}  // namespace

const xn_renderer_vt* CoreHost::vtable() { return &kVtable; }

QFont CoreHost::fontFor(const xn_font* f) {
    // Points at the page's 150 px per inch, in pixels: sized the same whatever device draws it.
    return fontForFace(fromUtf16(f->face, f->face_len), f->point_size * 150.0 / 72.0, f->bold != 0, f->italic != 0);
}

QFont CoreHost::fontForFace(const QString& face, double pixelSize, bool bold, bool italic) {
    QFont font;
    if (face == QLatin1String("serif")) font.setFamilies({QStringLiteral("serif")});
    else if (face == QLatin1String("mono") || face.isEmpty()) font.setFamilies({QStringLiteral("monospace")});
    else if (face == QLatin1String("sans")) font.setFamilies({QStringLiteral("sans-serif")});
    else if (face == QLatin1String("hand")) font.setFamilies({QStringLiteral("cursive"), QStringLiteral("sans-serif")});
    else font.setFamilies({face, QStringLiteral("sans-serif")});
    // Whole pixels, rounded with a little slack: the core converts points in float (37.4999985 for
    // 18 pt), this side in double (37.5), and both must land on the same font.
    font.setPixelSize(std::max(1, int(std::floor(pixelSize + 0.5 + 1e-4))));
    font.setStyleHint(face == QLatin1String("serif") ? QFont::Serif : face == QLatin1String("sans") ? QFont::SansSerif : QFont::Monospace);
    font.setBold(bold);
    font.setItalic(italic);
    return font;
}

// --- host services ---------------------------------------------------------------------------

CoreHost::CoreHost(bool background)
    : pages_(background ? std::make_unique<PdfPages>([this] { if (onPdfReady) onPdfReady(); }) : std::make_unique<PdfPages>()) {
    host_.ctx = this;
    host_.renderer = &kVtable;
    host_.surface_create = surfaceCreate;
    host_.surface_fill = surfaceFill;
    host_.surface_renderer = surfaceRenderer;
    host_.surface_release = surfaceRelease;
    host_.text_measure = textMeasure;
    host_.text_metrics = textMetrics;
    host_.text_advances = textAdvances;
    host_.image_probe = imageProbe;
    host_.request_render = requestRender;
    host_.content_changed = contentChanged;
    host_.view_changed = viewChanged;
    host_.notice = notice;
    host_.post_delayed = postDelayed;
    host_.cancel_task = cancelTask;
    host_.post_frame = postFrame;
    host_.selection_menu = selectionMenu;
    host_.context_menu = contextMenu;
    host_.tool_changed = toolChanged;
    host_.text_edit = textEdit;
    host_.pdf_page_count = pdfPageCount;
    host_.pdf_page_size = pdfPageSize;
    host_.draw_pdf_page = drawPdfPage;
    if (background) host_.wake = wake;
}

CoreHost::~CoreHost() {
    for (QTimer* t : std::as_const(timers_)) delete t;
    qDeleteAll(pdfs_);
}

void* CoreHost::surfaceCreate(void* ctx, int w, int h, double) {
    auto* s = new Surface;
    s->image = QImage(std::max(1, w), std::max(1, h), QImage::Format_ARGB32_Premultiplied);
    s->image.fill(Qt::transparent);
    H(ctx)->liveSurfaces_++;
    return s;
}

void CoreHost::surfaceFill(void*, void* surface, xn_rgba c) {
    auto* s = static_cast<Surface*>(surface);
    s->endPainting();
    s->image.fill(toColor(c));
}

void* CoreHost::surfaceRenderer(void*, void* surface) { return static_cast<Surface*>(surface)->restart(); }

void CoreHost::surfaceRelease(void* ctx, void* surface) {
    auto* s = static_cast<Surface*>(surface);
    s->endPainting();
    delete s;
    H(ctx)->liveSurfaces_--;
}

void CoreHost::textMeasure(void*, const uint16_t* text, int len, const xn_font* font, double wrap, int wordWrap, double* out) {
    const QFont f = fontFor(font);
    QString s = fromUtf16(text, len);
    if (s.isEmpty()) s = QStringLiteral(" ");  // one line tall, as the core expects
    QFontMetricsF fm(f);
    QRectF rect = fm.boundingRect(QRectF(0, 0, wrap, 1e9), (wordWrap ? Qt::TextWordWrap : 0) | Qt::AlignLeft | Qt::AlignTop, s);
    out[0] = 0;
    out[1] = 0;
    out[2] = wrap;
    out[3] = rect.height();
}

void CoreHost::textMetrics(void*, const xn_font* font, double* ascent, double* descent) {
    QFontMetricsF fm(fontFor(font));
    *ascent = fm.ascent();
    *descent = fm.descent() + fm.leading();
}

void CoreHost::textAdvances(void*, const uint16_t* text, int len, const xn_font* font, double* out) {
    // Per UTF-16 unit, from the laid-out line so the sum matches what drawText places; the second
    // unit of a surrogate pair gets 0.
    const QString s = fromUtf16(text, len);
    QTextLayout layout(s, fontFor(font));
    layout.beginLayout();
    QTextLine line = layout.createLine();
    layout.endLayout();
    double prev = 0;
    for (int i = 0; i < len; i++) {
        double x = line.isValid() ? line.cursorToX(i + 1) : 0;
        out[i] = x - prev;
        prev = x;
    }
}

int CoreHost::imageProbe(void*, const char* path, int* w, int* h) {
    QImageReader reader(QString::fromUtf8(path));
    QSize size = reader.size();
    if (!size.isValid()) return 0;
    *w = size.width();
    *h = size.height();
    return 1;
}

void CoreHost::wake(void* ctx) { if (H(ctx)->onWake) H(ctx)->onWake(); }

void CoreHost::requestRender(void* ctx) { if (H(ctx)->onRender) H(ctx)->onRender(); }
void CoreHost::contentChanged(void* ctx) { if (H(ctx)->onContentChanged) H(ctx)->onContentChanged(); }
void CoreHost::viewChanged(void* ctx) { if (H(ctx)->onViewChanged) H(ctx)->onViewChanged(); }
void CoreHost::notice(void* ctx, int code) { if (H(ctx)->onNotice) H(ctx)->onNotice(code); }

void CoreHost::schedule(uint64_t task, int delayMs) {
    cancelTask(this, task);  // posted again: the new time replaces the old
    auto* timer = new QTimer;
    timer->setSingleShot(true);
    timer->setTimerType(Qt::PreciseTimer);
    QObject::connect(timer, &QTimer::timeout, [this, task, timer] {
        if (timers_.value(task) == timer) timers_.remove(task);
        timer->deleteLater();
        if (runTask) runTask(task);
    });
    timers_.insert(task, timer);
    timer->start(std::max(0, delayMs));
}

void CoreHost::postDelayed(void* ctx, uint64_t task, int64_t delayMs) { H(ctx)->schedule(task, int(std::min<int64_t>(delayMs, 1 << 30))); }

void CoreHost::cancelTask(void* ctx, uint64_t task) {
    if (QTimer* t = H(ctx)->timers_.take(task)) {
        t->stop();
        t->deleteLater();
    }
}

void CoreHost::postFrame(void* ctx, uint64_t task) { H(ctx)->schedule(task, 16); }

void CoreHost::selectionMenu(void* ctx, int shown, double x, double y, double w, double h) {
    if (H(ctx)->onSelectionMenu) H(ctx)->onSelectionMenu(shown != 0, QRectF(x, y, w, h));
}

void CoreHost::contextMenu(void* ctx, double x, double y, int onLocked) {
    if (H(ctx)->onContextMenu) H(ctx)->onContextMenu(QPointF(x, y), onLocked != 0);
}

void CoreHost::textEdit(void* ctx, const xn_text_field* field) {
    if (H(ctx)->onTextEdit) H(ctx)->onTextEdit(field);
}

QPdfDocument* CoreHost::pdf(const char* path) {
    const QString key = QString::fromUtf8(path);
    if (QPdfDocument* doc = pdfs_.value(key)) return doc;
    auto* doc = new QPdfDocument;
    if (doc->load(key) != QPdfDocument::Error::None) {
        delete doc;
        return nullptr;
    }
    pdfs_.insert(key, doc);
    return doc;
}

int CoreHost::pdfPageCount(void* ctx, const char* path) {
    QPdfDocument* doc = H(ctx)->pdf(path);
    return doc ? doc->pageCount() : -1;
}

int CoreHost::pdfPageSize(void* ctx, const char* path, int page, double* w, double* h) {
    QPdfDocument* doc = H(ctx)->pdf(path);
    if (!doc || page < 0 || page >= doc->pageCount()) return 0;
    const QSizeF size = doc->pagePointSize(page);
    if (size.isEmpty()) return 0;
    *w = size.width();
    *h = size.height();
    return 1;
}

int CoreHost::drawPdfPage(void* ctx, void* renderer, const char* path, int page, int fullW, int fullH, int rx, int ry,
                          int rw, int rh, double x, double y, double w, double h) {
    if (rw <= 0 || rh <= 0) return 1;
    return H(ctx)->pages_->draw(P(renderer), QString::fromUtf8(path), page, QSize(fullW, fullH), QRect(rx, ry, rw, rh),
                                QRectF(x, y, w, h)) ? 1 : 0;
}

int CoreHost::pendingPdf() const { return pages_->pending(); }

void CoreHost::toolChanged(void* ctx, const char* toolId) {
    if (H(ctx)->onToolChanged) H(ctx)->onToolChanged(QString::fromUtf8(toolId));
}

}  // namespace xn
