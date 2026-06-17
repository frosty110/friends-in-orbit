# Features — index

**Status:** active
**Last reviewed:** 2026-06-09
**Canonical for:** feature-level product and technical specification

---

## Foundations

Not features — shared across all work.

- [`_foundations/mission.md`](_foundations/mission.md) — why this app exists, target user, principles
- [`_foundations/voice.md`](_foundations/voice.md) — content rules, "never say" list, tone
- [`_foundations/stack.md`](_foundations/stack.md) — technical stack summary (defers to `android/gradle/libs.versions.toml`)
- [`_foundations/ADRs/`](_foundations/ADRs/) — architecture decision records (0001-0004 currently accepted)

---

## Cross-feature views

- [`PAGE_VIEWS.md`](PAGE_VIEWS.md) — index of per-screen page views (one file each under [`page-views/`](page-views/)) capturing the user's feature expectations (what each page is expected to let you see/do). Screen-oriented companion to the feature-oriented map below.

## Feature map

**Status legend.** `stub` (described, not started) · `scaffolded` (UI shell exists, no real integration) · `in-progress` (actively being built) · `shipped` (complete and in production) · `deferred` (explicitly deferred to post-v1).

| Feature | Status | One line |
|---|---|---|
| [home](home/README.md) | in-progress | Mood picker entry; list tiles with due counts, "surprise me", long-press quick actions |
| [card-view](card-view/README.md) | in-progress | One contact at a time; swipe left/right, tap to call |
| [browse](browse/README.md) | in-progress | Full list browse, search, long-press quick actions |
| [contact-detail](contact-detail/README.md) | in-progress | Profile, stats, history, notes, per-contact overrides |
| [call-history](call-history/README.md) | in-progress | In-app chronological log of calls to tracked contacts |
| [orbit-lists](orbit-lists/README.md) | in-progress | Create, configure, archive lists; cross-list state propagation |
| [rule-engine](rule-engine/README.md) | shipped | Preset templates, tunable params, per-list and per-contact rules |
| [call-detection](call-detection/README.md) | in-progress | CALL_LOG read, 90-day import, manual sync, incoming/outgoing |
| [contacts-ingestion](contacts-ingestion/README.md) | in-progress | ContactsContract read, delta-sync, multi-number matching, orphan handling |
| [onboarding](onboarding/README.md) | in-progress | First-run flow: permissions, call-log sync gate, suggested first list |
| [notifications](notifications/README.md) | stub | Daily digest, time-of-day list prompts, incoming follow-up |
| [widgets](widgets/README.md) | stub | 2x2 + 4x2 home screen widgets |
| [privacy-and-lock](privacy-and-lock/README.md) | in-progress | Quick-hide on focus loss, encrypted-at-rest, encrypted export/import. Biometric lock + minimal mode removed 2026-04-28. |
| [settings](settings/README.md) | in-progress | App-wide configuration page |

14 features. Every entry in this table points to its canonical README. When a feature is added, renamed, or removed, this table updates in the same commit.

---

## Feature README schema

Every feature `README.md` follows this schema (enforced for agent retrieval predictability — principle II):

```
# <feature>

**Status:** stub | scaffolded | in-progress | shipped | deferred
**Last reviewed:** YYYY-MM-DD
**Ground truth:**
- Code: <path or "not yet implemented">
- Tests: <path or "none yet">

---

## Product

### Why it exists
### User story
### Behavior
### Acceptance criteria
### Not in scope
### Open product questions

---

## Technical

### Architecture
### Data model
### Permissions / integrations
### Known gotchas
### Not in scope (technical)
### Open technical questions
```

**Ground truth** = verifiable external references (code, tests, build config). What you cross-check before acting.
**The feature README itself is canonical** for its feature — no header field needed to assert that; it is derivable from the file's location under `features/`.
**Historical provenance** (where content came from) is not tracked in-file; git history is the archaeological record.

Section order is load-bearing. Grepping `## Acceptance criteria` or `## Open product questions` across the tree must return consistent, predictable results.

---

## Layering — vertical and horizontal

**Vertical (per-feature):** each `features/<feature>/README.md`. Owns its own spec end-to-end, product through technical. Grows or splits as the feature matures; never absorbs content that belongs to another feature.

**Horizontal (cross-cutting):** `features/_foundations/`. Owns specifications that span every feature — mission, voice, stack, ADRs. Feature READMEs point into foundations, never copy from them. When a horizontal concern mutates, it mutates in one place.

**Operating memory (orthogonal):** the project's internal implementation notes. Not specs — they capture *how* horizontal concerns play out during implementation (gotchas, session learnings). Link into specs, never duplicate them.

| Layer | What lives here | Canonical example |
|---|---|---|
| Vertical | Feature-specific product + tech spec | `features/card-view/README.md` |
| Horizontal (spec) | Cross-feature contracts | `features/_foundations/voice.md` |
| Horizontal (decisions) | Architecture decision records | `features/_foundations/ADRs/0002-sqlcipher-for-room.md` |

---

## How to use this tree

- **Looking for what a feature does?** Read its `README.md` Product section.
- **Looking for how it's built?** Read its `README.md` Technical section.
- **Feature getting long?** Split into sub-files (`acceptance.md`, `data-model.md`, `edge-cases.md`) only when the README exceeds ~300 lines. Don't pre-split.
- **Adding a feature?** Copy an existing feature folder, rename, update this INDEX, fill the schema header (Status, Last reviewed, Ground truth).
- **Making an architectural decision?** Add an ADR in `_foundations/ADRs/` with the next sequential number; update the relevant feature README's Ground truth block and any affected ADR cross-references atomically.

---

## Relationship to other docs

The original flat PRD (`docs/prd.md`) was fully decomposed into this tree on 2026-04-22 and removed from the repo. Origin recoverable via `git log --all -- docs/prd.md` + `git show <sha>:docs/prd.md`.
