#include "MainWindow.h"

#include "CanvasWidget.h"
#include "NoteFinder.h"
#include "PageStylePopup.h"
#include "PdfExport.h"
#include "SelectionBar.h"

#include <QApplication>
#include <QCloseEvent>
#include <QClipboard>
#include <QColorDialog>
#include <QDateTime>
#include <QGridLayout>
#include <QInputDialog>
#include <QPushButton>
#include <QScrollBar>
#include <QImageReader>
#include <QMimeData>
#include <QUuid>
#include <QComboBox>
#include <QSlider>
#include <QSpinBox>
#include <QMenu>
#include <QToolButton>
#include <QFileDialog>
#include <QFileInfo>
#include <QLabel>
#include <QMessageBox>
#include <QPainter>
#include <QPixmap>
#include <QStatusBar>
#include <QTimer>
#include <QToolBar>

namespace {

struct ToolEntry {
    const char* id;
    const char* label;
    const char* key;
};

const ToolEntry kTools[] = {
    {"pan", "Рука", "1"},
    {"pen", "Перо", "2"},
    {"highlighter", "Маркер", "3"},
    {"eraser", "Ластик", "4"},
    {"lasso", "Лассо", "5"},
    {"select", "Выделение", "6"},
    {"shape", "Фигура", "7"},
    {"text_box", "Текст", "8"},
};

struct FaceEntry {
    const char* id;
    const char* label;
};

const FaceEntry kFaces[] = {{"mono", "Моноширинный"}, {"sans", "Без засечек"}, {"serif", "С засечками"}, {"hand", "Рукописный"}};

struct ShapeEntry {
    const char* id;
    const char* label;
};

const ShapeEntry kShapes[] = {
    {"line", "Линия"}, {"arrow", "Стрелка"}, {"rectangle", "Прямоугольник"},
    {"ellipse", "Эллипс"}, {"circle", "Круг"}, {"triangle", "Треугольник"},
};

/* The app's ink presets (InkPalette) plus black and blue for light paper. */
const QList<QColor> kSwatches = {
    QColor(0, 230, 118), QColor(236, 236, 236), QColor(255, 92, 92), QColor(255, 199, 64),
    QColor(88, 196, 255), QColor(199, 134, 255), QColor(128, 128, 128), QColor(28, 28, 28), QColor(33, 150, 243),
};

/* Tools whose ink width the toolbar sets. */
/* The width slider's range per tool, as on Android (ToolDefaults.widthRange); the eraser's is its radius. */
std::pair<double, double> widthRange(const QByteArray& tool) {
    if (tool == "highlighter") return {4.0, 40.0};
    if (tool == "eraser") return {1.0, 80.0};
    return {1.0, 20.0};
}

/* Tools whose ink width (the eraser: its radius) the toolbar sets. */
bool hasWidth(const QByteArray& tool) { return tool == "pen" || tool == "highlighter" || tool == "shape" || tool == "eraser"; }

QString takeError(char* error) {
    QString message = error ? QString::fromUtf8(error) : QStringLiteral("неизвестная ошибка");
    xn_free_string(error);
    return message;
}

}  // namespace

MainWindow::MainWindow() {
    // The canvas with scroll bars beside and under it (device pixels, as the core counts).
    auto* central = new QWidget(this);
    auto* grid = new QGridLayout(central);
    grid->setContentsMargins(0, 0, 0, 0);
    grid->setSpacing(0);
    canvas_ = new CanvasWidget(central);
    vbar_ = new QScrollBar(Qt::Vertical, central);
    hbar_ = new QScrollBar(Qt::Horizontal, central);
    grid->addWidget(canvas_, 0, 0);
    grid->addWidget(vbar_, 0, 1);
    grid->addWidget(hbar_, 1, 0);
    setCentralWidget(central);
    auto scrolled = [this] {
        if (syncingBars_ || !canvas_->editor()) return;
        xn_editor_scroll_to(canvas_->editor(), hbar_->value(), vbar_->value());
    };
    connect(vbar_, &QScrollBar::valueChanged, this, scrolled);
    connect(hbar_, &QScrollBar::valueChanged, this, scrolled);
    buildUi();
    connect(canvas_, &CanvasWidget::contentChanged, this, &MainWindow::refresh);
    connect(canvas_, &CanvasWidget::viewChanged, this, &MainWindow::refreshView);
    connect(canvas_, &CanvasWidget::notice, this, [this](int code) {
        statusBar()->showMessage(code == XN_NOTICE_KEEP_ONE_PAGE ? QStringLiteral("В заметке должна остаться хотя бы одна страница")
                                 : code == XN_NOTICE_PAGE_ALREADY_EMPTY ? QStringLiteral("Страница уже пуста")
                                                                        : QString(), 4000);
    });
    // Input and speed, for checking a tablet: whether Qt reports it as a pen with pressure.
    inputLabel_ = new QLabel(this);
    statusBar()->addPermanentWidget(inputLabel_);
    auto* stats = new QTimer(this);
    connect(stats, &QTimer::timeout, this, [this] {
        const QString input = canvas_->lastInput();
        const double interval = canvas_->frameIntervalMs();
        const int b = canvas_->penButtons();
        const QString buttons = b == 0 ? QString() : b == 1 ? QStringLiteral("кнопка пера 1   ") : b == 2 ? QStringLiteral("кнопка пера 2   ") : QStringLiteral("кнопки пера 1+2   ");
        inputLabel_->setText(buttons + (input.isEmpty() ? QString() : QStringLiteral("ввод: %1, давление %2   ").arg(input).arg(canvas_->lastPressure(), 0, 'f', 2)) +
                             QStringLiteral("%1: кадр %2 мс").arg(canvas_->render() == CanvasWidget::Render::Gl ? QStringLiteral("GPU") : QStringLiteral("CPU"))
                                 .arg(canvas_->lastFrameMs(), 0, 'f', 1) +
                             (interval > 0 ? QStringLiteral(", %1 к/с").arg(int(std::lround(1000 / interval))) : QString()));
    });
    stats->start(250);
    resize(1200, 900);
}

MainWindow::~MainWindow() {
    canvas_->setNote(nullptr);
    if (note_) xn_note_close(note_);
}

void MainWindow::buildUi() {
    QToolBar* bar = addToolBar(QStringLiteral("Основное"));
    bar->setMovable(false);
    bar->addAction(QStringLiteral("Открыть…"), QKeySequence::Open, this, &MainWindow::chooseOpen);
    bar->addAction(QStringLiteral("Сохранить"), QKeySequence::Save, this, [this] { save(false); });
    bar->addAction(QStringLiteral("Сохранить как…"), QKeySequence::SaveAs, this, [this] { save(true); });
    bar->addAction(QStringLiteral("Экспорт в PDF…"), QKeySequence(Qt::CTRL | Qt::Key_E), this, &MainWindow::exportPdf);
    bar->addSeparator();

    undo_ = bar->addAction(QStringLiteral("↶"), QKeySequence::Undo, this, [this] { if (canvas_->editor()) xn_editor_undo(canvas_->editor()); });
    undo_->setToolTip(QStringLiteral("Отменить (Ctrl+Z)"));
    redo_ = bar->addAction(QStringLiteral("↷"), this, [this] { if (canvas_->editor()) xn_editor_redo(canvas_->editor()); });
    redo_->setShortcuts({QKeySequence(Qt::CTRL | Qt::SHIFT | Qt::Key_Z), QKeySequence(Qt::CTRL | Qt::Key_Y)});
    redo_->setToolTip(QStringLiteral("Повторить (Ctrl+Shift+Z)"));
    bar->addSeparator();

    bar->addAction(QStringLiteral("←"), QKeySequence(Qt::Key_PageUp), this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_go_to_page(e, std::max(0, xn_editor_current_page(e) - 1));
    });
    pageLabel_ = new QLabel(this);
    pageLabel_->setMinimumWidth(60);
    pageLabel_->setAlignment(Qt::AlignCenter);
    bar->addWidget(pageLabel_);
    bar->addAction(QStringLiteral("→"), QKeySequence(Qt::Key_PageDown), this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_go_to_page(e, std::min(xn_note_page_count(note_) - 1, xn_editor_current_page(e) + 1));
    });
    bar->addAction(QStringLiteral("−"), QKeySequence::ZoomOut, this, [this] { if (auto* e = canvas_->editor()) xn_editor_zoom_step(e, 0); });
    zoomLabel_ = new QLabel(this);
    zoomLabel_->setMinimumWidth(50);
    zoomLabel_->setAlignment(Qt::AlignCenter);
    bar->addWidget(zoomLabel_);
    bar->addAction(QStringLiteral("+"), QKeySequence(Qt::CTRL | Qt::Key_Equal), this, [this] { if (auto* e = canvas_->editor()) xn_editor_zoom_step(e, 1); });
    bar->addAction(QStringLiteral("По ширине"), this, [this] { if (auto* e = canvas_->editor()) xn_editor_fit_width(e); });
    bar->addAction(QStringLiteral("+ Страница"), this, [this] { if (auto* e = canvas_->editor()) xn_editor_add_page(e); });
    bar->addAction(QStringLiteral("− Страница"), this, [this] {
        if (!canvas_->editor()) return;
        if (QMessageBox::question(this, QStringLiteral("Удалить страницу"), QStringLiteral("Удалить текущую страницу?")) == QMessageBox::Yes)
            xn_editor_delete_current_page(canvas_->editor());
    });
    bar->addSeparator();
    bar->addAction(QStringLiteral("Фон…"), this, [this] {
        if (!canvas_->editor()) return;
        if (!pageStyle_) {
            pageStyle_ = new PageStylePopup(canvas_->editor(), this);
            pageStyle_->setAttribute(Qt::WA_DeleteOnClose);
        }
        pageStyle_->show();
        pageStyle_->raise();
    });
    QAction* dark = bar->addAction(QStringLiteral("Тёмная"));
    dark->setCheckable(true);
    dark->setChecked(dark_);
    connect(dark, &QAction::toggled, this, [this](bool on) {
        dark_ = on;
        canvas_->setDark(on);
    });

    // Second row: tools and their style.
    addToolBarBreak();
    QToolBar* tools = addToolBar(QStringLiteral("Инструменты"));
    tools->setMovable(false);
    tools_ = new QActionGroup(this);
    for (const ToolEntry& t : kTools) {
        QAction* a = tools->addAction(QString::fromUtf8(t.label));
        a->setCheckable(true);
        a->setShortcut(QKeySequence(QString::fromLatin1(t.key)));
        a->setData(QByteArray(t.id));
        tools_->addAction(a);
        if (qstrcmp(t.id, "pen") == 0) a->setChecked(true);
        if (qstrcmp(t.id, "shape") == 0) {
            // The shape tool's button also opens what it draws.
            auto* menu = new QMenu(this);
            auto* kinds = new QActionGroup(menu);
            for (const ShapeEntry& k : kShapes) {
                QAction* ka = menu->addAction(QString::fromUtf8(k.label));
                ka->setCheckable(true);
                ka->setChecked(shapeKind_ == k.id);
                kinds->addAction(ka);
                connect(ka, &QAction::triggered, this, [this, a, k] {
                    shapeKind_ = k.id;
                    applyShape();
                    a->trigger();
                });
            }
            menu->addSeparator();
            QAction* fill = menu->addAction(QStringLiteral("Заливка"));
            fill->setCheckable(true);
            connect(fill, &QAction::toggled, this, [this](bool on) { shapeFill_ = on; applyShape(); });
            QAction* dashed = menu->addAction(QStringLiteral("Пунктир"));
            dashed->setCheckable(true);
            connect(dashed, &QAction::toggled, this, [this](bool on) { shapeDashed_ = on; applyShape(); });
            a->setMenu(menu);
            if (auto* button = qobject_cast<QToolButton*>(tools->widgetForAction(a))) button->setPopupMode(QToolButton::MenuButtonPopup);
        }
    }
    connect(tools_, &QActionGroup::triggered, this, [this](QAction* a) {
        canvas_->setTool(a->data().toByteArray().constData());
        syncWidth();
    });
    tools->addSeparator();

    widthLabel_ = new QLabel(this);
    widthActions_ << tools->addWidget(widthLabel_);
    // A slider in half-pixel steps (its value is twice the width), with the width beside it.
    width_ = new QSlider(Qt::Horizontal, this);
    width_->setFixedWidth(160);
    width_->setFocusPolicy(Qt::ClickFocus);
    widthValue_ = new QLabel(this);
    widthValue_->setMinimumWidth(36);
    connect(width_, &QSlider::valueChanged, this, [this](int v) {
        const double w = v / 2.0;
        widthValue_->setText(QString::number(w, 'f', 1));
        const QByteArray tool = currentTool();
        if (!hasWidth(tool) || syncingWidth_) return;
        widths_[tool] = w;
        if (tool == "shape") applyShape();
        else if (auto* e = canvas_->editor()) xn_editor_set_tool_width(e, tool.constData(), w);
    });
    widthActions_ << tools->addWidget(width_) << tools->addWidget(widthValue_);

    // The eraser: whole strokes or just the ink under it.
    eraseMode_ = new QComboBox(this);
    eraseMode_->addItem(QStringLiteral("Штрих целиком"), int(XN_ERASE_STROKE));
    eraseMode_->addItem(QStringLiteral("Область"), int(XN_ERASE_AREA));
    eraseMode_->setToolTip(QStringLiteral("Ластик стирает касающиеся штрихи целиком или только то, что под ним"));
    eraseMode_->setFocusPolicy(Qt::ClickFocus);
    connect(eraseMode_, &QComboBox::currentIndexChanged, this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_set_erase_mode(e, eraseMode_->currentData().toInt());
    });
    eraserActions_ << tools->addWidget(eraseMode_);

    // Text box style: the box being edited or selected, and the next one.
    face_ = new QComboBox(this);
    for (const FaceEntry& f : kFaces) face_->addItem(QString::fromUtf8(f.label), QByteArray(f.id));
    face_->setFocusPolicy(Qt::ClickFocus);
    connect(face_, &QComboBox::currentIndexChanged, this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_set_text_face(e, face_->currentData().toByteArray().constData());
    });
    textSize_ = new QSpinBox(this);
    textSize_->setRange(6, 96);
    textSize_->setValue(13);
    textSize_->setSuffix(QStringLiteral(" пт"));
    textSize_->setFocusPolicy(Qt::ClickFocus);
    connect(textSize_, &QSpinBox::valueChanged, this, [this](int pt) { if (auto* e = canvas_->editor()) xn_editor_set_text_size(e, pt); });
    textActions_ << tools->addWidget(face_) << tools->addWidget(textSize_);
    tools->addSeparator();

    for (const QColor& c : kSwatches) {
        QAction* a = tools->addAction(swatchIcon(c), QString());
        a->setToolTip(c.name());
        connect(a, &QAction::triggered, this, [this, c] { setInk(c); });
    }
    colorAction_ = tools->addAction(QStringLiteral("Цвет…"), this, &MainWindow::chooseColor);
    tools->addSeparator();
    tools->addAction(QStringLiteral("Картинка…"), this, &MainWindow::chooseImage);
    colorAction_->setToolTip(QStringLiteral("Текущий цвет; нажмите, чтобы выбрать другой"));

    selectionBar_ = new SelectionBar(canvas_);
    selectionBar_->setSwatches(kSwatches);
    connect(canvas_, &CanvasWidget::selectionMenu, this, [this](bool shown, QRectF) { selectionBar_->onMenu(shown); });
    connect(canvas_, &CanvasWidget::viewChanged, selectionBar_, &SelectionBar::refresh);
    connect(canvas_, &CanvasWidget::contentChanged, selectionBar_, &SelectionBar::refresh);
    connect(canvas_, &CanvasWidget::contextMenu, this, &MainWindow::showContextMenu);
    connect(canvas_, &CanvasWidget::toolChanged, this, [this](const QString& id) {
        for (QAction* a : tools_->actions())
            if (a->data().toByteArray() == id.toUtf8()) a->setChecked(true);
        syncWidth();
    });

    // Canvas commands without buttons.
    auto bind = [this](const QKeySequence& key, auto fn) {
        auto* a = new QAction(this);
        a->setShortcut(key);
        connect(a, &QAction::triggered, this, fn);
        addAction(a);
    };
    bind(QKeySequence(Qt::Key_Escape), [this] { if (canvas_->editor()) xn_editor_escape(canvas_->editor()); });
    bind(QKeySequence(Qt::Key_Delete), [this] { if (canvas_->editor()) xn_editor_delete_selection(canvas_->editor()); });
    bind(QKeySequence::New, [this] { if (confirmDiscard()) newNote(); });
    bind(QKeySequence::Copy, [this] { if (canvas_->editor()) xn_editor_copy(canvas_->editor()); clipboardIsNewer_ = false; });
    bind(QKeySequence::Cut, [this] { if (canvas_->editor()) xn_editor_cut(canvas_->editor()); clipboardIsNewer_ = false; });
    connect(QApplication::clipboard(), &QClipboard::dataChanged, this, [this] { clipboardIsNewer_ = true; });
    connect(canvas_, &CanvasWidget::filesDropped, this, [this](const QStringList& files, QPointF at) {
        for (const QString& f : files) insertImageFile(f, canvas_->toDevice(at), true);
    });
    bind(QKeySequence::Paste, [this] { pasteAt(canvas_->mapFromGlobal(QCursor::pos())); });
    bind(QKeySequence(Qt::CTRL | Qt::Key_D), [this] { if (canvas_->editor()) xn_editor_duplicate(canvas_->editor()); });
    bind(QKeySequence::SelectAll, [this] { selectAll(); });
    bind(QKeySequence::Quit, [this] { close(); });
    setInk(kSwatches.first());
    syncWidth();
}

QByteArray MainWindow::currentTool() const {
    QAction* a = tools_->checkedAction();
    return a ? a->data().toByteArray() : QByteArray();
}

void MainWindow::syncWidth() {
    const QByteArray tool = currentTool();
    for (QAction* a : std::as_const(widthActions_)) a->setVisible(tool != "text_box");
    for (QAction* a : std::as_const(textActions_)) a->setVisible(tool == "text_box");
    for (QAction* a : std::as_const(eraserActions_)) a->setVisible(tool == "eraser");
    width_->setEnabled(hasWidth(tool));
    widthLabel_->setText(tool == "eraser" ? QStringLiteral(" Размер ") : QStringLiteral(" Толщина "));
    if (!hasWidth(tool)) return;
    double w = widths_.value(tool, 0);
    if (w <= 0) w = tool == "shape" ? 3.0 : canvas_->editor() ? xn_editor_tool_width(canvas_->editor(), tool.constData()) : 3.0;
    const auto [lo, hi] = widthRange(tool);
    syncingWidth_ = true;
    width_->setRange(int(lo * 2), int(hi * 2));
    width_->setValue(int(std::lround(std::clamp(w, lo, hi) * 2)));
    widthValue_->setText(QString::number(width_->value() / 2.0, 'f', 1));
    syncingWidth_ = false;
}

void MainWindow::applyShape() {
    auto* e = canvas_->editor();
    if (!e) return;
    const xn_shape_style style{shapeKind_.constData(), widths_.value("shape", 3.0), shapeFill_ ? 1 : 0, 0.25, shapeDashed_ ? 1 : 0};
    xn_editor_set_shape_style(e, &style);
}

/* Settings the toolbar holds, handed to a fresh editor. */
void MainWindow::applyToolSettings() {
    auto* e = canvas_->editor();
    if (!e) return;
    for (auto it = widths_.cbegin(); it != widths_.cend(); ++it)
        if (it.key() != "shape") xn_editor_set_tool_width(e, it.key().constData(), it.value());
    applyShape();
    xn_editor_set_erase_mode(e, eraseMode_->currentData().toInt());
    xn_editor_set_text_face(e, face_->currentData().toByteArray().constData());
    xn_editor_set_text_size(e, textSize_->value());
}

/*
 * Paste what was copied last: items copied in the editor, or an image another program put on the
 * system clipboard since.
 */
void MainWindow::pasteAt(QPointF widget) {
    auto* e = canvas_->editor();
    if (!e) return;
    if (!canvas_->rect().contains(widget.toPoint())) widget = QPointF(canvas_->width() / 2.0, canvas_->height() / 3.0);
    const QPointF at = canvas_->toDevice(widget);
    const QMimeData* mime = QApplication::clipboard()->mimeData();
    const bool image = mime && mime->hasImage();
    if (image && (clipboardIsNewer_ || !xn_editor_can_paste(e))) {
        const QImage picture = qvariant_cast<QImage>(mime->imageData());
        const QString file = workFile(QStringLiteral("png"));
        if (!picture.isNull() && !file.isEmpty() && picture.save(file, "PNG")) insertImage(file, at, true);
        return;
    }
    if (!xn_editor_can_paste(e)) return;
    xn_editor_paste_at(e, at.x(), at.y());
    selectionBar_->refresh();
}

/* A fresh file name in the note's work folder, which lives as long as the note. */
QString MainWindow::workFile(const QString& suffix) {
    if (!workDir_ || !workDir_->isValid()) workDir_ = std::make_unique<QTemporaryDir>();
    if (!workDir_->isValid()) return {};
    return workDir_->filePath(QStringLiteral("inserted-%1.%2").arg(QUuid::createUuid().toString(QUuid::Id128), suffix));
}

void MainWindow::chooseImage() {
    if (!canvas_->editor()) return;
    QStringList patterns;
    for (const QByteArray& f : QImageReader::supportedImageFormats()) patterns << QStringLiteral("*.") + QString::fromLatin1(f);
    const QString path = QFileDialog::getOpenFileName(this, QStringLiteral("Вставить картинку"), QString(),
                                                      QStringLiteral("Изображения (%1)").arg(patterns.join(QLatin1Char(' '))));
    if (!path.isEmpty()) insertImageFile(path, QPointF(), false);
}

/* Copy an image file into the work folder and put it on the page (at a device point, if given). */
bool MainWindow::insertImageFile(const QString& path, QPointF at, bool atPoint) {
    const QString copy = workFile(QFileInfo(path).suffix().isEmpty() ? QStringLiteral("img") : QFileInfo(path).suffix().toLower());
    if (copy.isEmpty() || !QFile::copy(path, copy)) {
        QMessageBox::warning(this, QStringLiteral("Картинка"), QStringLiteral("Не удалось скопировать %1").arg(path));
        return false;
    }
    return insertImage(copy, at, atPoint);
}

bool MainWindow::insertImage(const QString& file, QPointF at, bool atPoint) {
    auto* e = canvas_->editor();
    if (!e) return false;
    if (!xn_editor_insert_image(e, QFile::encodeName(file).constData(), atPoint ? 1 : 0, at.x(), at.y())) {
        QFile::remove(file);
        QMessageBox::warning(this, QStringLiteral("Картинка"), QStringLiteral("Не удалось прочитать изображение"));
        return false;
    }
    return true;
}

void MainWindow::selectAll() {
    auto* e = canvas_->editor();
    if (!e) return;
    xn_editor_select_all(e);
    selectionBar_->refresh();
}

void MainWindow::showContextMenu(QPointF at, bool onLocked) {
    auto* e = canvas_->editor();
    if (!e) return;
    QMenu menu(this);
    QAction* paste = menu.addAction(QStringLiteral("Вставить"), this, [this, at] { pasteAt(at); });
    const QMimeData* mime = QApplication::clipboard()->mimeData();
    paste->setEnabled(xn_editor_can_paste(e) || (mime && mime->hasImage()));
    if (onLocked) menu.addAction(QStringLiteral("Открепить"), this, [e] { xn_editor_unlock_pressed(e); });
    menu.addAction(QStringLiteral("Выделить всё"), this, &MainWindow::selectAll);
    menu.exec(canvas_->mapToGlobal(at.toPoint()));
}

void MainWindow::install(xn_note* note, std::unique_ptr<QTemporaryDir> workDir, const QString& path) {
    suggestedName_.clear();
    if (pageStyle_) pageStyle_->close();  // it edits the editor going away
    canvas_->setNote(nullptr);
    if (note_) xn_note_close(note_);
    note_ = note;
    workDir_ = std::move(workDir);
    path_ = path;
    canvas_->setNote(note_);
    canvas_->setDark(dark_);
    if (QAction* tool = tools_->checkedAction()) canvas_->setTool(tool->data().toByteArray().constData());
    if (colorAction_->data().isValid()) setInk(colorAction_->data().value<QColor>());
    applyToolSettings();
    syncWidth();
    refresh();
    refreshView();
}

void MainWindow::newNote() { install(xn_note_new(1), std::make_unique<QTemporaryDir>(), QString()); }

bool MainWindow::open(const QString& path) {
    auto workDir = std::make_unique<QTemporaryDir>();
    if (!workDir->isValid()) {
        QMessageBox::warning(this, QStringLiteral("Открыть"), QStringLiteral("Не удалось создать временную папку"));
        return false;
    }
    if (path.endsWith(QLatin1String(".pdf"), Qt::CaseInsensitive)) {
        // A note made from this PDF before is usually what is wanted, rather than a fresh one.
        const QStringList notes = notesForPdf(path);
        if (!notes.isEmpty()) {
            bool cancelled = false;
            const QString note = chooseExistingNote(path, notes, &cancelled);
            if (cancelled) return false;
            if (!note.isEmpty()) return open(note);
        }
        return importPdf(path, std::move(workDir));
    }
    char* error = nullptr;
    const QByteArray file = QFile::encodeName(QFileInfo(path).absoluteFilePath());
    const QByteArray dir = QFile::encodeName(workDir->path());
    xn_note* note = xn_note_open(file.constData(), dir.constData(), canvas_->host(), &error);
    if (!note) {
        QMessageBox::warning(this, QStringLiteral("Открыть"), QStringLiteral("Не удалось открыть %1:\n%2").arg(path, takeError(error)));
        return false;
    }
    install(note, std::move(workDir), QFileInfo(path).absoluteFilePath());
    return true;
}

/* Which of the notes made from the PDF to open; empty for a new one. */
QString MainWindow::chooseExistingNote(const QString& pdf, const QStringList& notes, bool* cancelled) {
    const QString pdfName = QFileInfo(pdf).fileName();
    if (notes.size() == 1) {
        QMessageBox box(QMessageBox::Question, QStringLiteral("Открыть PDF"),
                        QStringLiteral("Для «%1» уже есть заметка «%2». Открыть её?").arg(pdfName, QFileInfo(notes.first()).fileName()),
                        QMessageBox::NoButton, this);
        QPushButton* openNote = box.addButton(QStringLiteral("Открыть заметку"), QMessageBox::AcceptRole);
        QPushButton* fresh = box.addButton(QStringLiteral("Создать новую"), QMessageBox::DestructiveRole);
        box.addButton(QMessageBox::Cancel);
        box.setDefaultButton(openNote);
        box.exec();
        if (box.clickedButton() == openNote) return notes.first();
        if (box.clickedButton() == fresh) return {};
        *cancelled = true;
        return {};
    }
    QStringList items;
    for (const QString& n : notes)
        items << QStringLiteral("%1 — изменена %2").arg(QFileInfo(n).fileName(), QFileInfo(n).lastModified().toString(QStringLiteral("dd.MM.yyyy HH:mm")));
    const QString freshItem = QStringLiteral("Создать новую заметку");
    items << freshItem;
    bool ok = false;
    const QString picked = QInputDialog::getItem(this, QStringLiteral("Открыть PDF"),
                                                 QStringLiteral("Для «%1» уже есть заметки:").arg(pdfName), items, 0, false, &ok);
    if (!ok) {
        *cancelled = true;
        return {};
    }
    const qsizetype index = items.indexOf(picked);
    return picked == freshItem || index < 0 ? QString() : notes.at(index);
}

/* A new note over a copy of the PDF (the note embeds it when saved); saving asks for a name. */
bool MainWindow::importPdf(const QString& path, std::unique_ptr<QTemporaryDir> workDir) {
    const QString copy = workDir->filePath(QStringLiteral("source.pdf"));
    if (!QFile::copy(path, copy)) {
        QMessageBox::warning(this, QStringLiteral("Открыть"), QStringLiteral("Не удалось скопировать %1").arg(path));
        return false;
    }
    char* error = nullptr;
    xn_note* note = xn_note_import_pdf(QFile::encodeName(copy).constData(), canvas_->host(), &error);
    if (!note) {
        QMessageBox::warning(this, QStringLiteral("Открыть"), QStringLiteral("Не удалось открыть PDF %1:\n%2").arg(path, takeError(error)));
        return false;
    }
    install(note, std::move(workDir), QString());
    suggestedName_ = QFileInfo(path).absolutePath() + QLatin1Char('/') + QFileInfo(path).completeBaseName() + QStringLiteral(".xnote");
    refresh();
    return true;
}

void MainWindow::chooseOpen() {
    if (!confirmDiscard()) return;
    const QString path = QFileDialog::getOpenFileName(this, QStringLiteral("Открыть"), QFileInfo(path_).absolutePath(),
                                                      QStringLiteral("Заметки и PDF (*.xnote *.pdf);;Заметки xnotes (*.xnote);;PDF (*.pdf)"));
    if (!path.isEmpty()) open(path);
}

/*
 * The note as a PDF, a page per page at its own size: ink, shapes and text stay vector; a PDF
 * background is rendered at 300 dpi; paper is the screen's paper, so light ink stays visible.
 */
void MainWindow::exportPdf() {
    if (!note_) return;
    QString base = !path_.isEmpty() ? path_ : suggestedName_;
    base = base.isEmpty() ? QStringLiteral("note.pdf") : QFileInfo(base).absolutePath() + QLatin1Char('/') + QFileInfo(base).completeBaseName() + QStringLiteral(".pdf");
    QString out = QFileDialog::getSaveFileName(this, QStringLiteral("Экспорт в PDF"), base, QStringLiteral("PDF (*.pdf)"));
    if (out.isEmpty()) return;
    if (!out.endsWith(QLatin1String(".pdf"), Qt::CaseInsensitive)) out += QLatin1String(".pdf");
    const QString error = writePdf(note_, out, dark_ ? 0x161616FFu : 0xFFFFFFFFu, [this](int done, int total) {
        statusBar()->showMessage(QStringLiteral("Экспорт: страница %1 из %2").arg(done).arg(total));
        QApplication::processEvents(QEventLoop::ExcludeUserInputEvents);
    });
    if (!error.isEmpty()) QMessageBox::warning(this, QStringLiteral("Экспорт в PDF"), error);
    else statusBar()->showMessage(QStringLiteral("Экспортировано: %1").arg(out), 4000);
}

bool MainWindow::save(bool askPath) {
    if (!note_) return false;
    QString path = path_;
    if (askPath || path.isEmpty()) {
        const QString start = !path.isEmpty() ? path : !suggestedName_.isEmpty() ? suggestedName_ : QStringLiteral("note.xnote");
        path = QFileDialog::getSaveFileName(this, QStringLiteral("Сохранить заметку"), start,
                                            QStringLiteral("Заметки xnotes (*.xnote)"));
        if (path.isEmpty()) return false;
        if (!path.endsWith(QLatin1String(".xnote"))) path += QLatin1String(".xnote");
    }
    char* error = nullptr;
    if (!xn_note_save(note_, QFile::encodeName(path).constData(), canvas_->host(), &error)) {
        QMessageBox::warning(this, QStringLiteral("Сохранить"), QStringLiteral("Не удалось сохранить %1:\n%2").arg(path, takeError(error)));
        return false;
    }
    path_ = path;
    statusBar()->showMessage(QStringLiteral("Сохранено: %1").arg(path), 3000);
    refresh();
    return true;
}

bool MainWindow::confirmDiscard() {
    if (!note_ || !xn_note_is_dirty(note_)) return true;
    const auto answer = QMessageBox::question(this, QStringLiteral("Несохранённые изменения"),
                                              QStringLiteral("Сохранить изменения в заметке?"),
                                              QMessageBox::Save | QMessageBox::Discard | QMessageBox::Cancel);
    if (answer == QMessageBox::Save) return save(false);
    return answer == QMessageBox::Discard;
}

void MainWindow::closeEvent(QCloseEvent* e) {
    if (confirmDiscard()) e->accept(); else e->ignore();
}

void MainWindow::chooseColor() {
    const QColor c = QColorDialog::getColor(colorAction_->data().value<QColor>(), this, QStringLiteral("Цвет чернил"));
    if (c.isValid()) setInk(c);
}

void MainWindow::setInk(const QColor& c) {
    colorAction_->setData(c);
    colorAction_->setIcon(swatchIcon(c));
    if (auto* e = canvas_->editor())
        xn_editor_set_ink_color(e, (xn_rgba(c.red()) << 24) | (xn_rgba(c.green()) << 16) | (xn_rgba(c.blue()) << 8) | 0xFF);
}

void MainWindow::refresh() {
    auto* e = canvas_->editor();
    undo_->setEnabled(e && xn_editor_can_undo(e));
    redo_->setEnabled(e && xn_editor_can_redo(e));
    const QString name = path_.isEmpty() ? QStringLiteral("Новая заметка") : QFileInfo(path_).fileName();
    const bool dirty = note_ && xn_note_is_dirty(note_);
    setWindowTitle((dirty ? QStringLiteral("• ") : QString()) + name + QStringLiteral(" — xnotes"));
    refreshView();
}

void MainWindow::syncScrollBars() {
    auto* e = canvas_->editor();
    double sc[6] = {0, 0, 0, 0, 0, 0};
    if (e) xn_editor_scroll_info(e, sc);
    syncingBars_ = true;
    vbar_->setRange(0, int(std::lround(sc[3])));
    vbar_->setPageStep(std::max(1, int(sc[5])));
    vbar_->setSingleStep(std::max(1, int(sc[5] / 20)));
    vbar_->setValue(int(std::lround(sc[1])));
    hbar_->setRange(0, int(std::lround(sc[2])));
    hbar_->setPageStep(std::max(1, int(sc[4])));
    hbar_->setSingleStep(std::max(1, int(sc[4] / 20)));
    hbar_->setValue(int(std::lround(sc[0])));
    hbar_->setVisible(sc[2] > 0);
    syncingBars_ = false;
}

void MainWindow::refreshView() {
    syncScrollBars();
    auto* e = canvas_->editor();
    if (!e || !note_) return;
    pageLabel_->setText(QStringLiteral("%1 / %2").arg(xn_editor_current_page(e) + 1).arg(xn_note_page_count(note_)));
    zoomLabel_->setText(QStringLiteral("%1%").arg(int(xn_editor_zoom(e) * 100)));
}
