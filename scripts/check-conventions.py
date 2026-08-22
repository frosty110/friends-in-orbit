#!/usr/bin/env python3
"""Enforce the documentation and code conventions in features/_foundations/rules.md.

Four checks. Three are hard gates that pass today and must keep passing; the
fourth is a ratchet over pre-existing debt, so the build goes red only if the
debt grows.

  A. rules.md citations resolve   (hard)  — `rules.md §Design 3` names a real rule
  B. PII-free layers              (hard)  — no logging in ui/domain/data/nav
  C. Markdown links resolve       (hard)  — no dangling relative doc links
  D. Undocumented requirement IDs (ratchet) — count may fall, never rise

Usage:
    python3 scripts/check-conventions.py            # check, exit 1 on failure
    python3 scripts/check-conventions.py --update-baseline
    python3 scripts/check-conventions.py --quiet    # only print failures

No third-party dependencies, matching scripts/coverage-summary.py.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULES_DOC = ROOT / "features" / "_foundations" / "rules.md"
BASELINE = Path(__file__).resolve().parent / "conventions-baseline.json"

KOTLIN_ROOTS = [ROOT / "android" / "app" / "src"]
DOC_ROOTS = [ROOT / "features", ROOT / "docs", ROOT / "design", ROOT / "vision"]
DOC_FILES_EXTRA = [ROOT / "README.md", ROOT / "DESIGN.md", ROOT / "CLAUDE.md"]

# Layers that hold contact data. rules.md Code 4: no logging calls at all here —
# PII must never reach a log line, and call-site discipline is the first of the
# three layers that guarantee it. Infrastructure (notify/, calllog/, widget/)
# logs structured non-PII events and is deliberately not listed.
PII_FREE_PACKAGES = ["ui", "domain", "data", "nav"]
LOGGING_CALL = re.compile(r"\bTimber\s*\.|\bandroid\.util\.Log\b|(?<![\w.])Log\.[dviwe]\s*\(|(?<![\w.])println\s*\(")

# `rules.md §Design 3`, `rules.md Design 3`, `rules.md design rule 5`,
# `rules.md Code 4` — and the bare `§Design 5` form used once in source.
CITE_NEAR_RULES = re.compile(
    r"rules\.md[\s,;:*/]*(?:§\s*)?(Design|Code)\s+(?:rule\s+)?(\d+)", re.IGNORECASE
)
CITE_SECTION = re.compile(r"§\s*(Design|Code)\s+(\d+)", re.IGNORECASE)

RULE_DEF = re.compile(r"^\*\*(Design|Code)\s+(\d+)\s+—", re.MULTILINE)
ARCH_DEF = re.compile(r"^\*\*(ARCH-\d+)\s+—", re.MULTILINE)

REQUIREMENT_ID = re.compile(r"\b([A-Z][A-Z0-9]{2,9})-(\d{2})\b")

# Relative markdown links, minus anchors/external schemes.
MD_LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


def kotlin_files() -> list[Path]:
    out: list[Path] = []
    for root in KOTLIN_ROOTS:
        if root.exists():
            out.extend(sorted(root.rglob("*.kt")))
    return out


def doc_files() -> list[Path]:
    out: list[Path] = []
    for root in DOC_ROOTS:
        if root.exists():
            out.extend(sorted(root.rglob("*.md")))
    out.extend(p for p in DOC_FILES_EXTRA if p.exists())
    return sorted(set(out))


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


# ── A. rules.md citations resolve ──────────────────────────────────────────────

def check_citations() -> list[str]:
    if not RULES_DOC.exists():
        return [f"{rel(RULES_DOC)} is missing — every `rules.md` citation dangles."]

    text = RULES_DOC.read_text(encoding="utf-8")
    defined = {(m.group(1).lower(), int(m.group(2))) for m in RULE_DEF.finditer(text)}
    if not defined:
        return [f"{rel(RULES_DOC)} defines no rules — expected `**Design N — …**` headings."]

    failures = []
    for path in kotlin_files():
        body = path.read_text(encoding="utf-8", errors="replace")
        cited = set()
        for pattern in (CITE_NEAR_RULES, CITE_SECTION):
            for m in pattern.finditer(body):
                cited.add((m.group(1).lower(), int(m.group(2))))
        for section, number in sorted(cited):
            if (section, number) not in defined:
                failures.append(
                    f"{rel(path)}: cites {section.capitalize()} {number}, "
                    f"which {rel(RULES_DOC)} does not define"
                )
    return failures


# ── B. PII-free layers ─────────────────────────────────────────────────────────

def check_pii_layers() -> list[str]:
    failures = []
    base = ROOT / "android" / "app" / "src" / "main" / "java" / "app" / "orbit"
    for package in PII_FREE_PACKAGES:
        pkg = base / package
        if not pkg.exists():
            continue
        for path in sorted(pkg.rglob("*.kt")):
            for lineno, line in enumerate(
                path.read_text(encoding="utf-8", errors="replace").splitlines(), 1
            ):
                stripped = line.strip()
                # Comments discuss the rule constantly; only real calls count.
                if stripped.startswith(("*", "//", "/*")):
                    continue
                if LOGGING_CALL.search(line):
                    failures.append(
                        f"{rel(path)}:{lineno}: logging call in a PII-bearing layer "
                        f"(rules.md Code 4) — {stripped[:70]}"
                    )
    return failures


# ── C. Markdown links resolve ──────────────────────────────────────────────────

def check_doc_links() -> list[str]:
    failures = []
    for path in doc_files():
        body = path.read_text(encoding="utf-8", errors="replace")
        for m in MD_LINK.finditer(body):
            target = m.group(1).strip()
            if not target or target.startswith(("http://", "https://", "#", "mailto:", "<")):
                continue
            target = target.split()[0]          # drop ` "title"`
            target = target.split("#", 1)[0]    # drop anchor
            if not target:
                continue
            resolved = (path.parent / target).resolve()
            if not resolved.exists():
                failures.append(f"{rel(path)}: link to `{target}` does not resolve")
    return failures


# ── D. Undocumented requirement IDs (ratchet) ──────────────────────────────────

def collect_requirement_ids() -> tuple[set[str], set[str]]:
    """Return (cited in Kotlin, defined in docs)."""
    cited: set[str] = set()
    for path in kotlin_files():
        body = path.read_text(encoding="utf-8", errors="replace")
        cited.update(f"{m.group(1)}-{m.group(2)}" for m in REQUIREMENT_ID.finditer(body))

    documented: set[str] = set()
    for path in doc_files():
        body = path.read_text(encoding="utf-8", errors="replace")
        documented.update(f"{m.group(1)}-{m.group(2)}" for m in REQUIREMENT_ID.finditer(body))

    return cited, documented


def check_id_ratchet(update: bool, quiet: bool) -> list[str]:
    cited, documented = collect_requirement_ids()
    undocumented = sorted(cited - documented)
    count = len(undocumented)

    if update:
        BASELINE.write_text(
            json.dumps(
                {
                    "_comment": (
                        "Ratchet baseline for scripts/check-conventions.py. "
                        "Requirement IDs cited in Kotlin but defined in no spec. "
                        "This number may fall, never rise — see "
                        "features/_foundations/rules.md 'Known documentation debt'."
                    ),
                    "undocumented_requirement_ids": count,
                    "prefixes": sorted({i.split("-")[0] for i in undocumented}),
                    "ids": undocumented,
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        print(f"baseline updated: {count} undocumented requirement IDs")
        return []

    if not BASELINE.exists():
        return [f"{rel(BASELINE)} is missing — run with --update-baseline once."]

    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    allowed = baseline["undocumented_requirement_ids"]
    if count > allowed:
        # Name the IDs *this change* introduced, not the alphabetically-first
        # ones — the author needs to know which of theirs to document.
        added = sorted(set(undocumented) - set(baseline.get("ids", [])))
        detail = ", ".join(added[:10]) if added else ", ".join(undocumented[:10])
        return [
            f"undocumented requirement IDs rose to {count} (baseline {allowed}). "
            f"Define these in the owning feature spec — see rules.md "
            f"'Citing conventions': {detail}"
        ]

    if not quiet:
        trend = "unchanged" if count == allowed else f"down from {allowed} — update the baseline"
        print(f"  requirement-ID debt: {count} undocumented ({trend})")
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    if args.update_baseline:
        check_id_ratchet(update=True, quiet=args.quiet)
        return 0

    checks = [
        ("rules.md citations resolve", check_citations()),
        ("PII-free layers (Code 4)", check_pii_layers()),
        ("markdown links resolve", check_doc_links()),
    ]

    failed = False
    for name, failures in checks:
        if failures:
            failed = True
            print(f"FAIL  {name} — {len(failures)} problem(s):")
            for f in failures[:40]:
                print(f"        {f}")
            if len(failures) > 40:
                print(f"        … and {len(failures) - 40} more")
        elif not args.quiet:
            print(f"ok    {name}")

    ratchet = check_id_ratchet(update=False, quiet=args.quiet)
    if ratchet:
        failed = True
        print("FAIL  requirement-ID ratchet:")
        for f in ratchet:
            print(f"        {f}")
    elif not args.quiet:
        print("ok    requirement-ID ratchet")

    if failed:
        print("\nSee features/_foundations/rules.md and development-cycle.md.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
