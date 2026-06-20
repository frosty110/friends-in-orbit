#!/usr/bin/env python3
"""Summarise Kover's XML coverage report by layer.

Kover emits a JaCoCo-compatible XML report. The single whole-app percentage is
dominated by UI / navigation / DI / entry-point code that unit tests don't cover
(plus the data layer, which is verified on-device), so this breaks LINE coverage
down per top-level layer (app/orbit/<layer>) and highlights the `domain` figure
— the business logic that actually warrants confidence.

Usage:
  coverage-summary.py [report.xml]            # console breakdown
  coverage-summary.py --markdown OUT.md       # also write a PR-comment summary
  coverage-summary.py --min-domain 90         # gate: exit 1 if domain < 90% LINE

Default report path is relative to the android/ working directory.
"""
import argparse
import collections
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_PATH = "app/build/reports/kover/reportDebug.xml"
CRITICAL_PREFIXES = ("app/orbit/domain", "app/orbit/data")
DOMAIN_LAYER = "app/orbit/domain"

# Per-layer LINE-coverage targets — deliberately NOT uniform. Business logic is
# held high; UI view-models moderate; navigation/DI/entry-point glue and the
# data layer (DAOs/migrations are verified on-device, not here) are expected low.
# A target of 0 means "no unit-test expectation" (shown as n/a, not pass/fail).
LAYER_TARGETS = {
    "domain": 90,    # business logic — also the enforced CI gate
    "logging": 85,
    "calllog": 70,
    "notify": 65,
    "widget": 60,
    "ui": 55,        # view-models (composables excluded from the denominator)
    "data": 30,      # logic only; DAOs/migrations covered by the instrumented job
    "nav": 10,       # navigation graph — framework glue
    "di": 0,          # Hilt wiring — not unit-tested (⚪ n/a, fine at 0%)
    # The top-level package is the app shell: MainActivity + OrbitApp (Android
    # entry points) dominate it, with AppViewModel the only testable unit. It's a
    # mixed glue directory, so it's ⚪ n/a (not held to a bar) rather than graded
    # on a muddy package number — AppViewModel's tests still count toward the total.
    "app/orbit": 0,
}
DEFAULT_TARGET = 50
YELLOW_MARGIN = 10  # percentage points below target still counts as "close"


def line_counter(elem):
    """Return (missed, covered) for the LINE counter of an element, or (0, 0)."""
    for c in elem.findall("counter"):
        if c.get("type") == "LINE":
            return int(c.get("missed")), int(c.get("covered"))
    return 0, 0


def pct(covered, total):
    return (100.0 * covered / total) if total else 0.0


def collect(root):
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
    return layers, packages


def sum_prefix(layers, prefixes):
    missed = sum(m for layer, (m, c) in layers.items() if layer.startswith(prefixes))
    covered = sum(c for layer, (m, c) in layers.items() if layer.startswith(prefixes))
    return missed, covered


def short(layer):
    return layer.replace("app/orbit/", "") or "(root)"


def status_icon(actual, target):
    if actual + 1e-9 >= target:
        return "🟢"
    if actual + YELLOW_MARGIN + 1e-9 >= target:
        return "🟡"
    return "🔴"


def write_markdown(path, layers, total, domain, critical):
    dom_pct = pct(domain[1], domain[0] + domain[1])
    tot_pct = pct(total[1], total[0] + total[1])
    crit_pct = pct(critical[1], critical[0] + critical[1])
    out = []
    out.append("<!-- orbit-coverage -->")
    out.append("### 📊 Code Coverage — unit tests (LINE)")
    out.append("")
    out.append(f"**Core logic (`domain`): {dom_pct:.1f}%**  ·  **Total project: {tot_pct:.1f}%**")
    out.append("")
    out.append(
        "> The total blends in UI / navigation / DI / entry-point code that unit tests "
        "don't cover, plus the `data` layer that's verified on-device (see the "
        "**instrumented** job). Judge confidence by the per-layer rows, not the total.",
    )
    out.append("")
    out.append("| Layer | LINE coverage | Target |")
    out.append("|---|--:|:--:|")
    for layer in sorted(layers, key=lambda k: pct(layers[k][1], sum(layers[k]) or 1), reverse=True):
        missed, covered = layers[layer]
        p = pct(covered, missed + covered)
        name = short(layer)
        target = LAYER_TARGETS.get(name, DEFAULT_TARGET)
        if target <= 0:
            icon, target_str = "⚪", "n/a"
        else:
            icon, target_str = status_icon(p, target), f"≥ {target}%"
        out.append(f"| `{name}` | {icon} {p:.1f}% ({covered}/{missed + covered}) | {target_str} |")
    out.append("")
    out.append("🟢 meets target · 🟡 within 10 pts · 🔴 below · ⚪ not held to a target. "
               "Targets are per testable unit. Generated code + @Composable UI are excluded "
               "from measurement; the app shell (`app/orbit`) and `di` (Hilt wiring) are ⚪ n/a; "
               "`data` DAOs/migrations are verified on-device (see the **instrumented** job).")
    out.append("")
    out.append(f"_Critical paths (domain + data): {crit_pct:.1f}%. Domain floor (90%) enforced in CI._")
    with open(path, "w") as fh:
        fh.write("\n".join(out) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path", nargs="?", default=DEFAULT_PATH)
    ap.add_argument("--markdown", help="write a PR-comment markdown summary to this path")
    ap.add_argument("--min-domain", type=float, help="exit 1 if domain LINE coverage is below this %")
    args = ap.parse_args()

    if not os.path.exists(args.path):
        print(f"coverage-summary: no report at {args.path} — skipping.")
        return 0

    root = ET.parse(args.path).getroot()
    layers, packages = collect(root)

    total = (sum(m for m, c in layers.values()), sum(c for m, c in layers.values()))
    domain = layers.get(DOMAIN_LAYER, [0, 0])
    critical = sum_prefix(layers, CRITICAL_PREFIXES)

    print("=== Coverage by layer (LINE) ===")
    for layer in sorted(layers):
        missed, covered = layers[layer]
        print(f"  {layer:32s} {pct(covered, missed + covered):6.2f}%  ({covered}/{missed + covered})")
    print()
    print(f">> domain: {pct(domain[1], sum(domain)):.2f}%   "
          f"critical paths (domain+data): {pct(critical[1], critical[0] + critical[1]):.2f}%   "
          f"total: {pct(total[1], total[0] + total[1]):.2f}%")

    print()
    print("=== Per-package (LINE), lowest coverage first ===")
    for name, missed, covered in sorted(packages, key=lambda r: pct(r[2], r[1] + r[2])):
        print(f"  {pct(covered, missed + covered):6.2f}%  ({covered}/{missed + covered})  {name}")

    if args.markdown:
        write_markdown(args.markdown, layers, total, domain, critical)
        print(f"\ncoverage-summary: wrote {args.markdown}")

    if args.min_domain is not None:
        dtotal = sum(domain)
        if dtotal == 0:
            print(f"::error::domain layer '{DOMAIN_LAYER}' not found in report — cannot enforce floor")
            return 1
        dpct = pct(domain[1], dtotal)
        if dpct + 1e-9 < args.min_domain:
            print(f"::error::domain LINE coverage {dpct:.2f}% is below the floor of {args.min_domain:.2f}%")
            return 1
        print(f"\ndomain LINE coverage {dpct:.2f}% >= floor {args.min_domain:.2f}% ✓")

    return 0


if __name__ == "__main__":
    sys.exit(main())
