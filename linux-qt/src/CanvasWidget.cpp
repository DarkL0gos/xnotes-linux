#include "CanvasWidget.h"

#include "TextOverlay.h"

#include <QMimeData>
#include <QNativeGestureEvent>
#include <QOpenGLWidget>
#include <QSurfaceFormat>
#include <QMouseEvent>
#include <QPainter>
#include <QPointingDevice>
#include <QTabletEvent>
#include <QTouchEvent>
#include <QWheelEvent>

#include <cmath>

namespace {

/* The canvas on the GPU: QPainter over Qt's OpenGL engine, multisampled for smooth ink; page caches
   become textures, uploaded again only when they change. Input passes through to the canvas. */
class GlView : public QOpenGLWidget {
public:
    explicit GlView(CanvasWidget* canvas) : QOpenGLWidget(canvas), canvas_(canvas) {
        QSurfaceFormat format = QSurfaceFormat::defaultFormat();
        format.setSamples(4);
        setFormat(format);
        // Keep the framebuffer between frames, so a frame can repaint just what changed.
        setUpdateBehavior(QOpenGLWidget::PartialUpdate);
        setAttribute(Qt::WA_TransparentForMouseEvents);
        setFocusPolicy(Qt::NoFocus);
        setGeometry(canvas->rect());
    }

protected:
    void resizeGL(int, int) override { fresh_ = true; }

    void paintGL() override {
        QPainter p(this);
        canvas_->renderFrame(p, !fresh_);
        fresh_ = false;
    }

private:
    CanvasWidget* canvas_;
    bool fresh_ = true;  // a new framebuffer holds nothing to keep
};

// Writing repaints only around the pen, which the CPU does in a fraction of a millisecond, while a
// GL frame pays for the whole framebuffer every time (measured on Iris Xe at 3456x2304: CPU
// 0.1 ms, GL 10 ms per pen sample); so the CPU unless asked otherwise.
CanvasWidget::Render gDefaultRender =
    qgetenv("XNOTES_RENDER") == "gl" ? CanvasWidget::Render::Gl : CanvasWidget::Render::Raster;

constexpr double kWheelStepPx = 60.0;   // one notch, in logical pixels
constexpr double kZoomPerNotch = 1.1;

xn_palette darkPalette() {
    return {0x0a0a0aff, 0x161616ff, 0x2c2c2cff, 0x00e676ff, 0x6f6f6fff,
            0x0d0d0dff, 0x242424ff, 0xc8c8c8ff, 0x111111ff, 1};
}

xn_palette lightPalette() {
    return {0xe8e8e8ff, 0xffffffff, 0xc4c4c4ff, 0x00a152ff, 0x6f6f6fff,
            0xf4f4f4ff, 0xd0d0d0ff, 0x1c1c1cff, 0xfbfbfbff, 0};
}

}  // namespace

CanvasWidget::CanvasWidget(QWidget* parent) : QWidget(parent) {
    setAttribute(Qt::WA_OpaquePaintEvent);
    setAttribute(Qt::WA_NoSystemBackground);
    setAttribute(Qt::WA_AcceptTouchEvents);
    setAttribute(Qt::WA_TabletTracking);  // hover of a pen in proximity
    setMouseTracking(true);
    setFocusPolicy(Qt::StrongFocus);
    setCursor(Qt::CrossCursor);
    setAcceptDrops(true);
    clock_.start();

    core_.runTask = [this](uint64_t task) { if (editor_) xn_editor_run_task(editor_, task); };
    core_.onRender = [this] { requestFrame(); };
    core_.onContentChanged = [this] { emit contentChanged(); };
    core_.onViewChanged = [this] {
        requestFrame();
        textOverlay_->reposition();
        emit viewChanged();
    };
    if (defaultRender() == Render::Gl) {
        gl_ = new GlView(this);
        gl_->lower();  // under the text field and the selection bar
    }
    textOverlay_ = new TextOverlay(this);
    core_.onTextEdit = [this](const xn_text_field* field) { textOverlay_->onTextEdit(field); };
    core_.onPdfReady = [this] { if (editor_) xn_editor_refresh_backgrounds(editor_); };
    core_.onNotice = [this](int code) { emit notice(code); };
    core_.onSelectionMenu = [this](bool shown, QRectF r) {
        emit selectionMenu(shown, QRectF(r.topLeft() / dpr_, r.size() / dpr_));
    };
    core_.onContextMenu = [this](QPointF at, bool locked) { emit contextMenu(at / dpr_, locked); };
    core_.onToolChanged = [this](QString id) {
        setCursor(id == QLatin1String("pan") ? Qt::OpenHandCursor : Qt::CrossCursor);
        emit toolChanged(id);
    };
}

void CanvasWidget::setDefaultRender(Render render) { gDefaultRender = render; }
CanvasWidget::Render CanvasWidget::defaultRender() { return gDefaultRender; }

CanvasWidget::~CanvasWidget() { setNote(nullptr); }

void CanvasWidget::setNote(xn_note* note) {
    if (editor_) {
        xn_editor_destroy(editor_);
        editor_ = nullptr;
    }
    textOverlay_->onTextEdit(nullptr);
    penDown_ = mouseDown_ = panning_ = false;
    touches_.clear();
    requestFrame();
    if (!note) return;
    editor_ = xn_editor_create(note, core_.host());
    updateViewport();
    requestFrame();
}

void CanvasWidget::setTool(const char* toolId) {
    if (!editor_) return;
    xn_editor_set_tool(editor_, toolId);
    setCursor(qstrcmp(toolId, "pan") == 0 ? Qt::OpenHandCursor : Qt::CrossCursor);
}

QRectF CanvasWidget::selectionRect() const {
    double r[4];
    if (!editor_ || !xn_editor_selection_rect(editor_, r)) return {};
    return QRectF(r[0] / dpr_, r[1] / dpr_, r[2] / dpr_, r[3] / dpr_);
}

void CanvasWidget::setDark(bool dark) {
    if (!editor_) return;
    const xn_palette p = dark ? darkPalette() : lightPalette();
    xn_editor_set_palette(editor_, &p);
}

/*
 * The core fits the first viewport it is given (fit to width, top of the page), so a hidden
 * widget, whose size is still a placeholder, gives it none: the first real one comes on show.
 */
void CanvasWidget::updateViewport() {
    dpr_ = devicePixelRatioF();
    if (!editor_ || !isVisible()) return;
    const int w = int(std::ceil(width() * dpr_)), h = int(std::ceil(height() * dpr_));
    if (w <= 0 || h <= 0) return;
    xn_editor_set_viewport(editor_, w, h, dpr_);
    textOverlay_->reposition();
    emit viewChanged();
}

void CanvasWidget::showEvent(QShowEvent* e) {
    QWidget::showEvent(e);
    updateViewport();
}

bool CanvasWidget::event(QEvent* e) {
    switch (e->type()) {
    case QEvent::TouchBegin:
    case QEvent::TouchUpdate:
    case QEvent::TouchEnd:
    case QEvent::TouchCancel:
        touchEvent(static_cast<QTouchEvent*>(e));
        return true;
    case QEvent::DevicePixelRatioChange:
        updateViewport();
        requestFrame();
        break;
    case QEvent::NativeGesture: {
        // A touchpad pinch: zoom about the fingers.
        auto* g = static_cast<QNativeGestureEvent*>(e);
        if (g->gestureType() == Qt::ZoomNativeGesture && editor_) {
            const QPointF at = device(g->position());
            xn_editor_zoom_at(editor_, at.x(), at.y(), std::clamp(1.0 + g->value(), 0.5, 2.0));
            return true;
        }
        break;
    }
    default:
        break;
    }
    return QWidget::event(e);
}

void CanvasWidget::resizeEvent(QResizeEvent*) {
    if (gl_) gl_->setGeometry(rect());
    updateViewport();
}

void CanvasWidget::requestFrame() {
    if (gl_) return gl_->update();
    // In memory the frame persists, so only the changed part has to reach the window.
    double d[4];
    if (editor_ && xn_editor_frame_damage(editor_, d)) {
        update(QRectF(d[0] / dpr_, d[1] / dpr_, d[2] / dpr_, d[3] / dpr_).toAlignedRect().adjusted(-1, -1, 1, 1));
    } else {
        update();
    }
}

void CanvasWidget::renderFrame(QPainter& p, bool keptFrame) {
    lastDamage_ = QRectF();
    if (!editor_) {
        p.fillRect(QRectF(0, 0, width(), height()), QColor(0x0a, 0x0a, 0x0a));
        return;
    }
    double d[4];
    if (keptFrame && xn_editor_frame_damage(editor_, d)) {
        lastDamage_ = QRectF(d[0] / dpr_, d[1] / dpr_, d[2] / dpr_, d[3] / dpr_);
        p.setClipRect(lastDamage_);
    }
    const qint64 since = sinceFrame_.isValid() ? sinceFrame_.restart() : (sinceFrame_.start(), 0);
    // Frames closer than 100 ms apart are a run (drawing, panning); a longer gap starts a new one.
    frameIntervalMs_ = since > 0 && since < 100 ? (frameIntervalMs_ > 0 ? frameIntervalMs_ * 0.8 + since * 0.2 : since) : 0;
    QElapsedTimer t;
    t.start();
    p.save();
    p.setRenderHints(QPainter::Antialiasing | QPainter::SmoothPixmapTransform | QPainter::TextAntialiasing);
    p.scale(1 / dpr_, 1 / dpr_);
    xn::PainterTarget target(&p);
    xn_editor_paint(editor_, &target);
    p.restore();
    lastFrameMs_ = t.nsecsElapsed() / 1e6;
}

void CanvasWidget::paintEvent(QPaintEvent*) {
    if (gl_) return;  // the GL view covers the canvas
    QPainter out(this);
    if (!editor_) {
        out.fillRect(rect(), QColor(0x0a, 0x0a, 0x0a));
        return;
    }
    // Composed in memory at device resolution, then copied out. The frame carries the device pixel
    // ratio, so painting into it works in logical pixels and the copy is pixel for pixel (an
    // unmarked frame drawn into the scaled window painter took Qt's slow transforming path).
    const QSize size(int(std::ceil(width() * dpr_)), int(std::ceil(height() * dpr_)));
    const bool kept = frame_.size() == size && frame_.devicePixelRatio() == dpr_;
    if (!kept) {
        frame_ = QImage(size, QImage::Format_ARGB32_Premultiplied);
        frame_.setDevicePixelRatio(dpr_);
    }
    {
        QPainter p(&frame_);
        renderFrame(p, kept);
    }
    out.drawImage(QPointF(0, 0), frame_);
}

void CanvasWidget::send(int action, int actionIndex, int buttons, const std::vector<xn_pointer>& pointers, bool hover) {
    if (!editor_ || pointers.empty()) return;
    if (!hover) {
        const int type = pointers.front().tool_type;
        lastInput_ = type == XN_TOOL_TYPE_STYLUS ? QStringLiteral("перо") : type == XN_TOOL_TYPE_ERASER ? QStringLiteral("ластик")
                   : type == XN_TOOL_TYPE_FINGER ? QStringLiteral("касание") : QStringLiteral("мышь");
        lastPressure_ = pointers.front().pressure;
    }
    xn_pointer_event ev{};
    ev.action = action;
    ev.action_index = actionIndex;
    ev.button_state = buttons;
    ev.time_ms = now();
    ev.pointer_count = int(pointers.size());
    ev.pointers = pointers.data();
    if (hover) xn_editor_hover(editor_, &ev); else xn_editor_touch(editor_, &ev);
}

// --- pen -----------------------------------------------------------------------------------------

void CanvasWidget::tabletEvent(QTabletEvent* e) {
    e->accept();  // no synthesized mouse events for the pen
    const bool eraser = e->pointerType() == QPointingDevice::PointerType::Eraser;
    const QPointF at = device(e->position());
    xn_pointer p{0, eraser ? XN_TOOL_TYPE_ERASER : XN_TOOL_TYPE_STYLUS, float(at.x()), float(at.y()), float(e->pressure())};
    // The barrel buttons: Qt reports them as the middle and right buttons of the pen.
    const bool first = e->buttons() & Qt::MiddleButton, second = e->buttons() & Qt::RightButton;
    penButtons_ = (first ? 1 : 0) | (second ? 2 : 0);

    // The second button alone grabs the canvas: it follows the pen, touching or hovering, while
    // the button is held. Not in the middle of a stroke, which the button does not interrupt.
    if (penPanning_) {
        if (second && !first && e->type() != QEvent::TabletLeaveProximity) {
            if (editor_) xn_editor_scroll_by(editor_, penPanFrom_.x() - at.x(), penPanFrom_.y() - at.y());
            penPanFrom_ = at;
        } else {
            penPanning_ = false;
            setCursor(panCursor_);
        }
        return;
    }
    if (second && !first && !penDown_ && e->type() != QEvent::TabletLeaveProximity) {
        penPanning_ = true;
        penPanFrom_ = at;
        panCursor_ = cursor();
        setCursor(Qt::ClosedHandCursor);
        return;
    }

    // The first button, or both, is the core's pen button (the eraser).
    const int buttons = first ? XN_BUTTON_STYLUS_PRIMARY | (second ? XN_BUTTON_STYLUS_SECONDARY : 0) : 0;
    switch (e->type()) {
    case QEvent::TabletPress:
        if (penDown_ || e->button() != Qt::LeftButton) break;
        penDown_ = true;
        setFocus(Qt::MouseFocusReason);
        send(XN_ACTION_DOWN, 0, buttons, {p});
        break;
    case QEvent::TabletMove:
        if (penDown_) send(XN_ACTION_MOVE, 0, buttons, {p});
        else send(XN_ACTION_HOVER_MOVE, 0, buttons, {p}, true);
        break;
    case QEvent::TabletRelease:
        if (!penDown_ || e->button() != Qt::LeftButton) break;
        penDown_ = false;
        send(XN_ACTION_UP, 0, buttons, {p});
        break;
    case QEvent::TabletLeaveProximity:
        send(XN_ACTION_HOVER_EXIT, 0, 0, {p}, true);
        break;
    default:
        break;
    }
}

// --- mouse ---------------------------------------------------------------------------------------

void CanvasWidget::mousePressEvent(QMouseEvent* e) {
    setFocus(Qt::MouseFocusReason);
    if (penDown_ || e->source() != Qt::MouseEventNotSynthesized) return;
    const QPointF at = device(e->position());
    if (e->button() == Qt::MiddleButton) {
        panning_ = true;
        panFrom_ = at;
    } else if (e->button() == Qt::LeftButton && !mouseDown_) {
        mouseDown_ = true;
        send(XN_ACTION_DOWN, 0, 0, {{0, XN_TOOL_TYPE_MOUSE, float(at.x()), float(at.y()), 1.0f}});
    }
}

void CanvasWidget::mouseMoveEvent(QMouseEvent* e) {
    if (penDown_ || e->source() != Qt::MouseEventNotSynthesized) return;
    const QPointF at = device(e->position());
    const xn_pointer p{0, XN_TOOL_TYPE_MOUSE, float(at.x()), float(at.y()), 1.0f};
    if (panning_) {
        if (editor_) xn_editor_scroll_by(editor_, panFrom_.x() - at.x(), panFrom_.y() - at.y());
        panFrom_ = at;
    } else if (mouseDown_) {
        send(XN_ACTION_MOVE, 0, 0, {p});
    } else {
        send(XN_ACTION_HOVER_MOVE, 0, 0, {p}, true);
    }
}

void CanvasWidget::mouseReleaseEvent(QMouseEvent* e) {
    if (e->source() != Qt::MouseEventNotSynthesized) return;
    const QPointF at = device(e->position());
    if (e->button() == Qt::MiddleButton) {
        panning_ = false;
    } else if (e->button() == Qt::LeftButton && mouseDown_) {
        mouseDown_ = false;
        send(XN_ACTION_UP, 0, 0, {{0, XN_TOOL_TYPE_MOUSE, float(at.x()), float(at.y()), 1.0f}});
    }
}

void CanvasWidget::leaveEvent(QEvent*) {
    const QPointF at = device(mapFromGlobal(QCursor::pos()).toPointF());
    send(XN_ACTION_HOVER_EXIT, 0, 0, {{0, XN_TOOL_TYPE_MOUSE, float(at.x()), float(at.y()), 1.0f}}, true);
}

void CanvasWidget::wheelEvent(QWheelEvent* e) {
    if (!editor_) return;
    e->accept();
    const QPointF at = device(e->position());
    // Touchpads report pixels; wheels report eighths of a degree, 120 per notch.
    const QPointF notches = QPointF(e->angleDelta()) / 120.0;
    if (e->modifiers() & Qt::ControlModifier) {
        const double steps = !e->pixelDelta().isNull() ? e->pixelDelta().y() / 50.0 : notches.y();
        xn_editor_zoom_at(editor_, at.x(), at.y(), std::pow(kZoomPerNotch, steps));
        return;
    }
    QPointF delta = !e->pixelDelta().isNull() ? QPointF(e->pixelDelta()) : notches * kWheelStepPx;
    if (e->modifiers() & Qt::ShiftModifier && delta.x() == 0) delta = QPointF(delta.y(), 0);
    xn_editor_scroll_by(editor_, -delta.x() * dpr_, -delta.y() * dpr_);
}

// --- drops ---------------------------------------------------------------------------------------

namespace {

QStringList localFiles(const QMimeData* mime) {
    QStringList files;
    if (!mime || !mime->hasUrls()) return files;
    for (const QUrl& url : mime->urls())
        if (url.isLocalFile()) files << url.toLocalFile();
    return files;
}

}  // namespace

void CanvasWidget::dragEnterEvent(QDragEnterEvent* e) {
    if (editor_ && !localFiles(e->mimeData()).isEmpty()) e->acceptProposedAction();
}

void CanvasWidget::dropEvent(QDropEvent* e) {
    const QStringList files = localFiles(e->mimeData());
    if (files.isEmpty()) return;
    e->acceptProposedAction();
    emit filesDropped(files, e->position());
}

// --- touch ---------------------------------------------------------------------------------------

/*
 * Qt reports every changed point in one event; Android-style input has one action per event. Moves
 * go first as one MOVE, then each new finger as (POINTER_)DOWN and each lifted one as (POINTER_)UP.
 */
void CanvasWidget::touchEvent(QTouchEvent* e) {
    if (e->device() && e->device()->type() == QInputDevice::DeviceType::TouchPad) return;
    auto pointers = [this] {
        std::vector<xn_pointer> out;
        for (const Touch& t : touches_)
            out.push_back({t.id, XN_TOOL_TYPE_FINGER, float(t.pos.x()), float(t.pos.y()), 1.0f});
        return out;
    };
    if (e->type() == QEvent::TouchCancel) {
        if (!touches_.empty()) send(XN_ACTION_CANCEL, 0, 0, pointers());
        touches_.clear();
        return;
    }
    if (penDown_) return;
    bool moved = false;
    for (const QEventPoint& pt : e->points()) {
        for (Touch& t : touches_) {
            if (t.id == pt.id() && pt.state() != QEventPoint::Pressed) {
                const QPointF at = device(pt.position());
                moved |= at != t.pos;
                t.pos = at;
            }
        }
    }
    if (moved) send(XN_ACTION_MOVE, 0, 0, pointers());
    for (const QEventPoint& pt : e->points()) {
        if (pt.state() != QEventPoint::Pressed) continue;
        touches_.push_back({pt.id(), device(pt.position())});
        const int index = int(touches_.size()) - 1;
        send(index == 0 ? XN_ACTION_DOWN : XN_ACTION_POINTER_DOWN, index, 0, pointers());
    }
    for (const QEventPoint& pt : e->points()) {
        if (pt.state() != QEventPoint::Released) continue;
        for (size_t i = 0; i < touches_.size(); i++) {
            if (touches_[i].id != pt.id()) continue;
            send(touches_.size() == 1 ? XN_ACTION_UP : XN_ACTION_POINTER_UP, int(i), 0, pointers());
            touches_.erase(touches_.begin() + long(i));
            break;
        }
    }
}
