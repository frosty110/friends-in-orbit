# Orbit — Product Vision

> One file per page (or page-section). Each one says what that piece **is for**, shows what it **is today**, and lays out where it **could go**. This is the "what good looks like" layer — the bridge between `design/` (how it looks) and `features/` (what's specified to build).

This directory is a thinking tool, not a backlog. It exists so that anyone — you, a collaborator, or an AI agent — can open a single screen's file and understand its intent, its current state, and the moves that would make it better, without reverse-engineering all of that from the code.

---

## Why this exists

Orbit's core value is one sentence: **remove the "who should I call?" friction.** The Card View hands you one name at a time with enough context to say yes. Every screen either serves that loop or supports it.

Screenshots and code tell you what the app *does*. They don't tell you what each surface is *trying to achieve*, or where it falls short of that aim. This directory captures that — the product intent and the gap between today and the long-term vision — so improvement is deliberate, not accidental.

The through-line across most suggestions here: **today the app gives you logistics (last called, average length, call count); the vision gives you context (what you last talked about, why now, what to say).** Logistics tell you *whether* to call. Context is what makes you actually pick up the phone. That shift — stats → context — is the spine of the long-term product.

---

## How it's organized

```
vision/
├── README.md            ← you are here: how to use this, the index, the guardrails
├── _assets/             ← curated screenshots referenced by the section files
├── 00-home.md
├── 01-card-view.md      ← the core loop. start here.
├── 02-contact-detail.md
├── 03-browse-list.md
├── 04-search.md
├── 05-call-log.md
├── 06-lists-manager.md
├── 07-list-config.md
├── 08-create-list.md
├── 09-pickers.md
├── 10-settings.md
├── 11-onboarding.md
├── 12-widgets.md
└── 99-cross-cutting.md  ← system-wide moves that aren't tied to one screen
```

### Anatomy of a section file

Every section file follows the same three-part shape:

1. **Intent** — what this surface is *for*. One short paragraph. The purpose it serves in the product, independent of how it's currently built.
2. **Today** — what it actually is right now, with a screenshot. Honest and concrete.
3. **Where it's going** — the suggestions. Each is a discrete, ID'd move with a status and a short rationale tying it back to the mission and the voice.

### Reading a suggestion

Each suggestion looks like:

> ### `CARD-1` · Surface the last note on the card face · **Now**
> *The improvement, why it helps the user, and how it stays on-brand.*

- **ID** (`CARD-1`) — stable handle so a move can be referenced in chat, graduated into `features/`, or turned into a `.planning/` phase without losing its lineage.
- **Status** — a rough sequencing signal, not a commitment:
  - **Now** — high impact, small lift. The data or surface already exists; this is mostly assembly.
  - **Next** — clear value, medium lift. Worth a proper design+build pass.
  - **Later** — long-term or exploratory. Directionally right; needs more thought before it's real.

Status is about *sequencing and confidence*, not priority ranking. A "Later" can be more important than a "Now" — it's just less ready.

---

## Index

| # | Surface | Intent in one line | Headline move | Status |
|---|---------|--------------------|---------------|--------|
| 00 | [Home](./00-home.md) | The front door — always hand you someone to call | "Next up" on every card; retire the "due/caught up" framing | Now |
| 01 | [Card View](./01-card-view.md) | Hand you one name with enough context to say yes | Put the last note/topic on the card face | Now |
| 02 | [Contact Detail](./02-contact-detail.md) | The full picture of one person, and where you act + remember | Show "comes up again in ~X" | Now |
| 03 | [Browse List](./03-browse-list.md) | See a whole list as a queue; find and bulk-manage | Make the numbered queue legible | Next |
| 04 | [Search](./04-search.md) | Jump straight to a person from anywhere | Useful empty state (people not on any list) | Next |
| 05 | [Call Log](./05-call-log.md) | An honest, calm record of real calls | Inline "add a note" on a call | Next |
| 06 | [Lists Manager](./06-lists-manager.md) | Shape your orbits; order, archive, prioritise | Remove redundant create + fix menu theming | Now |
| 07 | [List Config](./07-list-config.md) | Define a list's rhythm and membership in plain terms | One plain-language rhythm summary line | Now |
| 08 | [Create List](./08-create-list.md) | Start a new orbit from an intention, not a blank form | Show each template's resulting rhythm | Next |
| 09 | [Pickers](./09-pickers.md) | Add the right people fast; file a person into lists | Smart "people you call but haven't filed" | Next |
| 10 | [Settings](./10-settings.md) | Trust and control — permissions, sync, privacy | Say the privacy promise out loud | Next |
| 11 | [Onboarding](./11-onboarding.md) | Earn trust, get permissions, leave with one real list | Carry "context" into the preview | Next |
| 12 | [Widgets](./12-widgets.md) | The loop without opening the app | Act (call / later / sooner) from the widget | Next |
| 99 | [Cross-cutting](./99-cross-cutting.md) | System-wide moves | Warm-theme dropdowns; nickname overrides | Now |

---

## Guardrails — every suggestion must stay on-brand

This directory proposes *what* and *why*. The *how* is bound by the existing system. Before any of these becomes code, it must pass the same bars everything else does:

- **Design tokens only.** Colours, type, spacing, radius, motion come from `android/app/src/main/java/app/orbit/ui/theme/` (warm cream `#FAF6F0`, single terracotta accent `#C8654A`, Inter, 4dp grid, 250ms base motion, ≤5% overshoot). Source of truth: `design/colors_and_type.css`. Never hardcode.
- **The voice.** Sentence case, no exclamation marks, no gamification (no streaks/achievements/XP), no shame framing ("you haven't called X in N days" is forbidden), no emoji in product copy. Pattern language over performance language. See `README.md` → "Content fundamentals" and `features/_foundations/`.
- **The mission filter.** If a move doesn't serve "hand you one name with enough context to say yes" — or clearly support it — it's a distraction, no matter how nice.
- **Accessibility floor.** 16sp minimum body, 48dp minimum touch targets, font-scale safety.

A suggestion that can't be built within these isn't ready — rework it until it can.

---

## From vision → execution

These are not tasks. When a move is ready to build, it graduates:

1. **Vision** (here) — intent + rationale + rough status.
2. **Spec** — promote to a `features/<feature>/` PRD+TECH entry (or extend an existing one) with acceptance criteria.
3. **Plan** — a `.planning/` phase via the GSD flow, then build.

Keep the suggestion's ID through that journey so the lineage stays traceable (`CARD-1` in vision → `CARD-1` referenced in the feature spec → the commit that ships it).

---

## Provenance & maintenance

- **Captured:** 2026-06-19 → 06-21, on the `orbit` emulator (Android 14), build `app.orbit.debug`. After the device was reseeded with synthetic personas (see below), the core screens were re-captured clean, so most images show the same cast (Kai Nakamura, Sarah Chen, …).
- **All screens are now illustrated** — every section has a live screenshot. Two carry a noted caveat: the **Contact Picker** shot predates the reseed (kept because it shows the A–Z rail on a full address book, which the sparse seed can't), and the **Home post-call banner** retains a redacted name (the call→return state is awkward to reproduce on demand). Both are PII-free.
- **No real data.** Every screenshot is synthetic by construction: the device is seeded with 12 fictional personas and `+1…555…` numbers via `android/scripts/seed-avd.py` (pristine wipe). A couple of pre-reseed shots were additionally pixel-edited to scrub names/numbers. Nothing here surfaces a real contact.
- **This is a living document.** When a screen changes materially, refresh its screenshot and its **Today** section, and move any shipped suggestion out of "Where it's going" (note the commit). Stale "today" is worse than no doc.
- **Relationship to other dirs:**
  - `.review/` — the dated screenshot timeline + design-compliance scoring. Operational, partly gitignored. `vision/_assets/` is a *curated, durable* subset, not a mirror.
  - `design/` — how the app looks (tokens, system). Vision defers to it.
  - `features/INDEX.md` — what's specified to build. Vision feeds it.

---

## A note on the location

This lives at the repo root as a **visible** `vision/`, not `.vision/` or under `.planning/`, on purpose: it's a durable, human-read product artifact that belongs next to `design/` and `features/`, not hidden tooling like `.claude/` / `.review/` (working state) or ephemeral GSD phase output in `.planning/`. If you'd rather it were hidden, it's a one-line `git mv vision .vision` — nothing here depends on the path.
