#include "PageStylePopup.h"

#include "SelectionBar.h"

#include <QButtonGroup>
#include <QCheckBox>
#include <QColorDialog>
#include <QGridLayout>
#include <QHBoxLayout>
#include <QLabel>
#include <QRadioButton>
#include <QSlider>
#include <QToolButton>
#include <QVBoxLayout>

#include <cmath>

namespace {

/* The Android app's paper presets: two darks, white, cream, light grey. */
const xn_rgba kPapers[] = {0x161616FFu, 0x0D0D0DFFu, 0xFFFFFFFFu, 0xF7F3E9FFu, 0xE8E8E8FFu};

/* The built-in ruling colour: grey at a quarter opacity (PageStyle.DEFAULT_PATTERN_COLOR). */
constexpr xn_rgba kDefaultLineColor = 0x96969640u;
constexpr double kDefaultSpacing = 64.0;

/* Button ids in the groups (QButtonGroup takes -1 to mean "pick one"). */
constexpr int kDefaultPaperId = 50, kCustomPaperId = 100;
constexpr int kPatternIdBase = 10;  // pattern p is button kPatternIdBase + p

struct PatternEntry {
    int id;
    const char* label;
};

const PatternEntry kPatterns[] = {
    {XN_PATTERN_INHERIT, "По умолчанию"}, {XN_PATTERN_NONE, "Нет"}, {XN_PATTERN_LINES, "Линейка"},
    {XN_PATTERN_DOTS, "Точки"}, {XN_PATTERN_GRID, "Клетка"},
};

QToolButton* chip(const QString& text, QWidget* parent) {
    auto* b = new QToolButton(parent);
    b->setText(text);
    b->setCheckable(true);
    b->setAutoRaise(true);
    b->setToolButtonStyle(Qt::ToolButtonTextOnly);
    return b;
}

QToolButton* swatch(xn_rgba c, QWidget* parent) {
    auto* b = new QToolButton(parent);
    b->setIcon(swatchIcon(fromRgba(c | 0xFF), 22));
    b->setIconSize(QSize(22, 22));
    b->setCheckable(true);
    b->setAutoRaise(true);
    return b;
}

QLabel* caption(const QString& text, QWidget* parent) {
    auto* l = new QLabel(text, parent);
    QFont f = l->font();
    f.setBold(true);
    l->setFont(f);
    return l;
}

}  // namespace

PageStylePopup::PageStylePopup(xn_editor* editor, bool canvas, QWidget* parent) : QDialog(parent), editor_(editor) {
    setWindowTitle(canvas ? QStringLiteral("Фон холста") : QStringLiteral("Фон страниц"));
    auto* col = new QVBoxLayout(this);

    auto* scopeRow = new QHBoxLayout;
    scope_ = new QButtonGroup(this);
    auto* all = new QRadioButton(QStringLiteral("Все страницы"), this);
    auto* current = new QRadioButton(QStringLiteral("Текущая страница"), this);
    scope_->addButton(all, 1);
    scope_->addButton(current, 0);
    all->setChecked(true);
    scopeRow->addWidget(all);
    scopeRow->addWidget(current);
    col->addLayout(scopeRow);
    all->setVisible(!canvas);  // a canvas has one background
    current->setVisible(!canvas);
    connect(scope_, &QButtonGroup::idClicked, this, [this](int id) {
        allPages_ = id == 1;
        load();
    });

    // Paper.
    col->addWidget(caption(QStringLiteral("Бумага"), this));
    auto* paperRow = new QHBoxLayout;
    paper_ = new QButtonGroup(this);
    auto* defaultPaper = chip(QStringLiteral("По умолчанию"), this);
    paper_->addButton(defaultPaper, kDefaultPaperId);
    paperRow->addWidget(defaultPaper);
    for (int i = 0; i < int(std::size(kPapers)); i++) {
        QToolButton* b = swatch(kPapers[i], this);
        paper_->addButton(b, i);
        paperRow->addWidget(b);
    }
    customPaper_ = chip(QStringLiteral("…"), this);
    customPaper_->setToolTip(QStringLiteral("Другой цвет"));
    paper_->addButton(customPaper_, kCustomPaperId);
    paperRow->addWidget(customPaper_);
    paperRow->addStretch();
    col->addLayout(paperRow);
    connect(paper_, &QButtonGroup::idClicked, this, [this](int id) {
        if (id == kDefaultPaperId) {
            style_.has_page_color = 0;
        } else if (id == kCustomPaperId) {
            const QColor c = QColorDialog::getColor(style_.has_page_color ? fromRgba(style_.page_color) : Qt::white, this,
                                                    QStringLiteral("Цвет бумаги"));
            if (!c.isValid()) return load();
            style_.has_page_color = 1;
            style_.page_color = toRgba(c) | 0xFF;
        } else {
            style_.has_page_color = 1;
            style_.page_color = kPapers[id];
        }
        apply();
    });

    // Ruling.
    col->addWidget(caption(QStringLiteral("Узор"), this));
    auto* patternRow = new QHBoxLayout;
    pattern_ = new QButtonGroup(this);
    for (const PatternEntry& p : kPatterns) {
        QToolButton* b = chip(QString::fromUtf8(p.label), this);
        pattern_->addButton(b, kPatternIdBase + p.id);
        patternRow->addWidget(b);
    }
    patternRow->addStretch();
    col->addLayout(patternRow);
    connect(pattern_, &QButtonGroup::idClicked, this, [this](int id) {
        style_.pattern = id - kPatternIdBase;
        apply();
    });

    spacingLabel_ = new QLabel(this);
    col->addWidget(spacingLabel_);
    auto* spacingRow = new QHBoxLayout;
    defaultSpacing_ = new QCheckBox(QStringLiteral("По умолчанию"), this);
    spacing_ = new QSlider(Qt::Horizontal, this);
    spacing_->setRange(int(XN_PATTERN_MIN_SPACING), int(XN_PATTERN_MAX_SPACING));
    spacingRow->addWidget(defaultSpacing_);
    spacingRow->addWidget(spacing_, 1);
    col->addLayout(spacingRow);
    connect(defaultSpacing_, &QCheckBox::toggled, this, [this](bool on) {
        if (syncing_) return;
        style_.spacing = on ? 0 : spacing_->value();
        apply();
    });
    connect(spacing_, &QSlider::valueChanged, this, [this](int v) {
        if (syncing_) return;
        style_.spacing = v;
        apply();
    });

    // Line colour and opacity.
    col->addWidget(caption(QStringLiteral("Цвет линий"), this));
    auto* lineRow = new QHBoxLayout;
    defaultLineColor_ = new QCheckBox(QStringLiteral("По умолчанию"), this);
    lineColor_ = new QToolButton(this);
    lineColor_->setText(QStringLiteral("Выбрать…"));
    lineColor_->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
    lineRow->addWidget(defaultLineColor_);
    lineRow->addWidget(lineColor_);
    lineRow->addStretch();
    col->addLayout(lineRow);
    connect(defaultLineColor_, &QCheckBox::toggled, this, [this](bool on) {
        if (syncing_) return;
        if (on) {
            style_.has_pattern_color = 0;
        } else {
            style_.has_pattern_color = 1;
            style_.pattern_color = effectiveLineColor();
        }
        apply();
    });
    connect(lineColor_, &QToolButton::clicked, this, [this] {
        const xn_rgba current = effectiveLineColor();
        const QColor c = QColorDialog::getColor(fromRgba(current | 0xFF), this, QStringLiteral("Цвет линий"));
        if (!c.isValid()) return;
        style_.has_pattern_color = 1;
        style_.pattern_color = (toRgba(c) & 0xFFFFFF00u) | (current & 0xFF);  // the opacity stays
        apply();
    });
    opacityLabel_ = new QLabel(this);
    col->addWidget(opacityLabel_);
    opacity_ = new QSlider(Qt::Horizontal, this);
    opacity_->setRange(0, 100);
    col->addWidget(opacity_);
    connect(opacity_, &QSlider::valueChanged, this, [this](int pct) {
        if (syncing_) return;
        style_.has_pattern_color = 1;
        style_.pattern_color = (effectiveLineColor() & 0xFFFFFF00u) | xn_rgba(std::lround(pct * 2.55));
        apply();
    });

    setMinimumWidth(420);
    load();
}

/* The ruling colour in effect: this level's, else (on the current page) the note's, else the default. */
xn_rgba PageStylePopup::effectiveLineColor() const {
    if (style_.has_pattern_color) return style_.pattern_color;
    if (!allPages_) {
        xn_page_style note{};
        xn_editor_page_style(editor_, 1, &note);
        if (note.has_pattern_color) return note.pattern_color;
    }
    return kDefaultLineColor;
}

void PageStylePopup::load() {
    xn_editor_page_style(editor_, allPages_ ? 1 : 0, &style_);
    syncing_ = true;
    int paper = kCustomPaperId;
    if (!style_.has_page_color) paper = kDefaultPaperId;
    for (int i = 0; i < int(std::size(kPapers)); i++)
        if (style_.has_page_color && style_.page_color == kPapers[i]) paper = i;
    paper_->button(paper)->setChecked(true);
    customPaper_->setIcon(paper == kCustomPaperId ? swatchIcon(fromRgba(style_.page_color | 0xFF), 16) : QIcon());
    if (QAbstractButton* b = pattern_->button(kPatternIdBase + style_.pattern)) b->setChecked(true);
    const bool inherit = style_.spacing <= 0;
    defaultSpacing_->setChecked(inherit);
    spacing_->setValue(int(std::lround(inherit ? kDefaultSpacing : style_.spacing)));
    spacingLabel_->setText(QStringLiteral("Шаг: %1 пикс.%2").arg(spacing_->value()).arg(inherit ? QStringLiteral(" (по умолчанию)") : QString()));
    const xn_rgba line = effectiveLineColor();
    defaultLineColor_->setChecked(!style_.has_pattern_color);
    lineColor_->setIcon(swatchIcon(fromRgba(line | 0xFF), 16));
    opacity_->setValue(int(std::lround((line & 0xFF) / 2.55)));
    opacityLabel_->setText(QStringLiteral("Непрозрачность линий: %1%").arg(opacity_->value()));
    syncing_ = false;
}

void PageStylePopup::apply() {
    xn_editor_set_page_style(editor_, allPages_ ? 1 : 0, &style_);
    load();
}
