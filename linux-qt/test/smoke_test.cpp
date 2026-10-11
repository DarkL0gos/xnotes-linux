// Drives the Qt host end to end without a display (QT_QPA_PLATFORM=offscreen): a pen stroke from
// tablet events and one from the mouse become items, ink reaches the screen, the note saves and
// reopens, undo works, and every surface is released.
#include "CanvasWidget.h"
#include "SelectionBar.h"
#include "TextOverlay.h"
#include "PdfExport.h"
#include "MainWindow.h"
#include "NoteFinder.h"
#include "PageStylePopup.h"
#include "PagesPanel.h"
#include "Icons.h"

#include <QAbstractButton>
#include <QComboBox>
#include <QElapsedTimer>
#include <QListView>
#include <QToolButton>
#include <QSlider>

#include <QScrollBar>
#include <QNativeGestureEvent>
#include <QOffscreenSurface>
#include <QOpenGLContext>
#include <QOpenGLFramebufferObject>
#include <QOpenGLPaintDevice>

#include <QPdfDocument>
#include <QPdfWriter>

#include <QPointingDevice>
#include <QTabletEvent>
#include <QTemporaryDir>
#include <QSignalSpy>
#include <QWheelEvent>
#include <QtTest>

#include <zlib.h>

/* A minimal .xnote: one stored manifest entry. */
static bool writeNote(const QString& path, const QByteArray& manifest) {
    const QByteArray name("manifest.json");
    const quint32 crc = quint32(crc32(0, reinterpret_cast<const Bytef*>(manifest.constData()), uInt(manifest.size())));
    QByteArray local, central, end;
    auto u16 = [](QByteArray& b, quint16 v) { b.append(char(v & 0xff)).append(char(v >> 8)); };
    auto u32 = [&](QByteArray& b, quint32 v) { u16(b, quint16(v)); u16(b, quint16(v >> 16)); };
    u32(local, 0x04034b50); u16(local, 10); u16(local, 0); u16(local, 0); u16(local, 0); u16(local, 0x21);
    u32(local, crc); u32(local, quint32(manifest.size())); u32(local, quint32(manifest.size()));
    u16(local, quint16(name.size())); u16(local, 0);
    local += name + manifest;
    u32(central, 0x02014b50); u16(central, 20); u16(central, 10); u16(central, 0); u16(central, 0); u16(central, 0);
    u16(central, 0x21); u32(central, crc); u32(central, quint32(manifest.size())); u32(central, quint32(manifest.size()));
    u16(central, quint16(name.size())); u16(central, 0); u16(central, 0); u16(central, 0); u16(central, 0); u32(central, 0);
    u32(central, 0);
    central += name;
    u32(end, 0x06054b50); u16(end, 0); u16(end, 0); u16(end, 1); u16(end, 1);
    u32(end, quint32(central.size())); u32(end, quint32(local.size())); u16(end, 0);
    QFile f(path);
    return f.open(QIODevice::WriteOnly) && f.write(local + central + end) > 0;
}

/*
 * A frame target that keeps its pixels between frames, as the window's frame does: an image, or
 * a multisampled framebuffer on the OpenGL engine for the GL path.
 */
class KeptTarget {
public:
    KeptTarget(CanvasWidget& canvas, bool gl) : canvas_(canvas), gl_(gl) {
        const QSize size = (QSizeF(canvas.size()) * canvas.devicePixelRatioF()).toSize();
        if (!gl) {
            image_ = QImage(size, QImage::Format_ARGB32_Premultiplied);
            return;
        }
        static QOpenGLContext* context = nullptr;
        static QOffscreenSurface* surface = nullptr;
        if (!context) {
            context = new QOpenGLContext;
            surface = new QOffscreenSurface;
            surface->create();
            context->create();
        }
        context_ = context;
        surface_ = surface;
        context_->makeCurrent(surface_);
        QOpenGLFramebufferObjectFormat format;
        format.setSamples(4);
        format.setAttachment(QOpenGLFramebufferObject::CombinedDepthStencil);
        fbo_ = std::make_unique<QOpenGLFramebufferObject>(size, format);
    }

    KeptTarget& frame(bool keep) {
        if (!gl_) {
            QPainter p(&image_);
            p.scale(canvas_.devicePixelRatioF(), canvas_.devicePixelRatioF());
            canvas_.renderFrame(p, keep);
            return *this;
        }
        context_->makeCurrent(surface_);
        fbo_->bind();
        QOpenGLPaintDevice device(fbo_->size());
        device.setDevicePixelRatio(canvas_.devicePixelRatioF());
        {
            QPainter p(&device);
            canvas_.renderFrame(p, keep);
        }
        fbo_->release();
        return *this;
    }

    QImage image() {
        if (!gl_) return image_.convertToFormat(QImage::Format_RGB32);
        context_->makeCurrent(surface_);
        return fbo_->toImage().convertToFormat(QImage::Format_RGB32);
    }

private:
    CanvasWidget& canvas_;
    bool gl_;
    QImage image_;
    QOpenGLContext* context_ = nullptr;
    QOffscreenSurface* surface_ = nullptr;
    std::unique_ptr<QOpenGLFramebufferObject> fbo_;
};

/* QTest::wheelEvent does not exist; a wheel notch as a mouse produces it. */
namespace QTest {
inline void wheelEvent(QWidget* w, QPointF at, QPoint angle) {
    QWheelEvent e(at, w->mapToGlobal(at), QPoint(), angle, Qt::NoButton, Qt::NoModifier, Qt::NoScrollPhase, false);
    QApplication::sendEvent(w, &e);
}
}  // namespace QTest

class SmokeTest : public QObject {
    Q_OBJECT

    static QPointingDevice* pen() {
        static auto* device = new QPointingDevice(QStringLiteral("test pen"), 42, QInputDevice::DeviceType::Stylus,
                                                  QPointingDevice::PointerType::Pen,
                                                  QInputDevice::Capability::Position | QInputDevice::Capability::Pressure, 1, 3);
        return device;
    }

    static void tablet(QWidget* w, QEvent::Type type, QPointF at, double pressure, Qt::MouseButton button, Qt::MouseButtons buttons) {
        QTabletEvent e(type, pen(), at, w->mapToGlobal(at), pressure, 0, 0, 0, 0, 0, Qt::NoModifier, button, buttons);
        QApplication::sendEvent(w, &e);
    }

    /*
     * The widget's pixels once the view settled: the sharp re-render follows ~90 ms after a zoom, and
     * pages a frame asks for are drawn on the core's threads, so frames are painted until none is
     * still coming.
     */
    static QImage grab(CanvasWidget& c) {
        auto frame = [&c] {
            if (c.render() == CanvasWidget::Render::Gl) return glFrame(c);
            return c.grab().toImage().convertToFormat(QImage::Format_RGB32);
        };
        frame();
        QTest::qWait(200);
        for (int i = 0; i < 20 && c.pendingPages() > 0; i++) {
            if (!QTest::qWaitFor([&c] { return c.pendingPages() == 0; }, 5000)) break;
            frame();
            QTest::qWait(120);
        }
        return frame();
    }

    /*
     * The GL path's frame: the offscreen platform cannot host a QOpenGLWidget, so the frame is
     * drawn the way the widget draws it, through renderFrame into a multisampled framebuffer on
     * Qt's OpenGL paint engine. One context for the whole run, so its texture cache carries over
     * between frames as it does on screen.
     */
    static QImage glFrame(CanvasWidget& c) {
        static QOpenGLContext* context = nullptr;
        static QOffscreenSurface* surface = nullptr;
        if (!context) {
            context = new QOpenGLContext;
            surface = new QOffscreenSurface;
            surface->create();
            if (!context->create()) qFatal("no OpenGL context");
        }
        context->makeCurrent(surface);
        QOpenGLFramebufferObjectFormat format;
        format.setSamples(4);
        format.setAttachment(QOpenGLFramebufferObject::CombinedDepthStencil);
        const double dpr = c.devicePixelRatioF();
        QOpenGLFramebufferObject fbo((QSizeF(c.size()) * dpr).toSize(), format);
        fbo.bind();
        QOpenGLPaintDevice device(fbo.size());
        device.setDevicePixelRatio(dpr);
        {
            QPainter p(&device);
            c.renderFrame(p);
        }
        QImage image = fbo.toImage().convertToFormat(QImage::Format_RGB32);
        fbo.release();
        context->doneCurrent();
        return image;
    }

    static int inkPixels(const QImage& img, QRect area) {
        int n = 0;
        for (int y = area.top(); y <= area.bottom(); y++)
            for (int x = area.left(); x <= area.right(); x++) {
                const QColor c = img.pixelColor(x, y);
                if (c.red() > 200 && c.green() < 60 && c.blue() < 60) n++;
            }
        return n;
    }

private slots:
    void penMouseSaveReopen() {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        xn_note* note = xn_note_new(2);
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        canvas.setDark(false);
        canvas.setTool("pen");
        xn_editor_set_ink_color(canvas.editor(), 0xff0000ff);
        QCoreApplication::processEvents();
        // Zoomed in so the ink is several pixels wide: a hole where its pieces overlap would show.
        xn_editor_zoom_at(canvas.editor(), 400, 300, 4.0);
        QCoreApplication::processEvents();

        // A long horizontal pen stroke with rising pressure. Past ~48 points its settled part goes
        // into the wet-ink surface, which keeps being drawn into while it is shown: all of the ink
        // so far must stay on screen mid-stroke.
        tablet(&canvas, QEvent::TabletPress, {200, 300}, 0.2, Qt::LeftButton, Qt::LeftButton);
        for (int i = 1; i <= 120; i++) {
            tablet(&canvas, QEvent::TabletMove, {200.0 + i * 3, 300}, 0.2 + i * 0.006, Qt::NoButton, Qt::LeftButton);
            if (i == 70 || i == 120) {
                const int head = 200 + i * 3 - 12;
                QCOMPARE(inkPixels(grab(canvas), QRect(210, 300, head - 210, 1)), head - 210);
            }
        }
        tablet(&canvas, QEvent::TabletRelease, {560, 300}, 0, Qt::LeftButton, Qt::NoButton);
        QCOMPARE(xn_note_item_count(note, 0), 1);
        QVERIFY(xn_note_is_dirty(note));
        QVERIFY(xn_editor_can_undo(canvas.editor()));

        QImage shot = grab(canvas);
        if (qEnvironmentVariableIsSet("XNOTES_SMOKE_SHOT")) shot.save(qEnvironmentVariable("XNOTES_SMOKE_SHOT"));
        QCOMPARE(inkPixels(shot, QRect(210, 300, 340, 1)), 340);  // solid along its middle
        QVERIFY2(inkPixels(shot, QRect(210, 290, 340, 20)) > 340 * 4, "the stroke is several pixels wide");
        QCOMPARE(inkPixels(shot, QRect(210, 500, 340, 20)), 0);
        QVERIFY(canvas.lastFrameMs() >= 0);

        // A diagonal mouse stroke.
        QTest::mousePress(&canvas, Qt::LeftButton, {}, QPoint(200, 400));
        for (int i = 1; i <= 20; i++) QTest::mouseMove(&canvas, QPoint(200 + i * 5, 400 + i * 5));
        QTest::mouseRelease(&canvas, Qt::LeftButton, {}, QPoint(300, 500));
        QCOMPARE(xn_note_item_count(note, 0), 2);
        QVERIFY(inkPixels(grab(canvas), QRect(240, 440, 20, 20)) > 20);

        xn_editor_undo(canvas.editor());
        QCOMPARE(xn_note_item_count(note, 0), 1);
        xn_editor_redo(canvas.editor());
        QCOMPARE(xn_note_item_count(note, 0), 2);

        // Other views keep painting.
        xn_editor_fit_width(canvas.editor());
        QVERIFY(inkPixels(grab(canvas), canvas.rect()) > 0);

        const QByteArray path = QFile::encodeName(dir.filePath(QStringLiteral("smoke.xnote")));
        char* error = nullptr;
        QVERIFY(xn_note_save(note, path.constData(), canvas.host(), &error));
        QVERIFY(!xn_note_is_dirty(note));

        canvas.setNote(nullptr);
        QCOMPARE(canvas.liveSurfaces(), 0);
        xn_note_close(note);

        QTemporaryDir work;
        xn_note* reopened = xn_note_open(path.constData(), QFile::encodeName(work.path()).constData(), canvas.host(), &error);
        QVERIFY2(reopened, error);
        QCOMPARE(xn_note_page_count(reopened), 2);
        QCOMPARE(xn_note_item_count(reopened, 0), 2);
        canvas.setNote(reopened);
        // Fitted to the width the strokes are under two pixels wide, so few pixels are pure ink.
        QVERIFY(inkPixels(grab(canvas), canvas.rect()) > 60);
        canvas.setNote(nullptr);
        QCOMPARE(canvas.liveSurfaces(), 0);
        xn_note_close(reopened);
    }

    /* Scrolling and zooming from the host repaint and report the view; pages are ruled; the palette switches. */
    void viewRulingAndPalette() {
        QTemporaryDir dir;
        const QString path = dir.filePath(QStringLiteral("lined.xnote"));
        QVERIFY(writeNote(path, R"({"format":"xnote","version":1,"dpi":150,"style":{"pattern":"lines"},)"
                                R"("pages":[{"width":1240.0,"height":1754.0,"items":[]},{"width":1240.0,"height":1754.0,"items":[]}]})"));
        CanvasWidget canvas;
        char* error = nullptr;
        xn_note* note = xn_note_open(QFile::encodeName(path).constData(), QFile::encodeName(dir.path()).constData(), canvas.host(), &error);
        QVERIFY2(note, error);
        QCOMPARE(xn_note_page_count(note), 2);
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        canvas.setDark(false);
        QSignalSpy views(&canvas, &CanvasWidget::viewChanged);

        const QImage page = grab(canvas);
        int ruled = 0;  // grey rule pixels down the middle of the page
        for (int y = 100; y < 900; y++) {
            const QColor c = page.pixelColor(400, y);
            if (c != QColor(Qt::white) && c.red() < 250 && c.red() > 80) ruled++;
        }
        QVERIFY2(ruled > 5, "the page is ruled");

        const int before = views.count();
        QTest::wheelEvent(&canvas, QPointF(400, 500), QPoint(0, -120));
        QVERIFY(views.count() > before);
        xn_editor_zoom_at(canvas.editor(), 400, 500, 1.5);
        QVERIFY(views.count() > before + 1);

        canvas.setDark(true);
        QVERIFY(grab(canvas).pixelColor(400, 500).lightness() < 60);

        canvas.setNote(nullptr);
        xn_note_close(note);
    }

    /* The highlighter multiplies over what is under it: yellow on paper, the red ink stays red. */
    void highlighterMultiplies() {
        xn_note* note = xn_note_new(1);
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        canvas.setDark(false);
        xn_editor_zoom_at(canvas.editor(), 400, 300, 4.0);
        canvas.setTool("pen");
        xn_editor_set_ink_color(canvas.editor(), 0xff0000ff);
        QTest::mousePress(&canvas, Qt::LeftButton, {}, QPoint(400, 150));
        for (int i = 1; i <= 30; i++) QTest::mouseMove(&canvas, QPoint(400, 150 + i * 10));
        QTest::mouseRelease(&canvas, Qt::LeftButton, {}, QPoint(400, 450));
        canvas.setTool("highlighter");
        xn_editor_set_ink_color(canvas.editor(), 0xffeb3bff);
        QTest::mousePress(&canvas, Qt::LeftButton, {}, QPoint(250, 300));
        for (int i = 1; i <= 30; i++) QTest::mouseMove(&canvas, QPoint(250 + i * 10, 300));
        QTest::mouseRelease(&canvas, Qt::LeftButton, {}, QPoint(550, 300));
        QCOMPARE(xn_note_item_count(note, 0), 2);

        const QImage shot = grab(canvas);
        const QColor onPaper = shot.pixelColor(300, 300), onInk = shot.pixelColor(400, 300), paper = shot.pixelColor(300, 600);
        QVERIFY2(onPaper.red() > 200 && onPaper.green() > 200 && onPaper.blue() < 200, qPrintable(onPaper.name()));
        QVERIFY2(onInk.red() > 200 && onInk.green() < 60 && onInk.blue() < 60, qPrintable(onInk.name()));
        QCOMPARE(paper, QColor(Qt::white));

        canvas.setNote(nullptr);
        QCOMPARE(canvas.liveSurfaces(), 0);
        xn_note_close(note);
    }

    /* A lasso around a stroke selects it; the bar shows over it and its actions reach the core. */
    void lassoSelectionBar() {
        xn_note* note = xn_note_new(1);
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        canvas.setTool("pen");
        QTest::mousePress(&canvas, Qt::LeftButton, {}, QPoint(300, 400));
        for (int i = 1; i <= 20; i++) QTest::mouseMove(&canvas, QPoint(300 + i * 5, 400 + (i % 4)));
        QTest::mouseRelease(&canvas, Qt::LeftButton, {}, QPoint(400, 400));
        QCOMPARE(xn_note_item_count(note, 0), 1);

        SelectionBar bar(&canvas);
        QSignalSpy menus(&canvas, &CanvasWidget::selectionMenu);
        connect(&canvas, &CanvasWidget::selectionMenu, &bar, [&bar](bool shown, QRectF) { bar.onMenu(shown); });
        canvas.setTool("lasso");
        const QPoint loop[] = {{250, 350}, {450, 350}, {450, 450}, {250, 450}, {250, 352}};
        QTest::mousePress(&canvas, Qt::LeftButton, {}, loop[0]);
        for (int k = 1; k < 5; k++)
            for (int i = 1; i <= 10; i++) QTest::mouseMove(&canvas, loop[k - 1] + (loop[k] - loop[k - 1]) * i / 10);
        QTest::mouseRelease(&canvas, Qt::LeftButton, {}, loop[4]);
        QVERIFY(xn_editor_has_selection(canvas.editor()));
        QVERIFY(!menus.isEmpty() && menus.last().at(0).toBool());
        const QRectF sel = canvas.selectionRect();
        QVERIFY2(sel.left() > 280 && sel.right() < 420, qPrintable(QStringLiteral("%1 %2").arg(sel.left()).arg(sel.right())));
        QVERIFY(bar.isVisible());
        QVERIFY(bar.geometry().bottom() < sel.top() || bar.geometry().top() > sel.bottom());

        xn_editor_duplicate(canvas.editor());
        QCOMPARE(xn_note_item_count(note, 0), 2);
        xn_editor_delete_selection(canvas.editor());
        QCOMPARE(xn_note_item_count(note, 0), 1);
        QVERIFY(!xn_editor_has_selection(canvas.editor()));
        QVERIFY(!bar.isVisible());

        // A shape drawn with the mouse.
        const xn_shape_style style{"rectangle", 4.0, 1, 0.3, 0};
        xn_editor_set_shape_style(canvas.editor(), &style);
        canvas.setTool("shape");
        QTest::mousePress(&canvas, Qt::LeftButton, {}, QPoint(200, 600));
        for (int i = 1; i <= 10; i++) QTest::mouseMove(&canvas, QPoint(200 + i * 20, 600 + i * 10));
        QTest::mouseRelease(&canvas, Qt::LeftButton, {}, QPoint(400, 700));
        QCOMPARE(xn_note_item_count(note, 0), 2);

        canvas.setNote(nullptr);
        xn_note_close(note);
    }

    /* A text box: a click opens the field, typing fills it, a click elsewhere files it on the page. */
    void textBox() {
        xn_note* note = xn_note_new(1);
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        QVERIFY(QTest::qWaitForWindowExposed(&canvas));
        canvas.setNote(note);
        canvas.setDark(false);
        xn_editor_set_ink_color(canvas.editor(), 0xff0000ff);
        xn_editor_set_text_size(canvas.editor(), 24);
        QSignalSpy tools(&canvas, &CanvasWidget::toolChanged);
        canvas.setTool("text_box");
        QTest::mouseClick(&canvas, Qt::LeftButton, {}, QPoint(200, 300));
        auto* field = canvas.findChild<TextOverlay*>();
        QVERIFY(field && field->isVisible());
        QVERIFY(qAbs(field->x() - 200) < 20 && qAbs(field->y() - 300) < 30);
        for (const QChar ch : QStringLiteral("Привет, мир")) {  // QTest::keyClicks is ASCII only
            QKeyEvent press(QEvent::KeyPress, Qt::Key_unknown, Qt::NoModifier, QString(ch));
            QApplication::sendEvent(field, &press);
        }
        QCOMPARE(field->text(), QStringLiteral("Привет, мир"));
        QCOMPARE(xn_note_item_count(note, 0), 0);  // still a draft

        QTest::mouseClick(&canvas, Qt::LeftButton, {}, QPoint(500, 800));
        QVERIFY(!field->isVisible());
        QCOMPARE(xn_note_item_count(note, 0), 1);
        QVERIFY(!tools.isEmpty() && tools.last().at(0).toString() == QLatin1String("pen"));
        QVERIFY2(inkPixels(grab(canvas), QRect(190, 290, 300, 60)) > 50, "the text is drawn on the page");

        // Finishing the edit re-arms the tool the text tool replaced; clicking the box again with
        // the text tool reopens it with its text.
        QCOMPARE(QByteArray(xn_editor_tool(canvas.editor())), QByteArray("pen"));
        canvas.setTool("text_box");
        QTest::mouseClick(&canvas, Qt::LeftButton, {}, QPoint(215, 310));
        QVERIFY(field->isVisible());
        QCOMPARE(field->text(), QStringLiteral("Привет, мир"));
        field->selectAll();
        QTest::keyClick(field, Qt::Key_Backspace);
        xn_editor_text_commit(canvas.editor());
        QCOMPARE(xn_note_item_count(note, 0), 0);  // emptied: removed

        canvas.setNote(nullptr);
        xn_note_close(note);
    }

    /* An image file on the page, drawn from the file; it survives save and reopen. */
    void image() {
        QTemporaryDir dir;
        const QString png = dir.filePath(QStringLiteral("red.png"));
        QImage red(200, 100, QImage::Format_RGB32);
        red.fill(QColor(255, 0, 0));
        QVERIFY(red.save(png));
        xn_note* note = xn_note_new(1);
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        canvas.setDark(false);
        QVERIFY(!xn_editor_insert_image(canvas.editor(), "/no/such.png", 0, 0, 0));
        QVERIFY(xn_editor_insert_image(canvas.editor(), QFile::encodeName(png).constData(), 1, 400, 500));
        QCOMPARE(xn_note_item_count(note, 0), 1);
        QVERIFY2(inkPixels(grab(canvas), QRect(380, 490, 40, 20)) == 40 * 20, "the image is drawn where it was put");

        const QByteArray saved = QFile::encodeName(dir.filePath(QStringLiteral("img.xnote")));
        char* error = nullptr;
        QVERIFY(xn_note_save(note, saved.constData(), canvas.host(), &error));
        canvas.setNote(nullptr);
        xn_note_close(note);
        QFile::remove(png);  // the saved note carries its own copy
        QTemporaryDir work;
        xn_note* back = xn_note_open(saved.constData(), QFile::encodeName(work.path()).constData(), canvas.host(), &error);
        QVERIFY2(back, error);
        canvas.setNote(back);
        QVERIFY(inkPixels(grab(canvas), canvas.rect()) > 1000);
        canvas.setNote(nullptr);
        xn_note_close(back);
    }

    /* A PDF becomes the pages' background; ink over it exports into a PDF of the same pages. */
    void pdfImportAndExport() {
        QTemporaryDir dir;
        const QString source = dir.filePath(QStringLiteral("lecture.pdf"));
        {
            QPdfWriter writer(source);
            writer.setPageSize(QPageSize(QPageSize::A4));
            writer.setPageMargins(QMarginsF(0, 0, 0, 0));
            writer.setResolution(72);
            QPainter p(&writer);
            p.fillRect(QRectF(100, 100, 300, 200), QColor(0, 0, 255));  // points from the top-left
            writer.newPage();
            p.fillRect(QRectF(50, 50, 100, 100), QColor(0, 160, 0));
        }
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        char* error = nullptr;
        xn_note* note = xn_note_import_pdf(QFile::encodeName(source).constData(), canvas.host(), &error);
        QVERIFY2(note, error);
        QCOMPARE(xn_note_page_count(note), 2);
        canvas.setNote(note);
        canvas.setDark(false);
        auto blue = [](const QImage& img) {
            int n = 0;
            for (int y = 0; y < img.height(); y += 2)
                for (int x = 0; x < img.width(); x += 2) {
                    const QColor c = img.pixelColor(x, y);
                    if (c.blue() > 200 && c.red() < 60 && c.green() < 60) n++;
                }
            return n;
        };
        QVERIFY2(blue(grab(canvas)) > 1000, "the PDF page is drawn as the background");

        xn_editor_set_ink_color(canvas.editor(), 0xff0000ff);
        canvas.setTool("pen");
        QTest::mousePress(&canvas, Qt::LeftButton, {}, QPoint(200, 700));
        for (int i = 1; i <= 30; i++) QTest::mouseMove(&canvas, QPoint(200 + i * 10, 700));
        QTest::mouseRelease(&canvas, Qt::LeftButton, {}, QPoint(500, 700));
        QCOMPARE(xn_note_item_count(note, 0), 1);

        const QString out = dir.filePath(QStringLiteral("export.pdf"));
        int pagesDone = 0;
        QCOMPARE(writePdf(note, out, 0xFFFFFFFFu, [&](int done, int) { pagesDone = done; }), QString());
        QCOMPARE(pagesDone, 2);
        QPdfDocument exported;
        QCOMPARE(exported.load(out), QPdfDocument::Error::None);
        QCOMPARE(exported.pageCount(), 2);
        const QSizeF size = exported.pagePointSize(0);
        QVERIFY2(qAbs(size.width() - 595) < 2 && qAbs(size.height() - 842) < 2, qPrintable(QStringLiteral("%1x%2").arg(size.width()).arg(size.height())));
        const QImage page = exported.render(0, QSize(595, 842)).convertToFormat(QImage::Format_RGB32);
        QVERIFY2(blue(page) > 1000, "the PDF background is in the export");
        QVERIFY2(inkPixels(page, page.rect()) > 50, "the ink is in the export");

        canvas.setNote(nullptr);
        xn_note_close(note);
    }

    /* The scroll bars follow the view and move it. */
    void scrollBars() {
        MainWindow window;
        window.newNote();
        window.resize(900, 700);
        window.show();
        QVERIFY(QTest::qWaitForWindowExposed(&window));
        auto* canvas = window.findChild<CanvasWidget*>();
        for (int i = 0; i < 3; i++) xn_editor_add_page(canvas->editor());
        QScrollBar* vbar = nullptr;
        for (QScrollBar* b : window.findChildren<QScrollBar*>())
            if (b->orientation() == Qt::Vertical && b->parentWidget() == canvas->parentWidget()) vbar = b;
        QVERIFY(vbar);
        QVERIFY(vbar->maximum() > 0);
        vbar->setValue(vbar->maximum() / 2);
        double sc[6];
        xn_editor_scroll_info(canvas->editor(), sc);
        QVERIFY(qAbs(sc[1] - vbar->maximum() / 2) <= 1);
        xn_editor_scroll_to(canvas->editor(), 0, 0);
        QCOMPARE(vbar->value(), 0);
    }

    /* Notes made from a PDF are found by name and, renamed, by the PDF they carry. */
    void notesForPdf_() {
        QTemporaryDir dir;
        auto makePdf = [&](const QString& name, QColor color) {
            const QString path = dir.filePath(name);
            QPdfWriter writer(path);
            QPainter p(&writer);
            p.fillRect(QRectF(0, 0, 500, 500), color);
            return path;
        };
        const QString a = makePdf(QStringLiteral("a.pdf"), Qt::red);
        const QString b = makePdf(QStringLiteral("b.pdf"), Qt::blue);
        QVERIFY(notesForPdf(a).isEmpty());

        CanvasWidget canvas;
        auto saveNoteOf = [&](const QString& pdf, const QString& name) {
            QTemporaryDir work;
            const QString copy = work.filePath(QStringLiteral("source.pdf"));
            QFile::copy(pdf, copy);
            char* error = nullptr;
            xn_note* note = xn_note_import_pdf(QFile::encodeName(copy).constData(), canvas.host(), &error);
            QVERIFY2(note, error);
            QVERIFY(xn_note_save(note, QFile::encodeName(dir.filePath(name)).constData(), canvas.host(), &error));
            xn_note_close(note);
        };
        saveNoteOf(a, QStringLiteral("renamed.xnote"));
        saveNoteOf(b, QStringLiteral("b.xnote"));

        const auto embedded = embeddedPdf(dir.filePath(QStringLiteral("renamed.xnote")));
        QVERIFY(embedded && embedded->size == uint64_t(QFileInfo(a).size()));
        QCOMPARE(notesForPdf(a), QStringList{dir.filePath(QStringLiteral("renamed.xnote"))});
        QCOMPARE(notesForPdf(b), QStringList{dir.filePath(QStringLiteral("b.xnote"))});

        // A note named after the PDF comes first, whatever it holds.
        QFile::copy(dir.filePath(QStringLiteral("b.xnote")), dir.filePath(QStringLiteral("a.xnote")));
        const QStringList both = notesForPdf(a);
        QCOMPARE(both.size(), 2);
        QCOMPARE(both.first(), dir.filePath(QStringLiteral("a.xnote")));
        QVERIFY(!embeddedPdf(a));  // not a ZIP at all
    }

    /*
     * Frames while writing repaint only where the stroke grew. Painted that way frame after frame
     * into one kept target, the result must be the full frame, for ink and for the highlighter's
     * multiply layer, on whichever path this run renders.
     */
    void partialFramesMatchFull() { partialFramesOn(xn_note_new(1)); }

    /* The same on an infinite canvas, whose ink comes from tiles and a composed base. */
    void partialFramesMatchFullOnCanvas() { partialFramesOn(xn_canvas_new()); }

    void partialFramesOn(xn_note* note) {
        CanvasWidget canvas;
        canvas.resize(700, 900);
        canvas.show();
        canvas.setNote(note);
        canvas.setDark(false);
        QTest::qWait(250);
        const bool gl = canvas.render() == CanvasWidget::Render::Gl;
        KeptTarget kept(canvas, gl);
        KeptTarget(canvas, gl).frame(false);  // settle caches

        auto stroke = [&](const char* tool, xn_rgba color, double y) {
            canvas.setTool(tool);
            xn_editor_set_ink_color(canvas.editor(), color);
            kept.frame(true);  // full: tool and colour changed
            tablet(&canvas, QEvent::TabletPress, {100, y}, 0.5, Qt::LeftButton, Qt::LeftButton);
            int partial = 0;
            double area = 0;
            for (int i = 1; i <= 120; i++) {
                tablet(&canvas, QEvent::TabletMove, {100 + i * 4.0, y + 25 * std::sin(i * 0.15)}, 0.3 + (i % 20) * 0.03, Qt::NoButton, Qt::LeftButton);
                kept.frame(true);
                if (!canvas.lastDamage().isEmpty()) {
                    partial++;
                    area += canvas.lastDamage().width() * canvas.lastDamage().height();
                }
            }
            const QImage incremental = kept.image();
            const QImage full = KeptTarget(canvas, gl).frame(false).image();
            // The settled run is baked into a raster while it stays on screen as it was drawn live,
            // so edges may differ by antialiasing, and a thin line by a fraction of a pixel; missing
            // or extra ink would differ by far more. Compared through a 3x3 average, which a
            // sub-pixel shift survives and a hole does not.
            auto blurred = [](const QImage& img, int x, int y, int c) {
                int sum = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        const int xx = std::clamp(x + dx, 0, img.width() - 1), yy = std::clamp(y + dy, 0, img.height() - 1);
                        const QRgb p = img.pixel(xx, yy);
                        sum += c == 0 ? qRed(p) : c == 1 ? qGreen(p) : qBlue(p);
                        n++;
                    }
                return sum / n;
            };
            int edges = 0, wrong = 0;
            for (int yy = 0; yy < full.height(); yy++)
                for (int xx = 0; xx < full.width(); xx++) {
                    const QColor a = full.pixelColor(xx, yy), b = incremental.pixelColor(xx, yy);
                    const int d = std::max({qAbs(a.red() - b.red()), qAbs(a.green() - b.green()), qAbs(a.blue() - b.blue())});
                    if (d <= 32) continue;
                    edges++;
                    int bd = 0;
                    for (int c = 0; c < 3; c++) bd = std::max(bd, qAbs(blurred(full, xx, yy, c) - blurred(incremental, xx, yy, c)));
                    if (bd > 64) wrong++;
                }
            if (wrong > 0 && qEnvironmentVariableIsSet("XNOTES_SMOKE_SHOT")) {
                incremental.save(qEnvironmentVariable("XNOTES_SMOKE_SHOT") + "-inc.png");
                full.save(qEnvironmentVariable("XNOTES_SMOKE_SHOT") + "-full.png");
            }
            tablet(&canvas, QEvent::TabletRelease, {580, y}, 0, Qt::LeftButton, Qt::NoButton);
            QVERIFY2(wrong == 0, qPrintable(QStringLiteral("%1: %2 pixels wrong").arg(tool).arg(wrong)));
            QVERIFY2(edges < 600, qPrintable(QStringLiteral("%1: %2 edge pixels differ").arg(tool).arg(edges)));
            QVERIFY2(partial > 100, qPrintable(QStringLiteral("%1: %2 partial frames").arg(tool).arg(partial)));
            QVERIFY2(area / partial < 0.1 * canvas.width() * canvas.height(), "partial frames are small");
            kept.frame(true);
            QVERIFY(canvas.lastDamage().isEmpty());  // filing the stroke repaints in full
        };
        stroke("pen", 0x1060c0ff, 300);
        stroke("highlighter", 0xffeb3bff, 310);  // across the ink
        canvas.setNote(nullptr);
        xn_note_close(note);
    }

    /* The pen's second button drags the canvas; the first, or both, erase; a pinch zooms. */
    void penButtonsAndPinch() {
        xn_note* note = xn_note_new(3);
        CanvasWidget canvas;
        canvas.resize(800, 900);
        canvas.show();
        canvas.setNote(note);
        canvas.setTool("pen");
        auto line = [&](double y, Qt::MouseButtons extra) {
            tablet(&canvas, QEvent::TabletPress, {200, y}, 0.5, Qt::LeftButton, Qt::LeftButton | extra);
            for (int i = 1; i <= 20; i++) tablet(&canvas, QEvent::TabletMove, {200.0 + i * 10, y}, 0.5, Qt::NoButton, Qt::LeftButton | extra);
            tablet(&canvas, QEvent::TabletRelease, {400, y}, 0, Qt::LeftButton, extra);
        };
        line(300, Qt::NoButton);
        QCOMPARE(xn_note_item_count(note, 0), 1);

        // Second button: hover in, press it, drag up; the view scrolls down and nothing is drawn.
        double before[6], after[6];
        xn_editor_scroll_info(canvas.editor(), before);
        tablet(&canvas, QEvent::TabletMove, {400, 600}, 0, Qt::NoButton, Qt::NoButton);
        tablet(&canvas, QEvent::TabletPress, {400, 600}, 0, Qt::RightButton, Qt::RightButton);
        for (int i = 1; i <= 10; i++) tablet(&canvas, QEvent::TabletMove, {400, 600.0 - i * 20}, 0.5, Qt::NoButton, Qt::RightButton | Qt::LeftButton);
        tablet(&canvas, QEvent::TabletRelease, {400, 400}, 0, Qt::RightButton, Qt::LeftButton);
        xn_editor_scroll_info(canvas.editor(), after);
        QVERIFY2(after[1] - before[1] > 150, qPrintable(QStringLiteral("scrolled %1").arg(after[1] - before[1])));
        QCOMPARE(xn_note_item_count(note, 0), 1);
        tablet(&canvas, QEvent::TabletRelease, {400, 400}, 0, Qt::LeftButton, Qt::NoButton);
        xn_editor_scroll_to(canvas.editor(), 0, 0);

        // Both buttons: the eraser, across the line.
        tablet(&canvas, QEvent::TabletPress, {300, 260}, 0.5, Qt::LeftButton, Qt::LeftButton | Qt::MiddleButton | Qt::RightButton);
        for (int i = 1; i <= 16; i++) tablet(&canvas, QEvent::TabletMove, {300, 260.0 + i * 5}, 0.5, Qt::NoButton, Qt::LeftButton | Qt::MiddleButton | Qt::RightButton);
        tablet(&canvas, QEvent::TabletRelease, {300, 340}, 0, Qt::LeftButton, Qt::MiddleButton | Qt::RightButton);
        QCOMPARE(xn_note_item_count(note, 0), 0);

        // A touchpad pinch.
        const double zoom = xn_editor_zoom(canvas.editor());
        for (int i = 0; i < 5; i++) {
            QNativeGestureEvent g(Qt::ZoomNativeGesture, QPointingDevice::primaryPointingDevice(), 2, QPointF(400, 400),
                                  QPointF(400, 400), canvas.mapToGlobal(QPointF(400, 400)), 0.1, QPointF());
            QApplication::sendEvent(&canvas, &g);
        }
        QVERIFY2(xn_editor_zoom(canvas.editor()) > zoom * 1.4, qPrintable(QStringLiteral("%1 -> %2").arg(zoom).arg(xn_editor_zoom(canvas.editor()))));

        canvas.setNote(nullptr);
        xn_note_close(note);
    }

    /* The page background dialog: grid for the whole note, lines for one page, a cream paper. */
    void pageBackground() {
        xn_note* note = xn_note_new(2);
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        canvas.setDark(false);
        // Rule pixels crossed by a vertical and by a horizontal scan through the page.
        auto rules = [&](const QImage& img) {
            auto grey = [&](int x, int y) {
                const QColor c = img.pixelColor(x, y);
                return c.red() < 245 && qAbs(c.red() - c.blue()) < 20 && c.red() > 120;
            };
            int across = 0, down = 0;
            for (int y = 200; y < 800; y++) across += grey(400, y) && !grey(400, y - 1);
            for (int x = 150; x < 650; x++) down += grey(x, 500) && !grey(x - 1, 500);
            return std::pair(across, down);
        };
        QCOMPARE(rules(grab(canvas)), std::pair(0, 0));

        PageStylePopup dialog(canvas.editor(), false, nullptr);
        auto button = [&](const QString& text) {
            for (QAbstractButton* b : dialog.findChildren<QAbstractButton*>())
                if (b->text() == text) return b;
            return static_cast<QAbstractButton*>(nullptr);
        };
        button(QStringLiteral("Клетка"))->click();
        auto [across, down] = rules(grab(canvas));
        QVERIFY2(across > 3 && down > 3, qPrintable(QStringLiteral("grid: %1 across, %2 down").arg(across).arg(down)));
        QVERIFY(xn_note_is_dirty(note));

        button(QStringLiteral("Текущая страница"))->click();
        button(QStringLiteral("Линейка"))->click();
        std::tie(across, down) = rules(grab(canvas));
        QVERIFY2(across > 3 && down == 0, qPrintable(QStringLiteral("lines: %1 across, %2 down").arg(across).arg(down)));
        xn_page_style all{};
        xn_editor_page_style(canvas.editor(), 1, &all);
        QCOMPARE(all.pattern, int(XN_PATTERN_GRID));

        button(QStringLiteral("Все страницы"))->click();
        xn_page_style cream{1, 0xF7F3E9FFu, XN_PATTERN_GRID, 0, 0, 0};
        xn_editor_set_page_style(canvas.editor(), 1, &cream);
        QCOMPARE(grab(canvas).pixelColor(160, 160), QColor(0xF7, 0xF3, 0xE9));

        canvas.setNote(nullptr);
        xn_note_close(note);
    }

    /* The toolbar's eraser controls: size and mode, used by the eraser tool. */
    void eraserControls() {
        MainWindow window;
        window.newNote();
        window.resize(2400, 900);  // the whole toolbar in view
        window.show();
        QVERIFY(QTest::qWaitForWindowExposed(&window));
        auto* canvas = window.findChild<CanvasWidget*>();
        auto action = [&](const QString& text) {
            for (QAction* a : window.findChildren<QAction*>())
                if (a->text() == text) return a;
            return static_cast<QAction*>(nullptr);
        };
        QComboBox* mode = nullptr;
        for (QComboBox* c : window.findChildren<QComboBox*>())
            if (c->findText(QStringLiteral("Область")) >= 0) mode = c;
        QVERIFY(mode);
        QSlider* size = nullptr;  // the toolbar's width slider: half-pixel steps
        for (QSlider* sl : window.findChildren<QSlider*>())
            if (sl->orientation() == Qt::Horizontal && sl->parentWidget() && sl->parentWidget()->inherits("QToolBar")) size = sl;
        QVERIFY(size);

        action(QStringLiteral("Перо"))->trigger();
        QTest::mousePress(canvas, Qt::LeftButton, {}, QPoint(300, 400));
        for (int i = 1; i <= 40; i++) QTest::mouseMove(canvas, QPoint(300 + i * 10, 400));
        QTest::mouseRelease(canvas, Qt::LeftButton, {}, QPoint(700, 400));
        QCOMPARE(xn_note_item_count(window.note(), 0), 1);

        action(QStringLiteral("Ластик"))->trigger();
        QCOMPARE(QByteArray(xn_editor_tool(canvas->editor())), QByteArray("eraser"));
        QTest::qWait(50);  // the toolbar lays out its shown widgets on the next pass
        QVERIFY(mode->isVisibleTo(&window));
        QCOMPARE(size->value(), 48);   // radius 24
        QCOMPARE(size->maximum(), 160); // up to 80
        mode->setCurrentIndex(mode->findText(QStringLiteral("Область")));
        QCOMPARE(xn_editor_erase_mode(canvas->editor()), int(XN_ERASE_AREA));
        size->setValue(16);  // radius 8
        QCOMPARE(xn_editor_tool_width(canvas->editor(), "eraser"), 8.0);
        QTest::mousePress(canvas, Qt::LeftButton, {}, QPoint(500, 370));
        for (int i = 1; i <= 12; i++) QTest::mouseMove(canvas, QPoint(500, 370 + i * 5));
        QTest::mouseRelease(canvas, Qt::LeftButton, {}, QPoint(500, 430));
        QCOMPARE(xn_note_item_count(window.note(), 0), 2);  // cut in two

        action(QStringLiteral("Перо"))->trigger();
        QTest::qWait(50);
        QVERIFY(!mode->isVisibleTo(&window));
        QCOMPARE(size->value(), 6);      // the pen keeps its own width, 3
        QCOMPARE(size->maximum(), 40);   // up to 20
        action(QStringLiteral("Маркер"))->trigger();
        QCOMPARE(size->minimum(), 8);    // 4..40
        size->setValue(30);
        QCOMPARE(xn_editor_tool_width(canvas->editor(), "highlighter"), 15.0);
        xn_note* n = window.note();
        QVERIFY(xn_note_is_dirty(n));
    }

    /* An infinite canvas in the window: page controls give way, ink goes on and pans, it saves. */
    void infiniteCanvas() {
        QTemporaryDir dir;
        MainWindow window;
        window.newCanvas();
        window.resize(2400, 900);
        window.show();
        QVERIFY(QTest::qWaitForWindowExposed(&window));
        QTest::qWait(50);
        auto* canvas = window.findChild<CanvasWidget*>();
        auto action = [&](const QString& text) {
            for (QAction* a : window.findChildren<QAction*>())
                if (a->text() == text) return a;
            return static_cast<QAction*>(nullptr);
        };
        QVERIFY(xn_note_is_canvas(window.note()));
        QVERIFY(!action(QStringLiteral("+ Страница"))->isVisible());
        QVERIFY(!action(QStringLiteral("Текст"))->isVisible());
        QVERIFY(action(QStringLiteral("Показать всё")));
        QVERIFY(!action(QStringLiteral("Экспорт в PDF…"))->isEnabled());

        // Dark ink on white paper, with no ruling, so the ink is easy to find.
        canvas->setDark(false);
        xn_page_style plain{1, 0xFFFFFFFFu, XN_PATTERN_NONE, 0, 0, 0};
        xn_editor_set_page_style(canvas->editor(), 1, &plain);
        xn_editor_set_ink_color(canvas->editor(), 0xff0000ff);
        action(QStringLiteral("Перо"))->trigger();
        QTest::mousePress(canvas, Qt::LeftButton, {}, QPoint(300, 300));
        for (int i = 1; i <= 40; i++) QTest::mouseMove(canvas, QPoint(300 + i * 10, 300));
        QTest::mouseRelease(canvas, Qt::LeftButton, {}, QPoint(700, 300));
        QCOMPARE(xn_note_item_count(window.note(), 0), 1);
        auto redAt = [&](int y) { return inkPixels(grab(*canvas), QRect(320, y - 6, 360, 12)); };
        QVERIFY2(redAt(300) > 300, qPrintable(QStringLiteral("%1 ink pixels").arg(redAt(300))));

        // Scroll down by a wheel notch: the ink moves up by the same amount.
        QTest::wheelEvent(canvas, QPointF(500, 400), QPoint(0, -120));
        QVERIFY(redAt(300) < 20);
        QVERIFY(redAt(240) > 300);
        const double zoom = xn_editor_zoom(canvas->editor());
        action(QStringLiteral("+"))->trigger();
        QVERIFY(xn_editor_zoom(canvas->editor()) > zoom);
        action(QStringLiteral("Показать всё"))->trigger();
        QVERIFY(inkPixels(grab(*canvas), canvas->rect()) > 300);

        // Its background, through the dialog: one for the whole canvas.
        PageStylePopup dialog(canvas->editor(), true, nullptr);
        for (QAbstractButton* b : dialog.findChildren<QAbstractButton*>()) {
            if (b->text() == QStringLiteral("Текущая страница")) QVERIFY(!b->isVisibleTo(&dialog));
            if (b->text() == QStringLiteral("Точки")) b->click();
        }
        xn_page_style bg{};
        xn_editor_page_style(canvas->editor(), 1, &bg);
        QCOMPARE(bg.pattern, int(XN_PATTERN_DOTS));

        const QString path = dir.filePath(QStringLiteral("board.xcanvas"));
        char* error = nullptr;
        QVERIFY(xn_note_save(window.note(), QFile::encodeName(path).constData(), canvas->host(), &error));
        QVERIFY(window.open(path));
        QVERIFY(xn_note_is_canvas(window.note()));
        QCOMPARE(xn_note_item_count(window.note(), 0), 1);
        QVERIFY(inkPixels(grab(*canvas), canvas->rect()) > 100);
        window.newNote();
        QVERIFY(action(QStringLiteral("+ Страница"))->isVisible());
    }

    /*
     * On a canvas the ink must not blink when the pen lifts: the first frame after the stroke is
     * filed already shows it from the tiles, for ink and for the highlighter, and every frame after.
     */
    void canvasInkStaysOnPenUp() {
        xn_note* note = xn_canvas_new();
        CanvasWidget canvas;
        canvas.resize(1200, 900);
        canvas.show();
        canvas.setNote(note);
        canvas.setDark(false);
        xn_page_style plain{1, 0xFFFFFFFFu, XN_PATTERN_NONE, 0, 0, 0};
        xn_editor_set_page_style(canvas.editor(), 1, &plain);
        const bool gl = canvas.render() == CanvasWidget::Render::Gl;
        // A dense canvas, so its tiles take real time to render.
        canvas.setTool("pen");
        xn_editor_set_ink_color(canvas.editor(), 0x808080ffu);
        for (int k = 0; k < 1500; k++) {
            const double x0 = 20 + (k % 50) * 23, y0 = 20 + (k / 50) * 29;
            tablet(&canvas, QEvent::TabletPress, {x0, y0}, 0.5, Qt::LeftButton, Qt::LeftButton);
            for (int i = 1; i <= 10; i++) tablet(&canvas, QEvent::TabletMove, {x0 + i * 2, y0 + 5 * std::sin(i)}, 0.5, Qt::NoButton, Qt::LeftButton);
            tablet(&canvas, QEvent::TabletRelease, {x0 + 20, y0}, 0, Qt::LeftButton, Qt::NoButton);
        }
        KeptTarget(canvas, gl).frame(false);
        // Ink across several tiles (512 px each), so a budgeted frame could not render them all.
        // As the app does: frames until every tile in view is rendered.
        auto settle = [&] {
            for (int f = 0; f < 20; f++) KeptTarget(canvas, gl).frame(false);
        };
        // Inked columns: where the frame differs from the one before the stroke (the highlighter
        // tints whatever is under it, the grey ink included).
        auto line = [&](const char* tool, xn_rgba color, double y) {
            canvas.setTool(tool);
            xn_editor_set_ink_color(canvas.editor(), color);
            settle();
            const QImage before = KeptTarget(canvas, gl).frame(false).image();
            tablet(&canvas, QEvent::TabletPress, {50, y}, 0.6, Qt::LeftButton, Qt::LeftButton);
            for (int i = 1; i <= 110; i++) tablet(&canvas, QEvent::TabletMove, {50 + i * 10.0, y + (i % 2)}, 0.6, Qt::NoButton, Qt::LeftButton);
            tablet(&canvas, QEvent::TabletRelease, {1150, y}, 0, Qt::LeftButton, Qt::NoButton);
            for (int f = 0; f < 4; f++) {
                const QImage img = KeptTarget(canvas, gl).frame(false).image();
                int n = 0;
                for (int x = 100; x < 1100; x++) {
                    const QColor a = before.pixelColor(x, int(y)), b = img.pixelColor(x, int(y));
                    n += qAbs(a.red() - b.red()) + qAbs(a.green() - b.green()) + qAbs(a.blue() - b.blue()) > 40;
                }
                QVERIFY2(n > 900, qPrintable(QStringLiteral("%1, frame %2 after pen up: %3 of 1000 columns inked").arg(tool).arg(f).arg(n)));
            }
        };
        line("pen", 0xd02020ffu, 300);
        line("highlighter", 0x2090f0ffu, 600);
        canvas.setNote(nullptr);
        xn_note_close(note);
    }

    /* The icons are built in, and every tool on the toolbar shows one. */
    void icons() {
        QVERIFY(QFile::exists(QStringLiteral(":/icons/eraser.svg")));
        QVERIFY(QFile::exists(QStringLiteral(":/icons/ic_stroke_regular.svg")));
        const QIcon eraser = xnIcon(QStringLiteral("eraser"), Qt::black);
        QVERIFY(!eraser.isNull());
        const QImage img = eraser.pixmap(48, 48).toImage();
        int dark = 0;
        for (int y = 0; y < img.height(); y++)
            for (int x = 0; x < img.width(); x++) dark += qAlpha(img.pixel(x, y)) > 128;
        QVERIFY2(dark > 50, "the eraser is drawn");
        QVERIFY(xnIcon(QStringLiteral("no-such-icon")).isNull());

        MainWindow window;
        window.newNote();
        for (const char* tool : {"Рука", "Перо", "Маркер", "Ластик", "Лассо", "Выделение", "Фигура", "Текст"}) {
            QAction* found = nullptr;
            for (QAction* a : window.findChildren<QAction*>())
                if (a->text() == QString::fromUtf8(tool)) found = a;
            QVERIFY2(found && !found->icon().isNull(), tool);
        }
    }

    void eraserEndErases() {
        xn_note* note = xn_note_new(1);
        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        canvas.setTool("pen");
        tablet(&canvas, QEvent::TabletPress, {200, 300}, 0.5, Qt::LeftButton, Qt::LeftButton);
        for (int i = 1; i <= 20; i++) tablet(&canvas, QEvent::TabletMove, {200.0 + i * 10, 300}, 0.5, Qt::NoButton, Qt::LeftButton);
        tablet(&canvas, QEvent::TabletRelease, {400, 300}, 0, Qt::LeftButton, Qt::NoButton);
        QCOMPARE(xn_note_item_count(note, 0), 1);

        // The pen's back end erases whatever tool is armed.
        static auto* eraser = new QPointingDevice(QStringLiteral("test eraser"), 43, QInputDevice::DeviceType::Stylus,
                                                  QPointingDevice::PointerType::Eraser,
                                                  QInputDevice::Capability::Position | QInputDevice::Capability::Pressure, 1, 3);
        auto send = [&](QEvent::Type type, QPointF at, Qt::MouseButton b, Qt::MouseButtons bs) {
            QTabletEvent e(type, eraser, at, canvas.mapToGlobal(at), 0.5, 0, 0, 0, 0, 0, Qt::NoModifier, b, bs);
            QApplication::sendEvent(&canvas, &e);
        };
        send(QEvent::TabletPress, {300, 250}, Qt::LeftButton, Qt::LeftButton);
        for (int i = 1; i <= 20; i++) send(QEvent::TabletMove, {300, 250.0 + i * 5}, Qt::NoButton, Qt::LeftButton);
        send(QEvent::TabletRelease, {300, 350}, Qt::LeftButton, Qt::NoButton);
        QCOMPARE(xn_note_item_count(note, 0), 0);

        canvas.setNote(nullptr);
        xn_note_close(note);
    }
    /* The pages panel: a thumbnail per page that follows the ink, and pages added, deleted, copied and pasted. */
    void pagesPanel() {
        MainWindow window;
        window.newNote();
        window.resize(1100, 800);
        window.show();
        QVERIFY(QTest::qWaitForWindowExposed(&window));
        auto* canvas = window.findChild<CanvasWidget*>();
        auto* panel = window.findChild<PagesPanel*>();
        QVERIFY(panel && panel->isVisible());
        xn_note* note = window.note();
        QCOMPARE(panel->pageCount(), 1);
        QTRY_COMPARE(panel->pendingThumbnails(), 0);
        QVERIFY(panel->thumbnail(0));

        // Ink on the page reaches its thumbnail once the edit settles.
        xn_editor_set_ink_color(canvas->editor(), 0xff0000ff);
        canvas->setTool("pen");
        QTest::mousePress(canvas, Qt::LeftButton, {}, QPoint(250, 200));
        for (int i = 1; i <= 30; i++) QTest::mouseMove(canvas, QPoint(250 + i * 8, 200 + i * 4));
        QTest::mouseRelease(canvas, Qt::LeftButton, {}, QPoint(490, 320));
        QCOMPARE(xn_note_item_count(note, 0), 1);
        QTest::qWait(400);
        QTRY_COMPARE(panel->pendingThumbnails(), 0);
        // Thin at this scale, so blended with the paper: count reddish pixels.
        auto reddish = [](const QImage* img) {
            int n = 0;
            for (int y = 0; y < img->height(); y++)
                for (int x = 0; x < img->width(); x++) {
                    const QColor c = img->pixelColor(x, y);
                    if (c.red() > 80 && c.red() > 2 * c.green() + 30) n++;
                }
            return n;
        };
        QVERIFY2(reddish(panel->thumbnail(0)) > 20, "the stroke is in the thumbnail");

        // The add button puts a page after the current one and goes to it.
        window.findChild<QToolButton*>(QStringLiteral("addPage"))->click();
        QCOMPARE(xn_note_page_count(note), 2);
        QCOMPARE(panel->pageCount(), 2);
        QCOMPARE(panel->currentPage(), 1);
        QTRY_COMPARE(panel->pendingThumbnails(), 0);
        QCOMPARE(reddish(panel->thumbnail(1)), 0);

        // A click goes to a page.
        QListView* list = panel->list();
        QTest::mouseClick(list->viewport(), Qt::LeftButton, {}, list->visualRect(list->model()->index(0, 0)).center());
        QCOMPARE(xn_editor_current_page(canvas->editor()), 0);
        QCOMPARE(panel->targetPages(), QList<int>{0});

        // Copy and paste with the keys while the list has the focus: the copy carries the ink.
        list->setFocus();
        QTest::keyClick(list, Qt::Key_C, Qt::ControlModifier);
        QCOMPARE(xn_editor_can_paste_pages(canvas->editor()), 1);
        QCOMPARE(xn_editor_can_paste(canvas->editor()), 0);  // the items' clipboard is left alone
        QTest::keyClick(list, Qt::Key_V, Qt::ControlModifier);
        QCOMPARE(xn_note_page_count(note), 3);
        QCOMPARE(xn_note_item_count(note, 1), 1);

        // Several pages at once, then the last one stays.
        list->selectionModel()->select(QItemSelection(list->model()->index(1, 0), list->model()->index(2, 0)), QItemSelectionModel::ClearAndSelect);
        QCOMPARE(panel->targetPages(), (QList<int>{1, 2}));
        QTest::keyClick(list, Qt::Key_Delete);
        QCOMPARE(xn_note_page_count(note), 1);
        QCOMPARE(panel->pageCount(), 1);
        QCOMPARE(xn_note_item_count(note, 0), 1);
        window.findChild<QToolButton*>(QStringLiteral("deletePages"))->click();
        QCOMPARE(xn_note_page_count(note), 1);
        xn_editor_undo(canvas->editor());
        QCOMPARE(panel->pageCount(), 3);

        // A canvas has no pages: the panel steps aside.
        window.newCanvas();
        QVERIFY(!panel->isVisible());
        QCOMPARE(panel->pageCount(), 0);
        window.newNote();
        QVERIFY(panel->isVisible());
        QCOMPARE(panel->pageCount(), 1);
    }
    /*
     * Pages are drawn on the core's threads: a frame never waits for one, the page shows once its
     * cache lands, and an editor closed while pages are being drawn waits for them and frees all.
     */
    void pagesDrawnInBackground() {
        // Ten pages of dense ink, so a page takes a while to draw.
        xn_note* note = xn_note_new(10);
        {
            CanvasWidget writer;
            writer.resize(800, 1000);
            writer.show();
            writer.setNote(note);
            writer.setTool("pen");
            xn_editor_set_ink_color(writer.editor(), 0xff0000ff);
            for (int page = 0; page < 10; page++) {
                xn_editor_go_to_page(writer.editor(), page);
                for (int line = 0; line < 30; line++) {
                    const int y = 120 + line * 25;
                    QTest::mousePress(&writer, Qt::LeftButton, {}, QPoint(100, y));
                    for (int i = 1; i <= 40; i++) QTest::mouseMove(&writer, QPoint(100 + i * 15, y + (i % 2) * 6));
                    QTest::mouseRelease(&writer, Qt::LeftButton, {}, QPoint(700, y));
                }
            }
            writer.setNote(nullptr);
            QCOMPARE(writer.liveSurfaces(), 0);
        }
        QCOMPARE(xn_note_item_count(note, 9), 30);

        CanvasWidget canvas;
        canvas.resize(800, 1000);
        canvas.show();
        canvas.setNote(note);
        QImage frame(canvas.size(), QImage::Format_ARGB32_Premultiplied);
        auto paint = [&] {
            QPainter p(&frame);
            canvas.renderFrame(p);
        };
        paint();
        QVERIFY2(canvas.pendingPages() > 0, "the first frame hands its pages to the cache threads");
        QVERIFY2(inkPixels(frame.convertToFormat(QImage::Format_RGB32), frame.rect()) == 0, "and does not wait for them");
        QTRY_COMPARE_WITH_TIMEOUT(canvas.pendingPages(), 0, 10000);
        paint();
        QVERIFY2(inkPixels(frame.convertToFormat(QImage::Format_RGB32), frame.rect()) > 1000, "the pages show once drawn");

        // Scrolling on: frames come at once while the pages arriving are drawn.
        double sc[6];
        xn_editor_scroll_info(canvas.editor(), sc);
        QElapsedTimer slowest;
        qint64 worst = 0;
        for (double y = 0; y < sc[3]; y += sc[5] / 4) {
            xn_editor_scroll_to(canvas.editor(), 0, y);
            slowest.start();
            paint();
            worst = std::max(worst, slowest.elapsed());
            QCoreApplication::processEvents();
        }
        QVERIFY2(worst < 100, qPrintable(QStringLiteral("slowest frame %1 ms").arg(worst)));

        // Closed with pages still being drawn.
        xn_editor_go_to_page(canvas.editor(), 0);
        xn_editor_zoom_at(canvas.editor(), 400, 500, 1.7);
        paint();
        canvas.setNote(nullptr);
        QCOMPARE(canvas.liveSurfaces(), 0);
        canvas.setNote(note);
        QCOMPARE(canvas.pendingPages(), 0);
        canvas.setNote(nullptr);
        xn_note_close(note);
        QCoreApplication::processEvents();  // a wake queued by the closed editor finds nothing
    }
    /* The same on an infinite canvas: tiles a pan or a zoom uncovers are rendered off the UI thread. */
    void canvasTilesDrawnInBackground() {
        xn_note* note = xn_canvas_new();
        CanvasWidget canvas;
        canvas.resize(1200, 900);
        canvas.show();
        canvas.setNote(note);
        canvas.setDark(false);
        canvas.setTool("pen");
        xn_editor_set_ink_color(canvas.editor(), 0xff0000ffu);
        for (int k = 0; k < 1500; k++) {
            const double x0 = 20 + (k % 50) * 23, y0 = 20 + (k / 50) * 29;
            tablet(&canvas, QEvent::TabletPress, {x0, y0}, 0.5, Qt::LeftButton, Qt::LeftButton);
            for (int i = 1; i <= 10; i++) tablet(&canvas, QEvent::TabletMove, {x0 + i * 2, y0 + 5 * std::sin(i)}, 0.5, Qt::NoButton, Qt::LeftButton);
            tablet(&canvas, QEvent::TabletRelease, {x0 + 20, y0}, 0, Qt::LeftButton, Qt::NoButton);
        }
        QImage frame(canvas.size(), QImage::Format_ARGB32_Premultiplied);
        QElapsedTimer timer;
        auto paint = [&] {
            timer.start();
            QPainter p(&frame);
            canvas.renderFrame(p);
            return timer.elapsed();
        };
        auto ink = [&] { return inkPixels(frame.convertToFormat(QImage::Format_RGB32), QRect(0, 0, 1200, 900)); };
        paint();
        QTRY_COMPARE_WITH_TIMEOUT(canvas.pendingPages(), 0, 10000);
        paint();
        const int whole = ink();
        QVERIFY(whole > 10000);

        // A pan to fresh ground: the frame does not wait for the tiles, which land soon.
        xn_editor_scroll_by(canvas.editor(), 900, 700);
        qint64 worst = paint();
        QVERIFY2(canvas.pendingPages() > 0, "the uncovered tiles go to the cache threads");
        QTRY_COMPARE_WITH_TIMEOUT(canvas.pendingPages(), 0, 10000);
        paint();
        xn_editor_scroll_by(canvas.editor(), -900, -700);
        worst = std::max(worst, paint());
        QTRY_COMPARE_WITH_TIMEOUT(canvas.pendingPages(), 0, 10000);
        paint();
        QVERIFY2(qAbs(ink() - whole) < whole / 50, "back where it was, the same ink");

        // A zoom: tiles at the new scale, rendered once it settles, off the UI thread.
        xn_editor_zoom_at(canvas.editor(), 600, 450, 1.6);
        worst = std::max(worst, paint());
        QTest::qWait(150);
        worst = std::max(worst, paint());
        QTRY_COMPARE_WITH_TIMEOUT(canvas.pendingPages(), 0, 10000);
        paint();
        QVERIFY(ink() > whole);
        QVERIFY2(worst < 60, qPrintable(QStringLiteral("slowest frame %1 ms").arg(worst)));

        // Closed with tiles still being drawn.
        xn_editor_zoom_at(canvas.editor(), 600, 450, 1.5);
        paint();
        QTest::qWait(150);
        paint();
        canvas.setNote(nullptr);
        QCOMPARE(canvas.liveSurfaces(), 0);
        xn_note_close(note);
        QCoreApplication::processEvents();
    }
};

QTEST_MAIN(SmokeTest)
#include "smoke_test.moc"
