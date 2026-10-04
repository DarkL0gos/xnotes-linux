// Drives the Qt host end to end without a display (QT_QPA_PLATFORM=offscreen): a pen stroke from
// tablet events and one from the mouse become items, ink reaches the screen, the note saves and
// reopens, undo works, and every surface is released.
#include "CanvasWidget.h"
#include "SelectionBar.h"
#include "TextOverlay.h"
#include "PdfExport.h"
#include "MainWindow.h"
#include "NoteFinder.h"

#include <QScrollBar>

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

    /* The widget's pixels once the view settled (the sharp re-render follows ~90 ms after a zoom). */
    static QImage grab(CanvasWidget& c) {
        c.grab();
        QTest::qWait(200);
        return c.grab().toImage().convertToFormat(QImage::Format_RGB32);
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
        QVERIFY(inkPixels(grab(canvas), canvas.rect()) > 100);
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
        QCOMPARE(writePdf(note, canvas.host(), out, 0xFFFFFFFFu, [&](int done, int) { pagesDone = done; }), QString());
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
};

QTEST_MAIN(SmokeTest)
#include "smoke_test.moc"
