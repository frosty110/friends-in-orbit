# life-right-now

**Status:** stub
**Last reviewed:** 2026-06-30
**Ground truth:**
- Code: not yet implemented
- Tests: none yet
- Related primitive: per-contact pause (`ContactEntity.pausedUntil`, `domain/usecase/PauseContactUseCase`, `domain/model/PauseDuration.kt`, `ui/screens/contact/sections/PauseSheet.kt`) — the existing "step a person back" mechanism this feature generalizes to the whole app.
- Mission tie: `features/_foundations/mission.md` principle 7 ("Bend with the user's life") and the "What Orbit adapts to" section.

---

## Product

### Why it exists

A person's capacity for connection isn't constant. Some weeks have room for everyone; some weeks are underwater. Every other reach-out tool treats a fixed cadence as the goal and a missed week as failure — the exact gamification this app rejects (mission principle 5). Orbit instead adapts to the season the user is in.

"Life right now" is a single, reversible setting that tells Orbit how much room the user has for connection — and Orbit bends its pace to match. When life is full, it asks for less and lets nothing pile up. When the user wants more people around them, it leans in and surfaces more. The goal serves the user; the user never serves the goal.

This is the product expression of mission principle 7. It is also the answer to the most common real-world failure of cadence apps: the user travels or hits a hard stretch, falls "behind," feels guilty, and quits.

### User story

As someone whose life has seasons, I can tell Orbit "things are quiet right now" before I travel or when I'm depleted — and it stops nudging, stops anything stacking up as overdue, and greets me with no backlog when I come back. Or I can tell it "I want more people around me right now" during a lonely stretch — and it surfaces more names, more often, including people I haven't reached in a long time. I set it in one tap, optionally with an end date, and I can change it any time.

### Behavior

**Three modes. Default is Steady.**

- **Steady (default).** The user's usual rhythm — about **three ready people a day** (adjustable), surfaced and nudged as their lists are configured. This is the implicit state; the user never has to choose it. The daily number is a *pace the app keeps*, not a score the user clears — see *Consistency without a streak* below.
- **Quiet — stepping back.** For travel, a heavy season, depletion, grief, a busy stretch. Nudges pause (or drop to the gentlest cadence). Rule intervals stretch so fewer people read as "ready." Crucially, **nothing accrues as overdue while Quiet** — due state is frozen, not silently piling up, so returning to Steady never dumps a backlog. The app goes supportive-silent.
- **Leaning in — wanting more.** For loneliness, a new city, a hard stretch where connection helps. Surfacing widens: more people eligible per session, longer-gap people included, warmer and slightly more frequent invitations. Never a quota — just a wider, kinder net.

**Temporary by design.** A mode can carry an optional "until": a **duration preset** (a few days, a week, two weeks), a **specific calendar date** (e.g. "no contact until 15 March"), or **open-ended** ("until I turn it off"). Duration presets reuse the `PauseDuration` vocabulary the app already has for per-contact pause; the calendar-date picker is the one new control. When the window passes, Orbit returns to Steady on its own.

**The return is the whole point.** Coming back from Quiet must never feel like punishment. No "42 waiting," no "catch up," no broken anything. The return reads like a friend, not a ledger.

**Worked examples.**

- *A death in the family; the user wants more people around them.* They set **Leaning in for two weeks.** For that window Orbit surfaces more names a day than their usual three, widens eligibility to include longer-gap people, and the Home banner reflects the choice warmly. After two weeks it returns to Steady on its own — no cliff, no "you fell behind."
- *The user needs to step fully back until a fixed date.* They set **Quiet until 15 March.** Scheduled nudges stop, the daily rhythm drops toward zero, and nothing accrues as overdue meanwhile. On the 15th — or whenever they end it early from the Home banner — they return to exactly where they left, greeted by "Welcome back. Nothing piled up."

**Consistency without a streak.** The user should get real encouragement for keeping this up — but a *consecutive-day streak* is the one mechanism that can't coexist with everything above. A streak's emotional engine is fear of breaking it, so a single quiet day — or a Quiet season the app itself offered — becomes a loss. That directly contradicts principle 7, and principle 5 forbids streaks outright. So the consistency signal is **retrospective and cumulative, never consecutive:**

- It counts *up* over a period and never resets: "this month you reached 14 people you'd been meaning to call."
- A quiet week just makes it grow more slowly; a Quiet season is invisible to it, never a wound.
- It speaks rhythm, not performance: "you've stayed close to your inner orbit lately" / "it's been a quieter stretch — no rush." Pattern language over performance language.

In one line: a streak measures days-without-a-gap and punishes the gap; this measures connection-that-happened and only ever celebrates it. Same encouragement, none of the loss aversion.

**Resolved (2026-06-30, UX-led):**

- **Three states total** — Steady (default) + Quiet + Leaning in. No "Lighter" tier in v1; clarity over granularity.
- Set in **Settings**; while a non-Steady season is active, **Home shows a calm banner** naming the season with one-tap "end early," so muted nudges are never a surprise.
- Quiet pauses **scheduled nudges only**; incoming-call follow-ups still fire (they respond to a real call the user just received, not a nag).

**Voice.** In-app surfaces (banners, settings) may name people, unlike notifications. All copy follows `voice.md`: sentence case, no exclamation, no shame, no streaks. The framing is always the user's season and the user's choice, never performance.

Example copy (in-app — not the notification, where names are forbidden):
- Settings entry: "How's life right now?"
- Quiet active banner: "Things are quiet right now. Reach out when you're ready — nothing's piling up."
- Leaning-in active banner: "You wanted more people around you. Here are a few who'd probably love to hear from you."
- Return from Quiet: "Welcome back. Nothing piled up — pick up wherever feels right."

### Acceptance criteria

- [ ] Setting "Life right now" to Quiet suppresses all list nudges within one nudge cycle (verify: no `ListPromptWorker` post fires while Quiet).
- [ ] While Quiet, a list's due count does not grow over time for members who would otherwise become due (verify: due count stable across the Quiet window).
- [ ] Returning to Steady after Quiet shows no backlog spike and surfaces the return copy — never a "catch up" / count-of-missed framing (grep: no shame strings; manual: return banner present).
- [ ] Leaning in surfaces more eligible contacts per session than Steady for the same data (verify: eligible-count delta > 0).
- [ ] A mode set with an "until" reverts to Steady automatically once the window passes (verify: mode reads Steady after the timestamp on next read).
- [ ] All new strings pass the voice gate (extend `CopyAuditTest` or an analogous audit: no exclamation, no shame patterns, no streak/level/achievement).
- [ ] The setting is reversible in one tap and persists across app restarts (verify: `AppPrefs` round-trip).
- [ ] The daily rhythm is never rendered as a scored fraction ("X of 3") or an empty-as-failure indicator (inspect: no "/3" denominator, no progress-against-target ring).
- [ ] The consistency reflection only counts up — it never resets on a missed day or during a Quiet season (verify: value is non-decreasing across a simulated multi-day gap).
- [ ] "Until" accepts a specific calendar date and reverts on it (verify: set until = a date; mode reads Steady on/after that date).

### Not in scope

- Per-list seasons. v1 is app-level only; per-list override is a later move (would live on `ListEntity` beside `nudgeScheduleJson`).
- Automatic season detection (calendar, location, "you seem busy"). The user always sets it explicitly. No inference, no surveillance.
- A **scored** daily/weekly target — anything shown as "1 of 3 done", a ring that reads empty-as-failure, or "you're behind". The daily rhythm is a pace the app keeps, surfaced as "today's people", never a denominator to clear.
- A **consecutive-day streak**, or any counter whose value drops when the user pauses or has a quiet day. Forbidden by mission principle 5 and irreconcilable with principle 7. The sanctioned consistency signal is the cumulative, never-resetting reflection under *Consistency without a streak*.

### Open product questions

Modes, placement, and follow-up behavior are resolved above (*Resolved (2026-06-30)*). Still open:

- A milder "Lighter" mode between Steady and Quiet — deferred; revisit only if Quiet feels too binary in dogfood.
- For the deepest Quiet ("no contact"), offer a *fully silent* sub-option that pauses incoming-call follow-ups too? Default Quiet keeps follow-ups; fully-silent is a possible deepening. Verify on dogfood.
- The daily-rhythm default — 3 is the starting point. Confirm it feels right against 2 on dogfood, and decide whether it lives per-person-per-day or is purely an app-wide surfacing volume.
- What period does the consistency reflection cover — rolling 30 days, calendar month, or "since you started"? Leaning rolling 30 days so it never has a jarring month-boundary reset.

---

## Technical

### Architecture

App-level mode is a single source of truth read by the surfaces that pace the app: `ListPromptWorker` (nudge gate), the rule engine / `RuleParams` (interval scaling), and the card/home eligibility query (surfacing width). Model it as a small sealed type — e.g. `LifeMode { Steady, Quiet, LeaningIn }` plus an optional `until: Instant?` — exposed as a `Flow` from a `LifeModeRepository`, so every surface reacts without manual plumbing, consistent with the app's existing StateFlow pattern.

The mode introduces no new scheduler. It parameterizes the ones that exist:
- **Nudge gate:** add a sixth gate to `ListPromptWorker.doWork` — if Quiet, skip posting (and the scheduler may lengthen the re-enqueue interval).
- **Interval scaling:** apply a multiplier to the effective `RuleParams` interval (Quiet stretches, Leaning in compresses) so "ready" / due derivation already reflects the season without a parallel code path.
- **No-accrual-while-Quiet:** the load-bearing promise. While Quiet, do not let `nextDueAt` slide into the past as a growing backlog — freeze the due horizon (treat Quiet as a global pause analogous to `pausedUntil`, or clamp due derivation) so the count the user returns to equals the count they left. This single detail is what separates "bends with your life" from "punishes you for leaving."

### Data model

- App-level: new `AppPrefs` / DataStore keys — `life_mode` (enum name); `life_mode_until` (epoch millis, nullable — a specific calendar date is just a millis value, so the date picker needs no extra storage); and `daily_rhythm_target` (int, default 3 — the Steady pace the modes flex). Greenfield; `AppPrefs.resetAll()` must clear them. No Room migration needed for v1.
- The consistency reflection is **derived, not stored as a streak** — compute it on read from existing `CallEventEntity` rows (count of distinct contacts reached in the trailing window). No counter to persist, nothing that can "break."
- Reuse `domain/model/PauseDuration.kt` for the "until" choices so the vocabulary matches per-contact pause.
- Per-list (later): a `lifeModeOverride` column on `ListEntity` beside `dueCount` / `nudgeScheduleJson` — explicitly deferred.

### Permissions / integrations

- No new permissions.
- Integrates with: notifications (nudge gate), rule-engine (interval scaling), home / card-view (surfacing width), settings (the control), and conceptually the existing pause primitive (shared "until" vocabulary, possibly shared suppression mechanism).

### Known gotchas

- WorkManager re-enqueue: when leaving Quiet, nudge chains must re-anchor promptly (reuse `OrbitApp.reAnchorAll()` semantics) so the user isn't left un-nudged after the season ends.
- "Until" expiry has no guaranteed wakeup — evaluate `until` lazily on app open / next nudge tick rather than relying on an exact alarm (consistent with ADR 0004's no-exact-alarm stance). Mode reverts to Steady the first time a surface reads it past `until`.
- Don't let Quiet silently swallow the incoming-call follow-up unless that is the decided behavior — see open question.
- Avoid any count that resembles a score: never render "N days quiet" or "you've reached X more this week."

### Not in scope (technical)

- A new WorkManager worker or notification channel. Reuse the list-prompt path.
- Server-driven or sensor-driven mode inference. Local and explicit only.
- Exact alarms for "until" expiry. Lazy evaluation per ADR 0004.

### Open technical questions

- Implement no-accrual-while-Quiet as (a) a global pause flag the due-derivation already respects, or (b) interval-stretch large enough to park everyone? (a) is cleaner and reuses pause semantics; confirm pause derivation is global-applicable before committing.
- Does interval scaling belong in `RuleParams` (data) or at the engine call site (behavior)? Leaning call-site multiplier so `RuleParams` stays the user's literal "aim for every N."
