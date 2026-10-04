// Finding the notes already made from a PDF, so opening the PDF again can offer them.
#pragma once

#include <QString>
#include <QStringList>

#include <cstdint>
#include <optional>

/*
 * Notes in the PDF's folder that annotate it: the one with the PDF's own name, then any whose
 * embedded source PDF has the same size and CRC-32 (found in the ZIP directory, nothing unpacked),
 * most recently changed first.
 */
QStringList notesForPdf(const QString& pdfPath);

/* Size and CRC-32 of a note's embedded source PDF, from its ZIP central directory. */
struct EmbeddedPdf {
    uint32_t crc;
    uint64_t size;
};
std::optional<EmbeddedPdf> embeddedPdf(const QString& notePath);
