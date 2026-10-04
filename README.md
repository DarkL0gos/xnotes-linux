<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="80" alt="xnotes icon" />
</p>

<h1 align="center">xnotes</h1>

<p align="center">
  A handwriting-first notebook for Android, built for pen and stylus
</p>

<p align="center">
  <a href="https://github.com/shardulvs/xnotes-android/releases/latest"><img src="https://img.shields.io/github/v/release/shardulvs/xnotes-android?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://img.shields.io/f-droid/v/com.xnotes?style=flat-square" alt="F-Droid" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="https://github.com/sponsors/shardulvs"><img src="https://img.shields.io/badge/sponsor-%E2%9D%A4-ec6cb9?style=flat-square&logo=githubsponsors&logoColor=white" alt="Sponsor" /></a>
</p>

<p align="center">
  <a href="https://github.com/shardulvs/xnotes-android/releases/latest">GitHub Releases</a> &bull;
  <a href="https://f-droid.org/en/packages/com.xnotes">F-Droid</a> &bull;
  <a href="https://play.google.com/store/apps/details?id=com.xnotes">Google Play</a>
</p>

<p align="center">
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="70" alt="Get it on F-Droid" /></a>
  <a href="https://play.google.com/store/apps/details?id=com.xnotes"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="70" alt="Get it on Google Play" /></a>
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/sevenInchScreenshots/1.png" width="90%" alt="xnotes editor: a PDF open with the ink toolbar and page thumbnails" />
  <br />
  <em>Annotating a PDF in dark mode with pages inverted</em>
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/sevenInchScreenshots/2.png" width="90%" alt="xnotes home screen: the file browser showing imported notes" />
  <br />
  <em>Browsing your notes and imported PDFs</em>
</p>

---

## At a glance

- **Pressure-sensitive ink**: a custom stroke engine turns raw stylus samples into a smooth, variable-width ribbon that swells and tapers with pen pressure, so handwriting and sketches feel natural instead of like a flat marker.
- **Vector PDF, in and out**: drop in a PDF as a page background to annotate, then export your notes back to PDF as true vector: ink and text stay crisp at any zoom instead of being flattened to pixels.
- **Razor-sharp deep zoom**: a background renderer redraws a high-resolution viewport off the main thread, so you can zoom far in and the ink stays sharp rather than turning blocky.
- **Real highlighter blending**: highlighters are composited live every frame with a true multiply blend, so overlapping strokes deepen like real ink instead of painting over one another.
- **Neon pen**: a glowing pen with a bright white core and saturated, luminous edges for accents that pop off the page.
- **Smart PDF dark mode**: invert a PDF page for comfortable night reading while leaving embedded photos and images untouched.
- **Nothing is ever flattened**: every stroke is stored as editable vector data in the open `.xnote` format, so you can re-select, move, restyle, or erase any mark at any time.
- **Stylus-aware by design**: pen and finger are handled separately, so you can pan with a finger while you draw with the pen; on devices without a stylus, finger drawing turns on automatically.
- **Private and open**: open source, no accounts, no telemetry, no network access at all. Files go through Android's Storage Access Framework, so the app needs no broad storage permission either.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/shardulvs/xnotes-android/releases/latest) | Signed APK |
| [F-Droid](https://f-droid.org/en/packages/com.xnotes) | Built reproducibly from source |
| [Google Play](https://play.google.com/store/apps/details?id=com.xnotes) | Automatic updates |

GitHub Releases and F-Droid ship the same signed APK. F-Droid rebuilds from source and verifies it against the GitHub release, so you can switch between those two without reinstalling.

## Build from source

Requires **JDK 17** (the project pins Java 17):

```bash
git clone https://github.com/shardulvs/xnotes-android.git
cd xnotes-android
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Linux desktop preview

The `shared` JVM module contains the document models, editing math, the canvas state and
input controllers, and the `.xnote` / `.xcanvas` codecs used by both hosts. The `desktop`
module is a Swing host: it opens either format and edits paged `.xnote` notes with the same
controller as Android — pen, highlighter, eraser, lasso, pan, ink colour, undo/redo, zoom
(Ctrl+wheel) — then saves the bundle safely. The mouse acts as a pressure-less pen; keys 1–5
pick tools. Text, shapes, image insertion, embedded PDF backgrounds and editing infinite
`.xcanvas` files are not available on the desktop yet.

With JDK 17 or 21 installed, build and run without an Android SDK. Both versions
produce Java 17 compatible desktop bytecode:

```bash
./gradlew -PdesktopOnly=true :shared:jvmTest :desktop:test :desktop:installDist
./desktop/build/install/desktop/bin/desktop /path/to/file.xnote
```

Omit the file argument to reopen the last file. The desktop preference file is
stored under `$XDG_CONFIG_HOME/xnotes` (or `~/.config/xnotes`), and extracted
document assets are kept under `$XDG_CACHE_HOME/xnotes` (or `~/.cache/xnotes`)
while a file is open. The application does not modify the original bundle until
Save is chosen. Unsaved edits are checkpointed under `$XDG_STATE_HOME/xnotes/session`
(or `~/.local/state/xnotes/session`) and restored on the next launch after a crash or
close; saving the document clears that session.

The only native code, tree-sitter code highlighting, lives in `native/highlight` as a
plain C library (`xn_highlight.h`) shared by the Android JNI bridge and future desktop
hosts. Build and test it standalone:

```bash
cmake -S native/highlight -B build/native-highlight
cmake --build build/native-highlight -j && ctest --test-dir build/native-highlight
```

The core also builds as a native shared library for non-JVM hosts such as the Qt app:
`libxnotes.so`, with the C API in `capi/include/xnotes.h`. Build it and run its C test
(needs gcc; no JVM at run time):

```bash
./gradlew -PdesktopOnly=true :capi:cTest
# library: capi/build/bin/linuxX64/xnotesReleaseShared/libxnotes.so
```

### Qt app (Linux)

`linux-qt/` is a Qt 6 host of `libxnotes.so`: it edits `.xnote` files with pen pressure from
tablets (the pen's eraser end erases), the mouse and touch, and follows the display's scale
natively on Wayland. Selection with its action bar, shapes, text boxes and images work; a PDF
opens as a note over its pages, and notes export to PDF. Needs Qt 6 development packages
(`qt6-base-dev`, `qt6-pdf-dev`, and `qt6-wayland` to run on Wayland) and CMake:

```bash
./gradlew -PdesktopOnly=true :capi:linkXnotesReleaseSharedLinuxX64
cmake -S linux-qt -B linux-qt/build
cmake --build linux-qt/build -j && ctest --test-dir linux-qt/build
./linux-qt/build/xnotes-qt [note.xnote]
```
