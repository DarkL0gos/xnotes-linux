// The side panel's Pages tab (the Android SidePanel): a thumbnail per page, the current one marked;
// a click goes to a page, Ctrl/Shift-click selects several, and pages are added, deleted, copied,
// cut, pasted and erased from its buttons, keys and context menu.
#pragma once

#include "CoreHost.h"
#include "xnotes.h"

#include <QHash>
#include <QImage>
#include <QList>
#include <QSize>
#include <QWidget>

class QListView;
class QTimer;
class PageListModel;

class PagesPanel : public QWidget {
    Q_OBJECT
public:
    explicit PagesPanel(QWidget* parent = nullptr);
    ~PagesPanel() override;

    /* Show note's pages, edited through editor; nullptrs empty the panel. */
    void setDocument(xn_note* note, xn_editor* editor);
    /* Paper for pages without a colour of their own (the canvas's): redraws the thumbnails. */
    void setPaper(xn_rgba paper);

    /* The pages or what is on them changed: thumbnails follow once the edit settles. */
    void contentChanged();
    /* The current page may have moved. */
    void viewChanged();

    /* Selected pages in order; the current page when none is. */
    QList<int> targetPages() const;
    int pageCount() const { return int(ids_.size()); }
    int currentPage() const { return current_; }

    /* For the delegate and the tests: the page's thumbnail (null until rendered), and its size. */
    const QImage* thumbnail(int page);
    QSize thumbSize(int page) const;
    /* Thumbnails of visible pages still to render or refresh. */
    int pendingThumbnails() const;

    QListView* list() const { return list_; }

protected:
    bool eventFilter(QObject* watched, QEvent* e) override;

private:
    struct Thumb {
        QImage image;
        bool stale = false;
    };

    xn_note* note_ = nullptr;
    xn_editor* editor_ = nullptr;
    xn::CoreHost host_;  // renders synchronously, PDF backgrounds included
    xn_rgba paper_ = 0x161616FFu;
    QList<qint64> ids_;
    QList<QSizeF> boxes_;  // page sizes, margins included
    int current_ = -1;
    QHash<qint64, Thumb> thumbs_;
    bool refreshStale_ = false;  // stale thumbnails may re-render (the edit settled)
    bool syncing_ = false;       // the panel itself moves the list's current row
    PageListModel* model_;
    QListView* list_;
    QTimer* settle_;
    QTimer* renderTick_;

    void reload();
    void scheduleRender();
    void renderNext();
    QImage render(int page) const;
    QList<int> visibleRows() const;
    bool needsRender(int page) const;

    void showMenu(const QPoint& at);
    void addAfter(int page);
    void remove(const QList<int>& pages);
    void copy(const QList<int>& pages, bool cut);
    void pasteAfter(int page);
};
