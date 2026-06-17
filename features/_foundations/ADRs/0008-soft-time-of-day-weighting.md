# ADR 0008 — Rule engine: soft time-of-day weighting, no hard active-hours gate

**Status:** accepted
**Date:** 2026-06-11
**Accepted:** 2026-06-12 — implemented in quick task `260612-9si`. The active-hours
exclusion was removed from `SurfaceNextUseCase` and `SurfaceQueueUseCase`; the
time-of-day weight is applied in `WidgetSurfaceUseCase` (the only cross-list
ranker). See the Implementation amendment below.
**Deciders:** the maintainer
**Supersedes:** none
**Refines:** `features/_foundations/` rule-engine model; the active-hours filter in
`SurfaceNextUseCase`, `SurfaceQueueUseCase`, and `WidgetSurfaceUseCase`

## Context

Orbit's core loop hands the user one name at a time — "the best person to call right
now." The surfacing model behind it is: each engine computes a `nextDue: Instant` per
contact (cooldown math over last-call recency, skip penalties, short-call / incoming
reductions), and `SurfaceNextUseCase` sorts the survivors by `nextDue ASC` and takes the
head. `SurfaceQueueUseCase` produces the same ordering for the Browse list view; the
widget reads it too.

Two of the inputs are **hard exclusion gates**, not sort factors:

| Gate | Where | Effect today |
|---|---|---|
| Active hours | `SurfaceNextUseCase.kt:115`, `SurfaceQueueUseCase.kt:93`, widget path | If `now` is outside the list's `activeHoursStart..activeHoursEnd`, the **entire list** is excluded — `NothingEligible` / `emptyList()`. |
| Pause | `:120` / `:105` (per contact) | A contact with `pausedUntil > now` is removed from the candidate set. |

The active-hours gate produces a failure the founder flagged directly:

> "I don't understand the error — 'No one is up next on this list.' There should always
> be someone next because it's a queue."

A "Late night" list (window 21:00–02:00) checked at noon excludes **every** member and
dead-ends on an empty-queue screen. This contradicts the user's mental model — a queue
should always have a head — and it contradicts the product's own tide-marker decision
(2026-05-08), which already stopped dropping *future-due* contacts so the queue reads as
an infinite rotation rather than a "you're done" completion mechanic. Active-hours is the
last remaining place where a perfectly-populated list can surface nobody.

The deeper objection is about the **signal quality** of time-of-day itself:

> "Active times is very subjective. Just because that person hasn't picked up at that time
> doesn't mean they won't. We have to be careful with the formula."

Time-of-day is a soft preference ("when I *like* making these calls"), not a fact about
when a person answers. Treating it as a binary gate over-trusts a weak signal. Worse would
be hardening it into a *learned* per-contact answer-rate — Orbit does not track call
outcomes and must not infer "never call them then" from a single miss.

## Options considered

### Option 1 — Status quo: active hours as a hard gate
Outside the window → list surfaces nobody. Rejected: produces the empty-queue dead-end,
over-trusts a subjective signal, and fights the tide-marker's infinite-rotation model.

### Option 2 — Full composite score
Replace `nextDue`-ascending with a real multi-term score blending overdue-ness, time fit,
novelty, call-history signals, etc. Rejected **for now**: many knobs, hard to reason about
per-engine in unit tests (the rule-engine shape values isolated truth tables),
and precisely the kind of over-confident formula the founder warned against. Kept on the
table as a future graduation if a single nudge proves insufficient.

### Option 3 — Soft time-of-day weight on the existing sort key (chosen)
Keep all engine cooldown math and the single-`Instant` sort key unchanged. Remove the
active-hours *gate*; replace it with a **bounded, decaying penalty** added to a contact's
*effective* due-time. Nobody leaves the queue; a wrong time-of-day sinks a contact a
bounded amount toward the tail. Minimal blast radius, legible, directly removes the
dead-end.

## Decision

Adopt **Option 3**. Three sub-decisions are locked:

### Decision 1 — Active hours becomes a weight, never a gate

Delete the `if (!inActiveHours(...)) return empty/NothingEligible` branch from all three
surfacing paths. Replace with an additive penalty on the sort key:

```
effectiveSortKey(contact) = nextDue(contact) + timeOfDayPenalty(now, list.activeWindow)

timeOfDayPenalty(now, window):
    if window is unset            -> 0          // no preference → no effect (unchanged)
    if now is inside window       -> 0          // preferred time → no penalty, sorts by due
    else:
        minutesUntilOpen = minutes from now until the window next opens   // handles midnight wrap
        return min(P_MAX, minutesUntilOpen)      // bounded; decays to 0 as the window approaches
```

Sort ascending by `effectiveSortKey`, then the existing tiebreaks (`lastCalledAt ASC`,
`contact.id ASC`). The function is pure over `now` + the user-set window — no randomness,
no per-contact learned state — so determinism is preserved.

### Decision 2 — Overdue-ness stays dominant; the penalty is gentle and bounded

`P_MAX` is a single tunable cap, **default 6 hours**. Because real cadences are measured in
days-to-weeks, a ≤6h push only reorders contacts whose `nextDue` values are already within
~6h of each other. A genuinely overdue contact (e.g. `nextDue` two days in the past) still
sorts ahead of a not-yet-due, perfectly-timed one:

```
A: overdue 2d, outside window  -> (now − 48h) + 6h = now − 42h   ← still first
B: due now,    inside window    ->  now + 0       = now
```

`P_MAX` is the one knob. Smaller → the window barely matters; larger → it asymptotically
re-approaches the old hard gate. 6h is the gentle default; it is a tuning value, not a
contract, and may be revised with on-device feel.

### Decision 3 — Pause stays a hard exclusion; per-list windows are kept (as the soft signal)

- **Pause is left untouched as a hard "do not surface until `pausedUntil`."** Pause is a
  *deliberate user action* ("not this person, until then") — categorically different from a
  soft inference about clock time. Softening it would let an explicitly-paused contact
  resurface, violating user intent. Only active-hours softens.
- **Per-list `activeHoursStart/End` is retained**, now interpreted as "the time I prefer to
  make these calls," feeding `timeOfDayPenalty` only. The concept is not removed; it is
  demoted from gate to nudge.

## Consequences

- The empty-queue dead-end caused by time-of-day disappears: a populated list always has a
  head whenever it has at least one surfaceable (non-paused, scheduled) member.
- `NothingEligible` loses its active-hours trigger. Its remaining causes are: **no rule
  template assigned**, every member paused, or engine `nextDue == null`. The
  no-rule-template case (a list created "from blank" and never configured) is now the
  dominant path into that empty state and reads with misleading copy ("No one needs a call
  right now"). That deserves its own honest state ("This list has no calling schedule yet"
  → set one) — tracked as a **follow-on**, separate from this ADR.
- Time-of-day stops being able to hide an overdue relationship. A wrong time can only sink
  a contact by ≤ `P_MAX`; it can never remove them.
- Engines are unaffected — they keep owning cooldown math only (`LateNightEngine.kt:9-14`
  comment stays true: active-hours was never the engine's job). The change is localized to
  the three surfacing use cases' sort step + one shared `timeOfDayPenalty` helper.
- "Late night"-style lists become *preferences* rather than *windows*: a late-night contact
  can still surface at noon if they are sufficiently overdue. This is intended (the founder's
  "subjective / they might still pick up" point) but is the main behavioral change to verify
  on-device.

## Non-consequences

- **No learned answer-rate model.** Orbit still does not track or infer per-contact pickup
  probability. Time-of-day remains a user-*stated* preference, nothing more.
- **No change to cooldown, skip, short-call, or incoming-call math.** `nextDue` is computed
  exactly as today; only the sort key it feeds is adjusted.
- **No new persistence / migration.** `activeHoursStart/End` columns are reused as-is;
  `P_MAX` is a code constant. No schema bump.
- **Pause, ignore, and archive semantics are unchanged.**

## Related

- Tide marker (2026-05-08, quick task `260507-o0f`) — stopped dropping future-due heads;
  this ADR completes that direction by also refusing to drop on time-of-day.
- `SurfaceNextUseCase.kt`, `SurfaceQueueUseCase.kt`, `WidgetSurfaceUseCase.kt` — the three
  consumers of the gate.
- Follow-on: split `NothingEligible` so the no-rule-template list gets honest copy + a
  "choose a schedule" action instead of the transient "caught up" line.

## Implementation amendment (2026-06-12)

Implementation surfaced two refinements to the decision above. Neither changes the
user-visible contract; both make the "where does the weight live" question honest:

1. **The penalty is realized cross-list, not within a single list.** `activeHoursStart/End`
   is a *list-level* property, so `timeOfDayPenalty` is identical for every member of a
   list — adding it to a single list's sort key shifts all candidates equally and cannot
   reorder them. The weight therefore bites only where candidates from *different* lists
   (with different windows) compete: **`WidgetSurfaceUseCase`**, the sole cross-list ranker
   (the other cross-list surface, "Surprise me", was removed in `260611-tx0`). Concretely:
   - `SurfaceNextUseCase` and `SurfaceQueueUseCase` (both single-list) simply **drop the
     active-hours gate** and surface the due-ordered queue regardless of clock. They no
     longer take a `zoneId` (it existed only for the gate).
   - `WidgetSurfaceUseCase` gains `clock` + `zoneId` and sorts its cross-list candidates by
     `nextDueAt + timeOfDayPenalty(list.window)`, deduping by contact on the effective key.
     This is where a late-night list's member sinks below a daytime list's member at noon.
   - The shared `timeOfDayPenalty` helper lives in `domain/usecase/TimeOfDayWeight.kt`
     (`DEFAULT_TIME_OF_DAY_PENALTY_CAP = 6h`).

2. **Active hours still governs notification timing — only *surfacing* changed.** The nudge
   scheduler (`ListPromptWorker` / `NudgeScheduler`) keeps using `activeHoursStart/End` to
   decide *when not to send a prompt* (e.g. don't notify at 3am). This ADR removes the
   window only as a *surfacing exclusion*; its role as a quiet-hours bound on notifications
   is unchanged and out of scope here.

Tests added/updated: a contact outside active hours still surfaces (no longer
`NothingEligible`); cross-list, the widget's primary flips with the clock when two lists
have different windows; an overdue out-of-window contact still beats a within-window
not-yet-due peer; pause still hard-excludes.
