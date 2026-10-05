#!/usr/bin/env python3
"""Convert the Phosphor SVGs in app/icons-src/phosphor/ to Android
VectorDrawables, and write the Kotlin name -> resource map PhIcon reads.

Why: PhIcon used to load each SVG through Coil at runtime, so icons appeared a
frame or more after the screen (and never appeared in JVM screenshot tests).
VectorDrawables draw synchronously, are crisp at every size, and the Glance
widgets can use the same set.

Phosphor "regular" icons are stroke drawings on a 256x256 canvas with a
16-unit round-capped stroke, so a 24dp icon has a 1.5dp stroke, as the design
system specifies. Supported elements: path, line, polyline, polygon, circle,
rect (the invisible full-canvas rect each file starts with is skipped).

Usage (from android/):  python3 scripts/phosphor_to_vector.py
"""
from __future__ import annotations

import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/icons-src/phosphor"  # sources only; not packaged in the APK
RES = ROOT / "app/src/main/res/drawable"
KT = ROOT / "app/src/main/java/app/orbit/ui/components/PhosphorIcons.kt"

NS = "{http://www.w3.org/2000/svg}"


def num(v: str | None, default: float = 0.0) -> float:
    return float(v) if v is not None else default


def fmt(x: float) -> str:
    s = f"{x:.3f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def points_path(points: str, close: bool) -> str:
    nums = [float(n) for n in re.split(r"[\s,]+", points.strip()) if n]
    pts = list(zip(nums[0::2], nums[1::2]))
    d = f"M{fmt(pts[0][0])},{fmt(pts[0][1])}" + "".join(f"L{fmt(x)},{fmt(y)}" for x, y in pts[1:])
    return d + ("Z" if close else "")


def circle_path(cx: float, cy: float, r: float) -> str:
    return (
        f"M{fmt(cx - r)},{fmt(cy)}"
        f"A{fmt(r)},{fmt(r)} 0 1,0 {fmt(cx + r)},{fmt(cy)}"
        f"A{fmt(r)},{fmt(r)} 0 1,0 {fmt(cx - r)},{fmt(cy)}Z"
    )


def rect_path(x: float, y: float, w: float, h: float, rx: float) -> str:
    if rx <= 0:
        return f"M{fmt(x)},{fmt(y)}H{fmt(x + w)}V{fmt(y + h)}H{fmt(x)}Z"
    rx = min(rx, w / 2, h / 2)
    return (
        f"M{fmt(x + rx)},{fmt(y)}H{fmt(x + w - rx)}"
        f"A{fmt(rx)},{fmt(rx)} 0 0,1 {fmt(x + w)},{fmt(y + rx)}V{fmt(y + h - rx)}"
        f"A{fmt(rx)},{fmt(rx)} 0 0,1 {fmt(x + w - rx)},{fmt(y + h)}H{fmt(x + rx)}"
        f"A{fmt(rx)},{fmt(rx)} 0 0,1 {fmt(x)},{fmt(y + h - rx)}V{fmt(y + rx)}"
        f"A{fmt(rx)},{fmt(rx)} 0 0,1 {fmt(x + rx)},{fmt(y)}Z"
    )


def element_path(el: ET.Element) -> str | None:
    tag = el.tag.replace(NS, "")
    a = el.attrib
    if tag == "path":
        return a["d"]
    if tag == "line":
        return f"M{fmt(num(a.get('x1')))},{fmt(num(a.get('y1')))}L{fmt(num(a.get('x2')))},{fmt(num(a.get('y2')))}"
    if tag == "polyline":
        return points_path(a["points"], close=False)
    if tag == "polygon":
        return points_path(a["points"], close=True)
    if tag == "circle":
        return circle_path(num(a.get("cx")), num(a.get("cy")), num(a.get("r")))
    if tag == "rect":
        w, h = num(a.get("width")), num(a.get("height"))
        x, y = num(a.get("x")), num(a.get("y"))
        # Each Phosphor file starts with an invisible full-canvas rect.
        if w == 256 and h == 256 and a.get("fill") == "none" and "stroke" not in a:
            return None
        return rect_path(x, y, w, h, num(a.get("rx"), num(a.get("ry"))))
    return None


def vector_xml(svg: Path) -> str:
    root = ET.parse(svg).getroot()
    out = []
    for el in root.iter():
        if el is root:
            continue
        d = element_path(el)
        if d is None:
            continue
        a = el.attrib
        filled = a.get("fill", "none") not in ("none",)
        stroked = a.get("stroke") not in (None, "none")
        attrs = [f'android:pathData="{d}"']
        attrs.append('android:fillColor="#FF000000"' if filled else 'android:fillColor="#00000000"')
        if stroked:
            attrs += [
                'android:strokeColor="#FF000000"',
                f'android:strokeWidth="{fmt(num(a.get("stroke-width"), 16))}"',
                f'android:strokeLineCap="{a.get("stroke-linecap", "round")}"',
                f'android:strokeLineJoin="{a.get("stroke-linejoin", "round")}"',
            ]
        out.append("    <path\n        " + "\n        ".join(attrs) + " />")
    return (
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        f"<!-- Generated from app/icons-src/phosphor/{svg.name} by scripts/phosphor_to_vector.py. Do not edit. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n    android:height="24dp"\n'
        '    android:viewportWidth="256"\n    android:viewportHeight="256">\n'
        + "\n".join(out)
        + "\n</vector>\n"
    )


def res_name(icon: str) -> str:
    return "ph_" + icon.replace("-", "_")


def main() -> int:
    svgs = sorted(SRC.glob("*.svg"))
    if not svgs:
        print(f"no SVGs in {SRC}", file=sys.stderr)
        return 1
    RES.mkdir(parents=True, exist_ok=True)
    for svg in svgs:
        (RES / f"{res_name(svg.stem)}.xml").write_text(vector_xml(svg))
    entries = "\n".join(f'    "{s.stem}" to R.drawable.{res_name(s.stem)},' for s in svgs)
    KT.write_text(
        "package app.orbit.ui.components\n\n"
        "import app.orbit.R\n\n"
        "// Generated by android/scripts/phosphor_to_vector.py. Do not edit by hand:\n"
        "// add the SVG to app/icons-src/phosphor/ and re-run the script.\n"
        "//\n"
        "// Phosphor icon name -> VectorDrawable resource. PhIcon reads this map so\n"
        "// icons draw synchronously instead of decoding an SVG through Coil.\n"
        "internal val PhosphorIcons: Map<String, Int> = mapOf(\n"
        f"{entries}\n"
        ")\n"
    )
    print(f"wrote {len(svgs)} vectors and {KT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
