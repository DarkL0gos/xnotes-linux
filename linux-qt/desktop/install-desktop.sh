#!/bin/sh
# Registers the Qt app for the current user: the .xnote and .xcanvas file types, a menu entry with the app's
# icon (the Android launcher icon from the README), and xnotes as the
# default application for both (PDFs only get it in "Open with"). Exec points at the build, so
# rerun after moving it. Undo with: install-desktop.sh --remove
set -e
here=$(cd "$(dirname "$0")" && pwd)
exe=${XNOTES_EXE:-$(cd "$here/.." && pwd)/build/xnotes-qt}
data=${XDG_DATA_HOME:-$HOME/.local/share}
desktop="$data/applications/xnotes-qt.desktop"
mime="$data/mime/packages/xnotes-mime.xml"
icon_src="$here/../../fastlane/metadata/android/en-US/images/icon.png"
icon="$data/icons/hicolor/512x512/apps/xnotes.png"

if [ "$1" = "--remove" ]; then
    rm -f "$desktop" "$mime" "$icon"
    gtk-update-icon-cache -q -t "$data/icons/hicolor" 2>/dev/null || true
    update-mime-database "$data/mime"
    update-desktop-database "$data/applications" 2>/dev/null || true
    echo "removed"
    exit 0
fi

[ -x "$exe" ] || { echo "no executable at $exe (build linux-qt first, or set XNOTES_EXE)" >&2; exit 1; }
mkdir -p "$data/mime/packages" "$data/applications" "$(dirname "$icon")"
cp "$icon_src" "$icon"
gtk-update-icon-cache -q -t "$data/icons/hicolor" 2>/dev/null || true
cp "$here/xnotes-mime.xml" "$mime"
update-mime-database "$data/mime"
cat > "$desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=xnotes
Comment=Handwritten notes
Comment[ru]=Рукописные заметки
Exec="$exe" %f
Icon=xnotes
Terminal=false
Categories=Office;Graphics;
MimeType=application/x-xnote;application/x-xcanvas;application/pdf;
DESKTOP
update-desktop-database "$data/applications" 2>/dev/null || true
xdg-mime default xnotes-qt.desktop application/x-xnote
xdg-mime default xnotes-qt.desktop application/x-xcanvas
echo "registered $exe for .xnote and .xcanvas"
