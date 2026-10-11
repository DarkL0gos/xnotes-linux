#include "PenStylePopup.h"

#include <QLabel>
#include <QSlider>
#include <QVBoxLayout>

#include <algorithm>

#include <cmath>

PenStylePopup::PenStylePopup(const QByteArray& tool, const xn_pen_style& style, std::function<void(const xn_pen_style&)> changed,
                             QWidget* parent)
    : QFrame(parent, Qt::Popup), style_(style), changed_(std::move(changed)) {
    setFrameShape(QFrame::StyledPanel);
    auto* col = new QVBoxLayout(this);
    const bool pressureKind = tool == "pen" || tool == "calligraphy" || tool == "speed" || tool == "taper";
    if (pressureKind) {
        addSlider(QStringLiteral("Чувствительность к нажиму"), 0, 100, &style_.sensitivity, QStringLiteral("%"), style_.pressure != 0);
        if (!style_.pressure) {
            auto* off = new QLabel(QStringLiteral("Нажим выключен на панели инструментов"), this);
            off->setEnabled(false);
            col->addWidget(off);
        }
    }
    if (tool == "calligraphy") addSlider(QStringLiteral("Контраст пера (толстое к тонкому)"), 1, 5, &style_.multiplier, QStringLiteral("×"));
    if (tool == "speed") addSlider(QStringLiteral("Утоньшение от скорости"), 0, 100, &style_.speed, QStringLiteral("%"));
    if (tool == "taper") addSlider(QStringLiteral("Ширина кончика"), 0, 100, &style_.tip, QStringLiteral("%"));
    if (tool == "dashed") {
        addSlider(QStringLiteral("Штрих"), 2, 40, &style_.dash, QStringLiteral(" px"));
        addSlider(QStringLiteral("Промежуток"), 2, 40, &style_.gap, QStringLiteral(" px"));
    }
    if (col->count() == 0) col->addWidget(new QLabel(QStringLiteral("У этого пера нет своих настроек"), this));
}

void PenStylePopup::addSlider(const QString& label, double lo, double hi, double* field, const QString& unit, bool enabled) {
    auto* col = static_cast<QVBoxLayout*>(layout());
    auto* caption = new QLabel(this);
    // Tenths for the multiplier's short range, whole steps for the rest.
    const double step = hi - lo <= 10 ? 0.1 : 1.0;
    auto* slider = new QSlider(Qt::Horizontal, this);
    slider->setMinimumWidth(240);
    slider->setRange(int(std::lround(lo / step)), int(std::lround(hi / step)));
    slider->setValue(int(std::lround(std::clamp(*field, lo, hi) / step)));
    slider->setEnabled(enabled);
    caption->setEnabled(enabled);
    auto show = [caption, label, unit, step](double v) {
        caption->setText(QStringLiteral("%1: %2%3").arg(label, QString::number(v, 'f', step < 1 ? 1 : 0), unit));
    };
    show(slider->value() * step);
    connect(slider, &QSlider::valueChanged, this, [this, field, step, show](int v) {
        *field = v * step;
        show(*field);
        changed_(style_);
    });
    col->addWidget(caption);
    col->addWidget(slider);
}
