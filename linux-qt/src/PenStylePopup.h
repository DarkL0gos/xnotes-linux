// A pen's own settings (the Android tool popup): how much a light touch thins the line, and each
// kind's signature control: the calligraphy nib's contrast, the speed pen's thinning, the taper
// pen's tip, the dashed pen's rhythm. Applied live.
#pragma once

#include "xnotes.h"

#include <QFrame>

#include <functional>

class PenStylePopup : public QFrame {
    Q_OBJECT
public:
    /* tool: a stroke tool id; changed runs on every change with the whole style. */
    PenStylePopup(const QByteArray& tool, const xn_pen_style& style, std::function<void(const xn_pen_style&)> changed,
                  QWidget* parent);

private:
    xn_pen_style style_;
    std::function<void(const xn_pen_style&)> changed_;

    /* A labelled slider over [lo, hi] for one field. */
    void addSlider(const QString& label, double lo, double hi, double* field, const QString& unit = QString(),
                   bool enabled = true);
};
