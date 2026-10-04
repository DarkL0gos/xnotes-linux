#!/usr/bin/env python3
"""Writes the Qt app's icons as SVG files from the Android app's own sources, so both share one set:
the line icons of app/src/main/java/com/xnotes/ui/icons/XnotesIcons.kt (24x24, 2 px round stroke)
and the drawn pen icons of app/src/main/res/drawable/ic_stroke_*.xml. Run from anywhere:

    python3 linux-qt/icons/generate.py

Icons are written in black; the app recolours them to its palette when it loads them.
"""
import pathlib
import re
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).resolve().parent / "svg"
KOTLIN = ROOT / "app/src/main/java/com/xnotes/ui/icons/XnotesIcons.kt"
DRAWABLES = ROOT / "app/src/main/res/drawable"

# The geometry helpers XnotesIcons.kt builds paths with, the same arithmetic in Python.
def num(v):
    return repr(float(v))

def circle(cx, cy, r):
    return f"M {num(cx - r)} {num(cy)} a {num(r)} {num(r)} 0 1 0 {num(2 * r)} 0 a {num(r)} {num(r)} 0 1 0 {num(-2 * r)} 0"

def rect(x, y, w, h):
    return f"M {num(x)} {num(y)} h {num(w)} v {num(h)} h {num(-w)} Z"

def roundRect(x, y, w, h, r):
    return (f"M {num(x + r)} {num(y)} h {num(w - 2 * r)} a {num(r)} {num(r)} 0 0 1 {num(r)} {num(r)} v {num(h - 2 * r)} "
            f"a {num(r)} {num(r)} 0 0 1 {num(-r)} {num(r)} h {num(-(w - 2 * r))} a {num(r)} {num(r)} 0 0 1 {num(-r)} {num(-r)} "
            f"v {num(-(h - 2 * r))} a {num(r)} {num(r)} 0 0 1 {num(r)} {num(-r)} Z")

def ellipse(cx, cy, rx, ry):
    return f"M {num(cx - rx)} {num(cy)} a {num(rx)} {num(ry)} 0 1 0 {num(2 * rx)} 0 a {num(rx)} {num(ry)} 0 1 0 {num(-2 * rx)} 0"

HELPERS = {"circle": circle, "rect": rect, "roundRect": roundRect, "ellipse": ellipse}

def balanced(src, start):
    """The text inside the parentheses opening at src[start]."""
    depth, i, in_str = 0, start, False
    while i < len(src):
        c = src[i]
        if in_str:
            if c == "\\":
                i += 1
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return src[start + 1:i]
        i += 1
    raise ValueError("unbalanced")

def arguments(body):
    """Top-level comma-separated arguments of a call."""
    args, depth, cur, in_str = [], 0, "", False
    for c in body:
        if in_str:
            cur += c
            if c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "," and depth == 0:
            args.append(cur.strip())
            cur = ""
            continue
        cur += c
    if cur.strip():
        args.append(cur.strip())
    return args

CONSTANTS = {}

def evaluate(arg):
    if arg in CONSTANTS:
        return CONSTANTS[arg]
    if arg.startswith('"'):
        s = arg[1:-1]
        if "$" in s:
            raise ValueError("template")
        return s
    m = re.fullmatch(r"(\w+)\((.*)\)", arg, re.S)
    if m and m.group(1) in HELPERS:
        return HELPERS[m.group(1)](*[float(x) for x in arguments(m.group(2))])
    raise ValueError(f"cannot evaluate {arg[:40]}")

def line_icons():
    src = KOTLIN.read_text()
    for m in re.finditer(r'\bconst val (\w+) = ("(?:[^"\\]|\\.)*")', src):
        CONSTANTS[m.group(1)] = evaluate(m.group(2))
    icons = {}
    for m in re.finditer(r"\bval (\w+) = icon\(", src):
        try:
            icons[m.group(1)] = [evaluate(a) for a in arguments(balanced(src, m.end() - 1))]
        except ValueError as e:
            print(f"skipped {m.group(1)}: {e}")
    # Aliases: val download = importDoc
    for m in re.finditer(r"\bval (\w+) = (\w+)\s*$", src, re.M):
        if m.group(2) in icons:
            icons[m.group(1)] = icons[m.group(2)]
    return icons

def write_line_icon(name, paths):
    d = " ".join(paths)
    (OUT / f"{name}.svg").write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">'
        f'<path d="{d}" fill="none" stroke="#000000" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>\n')

def write_drawable(xml):
    ns = "{http://schemas.android.com/apk/res/android}"
    root = ET.parse(xml).getroot()
    vw, vh = root.get(ns + "viewportWidth"), root.get(ns + "viewportHeight")
    parts = []
    for p in root.iter("path"):
        fill = "#000000" if p.get(ns + "fillColor") else "none"
        stroke = "#000000" if p.get(ns + "strokeColor") else "none"
        width = p.get(ns + "strokeWidth") or "0"
        parts.append(f'<path d="{p.get(ns + "pathData")}" fill="{fill}" stroke="{stroke}" stroke-width="{width}"/>')
    (OUT / f"{xml.stem}.svg").write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 {vw} {vh}">' + "".join(parts) + "</svg>\n")

if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    icons = line_icons()
    for name, paths in icons.items():
        write_line_icon(name, paths)
    for xml in sorted(DRAWABLES.glob("ic_stroke_*.xml")):
        write_drawable(xml)
    print(f"{len(icons)} line icons and {len(list(DRAWABLES.glob('ic_stroke_*.xml')))} pen icons in {OUT}")
