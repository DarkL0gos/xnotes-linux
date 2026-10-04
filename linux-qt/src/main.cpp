#include "CanvasWidget.h"
#include "MainWindow.h"

#include <QApplication>
#include <QOffscreenSurface>
#include <QOpenGLContext>

#include <cstdio>

int main(int argc, char** argv) {
    // Pen samples one by one: the core coalesces the repaints, and every sample shapes the ink.
    QApplication::setAttribute(Qt::AA_CompressTabletEvents, false);
    QApplication::setAttribute(Qt::AA_ShareOpenGLContexts);
    QApplication app(argc, argv);
    QApplication::setApplicationName(QStringLiteral("xnotes"));

    if (xn_api_version() != XN_API_VERSION) {
        std::fprintf(stderr, "libxnotes API %d, expected %d\n", xn_api_version(), XN_API_VERSION);
        return 1;
    }

    // Frames are composed on the CPU; --gl (or XNOTES_RENDER=gl) composes them on the GPU when
    // OpenGL works here.
    QStringList args = QApplication::arguments();
    args.removeFirst();
    if (args.removeAll(QStringLiteral("--raster")) > 0) CanvasWidget::setDefaultRender(CanvasWidget::Render::Raster);
    if (args.removeAll(QStringLiteral("--gl")) > 0) CanvasWidget::setDefaultRender(CanvasWidget::Render::Gl);
    if (CanvasWidget::defaultRender() == CanvasWidget::Render::Gl) {
        QOpenGLContext probe;
        QOffscreenSurface surface;
        surface.create();
        if (!probe.create() || !probe.makeCurrent(&surface)) {
            std::fprintf(stderr, "xnotes: no OpenGL, drawing on the CPU\n");
            CanvasWidget::setDefaultRender(CanvasWidget::Render::Raster);
        }
    }

    MainWindow window;
    if (args.isEmpty() || !window.open(args.first())) window.newNote();
    window.show();
    return QApplication::exec();
}
