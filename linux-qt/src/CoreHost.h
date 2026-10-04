// The Qt side of the core's C API (xnotes.h): drawing over QPainter, page-cache surfaces as
// QImages, text measurement with QFont, image probing, and timers on the Qt event loop.
#pragma once

#include "xnotes.h"

#include <QCache>
#include <QFont>
#include <QHash>
#include <QImage>
#include <QPainter>
#include <QString>

#include <functional>
#include <memory>
#include <vector>

class QPdfDocument;
class QTimer;

namespace xn {

/*
 * A drawing target the core draws into through xn_renderer_vt: a painter, plus the stack of
 * offscreen layers save_layer opened (Qt has no saveLayer, so a layer is an image composited on
 * restore with its alpha and blend mode).
 */
class PainterTarget {
public:
    /* Draws with painter; with resumeOn, a painter that was ended starts again on that image. */
    explicit PainterTarget(QPainter* painter, QImage* resumeOn = nullptr) : base_(painter), image_(resumeOn) {}

    QPainter* painter();
    void save();
    void restore();
    void saveLayer(const QRectF& bounds, double alpha, int blend);

private:
    struct Layer {
        // On the heap: the painter keeps the image's address, and a Layer moves (vector growth, restore).
        std::unique_ptr<QImage> image;
        std::unique_ptr<QPainter> painter;
        QRect deviceRect;
        double alpha;
        int blend;
    };
    QPainter* base_;
    QImage* image_;
    std::vector<Layer> layers_;
    std::vector<bool> saveIsLayer_;
};

/*
 * A cache surface: an image and the one renderer drawing into it. The core may keep that renderer
 * across frames, drawing into the surface after it was read, so reading ends the painter and the
 * next drawing call begins it again.
 */
struct Surface {
    QImage image;
    QPainter painter;
    PainterTarget target{&painter, &image};

    /* Finish pending drawing so the image can be read or filled. */
    void endPainting();
    /* Start over: identity transform, no clip. */
    PainterTarget* restart();
};

/*
 * Implements xn_host for one editor. Owns the surfaces and timers it hands out. The callbacks run
 * on the UI thread, inside the xn_* call that triggered them.
 */
class CoreHost {
public:
    CoreHost();
    ~CoreHost();

    const xn_host* host() const { return &host_; }

    /* Where tasks the core posts are delivered; set once the editor exists. */
    std::function<void(uint64_t)> runTask;
    std::function<void()> onRender;
    std::function<void()> onContentChanged;
    std::function<void()> onViewChanged;
    std::function<void(int)> onNotice;
    /* v2: a settled selection's viewport rect (device px), or shown = false. */
    std::function<void(bool, QRectF)> onSelectionMenu;
    std::function<void(QPointF, bool)> onContextMenu;
    std::function<void(QString)> onToolChanged;
    std::function<void(const xn_text_field*)> onTextEdit;

    int liveSurfaces() const { return liveSurfaces_; }

    /* The renderer vtable (the same for every target). */
    static const xn_renderer_vt* vtable();

    /* Fonts as the core sizes them: points at 150 pixels per inch. */
    static QFont fontFor(const xn_font* f);
    /* A face id ("mono", "sans", ...) at a pixel size. */
    static QFont fontForFace(const QString& face, double pixelSize, bool bold = false, bool italic = false);

private:
    xn_host host_{};
    int liveSurfaces_ = 0;
    QHash<uint64_t, QTimer*> timers_;
    /* Open PDFs by path, loaded on first use and kept while the host lives. */
    QHash<QString, QPdfDocument*> pdfs_;

    QPdfDocument* pdf(const char* path);

    static void* surfaceCreate(void* ctx, int w, int h, double dpr);
    static void surfaceFill(void* ctx, void* s, xn_rgba c);
    static void* surfaceRenderer(void* ctx, void* s);
    static void surfaceRelease(void* ctx, void* s);
    static void textMeasure(void* ctx, const uint16_t* text, int len, const xn_font* font, double wrap, int wordWrap, double* out);
    static void textMetrics(void* ctx, const xn_font* font, double* ascent, double* descent);
    static void textAdvances(void* ctx, const uint16_t* text, int len, const xn_font* font, double* out);
    static int imageProbe(void* ctx, const char* path, int* w, int* h);
    static void requestRender(void* ctx);
    static void contentChanged(void* ctx);
    static void viewChanged(void* ctx);
    static void notice(void* ctx, int code);
    static void postDelayed(void* ctx, uint64_t task, int64_t delayMs);
    static void cancelTask(void* ctx, uint64_t task);
    static void postFrame(void* ctx, uint64_t task);
    static void selectionMenu(void* ctx, int shown, double x, double y, double w, double h);
    static void contextMenu(void* ctx, double x, double y, int onLocked);
    static void toolChanged(void* ctx, const char* toolId);
    static void textEdit(void* ctx, const xn_text_field* field);
    static int pdfPageCount(void* ctx, const char* path);
    static int pdfPageSize(void* ctx, const char* path, int page, double* w, double* h);
    static void drawPdfPage(void* ctx, void* renderer, const char* path, int page, int fullW, int fullH, int rx, int ry,
                            int rw, int rh, double x, double y, double w, double h);

    void schedule(uint64_t task, int delayMs);
};

inline QColor toColor(xn_rgba c) { return QColor(int(c >> 24), int((c >> 16) & 0xFF), int((c >> 8) & 0xFF), int(c & 0xFF)); }

}  // namespace xn
