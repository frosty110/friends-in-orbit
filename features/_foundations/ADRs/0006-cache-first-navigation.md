# ADR 0006 — Cache-first navigation: no loading state in steady-state local-app screens

**Status:** accepted
**Date:** 2026-04-27
**Accepted:** 2026-04-28 — rollout shipped. Verify-gate evidence: PASS-WITH-DEFERRED-UAT; 48/48 grep gates green, 2 device-bound deferrals.
**Deciders:** the maintainer
**Supersedes:** none
**Refines:** none — introduces a UX architecture principle earlier ADRs did not address

## Context

Home initially shipped with a `HomeUiState.Loading` state that renders an empty tile grid until `HomeViewModel`'s `combine(observeAll, observeMembersOfList × N)` flips to `Ready`. Functionally correct; perceived as "loading lists from somewhere" by users who reasonably expect a local-only app to be instant.

Three observable symptoms:

- **Cold launch:** SQLCipher first-open dominates (native lib load + KDF + page-key derivation, ~100–300 ms on a mid-range device per ADR 0005). The user sees an empty tile grid through that window.
- **Warm launch:** the all-or-nothing `combine` waits for the slowest of N+1 Room queries (one `observeAll` + one `observeMembersOfList` per list). On a 40-list real database this paces the first tile flip noticeably.
- **Re-entry:** `SharingStarted.WhileSubscribed(5_000L)` is screen-scoped. Quick re-entry is fine (StateFlow caches the last value via default `replayExpirationMillis`), but ViewModel destruction (process death, certain nav patterns) drops back to `Loading`.

The product brief commits to a "warm, quiet, unhurried" voice. A loading state is none of those things in a local-only app — it announces a delay that has no honest cause.

We surveyed how the apps Orbit benchmarks against (per the `verify-work` skill) handle the same problem.

| App | Steady-state Home | Cold launch | First-install |
|---|---|---|---|
| Things 3 | Counts visible at first frame; no spinner, no skeleton | Hidden behind launch image; first user-interactive frame already shows data | Empty state with action prompt |
| Linear (mobile) | Last-viewed view renders from cache instantly; new items stream in below the fold without disturbing on-screen content | Splash absorbs cold init | Empty state |
| Apple Notes / Reminders | Lists with counts visible at first frame | Cold init is hidden; first composed frame is data | Empty state |
| Google Calendar | Today's agenda from cache, instantly; off-viewport days hydrate lazily | Splash absorbs cold init | Empty state |
| Reflectly / Stoic | Home renders from cached state | Splash absorbs init | Skeleton acceptable (genuine first-data case) |

The shared pattern is unambiguous:

> **Cache-first, instant render, silent refresh.**
>
> Skeletons exist for two cases only: the genuine first-install empty state, and explicit pull-to-refresh. Steady-state navigation has no loading state.

Implementing this requires that primary-navigation state lives **process-scoped, not screen-scoped**. A subscriber arriving at a screen reads last-known-good state synchronously; live mutations refine it in place. Cold-start cost for expensive init is paid before the first user-interactive frame, behind the launch image, not behind the user's first tap.

This ADR has not been validated by on-device measurement — the competitive observations above are from product use, not instrumented benchmarks. The principle is independently defensible (a local app has no honest reason to render a loading state), so we proceed without measurement; Rule 3 below puts a measurement gate on cold-start cost specifically.

## Options considered

### Option 1 — Status quo: `HomeUiState.Loading` rendered as empty
Screen renders nothing until `combine` fires. Below industry standard. Effort: zero (current state). Voice fit: poor — empty space on a local app implies "broken or slow."

### Option 2 — Skeleton in Loading state
Render placeholder tiles during `Loading`. Effort: ~30 min, one composable. Voice fit: better than empty, still announces a delay. **Below gold standard** because the benchmark apps don't show skeletons for steady-state navigation.

### Option 3 — Optimistic Ready (instant tiles, counts populate in)
Flip to `Ready` on `observeAll()` first emit; per-tile `dueCount` shows skeleton until its membership flow lands. Effort: ~1–2 hr (VM rewrite + nullable `dueCount` + tile skeleton). Voice fit: good — tiles appear instantly. **Approaching gold standard** but still has a transient skeleton state on cold launch.

### Option 4 — Cache-first navigation (gold standard)
Process-scoped state survives screen navigation; cold-start init is hidden behind the launch image; counts are denormalized so home is one query, not N+1. Effort: phase-sized (~half day to a day). Voice fit: **gold standard.** No loading state in steady-state. Skeletons reserved for genuine empty post-install case.

## Decision

**Adopt Option 4 as the architectural principle for primary-navigation surfaces (Home, Browse, Card View). Specific screen-by-screen implementation defers to phase plans.**

The decision pins three rules.

### Rule 1 — Primary-navigation state lives in process-scoped DI singletons, not ViewModels
Home, Browse, and Card View read from feed objects (e.g., `HomeFeed`, `BrowseFeed`) that are `@Singleton`-scoped and own a `StateFlow<...>` over the lifetime of the process. `SharingStarted.Eagerly` is acceptable at this layer because singleton lifecycle = process lifecycle; there is no consumer-count gate to defeat. ViewModels subscribe; they do not own.

`WhileSubscribed` becomes a per-screen cache policy (default acceptable), not the ground source of truth. Re-entering Home reads the cached `Ready` value synchronously.

**Why:** Screen navigation must not retrigger cold-start projection. The cost of computing tile state once is acceptable; recomputing it on every Home re-entry is not.

### Rule 2 — Hot-path derived values are denormalized into the schema
Counts, "next due", and other values that are read on every primary-navigation composition are stored as columns updated by the use cases that mutate the underlying data. The home query becomes a single `SELECT * FROM lists WHERE NOT is_archived` — no `combine` of N membership flows.

**Why:** N+1 query patterns scale linearly with list count and pace UI flips by the slowest query. They're correct on a 4-list test fixture and slow on a 40-list real database. Denormalization is acceptable because the writes that update derived columns are exactly the writes that already mutate the source data — the use case is the natural choke point.

### Rule 3 — Expensive init runs eagerly, before first user-interactive frame
SQLCipher first-open (native load + KDF + page-key derivation) is kicked off in `OrbitApp.onCreate` via `applicationScope.launch`. The launch image absorbs this cost; the first MainActivity composition reads from an already-warm DB. The implementing phase plan must include an on-device measurement of cold-launch-to-first-tile-frame and target ≤16 ms (one frame at 60 Hz) on a mid-range reference device with a populated database.

**Why:** The user's first tap is the wrong place to discover that we encrypt at rest. The launch image is the right place.

### Skeleton policy
Skeletons are permitted only for:

1. **Genuine first-install empty state** — no data exists yet (e.g., no lists created post-onboarding). In practice this is rendered as an empty state with copy + action, not a shimmer.
2. **Explicit pull-to-refresh** — user-initiated; the user knows they asked for new data.

Steady-state navigation MUST NOT show a skeleton. A grep for shimmer / skeleton composables outside these two cases is a review smell.

## Consequences

- **The perf-hotspots polish work is the natural home for the Home implementation work** under Rules 1–3. That work implements: a `HomeFeed` singleton, schema migration v3→v4 adding `lists.due_count`, use-case wiring to keep the column fresh, and eager warmup in `OrbitApp.onCreate`.
- **Future primary-navigation screens (Browse, Card View) are bound by this ADR.** Each introduces a `*Feed` singleton at screen-introduction time. A ViewModel that owns state directly is a deviation requiring an explicit waiver in the implementation plan.
- **`HomeUiState.Loading` is retained but its rendering changes** — used only for the genuine first-install empty case (rendered as an empty state with action prompt), not as a transient state on every cold launch.
- **`SharingStarted.Eagerly` on the singleton's flow is acceptable** despite being normally discouraged — a process-scoped flow over a small, frequently-read dataset has no meaningful resource cost. `WhileSubscribed` at the singleton layer would defeat the principle.
- **Verification gates** the implementing phase introduces:
  - Grep `HomeUiState.Loading -> emptyList()` — forbidden post-implementation (replaced by either the cached value or the empty state).
  - Grep `combine(.*observeMembersOfList` — forbidden (replaced by single-query path).
  - Grep `class .*ViewModel.*StateFlow` for primary-navigation screens — must show subscription to a `*Feed`, not direct repository ownership.
  - On-device cold-launch-to-first-tile-frame measurement (Rule 3 target).


## Non-consequences

- **Does not require a sync engine, telemetry, or background refresh job.** Local data needs none of these.
- **Does not change the encryption-at-rest model or any decision in ADR 0002 / 0005.** Eager warmup moves the cost; it does not change what is computed.
- **Does not specify Compose-side skeleton implementation details.** That's a UI concern, not architecture.
- **Does not retroactively rewrite existing screens.** They migrate when they next touch a primary-navigation surface.
- **Does not commit to denormalizing every derived value.** Only hot-path-on-composition values. Card View's surfacing weights, for example, stay in-memory because they're computed on tap, not on every frame.
- **Does not change the design tokens or motion system.** Tile layout, spacing, and motion remain unchanged.

## Related

- **`README.md`** — voice principles ("warm, quiet, unhurried") that this ADR operationalizes for perceived performance.
- **ADR 0005** — SQLCipher production tuning. The cold-init cost this ADR commits to hide is the cost ADR 0005 pinned.
- **The perf-hotspots polish work** — owns the Home implementation under Rules 1–3.
- **Project principle — reduce grey areas to zero**: this ADR closes the grey area around "what should we render while data loads."
- **The project's benchmark-app list** — the competitive analysis this ADR draws from.
