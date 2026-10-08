# ADR 0010 — The keep-in-touch interval floor is 1 day; the slider scale is linear

**Status:** accepted; its control (the slider) superseded by [ADR 0011](0011-number-wheel-for-day-and-count-settings.md), 2026-10-07. The range, the default and the floor rule stand.
**Date:** 2026-08-15
**Deciders:** the maintainer
**Supersedes:** none — reverses commit `1c8a0d3` ("fix(lists): floor keep-in-touch
interval slider at 2 days"), which was a code change with no decision record
**Refines:** `features/orbit-lists/`, `features/page-views/list-config.md`, and the
`RuleParams.KeepInTouch` interval contract

## Context

The same screenshot has now been read as a bug twice, in opposite directions.

The List Configuration interval slider showed **"Aim for every — 2 days"** with the
thumb pinned to the far left, directly above a tick labelled **`1d`**. The number and
the tick appeared to contradict each other.

- **July reading (`1c8a0d3`):** the count is right, the tick is wrong. A keep-in-touch
  cadence of every single day "isn't sensible," and the seeded default was already 48h,
  so the slider minimum, the ticks, and the end-labels were raised to 2 days.
- **August reading (the maintainer):** *"it should be 1 to 60 days. starting at 2
  doesn't make sense to me."*

**Neither reading was a rendering bug.** On a linear 1–60 day scale, the 48h default
sits at `(2 − 1) / (60 − 1) ≈ 1.7%` of the track — visually flush against the `1d`
tick. The count and the tick disagree *to the eye* while both are literally correct.
The July commit removed the symptom by deleting the left end of the range; it did not
touch the cause.

Two facts undercut the "daily isn't sensible" premise that motivated the floor:

1. **The product already ships a 1-day rhythm.** `RuleParams.Energize` defaults to
   `cooldownMinHours = 24`. Keep-in-touch was the only template whose *reachable
   minimum* sat above a cadence the app offers elsewhere.
2. **Nothing below the UI ever enforced 2 days.** `RuleParams.KeepInTouch
   .withIntervalHours` floors at 1 *hour*; the floor lived entirely in two composables
   (`ListConfigBody.IntervalSliderLocal` and `RuleOverrideSection.IntervalDaysSlider`).

The floor also introduced a quiet display lie. Any list or per-contact override stored
at 24h before Jul 3 kept running at 24h in the engine, but the slider rendered it as
"2 days" (`initialDays` was coerced up for display only) — and re-committed it to 48h
if the user so much as dragged the control.

## Decision

**The reachable keep-in-touch interval is 1–60 days. The seeded default stays 48h.**

1. `valueRange = 1f..60f`, commit floors at `coerceAtLeast(1)`, `INTERVAL_MIN_DAY = 1`,
   and the leading tick reads `1d` — in both the list-config slider and the per-contact
   override mirror, which must stay in lockstep.
2. The count text is singular/plural, so the minimum reads "1 day", never "1 days".
3. **A UI floor set above a template's own default is a product decision, not a display
   fix.** The floor is not to be raised again without superseding this ADR.
4. **The interval scale is linear**, and is to be documented as linear. The vision doc
   described a "non-linear 1d→2m scale"; that was never true of the implementation, and
   the mismatch is part of why the compressed low end kept reading as a bug.

## Consequences

- **The visual that prompted all of this comes back.** A list left at the 48h default
  again renders the thumb at ~1.7% under the `1d` tick while the count reads "2 days".
  That is now a known, accepted rendering of a correct state. If it is not acceptable,
  the fix is the **scale** — a non-linear/log mapping that gives 1d–7d real room — not
  the floor. Left open deliberately; see Related.
- **Sub-day re-surfacing is reachable.** The engine's reductions are percentages of the
  interval, so they scale down with it: a short *incoming* call cuts 25% + 50%, leaving
  `24h × 0.25 = 6h`. This is not new in kind — the 2-day floor allowed 12h, and Energize
  already allows 6h from its own 24h default. It stays deck-only: notifications remain
  pull, never push (ADR 0009), so a shorter interval cannot produce extra pings.
- `KeepInTouchEngine` clamps the escalation cap to at least the base cooldown, so a
  1-day interval can never be undercut by a stale 336h `cooldownMaxHours`.
- Rows stored at 24h from before Jul 3 now display honestly as "1 day" instead of being
  shown — and silently rewritten — as 2 days.

## Non-consequences

- **No migration.** The range only widens; every previously storable value stays valid.
- **Templates are untouched.** Energize (24h) and Late night (72h) keep their defaults,
  and both still carry no user-facing tunables.
- **Nudge scheduling is untouched.** Interval governs who surfaces in the deck; nudges
  fire on their own user-set schedule.

## Related

- Commit `1c8a0d3` — the 2-day floor this reverses, and its stated rationale.
- PR #10 — the revert.
- ADR 0009 — notifications are pull, never push (why a shorter interval adds no pings).
- `RuleParams.KeepInTouch.withIntervalHours` — why both cooldown bounds move together.
- `IntervalScaleLabelsTest` — the F-4 regression guard that pins tick placement to the
  same linear math the thumb uses.
