#include "CanvasWidget.h"

#include "TextOverlay.h"

#include <QMimeData>
#include <QMouseEvent>
#include <QPainter>
#include <QPointingDevice>
#include <QTabletEvent>
#include <QTouchEvent>
#include <QWheelEvent>

#include <cmath>

namespace {

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
    core_.onRender = [this] { update(); };
    core_.onContentChanged = [this] { emit contentChanged(); };
    core_.onViewChanged = [this] {
        update();
        textOverlay_->reposition();
        emit viewChanged();
    };
    textOverlay_ = new TextOverlay(this);
    core_.onTextEdit = [this](const xn_text_field* field) { textOverlay_->onTextEdit(field); };
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

CanvasWidget::~CanvasWidget() { setNote(nullptr); }

void CanvasWidget::setNote(xn_note* note) {
    if (editor_) {
        xn_editor_destroy(editor_);
        editor_ = nullptr;
    }
    textOverlay_->onTextEdit(nullptr);
    penDown_ = mouseDown_ = panning_ = false;
    touches_.clear();
    if (!note) return;
    editor_ = xn_editor_create(note, core_.host());
    updateViewport();
    update();
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
        update();
        break;
    default:
        break;
    }
    return QWidget::event(e);
}

void CanvasWidget::resizeEvent(QResizeEvent*) { updateViewport(); }

void CanvasWidget::paintEvent(QPaintEvent*) {
    QPainter out(this);
    if (!editor_) {
        out.fillRect(rect(), QColor(0x0a, 0x0a, 0x0a));
        return;
    }
    const QSize size(int(std::ceil(width() * dpr_)), int(std::ceil(height() * dpr_)));
    if (frame_.size() != size) frame_ = QImage(size, QImage::Format_ARGB32_Premultiplied);
    QElapsedTimer t;
    t.start();
    {
        QPainter p(&frame_);
        p.setRenderHints(QPainter::Antialiasing | QPainter::SmoothPixmapTransform | QPainter::TextAntialiasing);
        xn::PainterTarget target(&p);
        xn_editor_paint(editor_, &target);
    }
    // One frame pixel per device pixel (the frame keeps ratio 1 so the core's painter is unscaled).
    out.drawImage(QRectF(0, 0, size.width() / dpr_, size.height() / dpr_), frame_);
    lastFrameMs_ = t.nsecsElapsed() / 1e6;
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
    int buttons = 0;
    // The barrel buttons: Qt reports them as the middle and right buttons of the pen.
    if (e->buttons() & Qt::MiddleButton) buttons |= XN_BUTTON_STYLUS_PRIMARY;
    if (e->buttons() & Qt::RightButton) buttons |= XN_BUTTON_STYLUS_SECONDARY;
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
