#!/usr/bin/env python3
"""Summarise Kover's XML coverage report by layer and package.

Kover emits a JaCoCo-compatible XML report. The single whole-app percentage in
the PR comment is dominated by the large Compose UI surface, so this script
breaks LINE coverage down per top-level layer (app/orbit/<layer>) and prints a
combined domain+data "critical paths" figure — the number unit tests are
actually responsible for moving.

Usage: coverage-summary.py [path-to-report.xml]
Default path is relative to the android/ working directory.
"""
import collections
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_PATH = "app/build/reports/kover/reportDebug.xml"
CRITICAL_PREFIXES = ("app/orbit/domain", "app/orbit/data")


def line_counter(elem):
    """Return (missed, covered) for the LINE counter of an element, or (0, 0)."""
    for c in elem.findall("counter"):
        if c.get("type") == "LINE":
            return int(c.get("missed")), int(c.get("covered"))
    return 0, 0


def pct(covered, total):
    return (100.0 * covered / total) if total else 0.0


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PATH
    if not os.path.exists(path):
        print(f"coverage-summary: no Kover report at {path} — skipping.")
        return 0

    root = ET.parse(path).getroot()

    layers = collections.defaultdict(lambda: [0, 0])  # layer -> [missed, covered]
    packages = []
    for pkg in root.findall("package"):
        name = pkg.get("name", "?")
        missed, covered = line_counter(pkg)
        packages.append((name, missed, covered))
        parts = name.split("/")
        layer = "/".join(parts[:3]) if len(parts) >= 3 else name
        layers[layer][0] += missed
        layers[layer][1] += covered

    print("=== Coverage by layer (LINE) ===")
    for layer in sorted(layers):
        missed, covered = layers[layer]
        total = missed + covered
        print(f"  {layer:32s} {pct(covered, total):6.2f}%  ({covered}/{total})")

    crit_missed = sum(m for layer, (m, c) in layers.items() if layer.startswith(CRITICAL_PREFIXES))
    crit_covered = sum(c for layer, (m, c) in layers.items() if layer.startswith(CRITICAL_PREFIXES))
    crit_total = crit_missed + crit_covered
    print()
    print(f">> CRITICAL PATHS (domain + data): {pct(crit_covered, crit_total):.2f}%  ({crit_covered}/{crit_total})")

    print()
    print("=== Per-package (LINE), lowest coverage first ===")
    for name, missed, covered in sorted(packages, key=lambda r: pct(r[2], r[1] + r[2])):
        total = missed + covered
        print(f"  {pct(covered, total):6.2f}%  ({covered}/{total})  {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
