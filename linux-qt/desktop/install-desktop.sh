#!/bin/sh
# Registers the Qt app for the current user: the .xnote and .xcanvas file types with icons of their
# own, a menu entry with the app's icon (the README's), and xnotes as the default application for
# both (PDFs only get it in "Open with"). Exec points at the build, so rerun after moving it.
# Undo with: install-desktop.sh --remove
#
# The icons are made by make-icons.py from the app icon. GNOME Shell keeps the icons of the apps it
# knows in memory: if the menu or the dock still shows the old one, log out and back in.
set -e
here=$(cd "$(dirname "$0")" && pwd)
exe=${XNOTES_EXE:-$(cd "$here/.." && pwd)/build/xnotes-qt}
data=${XDG_DATA_HOME:-$HOME/.local/share}
desktop="$data/applications/xnotes-qt.desktop"
mime="$data/mime/packages/xnotes-mime.xml"
icons="$data/icons/hicolor"
icon_names="apps/xnotes.png mimetypes/application-x-xnote.png mimetypes/application-x-xcanvas.png"

# Let the desktop see what changed: menus, file types, and the icon theme (its cache, and the
# directory's time, which GTK watches).
refresh() {
    update-mime-database "$data/mime"
    update-desktop-database "$data/applications" 2>/dev/null || true
    touch "$icons"
    gtk-update-icon-cache -q -f -t "$icons" 2>/dev/null || true
    xdg-desktop-menu forceupdate 2>/dev/null || true
}

if [ "$1" = "--remove" ]; then
    rm -f "$desktop" "$mime"
    for size in "$here"/icons/*; do
        for name in $icon_names; do rm -f "$icons/$(basename "$size")/$name"; done
    done
    refresh
    echo "removed"
    exit 0
fi

[ -x "$exe" ] || { echo "no executable at $exe (build linux-qt first, or set XNOTES_EXE)" >&2; exit 1; }
mkdir -p "$data/mime/packages" "$data/applications"
for size in "$here"/icons/*; do
    for name in $icon_names; do
        mkdir -p "$icons/$(basename "$size")/$(dirname "$name")"
        cp "$size/$name" "$icons/$(basename "$size")/$name"
    done
done
cp "$here/xnotes-mime.xml" "$mime"
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
StartupWMClass=xnotes-qt
DESKTOP
refresh
xdg-mime default xnotes-qt.desktop application/x-xnote
xdg-mime default xnotes-qt.desktop application/x-xcanvas
echo "registered $exe for .xnote and .xcanvas"
