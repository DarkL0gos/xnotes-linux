// "Page background": paper colour and ruling (lines, dots, grid) for all pages or the current one,
// applied live (the Android Styles popup). A modeless dialog, so its colour pickers can open.
#pragma once

#include "xnotes.h"

#include <QDialog>

class QAbstractButton;
class QButtonGroup;
class QCheckBox;
class QLabel;
class QSlider;
class QToolButton;

class PageStylePopup : public QDialog {
    Q_OBJECT
public:
    /* canvas: an infinite canvas's background, one for the whole canvas. */
    PageStylePopup(xn_editor* editor, bool canvas, QWidget* parent);

private:
    xn_editor* editor_;
    bool allPages_ = true;
    bool syncing_ = false;
    xn_page_style style_{};

    QButtonGroup* scope_;
    QButtonGroup* paper_;
    QButtonGroup* pattern_;
    QToolButton* customPaper_;
    QLabel* spacingLabel_;
    QSlider* spacing_;
    QCheckBox* defaultSpacing_;
    QToolButton* lineColor_;
    QCheckBox* defaultLineColor_;
    QLabel* opacityLabel_;
    QSlider* opacity_;

    void load();
    void apply();
    xn_rgba effectiveLineColor() const;
};
