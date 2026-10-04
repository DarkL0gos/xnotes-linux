#include "NoteFinder.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>

#include <zlib.h>

#include <algorithm>

namespace {

const char kSourceEntry[] = "assets/source.pdf";

uint16_t u16(const uchar* p) { return uint16_t(p[0] | (p[1] << 8)); }
uint32_t u32(const uchar* p) { return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24); }

std::optional<uint32_t> crcOf(const QString& path) {
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) return std::nullopt;
    uLong crc = crc32(0, nullptr, 0);
    QByteArray chunk;
    while (!(chunk = f.read(1 << 20)).isEmpty())
        crc = crc32(crc, reinterpret_cast<const Bytef*>(chunk.constData()), uInt(chunk.size()));
    return uint32_t(crc);
}

}  // namespace

std::optional<EmbeddedPdf> embeddedPdf(const QString& notePath) {
    QFile f(notePath);
    if (!f.open(QIODevice::ReadOnly) || f.size() < 22) return std::nullopt;
    // The end-of-central-directory record: in the last 22 bytes plus at most a 64 KiB comment.
    const qint64 tail = std::min<qint64>(f.size(), 22 + 0xFFFF);
    f.seek(f.size() - tail);
    const QByteArray end = f.read(tail);
    const auto* e = reinterpret_cast<const uchar*>(end.constData());
    qint64 eocd = -1;
    for (qint64 i = end.size() - 22; i >= 0; i--)
        if (u32(e + i) == 0x06054b50) { eocd = i; break; }
    if (eocd < 0) return std::nullopt;
    const uint32_t dirSize = u32(e + eocd + 12), dirOffset = u32(e + eocd + 16);
    if (dirOffset == 0xFFFFFFFF || qint64(dirOffset) + dirSize > f.size()) return std::nullopt;  // ZIP64: not ours
    f.seek(dirOffset);
    const QByteArray dir = f.read(dirSize);
    const auto* d = reinterpret_cast<const uchar*>(dir.constData());
    for (qint64 at = 0; at + 46 <= dir.size() && u32(d + at) == 0x02014b50;) {
        const uint16_t nameLen = u16(d + at + 28), extraLen = u16(d + at + 30), commentLen = u16(d + at + 32);
        if (at + 46 + nameLen > dir.size()) break;
        const QByteArray name(reinterpret_cast<const char*>(d + at + 46), nameLen);
        if (name == kSourceEntry) {
            const uint32_t size = u32(d + at + 24);
            if (size == 0xFFFFFFFF) return std::nullopt;
            return EmbeddedPdf{u32(d + at + 16), size};
        }
        at += 46 + nameLen + extraLen + commentLen;
    }
    return std::nullopt;
}

QStringList notesForPdf(const QString& pdfPath) {
    const QFileInfo pdf(pdfPath);
    const QDir dir = pdf.absoluteDir();
    const QString sameName = dir.filePath(pdf.completeBaseName() + QStringLiteral(".xnote"));
    QStringList found;
    if (QFileInfo(sameName).isFile()) found << sameName;

    QFileInfoList others = dir.entryInfoList({QStringLiteral("*.xnote")}, QDir::Files | QDir::Readable);
    std::optional<uint32_t> crc;  // computed only once some note's embedded PDF has the same size
    std::sort(others.begin(), others.end(), [](const QFileInfo& a, const QFileInfo& b) { return a.lastModified() > b.lastModified(); });
    for (const QFileInfo& note : std::as_const(others)) {
        if (note.absoluteFilePath() == QFileInfo(sameName).absoluteFilePath()) continue;
        const auto embedded = embeddedPdf(note.absoluteFilePath());
        if (!embedded || embedded->size != uint64_t(pdf.size())) continue;
        if (!crc) crc = crcOf(pdf.absoluteFilePath());
        if (crc && *crc == embedded->crc) found << note.absoluteFilePath();
    }
    return found;
}
