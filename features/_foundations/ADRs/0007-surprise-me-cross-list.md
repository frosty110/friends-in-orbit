# ADR 0007 — Surprise me selects a contact, not a list

**Status:** superseded
**Date:** 2026-05-04
**Superseded:** 2026-06-12 — the "Surprise me" feature was removed from Home
entirely. `PickSurpriseContactUseCase`, the `SurpriseTile`, and all supporting
Home / ViewModel / Ui-state plumbing were deleted. Card View remains the
one-name-at-a-time mechanism; there is no longer a one-tap cross-list shortcut.
This ADR is retained as a record of the original decision.
**Deciders:** the maintainer
**Supersedes:** none
**Refines:** `features/home/README.md` (HOME-03 surprise behaviour)

## Context

An earlier release shipped "Surprise me" as a list-pick affordance: tapping the tile
invoked `SurpriseTileUseCase`, which weighted the user's lists by time-of-day match and
returned a single `listId`. Home then routed to Card View for that list, which
in turn surfaced the head of that list's queue.

Two-step picking. The user's mental model — surfaced during the 2026-05-04
founder UAT — is one-step:

> "I tap Surprise me because I don't want to think about WHO. I want a name."

The mission is "remove the who-should-I-call friction" (`README.md` core value).
Card View is the mechanism that hands you one name; "Surprise me" is its
shortest path. Routing through a list pick reintroduces the very decision
the affordance exists to skip — the user has to trust that the picked list
will surface the right person, and a poor list pick (lots of due people
on a list whose time-of-day weight happened to win) feels arbitrary.

## Options considered

1. **Status quo: list-pick with time-of-day weight.** Two-step. The list pick
   layer adds no honest information for the user — it's pure indirection.
2. **Cross-list contact pick (this ADR).** Score every list's head-of-queue
   contact, pick the most-overdue across the union, route to Card View for
   that contact's list. One step from the user's perspective; the list
   becomes a navigation context, not a first-class choice.
3. **Cross-list contact pick with random jitter.** As (2) but with session-seeded
   randomness so the same data emits different contacts across sessions.
   Rejected: the most-overdue contact IS the right answer; randomness undermines
   the "trust the pick" promise. Determinism is the feature.

## Decision

**Adopt option 2.** "Surprise me" picks a contact across all non-archived
lists and routes to Card View scoped to the list on which that contact is
most overdue.

### Algorithm

Inputs: `clock.now()`, the set of non-archived lists, and the existing
`SurfaceNextUseCase(listId)` Flow that returns each list's head candidate
(already filtered for ignored / archived / paused / active-hours / future-due).

For each non-archived list `L`:
1. Read `surfaceNext(L).first()` — the head contact, or `null` if no due
   candidate exists on `L`.
2. If non-null, read the membership row for `(contact, L)` from
   `listRepo.observeMembersOfList(L).first()` to obtain `nextDueAt`.
3. Compute `overdueMs = max(0, now - nextDueAt)` — `nextDueAt == null` is
   treated as `now` (cold-start contacts; never-called).

Build the candidate set `{(contactId, listId, listName, overdueMs)}`. If empty,
emit `null` (UI gates the tile).

De-duplicate by `contactId`: when a contact appears as the head of multiple
lists, keep the entry with the largest `overdueMs` (the list where they are
most overdue).

Read `callEventRepo.observeAggregatesForContacts(candidateContactIds).first()`
once to obtain per-contact call counts (default 0 for absent ids).

Sort the de-duplicated candidates by:
1. `overdueMs` DESC (most overdue first).
2. `callCount` ASC (less-called first — mild novelty bias on ties).
3. `contactId` ASC (deterministic terminal tiebreak).

Return the head. The list context for Card View is the same entry's `listId`;
Card View naturally re-surfaces this contact because `SurfaceNextUseCase` on
that list returns the same head we sourced from.

The algorithm is deterministic for fixed inputs — the same data emits the same
contact every tap until the data changes. We accept this: the most-overdue
contact is the right answer; re-tapping to "shuffle" is not a use case.

### Empty state

When no list has any due contact, the use case emits `null`. The Home tile
disables (reduced opacity, non-clickable) — no copy change. The ambient
"You're caught up" framing on Home (zero `dueCount` summed across tiles)
already communicates the same state from the surrounding chrome.

### Reversibility

Trivial. `SurpriseTileUseCase` (the prior list-pick) was a single-file use
case wired by Hilt; `PickSurpriseContactUseCase` (this ADR) replaces it with
the same wiring shape. Reverting is one git revert away — neither schema nor
public API changes.

## Consequences

- **`SurpriseTileUseCase` deleted.** Replaced by `PickSurpriseContactUseCase`.
- **`HomeViewModel.surprisePickEvents`** now carries `(contactId, listId)`,
  not just `listId`. Home routes to `Routes.card(listId)` as before — the
  contactId is informational at the VM layer (Card View re-derives the head).
- **`HomeUiState.Ready` gains `surpriseAvailable: Boolean`** so the tile
  can disable when no candidate exists. Sourced from `tiles.any { it.dueCount > 0 }`
  to avoid an extra eager pick.
- **No new `initialContactId` arg on Card View.** The list-scoped Card View
  already surfaces the same head our picker sourced. Adding a pre-position
  arg would be wasted plumbing — every existing test that exercises Card View
  via `surfaceNext` already covers the surprise-pick case implicitly.
- **No analytics or telemetry changes.** v1 ships local-only with no telemetry
  (`README.md` constraints); when telemetry lands, "surprise pick" emits
  `(contactId, listId)` rather than `(listId)`.
- **No rule engine contract changes.** The picker reuses `SurfaceNextUseCase`'s
  existing emission shape; `RuleEngine.nextDue` is untouched.

## Non-consequences

- **Does not change Card View's swipe / skip semantics.** The list context is
  still the unit Card View operates on; this ADR only changes how the user
  arrives at a Card View.
- **Does not change the Home tile grid, tile rendering, or list ordering.**
- **Does not change cross-list propagation** (`MarkCalled` /
  `SurfaceSooner` / `SkipContact` semantics). Those operate per-membership
  regardless of which list Surprise me picked.
- **Does not introduce randomness.** The pick is deterministic for fixed
  inputs. Founder feedback explicitly preferred trust over shuffle.

## Related

- `features/home/README.md` — HOME-03 spec, updated by this ADR.
- `README.md` core value: "remove the who-should-I-call friction."
- `features/_foundations/voice.md` — empty-state framing (the disabled tile
  inherits the warm tone of the surrounding "You're caught up" UI).
- `domain/usecase/SurfaceNextUseCase.kt` — sourced reactively per list to
  obtain each list's head candidate. Untouched by this ADR.
