// The editor window: one note on a canvas, a toolbar, and file commands.
#pragma once

#include "xnotes.h"

#include <QActionGroup>
#include <QHash>
#include <QMainWindow>
#include <QPointer>
#include <QTemporaryDir>

#include <memory>

class CanvasWidget;
class QComboBox;
class QSlider;
class QSpinBox;
class QLabel;
class QScrollBar;
class PageStylePopup;
class SelectionBar;

class MainWindow : public QMainWindow {
    Q_OBJECT
public:
    MainWindow();
    ~MainWindow() override;

    /* Open path; on failure shows why and keeps the current note. */
    bool open(const QString& path);
    void newNote();
    xn_note* note() const { return note_; }

protected:
    void closeEvent(QCloseEvent* e) override;

private:
    CanvasWidget* canvas_;
    xn_note* note_ = nullptr;
    std::unique_ptr<QTemporaryDir> workDir_;
    QString path_;
    QString suggestedName_;  // where "Save" proposes to put a note made from a PDF
    QAction* undo_;
    QAction* redo_;
    QAction* colorAction_;
    QActionGroup* tools_;
    QLabel* pageLabel_;
    QLabel* zoomLabel_;
    QLabel* inputLabel_;
    bool dark_ = true;
    SelectionBar* selectionBar_;
    QPointer<PageStylePopup> pageStyle_;
    QSlider* width_;
    QLabel* widthValue_;
    QComboBox* face_;
    QSpinBox* textSize_;
    QList<QAction*> widthActions_;
    QList<QAction*> textActions_;
    QList<QAction*> eraserActions_;
    QLabel* widthLabel_;
    QComboBox* eraseMode_;
    bool syncingWidth_ = false;
    QHash<QByteArray, double> widths_;
    QByteArray shapeKind_ = "rectangle";
    bool shapeFill_ = false;
    bool shapeDashed_ = false;
    bool clipboardIsNewer_ = false;
    QScrollBar* vbar_;
    QScrollBar* hbar_;
    bool syncingBars_ = false;  // the system clipboard changed after the last in-app copy

    void install(xn_note* note, std::unique_ptr<QTemporaryDir> workDir, const QString& path);
    void buildUi();
    void refresh();
    void refreshView();
    void syncScrollBars();
    void chooseOpen();
    bool save(bool askPath);
    bool confirmDiscard();
    void chooseColor();
    void setInk(const QColor& c);
    QByteArray currentTool() const;
    void syncWidth();
    void applyShape();
    void applyToolSettings();
    void pasteAt(QPointF widget);
    void selectAll();
    void showContextMenu(QPointF at, bool onLocked);
    QString workFile(const QString& suffix);
    void chooseImage();
    bool insertImageFile(const QString& path, QPointF at, bool atPoint);
    bool insertImage(const QString& file, QPointF at, bool atPoint);
    bool importPdf(const QString& path, std::unique_ptr<QTemporaryDir> workDir);
    QString chooseExistingNote(const QString& pdf, const QStringList& notes, bool* cancelled);
    void exportPdf();
};
