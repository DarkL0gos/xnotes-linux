#!/usr/bin/env python3
"""Make the desktop icons from the app icon (the README's): the app at several sizes, and the
.xnote / .xcanvas file icons (a ruled page with a folded corner, a dotted canvas), each with
the logo. Run after the app icon changes; install-desktop.sh installs what this writes.
Needs Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
SRC = HERE.parents[1] / "fastlane/metadata/android/en-US/images/icon.png"
OUT = HERE / "icons"
SIZES = [16, 24, 32, 48, 64, 128, 256, 512]
S = 1024  # drawn at twice the largest size, then scaled down

BG = (20, 20, 20, 255)
RULE = (52, 52, 52, 255)
EDGE = (90, 90, 90, 255)
FOLD = (70, 70, 70, 255)


def logo() -> Image.Image:
    """The X alone, on transparency: the icon's bright pixels."""
    icon = Image.open(SRC).convert("RGBA")
    # Coloured or light: the red shadow is dark by luminance, so take the strongest channel.
    mask = Image.new("L", icon.size)
    mask.putdata([255 if max(p[:3]) > 120 else 0 for p in icon.getdata()])
    glyph = Image.new("RGBA", icon.size, (0, 0, 0, 0))
    glyph.paste(icon, (0, 0), mask)
    return glyph.crop(glyph.getbbox())


def place(img: Image.Image, glyph: Image.Image, box):
    x0, y0, x1, y1 = box
    g = glyph.copy()
    g.thumbnail((x1 - x0, y1 - y0), Image.LANCZOS)
    img.alpha_composite(g, (x0 + (x1 - x0 - g.width) // 2, y0 + (y1 - y0 - g.height) // 2))


def note(glyph):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    l, t, r, b, fold = 170, 40, 854, 984, 190
    page = [(l, t), (r - fold, t), (r, t + fold), (r, b), (l, b)]
    d.polygon(page, fill=BG)
    for y in range(t + fold + 60, b - 40, 64):
        d.line([(l + 50, y), (r - 50, y)], fill=RULE, width=8)
    d.polygon(page, outline=EDGE, width=14)
    d.polygon([(r - fold, t), (r - fold, t + fold), (r, t + fold)], fill=FOLD, outline=EDGE, width=14)
    place(img, glyph, (l + 70, t + 250, r - 70, b - 160))
    return img


def canvas(glyph):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    box = (60, 140, 964, 884)
    d.rounded_rectangle(box, radius=70, fill=BG)
    for y in range(box[1] + 70, box[3] - 40, 72):
        for x in range(box[0] + 70, box[2] - 40, 72):
            d.ellipse([x - 9, y - 9, x + 9, y + 9], fill=(70, 70, 70, 255))
    d.rounded_rectangle(box, radius=70, outline=EDGE, width=14)
    place(img, glyph, (box[0] + 170, box[1] + 110, box[2] - 170, box[3] - 110))
    return img


def write(img, kind, name):
    for size in SIZES:
        path = OUT / f"{size}x{size}" / kind / f"{name}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        img.resize((size, size), Image.LANCZOS).save(path, optimize=True)


app = Image.open(SRC).convert("RGBA").resize((S, S), Image.LANCZOS)
g = logo()
write(app, "apps", "xnotes")
write(note(g), "mimetypes", "application-x-xnote")
write(canvas(g), "mimetypes", "application-x-xcanvas")
print("icons written to", OUT)
