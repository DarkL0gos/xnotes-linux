<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="80" alt="xnotes icon" />
</p>

<h1 align="center">xnotes for Linux</h1>

<p align="center">
  A handwriting-first notebook for pen and stylus, ported to the Linux desktop with Qt
</p>

This is a Linux port of [xnotes](https://github.com/shardulvs/xnotes-android), the Android
notebook by [shardulvs](https://github.com/shardulvs). The document model, the `.xnote` format,
the stroke engine and the canvas logic are the original app's Kotlin code, built with Kotlin/Native
into a C library, `libxnotes.so`; a Qt 6 application draws it and feeds it pen, mouse and touch
input. Notes stay compatible with the Android app.

## What it does

- **Pressure-sensitive ink** from graphics tablets (tested with Wacom): the pen's eraser end
  erases, its first barrel button erases too, and its second one drags the page.
- **Pen, highlighter, eraser, lasso and selection**, with a floating bar over the selection:
  delete, cut, copy, duplicate, bring to front, restyle, lock.
- **Text boxes and images** (from a file, the clipboard, or dropped onto the page).
- **PDF**: open a PDF to annotate it (an existing note made from it is offered first), and export
  any note to PDF.
- **Quick on large screens**: while writing, only the part of the view the stroke changed is
  repainted (about 0.1 ms per pen sample at 3456×2304); PDF pages render in the background.
- **Page backgrounds**: paper colour and ruling (lines, dots or grid, with spacing, colour and
  opacity) for the whole note or a single page.
- **Infinite canvases** (`.xcanvas`): pan and zoom without bounds, with the same tools, a
  ruled or dotted background, and the same partial repaints while writing.
- **Native Wayland scaling**, touchpad pinch zoom, scroll bars, dark and light paper.

## Building

You need a JDK (17 or 21), gcc, CMake and the Qt 6 development packages. On Debian or Ubuntu:

```bash
sudo apt install openjdk-21-jdk build-essential cmake qt6-base-dev qt6-pdf-dev qt6-wayland
```

### The core library

`capi/` builds the core as `libxnotes.so`, with its C API in `capi/include/xnotes.h`. The first
build downloads the Kotlin/Native toolchain; no JVM is needed at run time.

```bash
./gradlew -PdesktopOnly=true :capi:linkXnotesReleaseSharedLinuxX64
# capi/build/bin/linuxX64/xnotesReleaseShared/libxnotes.so
```

Its tests: `./gradlew -PdesktopOnly=true :shared:jvmTest :shared:linuxX64Test :capi:cTest`.

### The Qt application

`linux-qt/` links the library built above:

```bash
cmake -S linux-qt -B linux-qt/build
cmake --build linux-qt/build -j
ctest --test-dir linux-qt/build          # optional: runs offscreen
./linux-qt/build/xnotes-qt [note.xnote | canvas.xcanvas | document.pdf]
```

Frames are composed on the CPU by default; `--gl` composes them with OpenGL instead.

To open `.xnote` and `.xcanvas` files from the file manager (otherwise it treats them as ZIP archives), register
the file types, their icons and the application for your user. Rerun it after moving the build; `--remove`
undoes it. GNOME keeps the icons of known apps in memory, so a changed app icon may only show after logging
out and back in.

```bash
./linux-qt/desktop/install-desktop.sh
```

## Plan

- [x] Core as a Kotlin Multiplatform library (`.xnote` format, stroke engine, canvas, editing)
- [x] C API and `libxnotes.so`
- [x] Qt app: pen pressure, tablet eraser and barrel buttons, mouse, touch, touchpad pinch
- [x] Pen, highlighter, eraser (whole strokes or area, sized), lasso and selection with its action bar, undo/redo, pages
- [x] Text boxes and images
- [x] Page background: paper colour and ruling (lines, dots, grid) for the note or one page
- [x] PDF import with background rendering, PDF export
- [x] Partial repaints while writing, fast zoom on large screens
- [x] `.xnote` and `.xcanvas` file type registration, with the app icon and icons for both file types
- [x] Infinite canvas (`.xcanvas`): drawing, erasing, selection, shapes, images, background, tiles at the exact zoom
- [x] Pages side panel: thumbnails, adding, deleting, copying and pasting pages
- [x] Pages and canvas tiles drawn on background threads
- [x] Pen types (plain, dashed, calligraphy, speed, taper) with their settings, and a switch to ignore pressure
- [x] Basic shapes: line, arrow, rectangle, ellipse, circle, triangle
- [ ] The original app's new shapes: the shape library (flowchart, block arrows and callouts, 3D and maths),
  editing points and knobs, labels, connectors. The core already reads, writes and edits them; the Qt app
  needs the tools for them

## License

MIT, as the original project; see [LICENSE](LICENSE).
