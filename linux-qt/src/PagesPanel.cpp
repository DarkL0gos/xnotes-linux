#include "PagesPanel.h"

#include "Icons.h"

#include <QAbstractListModel>
#include <QApplication>
#include <QBoxLayout>
#include <QKeyEvent>
#include <QLabel>
#include <QListView>
#include <QMenu>
#include <QPainter>
#include <QSet>
#include <QStyledItemDelegate>
#include <QTimer>
#include <QToolButton>

#include <algorithm>
#include <cmath>
#include <functional>

namespace {

/* Thumbnail width and the room around it (logical px). */
constexpr int kThumbWidth = 150;
constexpr int kPad = 6;
/* How long an edit has to rest before the thumbnails it touched are redrawn. */
constexpr int kSettleMs = 350;

}  // namespace

/* One row per page; the delegate draws everything from the panel. */
class PageListModel : public QAbstractListModel {
public:
    explicit PageListModel(PagesPanel* panel) : QAbstractListModel(panel), panel_(panel) {}

    int rowCount(const QModelIndex& parent) const override { return parent.isValid() ? 0 : panel_->pageCount(); }

    QVariant data(const QModelIndex& index, int role) const override {
        if (!index.isValid()) return {};
        if (role == Qt::DisplayRole) return index.row() + 1;
        if (role == Qt::ToolTipRole) return QStringLiteral("Страница %1").arg(index.row() + 1);
        return {};
    }

    void reset(const std::function<void()>& change) {
        beginResetModel();
        change();
        endResetModel();
    }
    void relayout() {
        emit layoutAboutToBeChanged();
        emit layoutChanged();
    }

private:
    PagesPanel* panel_;
};

namespace {

/* A thumbnail with its page number under it; the current page and the selected ones in the accent. */
class PageDelegate : public QStyledItemDelegate {
public:
    PageDelegate(PagesPanel* panel, xn_rgba* paper) : QStyledItemDelegate(panel), panel_(panel), paper_(paper) {}

    QSize sizeHint(const QStyleOptionViewItem& option, const QModelIndex& index) const override {
        const QSize thumb = panel_->thumbSize(index.row());
        return QSize(thumb.width() + 2 * kPad, thumb.height() + 2 * kPad + option.fontMetrics.height());
    }

    void paint(QPainter* p, const QStyleOptionViewItem& option, const QModelIndex& index) const override {
        const int page = index.row();
        const QSize size = panel_->thumbSize(page);
        const QRect thumb(option.rect.x() + (option.rect.width() - size.width()) / 2, option.rect.y() + kPad, size.width(), size.height());
        const bool selected = option.state & QStyle::State_Selected;
        const bool current = page == panel_->currentPage();
        const QColor accent = option.palette.color(QPalette::Highlight);
        p->save();
        p->setRenderHint(QPainter::SmoothPixmapTransform);
        if (const QImage* image = panel_->thumbnail(page)) p->drawImage(QRectF(thumb), *image);
        else p->fillRect(thumb, xn::toColor(*paper_));
        if (selected) {
            QColor tint = accent;
            tint.setAlpha(46);
            p->fillRect(thumb, tint);
        }
        const int border = selected || current ? 2 : 1;
        p->setPen(QPen(selected || current ? accent : option.palette.color(QPalette::Mid), border));
        p->setBrush(Qt::NoBrush);
        p->drawRect(QRectF(thumb).adjusted(-border / 2.0, -border / 2.0, border / 2.0, border / 2.0));
        p->setPen(selected || current ? accent : option.palette.color(QPalette::PlaceholderText));
        const QRect label(option.rect.x(), thumb.bottom() + kPad / 2, option.rect.width(), option.fontMetrics.height());
        p->drawText(label, Qt::AlignCenter, QStringLiteral("%1").arg(page + 1, 2, 10, QLatin1Char('0')));
        p->restore();
    }

private:
    PagesPanel* panel_;
    xn_rgba* paper_;
};

QToolButton* toolButton(QWidget* parent, const QString& icon, const QString& tip) {
    auto* b = new QToolButton(parent);
    b->setIcon(xnIcon(icon));
    b->setToolTip(tip);
    b->setAutoRaise(true);
    b->setIconSize(QSize(18, 18));
    return b;
}

}  // namespace

PagesPanel::PagesPanel(QWidget* parent) : QWidget(parent) {
    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(0);

    auto* header = new QHBoxLayout;
    header->setContentsMargins(8, 2, 2, 2);
    auto* title = new QLabel(QStringLiteral("Страницы"), this);
    header->addWidget(title, 1);
    QToolButton* add = toolButton(this, QStringLiteral("plus"), QStringLiteral("Добавить страницу после текущей"));
    add->setObjectName(QStringLiteral("addPage"));
    connect(add, &QToolButton::clicked, this, [this] { addAfter(current_); });
    QToolButton* del = toolButton(this, QStringLiteral("trash"), QStringLiteral("Удалить выбранные страницы (Delete)"));
    del->setObjectName(QStringLiteral("deletePages"));
    connect(del, &QToolButton::clicked, this, [this] { remove(targetPages()); });
    header->addWidget(add);
    header->addWidget(del);
    layout->addLayout(header);

    model_ = new PageListModel(this);
    list_ = new QListView(this);
    list_->setModel(model_);
    list_->setItemDelegate(new PageDelegate(this, &paper_));
    list_->setSelectionMode(QAbstractItemView::ExtendedSelection);
    list_->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    list_->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    list_->setResizeMode(QListView::Adjust);
    list_->setSpacing(2);
    list_->setFrameShape(QFrame::NoFrame);
    list_->setContextMenuPolicy(Qt::CustomContextMenu);
    list_->setMinimumWidth(kThumbWidth + 2 * kPad + 24);
    list_->installEventFilter(this);
    layout->addWidget(list_, 1);

    // A plain click goes to the page (Ctrl and Shift only select); so do the arrow keys.
    connect(list_, &QListView::clicked, this, [this](const QModelIndex& index) {
        if (editor_ && !(QApplication::keyboardModifiers() & (Qt::ControlModifier | Qt::ShiftModifier)))
            xn_editor_go_to_page(editor_, index.row());
    });
    connect(list_->selectionModel(), &QItemSelectionModel::currentChanged, this, [this](const QModelIndex& index) {
        if (syncing_ || !editor_ || !index.isValid() || QApplication::mouseButtons() != Qt::NoButton) return;
        if (QApplication::keyboardModifiers() & (Qt::ControlModifier | Qt::ShiftModifier)) return;
        xn_editor_go_to_page(editor_, index.row());
    });
    connect(list_, &QListView::customContextMenuRequested, this, &PagesPanel::showMenu);

    settle_ = new QTimer(this);
    settle_->setSingleShot(true);
    settle_->setInterval(kSettleMs);
    connect(settle_, &QTimer::timeout, this, [this] {
        refreshStale_ = true;
        scheduleRender();
    });
    // One thumbnail per turn of the event loop, so input stays responsive while they draw.
    renderTick_ = new QTimer(this);
    renderTick_->setSingleShot(true);
    renderTick_->setInterval(0);
    connect(renderTick_, &QTimer::timeout, this, &PagesPanel::renderNext);
}

PagesPanel::~PagesPanel() = default;

void PagesPanel::setDocument(xn_note* note, xn_editor* editor) {
    model_->reset([&] {
        note_ = note && !xn_note_is_canvas(note) ? note : nullptr;
        editor_ = note_ ? editor : nullptr;
        ids_.clear();
        boxes_.clear();
        thumbs_.clear();
        current_ = -1;
    });
    reload();
    viewChanged();
}

void PagesPanel::setPaper(xn_rgba paper) {
    if (paper == paper_) return;
    paper_ = paper;
    for (Thumb& t : thumbs_) t.stale = true;
    refreshStale_ = true;
    list_->viewport()->update();
    scheduleRender();
}

void PagesPanel::contentChanged() {
    reload();
    for (Thumb& t : thumbs_) t.stale = true;
    refreshStale_ = false;
    settle_->start();
    viewChanged();
}

void PagesPanel::viewChanged() {
    const int page = editor_ ? xn_editor_current_page(editor_) : -1;
    if (page == current_) return;
    current_ = page;
    list_->viewport()->update();
    if (page < 0 || page >= pageCount()) return;
    // Keyboard navigation starts from the page on screen, which stays in sight in the panel.
    const QModelIndex index = model_->index(page);
    syncing_ = true;
    list_->selectionModel()->setCurrentIndex(index, QItemSelectionModel::NoUpdate);
    syncing_ = false;
    list_->scrollTo(index, QAbstractItemView::EnsureVisible);
}

/* Read the page list again; rows are rebuilt only when pages came, went or moved. */
void PagesPanel::reload() {
    QList<qint64> ids;
    QList<QSizeF> boxes;
    const int n = note_ ? xn_note_page_count(note_) : 0;
    ids.reserve(n);
    boxes.reserve(n);
    for (int i = 0; i < n; i++) {
        ids << xn_note_page_id(note_, i);
        double box[4];
        boxes << (xn_note_page_box(note_, i, box) && box[2] > 0 && box[3] > 0 ? QSizeF(box[2], box[3]) : QSizeF(1, std::sqrt(2.0)));
    }
    if (ids == ids_) {
        if (boxes != boxes_) {
            boxes_ = boxes;
            model_->relayout();
        }
        return;
    }
    QSet<qint64> selected;
    for (const QModelIndex& index : list_->selectionModel()->selectedIndexes()) selected.insert(ids_.value(index.row()));
    const QSet<qint64> alive(ids.cbegin(), ids.cend());
    syncing_ = true;
    model_->reset([&] {
        ids_ = ids;
        boxes_ = boxes;
        current_ = -1;
    });
    QItemSelection keep;
    for (int i = 0; i < ids_.size(); i++)
        if (selected.contains(ids_[i])) keep.select(model_->index(i), model_->index(i));
    list_->selectionModel()->select(keep, QItemSelectionModel::ClearAndSelect);
    syncing_ = false;
    for (auto it = thumbs_.begin(); it != thumbs_.end();) it = alive.contains(it.key()) ? std::next(it) : thumbs_.erase(it);
}

QList<int> PagesPanel::targetPages() const {
    QList<int> pages;
    for (const QModelIndex& index : list_->selectionModel()->selectedIndexes()) pages << index.row();
    std::sort(pages.begin(), pages.end());
    if (pages.isEmpty() && current_ >= 0 && current_ < pageCount()) pages << current_;
    return pages;
}

QSize PagesPanel::thumbSize(int page) const {
    const QSizeF box = boxes_.value(page, QSizeF(1, std::sqrt(2.0)));
    return QSize(kThumbWidth, std::max(1, int(std::lround(kThumbWidth * box.height() / box.width()))));
}

const QImage* PagesPanel::thumbnail(int page) {
    if (page < 0 || page >= pageCount()) return nullptr;
    if (needsRender(page)) scheduleRender();
    auto it = thumbs_.constFind(ids_[page]);
    return it == thumbs_.constEnd() || it->image.isNull() ? nullptr : &it->image;
}

bool PagesPanel::needsRender(int page) const {
    auto it = thumbs_.constFind(ids_.value(page));
    if (it == thumbs_.constEnd() || it->image.isNull()) return true;
    if (it->image.deviceIndependentSize().toSize() != thumbSize(page) || it->image.devicePixelRatio() != devicePixelRatioF()) return true;
    return it->stale && refreshStale_;
}

int PagesPanel::pendingThumbnails() const {
    int n = 0;
    for (int page : visibleRows()) n += needsRender(page) ? 1 : 0;
    return n;
}

QList<int> PagesPanel::visibleRows() const {
    QList<int> rows;
    if (!note_ || !list_->isVisible()) return rows;
    const QRect view = list_->viewport()->rect();
    for (int i = 0; i < pageCount(); i++)
        if (list_->visualRect(model_->index(i)).intersects(view)) rows << i;
    return rows;
}

void PagesPanel::scheduleRender() {
    if (!renderTick_->isActive()) renderTick_->start();
}

void PagesPanel::renderNext() {
    for (int page : visibleRows()) {
        if (!needsRender(page)) continue;
        Thumb& t = thumbs_[ids_[page]];
        t.image = render(page);
        t.stale = false;
        list_->update(model_->index(page));
        scheduleRender();
        return;
    }
}

/* The page as export draws it (paper, PDF background, ruling, items), scaled to its thumbnail. */
QImage PagesPanel::render(int page) const {
    double box[4];
    if (!note_ || !xn_note_page_box(note_, page, box) || box[2] <= 0) return {};
    const double dpr = devicePixelRatioF();
    const QSize size = thumbSize(page) * dpr;
    QImage image(size, QImage::Format_ARGB32_Premultiplied);
    image.fill(xn::toColor(paper_));
    {
        QPainter p(&image);
        p.setRenderHints(QPainter::Antialiasing | QPainter::SmoothPixmapTransform | QPainter::TextAntialiasing);
        const double scale = size.width() / box[2];
        p.scale(scale, size.height() / box[3]);
        p.translate(-box[0], -box[1]);
        xn::PainterTarget target(&p);
        xn_note_paint_page(note_, page, &target, host_.host(), paper_, scale);
    }
    image.setDevicePixelRatio(dpr);
    return image;
}

/* Delete, Ctrl+C, Ctrl+X and Ctrl+V act on pages while the list has the focus, not on the canvas. */
bool PagesPanel::eventFilter(QObject* watched, QEvent* e) {
    if (watched != list_ || (e->type() != QEvent::ShortcutOverride && e->type() != QEvent::KeyPress)) return QWidget::eventFilter(watched, e);
    auto* key = static_cast<QKeyEvent*>(e);
    std::function<void()> action;
    if (key->key() == Qt::Key_Delete || key->key() == Qt::Key_Backspace) action = [this] { remove(targetPages()); };
    else if (key->matches(QKeySequence::Copy)) action = [this] { copy(targetPages(), false); };
    else if (key->matches(QKeySequence::Cut)) action = [this] { copy(targetPages(), true); };
    else if (key->matches(QKeySequence::Paste)) action = [this] {
        const QList<int> pages = targetPages();
        pasteAfter(pages.isEmpty() ? pageCount() - 1 : pages.last());
    };
    if (!action || !editor_) return QWidget::eventFilter(watched, e);
    e->accept();
    if (e->type() == QEvent::KeyPress) action();
    return true;
}

void PagesPanel::showMenu(const QPoint& at) {
    if (!editor_) return;
    QMenu menu(this);
    const QModelIndex index = list_->indexAt(at);
    const bool canPaste = xn_editor_can_paste_pages(editor_);
    if (!index.isValid()) {
        const int last = pageCount() - 1;
        menu.addAction(xnIcon(QStringLiteral("plus")), QStringLiteral("Добавить страницу в конец"), this, [this, last] { addAfter(last); });
        menu.addAction(xnIcon(QStringLiteral("paste")), QStringLiteral("Вставить страницы в конец"), this, [this, last] { pasteAfter(last); })->setEnabled(canPaste);
    } else {
        // The menu acts on the selection the page belongs to, else on the page alone.
        const int page = index.row();
        if (!list_->selectionModel()->isSelected(index)) list_->selectionModel()->select(index, QItemSelectionModel::ClearAndSelect);
        const QList<int> pages = targetPages();
        const QString count = pages.size() > 1 ? QStringLiteral(" (%1)").arg(pages.size()) : QString();
        menu.addAction(xnIcon(QStringLiteral("plus")), QStringLiteral("Добавить страницу после"), this, [this, page] { addAfter(page); });
        menu.addSeparator();
        menu.addAction(xnIcon(QStringLiteral("copy")), QStringLiteral("Копировать") + count, this, [this, pages] { copy(pages, false); });
        menu.addAction(xnIcon(QStringLiteral("cut")), QStringLiteral("Вырезать") + count, this, [this, pages] { copy(pages, true); });
        menu.addAction(xnIcon(QStringLiteral("paste")), QStringLiteral("Вставить после"), this, [this, page] { pasteAfter(page); })->setEnabled(canPaste);
        menu.addSeparator();
        if (pages.size() == 1)
            menu.addAction(xnIcon(QStringLiteral("eraser")), QStringLiteral("Очистить страницу"), this, [this, page] { if (editor_) xn_editor_erase_page(editor_, page); });
        menu.addAction(xnIcon(QStringLiteral("trash")), QStringLiteral("Удалить") + count, this, [this, pages] { remove(pages); });
    }
    menu.exec(list_->viewport()->mapToGlobal(at));
}

void PagesPanel::addAfter(int page) {
    if (editor_) xn_editor_insert_page_after(editor_, page);
}

void PagesPanel::remove(const QList<int>& pages) {
    if (editor_ && !pages.isEmpty()) xn_editor_delete_pages(editor_, pages.constData(), int(pages.size()));
}

void PagesPanel::copy(const QList<int>& pages, bool cut) {
    if (!editor_ || pages.isEmpty()) return;
    if (cut) xn_editor_cut_pages(editor_, pages.constData(), int(pages.size()));
    else xn_editor_copy_pages(editor_, pages.constData(), int(pages.size()));
}

void PagesPanel::pasteAfter(int page) {
    if (editor_) xn_editor_paste_pages_after(editor_, page);
}
