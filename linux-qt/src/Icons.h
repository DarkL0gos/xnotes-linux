// The app's icons: the Android app's set (generated into linux-qt/icons/svg by
// linux-qt/icons/generate.py), recoloured to the palette and rendered sharp for HiDPI.
#pragma once

#include <QColor>
#include <QIcon>
#include <QString>

/* The icon [name] (an XnotesIcons name, or ic_stroke_* for a pen) in the palette's text colour. */
QIcon xnIcon(const QString& name);

/* The same in an explicit colour. */
QIcon xnIcon(const QString& name, const QColor& color);
