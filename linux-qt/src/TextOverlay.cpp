#include "TextOverlay.h"

#include "CanvasWidget.h"
#include "CoreHost.h"

#include <QApplication>
#include <QClipboard>
#include <QInputMethodEvent>
#include <QKeyEvent>
#include <QPainter>
#include <QStyleHints>
#include <QTextBlock>

#include <cmath>

namespace {

/* Edit keys the field takes before the window's shortcuts (Ctrl+C would otherwise copy the canvas selection). */
bool isEditKey(QKeyEvent* e) {
    static const QKeySequence::StandardKey keys[] = {
        QKeySequence::Copy, QKeySequence::Cut, QKeySequence::Paste, QKeySequence::SelectAll, QKeySequence::Undo,
        QKeySequence::Redo, QKeySequence::Delete, QKeySequence::MoveToNextWord, QKeySequence::MoveToPreviousWord,
        QKeySequence::SelectNextWord, QKeySequence::SelectPreviousWord, QKeySequence::DeleteStartOfWord,
        QKeySequence::DeleteEndOfWord, QKeySequence::MoveToStartOfDocument, QKeySequence::MoveToEndOfDocument,
    };
    for (auto k : keys)
        if (e->matches(k)) return true;
    switch (e->key()) {
    case Qt::Key_Backspace: case Qt::Key_Delete: case Qt::Key_Left: case Qt::Key_Right: case Qt::Key_Up:
    case Qt::Key_Down: case Qt::Key_Home: case Qt::Key_End: case Qt::Key_Return: case Qt::Key_Enter:
        return true;
    default:
        return !e->text().isEmpty() && e->text().at(0).isPrint() && !(e->modifiers() & Qt::ControlModifier);
    }
}

}  // namespace

TextOverlay::TextOverlay(CanvasWidget* canvas) : QWidget(canvas), canvas_(canvas), cursor_(&doc_) {
    setAttribute(Qt::WA_InputMethodEnabled);
    setFocusPolicy(Qt::StrongFocus);
    setCursor(Qt::IBeamCursor);
    doc_.setUndoRedoEnabled(true);
    hide();
}

void TextOverlay::onTextEdit(const xn_text_field* field) {
    if (!field) {
        const bool hadFocus = hasFocus();
        hide();
        if (blinkTimer_) killTimer(blinkTimer_);
        blinkTimer_ = 0;
        if (hadFocus) canvas_->setFocus();
        return;
    }
    const bool opening = !isVisible();
    face_ = field->face_len > 0 ? QString::fromUtf16(reinterpret_cast<const char16_t*>(field->face), field->face_len) : QString();
    field_ = *field;
    field_.face = nullptr;
    field_.text = nullptr;
    const QString text = field->text_len > 0
        ? QString::fromUtf16(reinterpret_cast<const char16_t*>(field->text), field->text_len) : QString();
    if (opening || text != doc_.toPlainText()) {
        syncing_ = true;
        doc_.setPlainText(text);
        doc_.clearUndoRedoStacks();
        cursor_ = QTextCursor(&doc_);
        cursor_.movePosition(QTextCursor::End);
        syncing_ = false;
    }
    font_ = xn::CoreHost::fontForFace(face_, field_.font_px);
    relayout();
    place();
    if (opening) {
        show();
        raise();
        setFocus(Qt::OtherFocusReason);
        restartBlink();
    }
}

void TextOverlay::reposition() {
    xn_text_field f{};
    if (!isVisible() || !canvas_->editor() || !xn_editor_text_field(canvas_->editor(), &f)) return;
    field_ = f;
    place();
}

/* One layout, like QPainter::drawText with Qt::TextWordWrap (lines apart by height + leading). */
void TextOverlay::relayout() {
    QString text = doc_.toPlainText();
    text.replace(QLatin1Char('\n'), QChar::LineSeparator);
    layout_.clearLayout();
    layout_.setText(text);
    layout_.setFont(font_);
    QTextOption option(field_.centred ? Qt::AlignHCenter : Qt::AlignLeft);  // a shape's label is centred
    option.setWrapMode(QTextOption::WordWrap);
    layout_.setTextOption(option);
    const double leading = QFontMetricsF(font_).leading();
    double y = -leading;
    layout_.beginLayout();
    for (;;) {
        QTextLine line = layout_.createLine();
        if (!line.isValid()) break;
        line.setLineWidth(field_.width);
        y += leading;
        line.setPosition(QPointF(0, y));
        y += line.height();
    }
    layout_.endLayout();
    layoutHeight_ = std::max(y, QFontMetricsF(font_).height());
}

void TextOverlay::place() {
    const double dpr = canvas_->devicePixelRatioF();
    scale_ = field_.zoom / dpr;
    const double w = field_.width * scale_ + 3;  // the caret may sit past the last glyph
    const double h = std::max(field_.height, layoutHeight_) * scale_ + 2;
    setGeometry(QRectF(field_.x / dpr - 1, field_.y / dpr - 1, w + 2, h).toAlignedRect());
    update();
}

void TextOverlay::changed() {
    if (syncing_) return;
    relayout();
    if (auto* e = canvas_->editor()) {
        const QString text = doc_.toPlainText();
        xn_editor_text_update(e, reinterpret_cast<const uint16_t*>(text.utf16()), int(text.size()));
        xn_text_field f{};
        if (xn_editor_text_field(e, &f)) field_ = f;
    }
    place();
    restartBlink();
}

void TextOverlay::selectAll() {
    cursor_.select(QTextCursor::Document);
    update();
}

// --- painting ------------------------------------------------------------------------------------

void TextOverlay::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.setRenderHints(QPainter::Antialiasing | QPainter::TextAntialiasing);
    // The box's outline, as the Android field shows it.
    QPen frame(palette().color(QPalette::Highlight), 1, Qt::DashLine);
    p.setPen(frame);
    p.drawRect(QRectF(0.5, 0.5, width() - 1, height() - 1));

    p.translate(1, 1);
    p.scale(scale_, scale_);
    QList<QTextLayout::FormatRange> selections;
    if (cursor_.hasSelection()) {
        QTextLayout::FormatRange sel;
        sel.start = cursor_.selectionStart();
        sel.length = cursor_.selectionEnd() - cursor_.selectionStart();
        QColor bg = palette().color(QPalette::Highlight);
        bg.setAlpha(110);
        sel.format.setBackground(bg);
        selections << sel;
    }
    p.setPen(xn::toColor(field_.color));
    layout_.draw(&p, QPointF(0, 0), selections);
    if (caretOn_ && hasFocus()) layout_.drawCursor(&p, QPointF(0, 0), cursor_.position(), std::max(1.0, 1.5 / scale_));
}

QRectF TextOverlay::caretRect() const {
    const int pos = cursor_.position();
    QTextLine line = layout_.lineForTextPosition(pos);
    if (!line.isValid()) return QRectF(1, 1, 1, QFontMetricsF(font_).height() * scale_);
    const double x = line.cursorToX(pos);
    return QRectF(1 + x * scale_, 1 + line.y() * scale_, 1, line.height() * scale_);
}

void TextOverlay::restartBlink() {
    caretOn_ = true;
    if (blinkTimer_) killTimer(blinkTimer_);
    const int period = QApplication::cursorFlashTime() / 2;
    blinkTimer_ = period > 0 ? startTimer(period) : 0;
    update();
}

void TextOverlay::timerEvent(QTimerEvent* e) {
    if (e->timerId() != blinkTimer_) return QWidget::timerEvent(e);
    caretOn_ = !caretOn_;
    update();
}

// --- input ---------------------------------------------------------------------------------------

bool TextOverlay::event(QEvent* e) {
    if (e->type() == QEvent::ShortcutOverride && isEditKey(static_cast<QKeyEvent*>(e))) {
        e->accept();
        return true;
    }
    return QWidget::event(e);
}

int TextOverlay::hitTest(QPointF widget) const {
    const QPointF page = (widget - QPointF(1, 1)) / scale_;
    for (int i = 0; i < layout_.lineCount(); i++) {
        QTextLine line = layout_.lineAt(i);
        if (page.y() < line.y() + line.height() || i == layout_.lineCount() - 1) return line.xToCursor(page.x());
    }
    return 0;
}

void TextOverlay::moveVertically(int lines, QTextCursor::MoveMode mode) {
    QTextLine line = layout_.lineForTextPosition(cursor_.position());
    if (!line.isValid()) return;
    const int target = line.lineNumber() + lines;
    if (target < 0) return cursor_.movePosition(QTextCursor::Start, mode), void();
    if (target >= layout_.lineCount()) return cursor_.movePosition(QTextCursor::End, mode), void();
    const double x = line.cursorToX(cursor_.position());
    cursor_.setPosition(layout_.lineAt(target).xToCursor(x), mode);
}

void TextOverlay::keyPressEvent(QKeyEvent* e) {
    const auto mode = (e->modifiers() & Qt::ShiftModifier) ? QTextCursor::KeepAnchor : QTextCursor::MoveAnchor;
    bool edited = false;
    if (e->matches(QKeySequence::SelectAll)) {
        selectAll();
    } else if (e->matches(QKeySequence::Copy) || e->matches(QKeySequence::Cut)) {
        if (cursor_.hasSelection()) {
            QApplication::clipboard()->setText(cursor_.selectedText().replace(QChar::ParagraphSeparator, QLatin1Char('\n')));
            if (e->matches(QKeySequence::Cut)) cursor_.removeSelectedText(), edited = true;
        }
    } else if (e->matches(QKeySequence::Paste)) {
        cursor_.insertText(QApplication::clipboard()->text());
        edited = true;
    } else if (e->matches(QKeySequence::Undo)) {
        doc_.undo(&cursor_);
        edited = true;
    } else if (e->matches(QKeySequence::Redo)) {
        doc_.redo(&cursor_);
        edited = true;
    } else if (e->matches(QKeySequence::DeleteStartOfWord)) {
        if (!cursor_.hasSelection()) cursor_.movePosition(QTextCursor::PreviousWord, QTextCursor::KeepAnchor);
        cursor_.removeSelectedText();
        edited = true;
    } else if (e->matches(QKeySequence::DeleteEndOfWord)) {
        if (!cursor_.hasSelection()) cursor_.movePosition(QTextCursor::NextWord, QTextCursor::KeepAnchor);
        cursor_.removeSelectedText();
        edited = true;
    } else if (e->matches(QKeySequence::MoveToNextWord) || e->matches(QKeySequence::SelectNextWord)) {
        cursor_.movePosition(QTextCursor::NextWord, mode);
    } else if (e->matches(QKeySequence::MoveToPreviousWord) || e->matches(QKeySequence::SelectPreviousWord)) {
        cursor_.movePosition(QTextCursor::PreviousWord, mode);
    } else if (e->matches(QKeySequence::MoveToStartOfDocument) || e->matches(QKeySequence::SelectStartOfDocument)) {
        cursor_.movePosition(QTextCursor::Start, mode);
    } else if (e->matches(QKeySequence::MoveToEndOfDocument) || e->matches(QKeySequence::SelectEndOfDocument)) {
        cursor_.movePosition(QTextCursor::End, mode);
    } else {
        switch (e->key()) {
        case Qt::Key_Backspace:
            cursor_.deletePreviousChar();
            edited = true;
            break;
        case Qt::Key_Delete:
            cursor_.deleteChar();
            edited = true;
            break;
        case Qt::Key_Left:
            cursor_.movePosition(QTextCursor::PreviousCharacter, mode);
            break;
        case Qt::Key_Right:
            cursor_.movePosition(QTextCursor::NextCharacter, mode);
            break;
        case Qt::Key_Up:
            moveVertically(-1, mode);
            break;
        case Qt::Key_Down:
            moveVertically(1, mode);
            break;
        case Qt::Key_Home: {
            QTextLine line = layout_.lineForTextPosition(cursor_.position());
            if (line.isValid()) cursor_.setPosition(line.textStart(), mode);
            break;
        }
        case Qt::Key_End: {
            QTextLine line = layout_.lineForTextPosition(cursor_.position());
            if (line.isValid()) {
                int end = line.textStart() + line.textLength();
                // Stop before a wrapped line's trailing space or a line break.
                if (end > line.textStart() && end < doc_.characterCount() - 1) end--;
                cursor_.setPosition(std::max(end, line.textStart()), mode);
            }
            break;
        }
        case Qt::Key_Return:
        case Qt::Key_Enter:
            cursor_.insertText(QStringLiteral("\n"));
            edited = true;
            break;
        default:
            if (!e->text().isEmpty() && e->text().at(0).isPrint() && !(e->modifiers() & Qt::ControlModifier)) {
                cursor_.insertText(e->text());
                edited = true;
            } else {
                return QWidget::keyPressEvent(e);
            }
        }
    }
    if (edited) changed(); else restartBlink();
}

void TextOverlay::inputMethodEvent(QInputMethodEvent* e) {
    if (!e->commitString().isEmpty() || e->replacementLength() > 0) {
        if (e->replacementLength() > 0) {
            cursor_.setPosition(cursor_.position() + e->replacementStart());
            cursor_.setPosition(cursor_.position() + e->replacementLength(), QTextCursor::KeepAnchor);
        }
        cursor_.insertText(e->commitString());
        changed();
    }
    e->accept();
}

QVariant TextOverlay::inputMethodQuery(Qt::InputMethodQuery query) const {
    switch (query) {
    case Qt::ImEnabled: return true;
    case Qt::ImCursorRectangle: return caretRect();
    case Qt::ImFont: return font_;
    case Qt::ImCursorPosition: return cursor_.position() - cursor_.block().position();
    case Qt::ImSurroundingText: return cursor_.block().text();
    case Qt::ImCurrentSelection: return cursor_.selectedText();
    case Qt::ImAnchorPosition: return cursor_.anchor() - cursor_.block().position();
    default: return QWidget::inputMethodQuery(query);
    }
}

void TextOverlay::mousePressEvent(QMouseEvent* e) {
    if (e->button() != Qt::LeftButton) return;
    cursor_.setPosition(hitTest(e->position()),
                        (e->modifiers() & Qt::ShiftModifier) ? QTextCursor::KeepAnchor : QTextCursor::MoveAnchor);
    restartBlink();
}

void TextOverlay::mouseMoveEvent(QMouseEvent* e) {
    if (!(e->buttons() & Qt::LeftButton)) return;
    cursor_.setPosition(hitTest(e->position()), QTextCursor::KeepAnchor);
    restartBlink();
}

void TextOverlay::mouseDoubleClickEvent(QMouseEvent* e) {
    cursor_.setPosition(hitTest(e->position()));
    cursor_.select(QTextCursor::WordUnderCursor);
    restartBlink();
}

void TextOverlay::focusOutEvent(QFocusEvent* e) {
    update();
    QWidget::focusOutEvent(e);
}
