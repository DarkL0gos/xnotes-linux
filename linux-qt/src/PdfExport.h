// Writing a note as a PDF with QPdfWriter, drawn by the core through the QPainter renderer.
#pragma once

#include "xnotes.h"

#include <QString>

#include <functional>

/*
 * Write note to path: a PDF page per note page at its own paper size. Ink, shapes and text go in
 * as vectors; a PDF background is rendered at 300 dpi; paper fills pages without a colour of their
 * own. progress(done, total) runs after each page. Returns an error message, or empty on success.
 */
QString writePdf(xn_note* note, const xn_host* host, const QString& path, xn_rgba paper,
                 const std::function<void(int, int)>& progress = {});
