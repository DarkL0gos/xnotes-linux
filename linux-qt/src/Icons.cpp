#include "Icons.h"

#include <QApplication>
#include <QBuffer>
#include <QFile>
#include <QHash>
#include <QImageReader>
#include <QPalette>
#include <QPixmap>

QIcon xnIcon(const QString& name) { return xnIcon(name, QApplication::palette().color(QPalette::ButtonText)); }

QIcon xnIcon(const QString& name, const QColor& color) {
    static QHash<QString, QIcon> cache;
    const QString key = name + QLatin1Char('@') + color.name(QColor::HexArgb);
    if (auto it = cache.constFind(key); it != cache.constEnd()) return *it;
    QFile file(QStringLiteral(":/icons/%1.svg").arg(name));
    if (!file.open(QIODevice::ReadOnly)) return {};
    // The files are drawn in black; recolour by text, then rasterize per size so no size is blurry.
    QByteArray svg = file.readAll();
    svg.replace("#000000", color.name(QColor::HexRgb).toLatin1());
    QIcon icon;
    for (int px : {16, 20, 24, 32, 40, 48, 64, 72}) {
        QBuffer buffer(&svg);
        buffer.open(QIODevice::ReadOnly);
        QImageReader reader(&buffer, "svg");
        reader.setScaledSize(QSize(px, px));
        const QImage image = reader.read();
        if (!image.isNull()) icon.addPixmap(QPixmap::fromImage(image));
    }
    cache.insert(key, icon);
    return icon;
}
