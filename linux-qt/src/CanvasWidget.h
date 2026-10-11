// The paged canvas: an xn_editor behind a QWidget. Frames are painted by the core into an image at
// device resolution and blitted; tablet, mouse and touch input reach it as xn_pointer_events in
// device pixels.
#pragma once

#include "CoreHost.h"

#include <QCursor>
#include <QElapsedTimer>
#include <QImage>
#include <QWidget>

#include <vector>

class QOpenGLWidget;
class QTabletEvent;
class TextOverlay;
class QTouchEvent;

class CanvasWidget : public QWidget {
    Q_OBJECT
public:
    /* How frames reach the screen: composed on the GPU through OpenGL, or in memory by the CPU. */
    enum class Render { Gl, Raster };
    /* For canvases created from now on (main sets it from --gl / XNOTES_RENDER). */
    static void setDefaultRender(Render render);
    static Render defaultRender();

    explicit CanvasWidget(QWidget* parent = nullptr);
    ~CanvasWidget() override;

    Render render() const { return gl_ ? Render::Gl : Render::Raster; }
    /* Average time between the last frames, while they come back to back (ms; 0 when idle). */
    double frameIntervalMs() const { return frameIntervalMs_; }

    /*
     * Paint one frame with p, whose units are logical pixels (the core works in device pixels).
     * With keptFrame, p's target still holds the previous frame, and when the core allows it only
     * the part that changed is repainted.
     */
    void renderFrame(QPainter& p, bool keptFrame = false);
    /* The rect the last frame repainted (logical px), or an empty one when it was full. */
    QRectF lastDamage() const { return lastDamage_; }

    /* The host services, also used to open and save notes. */
    const xn_host* host() const { return core_.host(); }

    /* Edit note (which must outlive the canvas or the next setNote); nullptr detaches. */
    void setNote(xn_note* note);
    xn_editor* editor() const { return editor_; }

    void setTool(const char* toolId);
    /* The selection's current rect in widget coordinates, or an empty rect. */
    QRectF selectionRect() const;
    /* A widget point in the core's device pixels. */
    QPointF toDevice(QPointF widget) const { return widget * dpr_; }
    void setDark(bool dark);

    /* Statistics for the smoke test and the status bar. */
    int liveSurfaces() const { return core_.liveSurfaces(); }
    int pendingPdf() const { return core_.pendingPdf(); }
    /* Page caches the core's threads are still drawing or have not handed over yet. */
    int pendingPages() const { return editor_ ? xn_editor_background_pending(editor_) : 0; }
    double lastFrameMs() const { return lastFrameMs_; }
    /* What drew last: "перо", "ластик", "мышь" or "касание", and the pen's pressure. */
    QString lastInput() const { return lastInput_; }
    double lastPressure() const { return lastPressure_; }
    /* The pen's barrel buttons now: bit 0 the first, bit 1 the second. */
    int penButtons() const { return penButtons_; }

signals:
    void contentChanged();
    void viewChanged();
    void notice(int code);
    /* The selection's menu: shown at rect (widget coordinates), or hidden. */
    void selectionMenu(bool shown, QRectF rect);
    /* A long press asked for a context menu at a widget point; onLocked: over a locked item. */
    void contextMenu(QPointF at, bool onLocked);
    void toolChanged(QString toolId);
    /* Image files dropped on the canvas, at a widget point. */
    void filesDropped(QStringList files, QPointF at);

protected:
    bool event(QEvent* e) override;
    void paintEvent(QPaintEvent* e) override;
    void resizeEvent(QResizeEvent* e) override;
    void showEvent(QShowEvent* e) override;
    void mousePressEvent(QMouseEvent* e) override;
    void mouseMoveEvent(QMouseEvent* e) override;
    void mouseReleaseEvent(QMouseEvent* e) override;
    void leaveEvent(QEvent* e) override;
    void wheelEvent(QWheelEvent* e) override;
    void tabletEvent(QTabletEvent* e) override;
    void dragEnterEvent(QDragEnterEvent* e) override;
    void dropEvent(QDropEvent* e) override;

private:
    xn::CoreHost core_{true};
    xn_editor* editor_ = nullptr;
    TextOverlay* textOverlay_;
    QOpenGLWidget* gl_ = nullptr;
    QElapsedTimer sinceFrame_;
    double frameIntervalMs_ = 0;
    QRectF lastDamage_;
    QImage frame_;
    double dpr_ = 1.0;
    double lastFrameMs_ = 0;
    QString lastInput_;
    double lastPressure_ = 0;
    bool penDown_ = false;
    bool mouseDown_ = false;
    QPointF panFrom_;
    bool panning_ = false;
    bool penPanning_ = false;  // the pen's second button holds the canvas
    QPointF penPanFrom_;
    QCursor panCursor_;
    int penButtons_ = 0;
    QElapsedTimer clock_;

    struct Touch {
        int id;
        QPointF pos;
    };
    std::vector<Touch> touches_;

    void updateViewport();
    /* Schedule a frame on whichever surface shows the canvas. */
    void requestFrame();
    int64_t now() const { return clock_.elapsed(); }
    QPointF device(QPointF logical) const { return logical * dpr_; }
    void send(int action, int actionIndex, int buttons, const std::vector<xn_pointer>& pointers, bool hover = false);
    void touchEvent(QTouchEvent* e);
};
