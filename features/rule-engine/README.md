# rule-engine

**Status:** shipped
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/domain/rule/` (`RuleEngine.kt`, `KeepInTouchEngine.kt`, `LateNightEngine.kt`, `EnergizeEngine.kt`, `EngineFactory.kt`, `RuleParams.kt`, `RuleContext.kt`, `ContactSnapshot.kt`, `OverrideResolver.kt`)
- Tests: `android/app/src/test/java/app/orbit/domain/rule/` (`KeepInTouchEngineTest`, `LateNightEngineTest`, `EnergizeEngineTest`, `OverrideResolverTest`, `RuleParamsSerializationTest`)

---

## Product

### Why it exists

The rule engine decides who is "due" on each list at any moment. It turns the home due-counts from decoration into information and makes the card-view queue intelligent. Without it, the app is a contact list; with it, the app is a quiet friend who knows who you meant to call.

### User story

As a user, I pick a simple rule template for each list ("keep in touch"), optionally tune the knobs, and the app handles the rest. I never see the math.

### Behavior

**Templates (v1).** Three presets, one engine each: "Keep in touch" (48h–336h cooldown defaults), "Late night" (72h–504h), "Energize" (24h–168h). Defaults are locked.

**Tunable parameters per rule** (`RuleParams` subtypes, JSON in `RuleTemplateEntity.paramsJson`).
- Cooldown min / max (how soon can a contact re-surface).
- Escalation factor (how much urgency grows past cooldown).
- Skip penalty (effect of swipe-left on next surface time).
- Short-call threshold (default 60s) + short-call reset percent — calls under the threshold don't fully reset cooldown.
- Incoming-call reset percent.

**Interval honesty (`KeepInTouch.withIntervalHours`, 2026-06-09).** The List Configuration "aim for every N" slider commits through a single entry point that moves **both** cooldown bounds together: `cooldownMinHours = interval`, `cooldownMaxHours = interval + 288h` of skip headroom. Committing only the min let the default 336h cap silently turn "aim for every 30 days" into every 14 days; the cap must never force someone to surface more often than the rhythm the user chose.

**Call metadata considered.** Duration, time of day, direction (outgoing vs incoming).

**Incoming calls count as 50% cooldown reset by default** — user-configurable per rule.

**Short calls treated differently** — below threshold, cooldown reset is partial, not full.

**Rule layering.**
- Per-list by default.
- Per-contact override available but UI hidden until contact is on 2+ lists (avoid config clutter for single-list contacts).

**Defer behavior configurable per list:**
- **Escalate** — skipped contacts surface more urgently.
- **Deprioritize** — skipped contacts surface less often.

### Acceptance criteria

- [x] Pure function — given contact state + rule context + clock, returns the next-due `Instant?` deterministically.
- [x] Injectable clock (`app.orbit.domain.clock.Clock`). No `System.currentTimeMillis()` inside the engine.
- [x] JVM-unit-testable without Android runtime.
- [x] Rule config round-trips through JSON losslessly (`RuleParamsSerializationTest`).
- [x] Short-call threshold, skip penalty, and incoming-call weight all respected.
- [x] Ignored contacts short-circuit before cooldown math (engines return `null` — never due).
- [x] Interval slider commits via `withIntervalHours` so the cooldown cap never overrides the chosen cadence.

### Not in scope

- Custom rule builder UI beyond preset tuning. PRD §v1 Scope "Out."
- Learning / ML-adjusted cooldowns. Rules are declarative in v1.
- Cross-list rule interactions. Each list evaluates independently against shared call history.

### Open product questions

- ~~"Escalation factor" — multiplier on urgency score, or cooldown reducer?~~ Resolved in implementation: `escalationFactor` lives in `RuleParams`; skip-driven escalation is bounded by `cooldownMaxHours`.
- Incoming call from an unknown number (not tracked) — does rule-engine surface an "add to orbit?" signal, or is that the notifications feature's job? Leaning notifications — rule-engine stays pure.

---

## Technical

### Architecture

Lives in `android/app/src/main/java/app/orbit/domain/rule/`. Pure Kotlin, no Android dependencies. Consumed by use cases (`SurfaceNextUseCase`, `MarkCalledUseCase`, `SkipContactUseCase`, due-count recompute), never directly by UI.

Contract (`RuleEngine.kt`):
```kotlin
sealed interface RuleEngine {
    fun nextDue(contact: ContactSnapshot, ctx: RuleContext, clock: Clock): Instant?
}
```

Strategy-per-template — three sealed implementations (`KeepInTouchEngine`, `LateNightEngine`, `EnergizeEngine`), each reading its own `RuleParams` subtype. `EngineFactory.engineFor` decodes `RuleTemplateEntity.paramsJson` into the matching engine. `OverrideResolver` layers per-contact `ruleParamsOverrideJson` over the list-level params.

### Data model

**Consumes.** `ContactSnapshot` (id, `isIgnored`, `pausedUntil`), `RuleContext` (last call's timestamp/duration/direction/source, `skipCount`, decoded `RuleParams`, optional active hours), `Clock`. Null `lastCallAt` = cold start, surface immediately.

**Produces.** `Instant?` — the moment the contact should next surface; `null` = never due in this context (e.g. ignored contacts short-circuit before cooldown math). Persisted to `ListMembership.nextDueAt`; "due" everywhere in the app means `nextDueAt IS NULL OR nextDueAt <= now`.

### Permissions / integrations

None — pure domain code.

### Known gotchas

- Use the injected `app.orbit.domain.clock.Clock` exclusively (tests inject `TestClock`; prod injects `SystemClock`). No `Instant.now()` / `System.currentTimeMillis()` inside engines — enforced by project convention and a grep acceptance criterion.
- Rule JSON parser must be tolerant — unknown fields ignored (`ignoreUnknownKeys = true`), missing fields defaulted. A rule schema change must not brick existing lists. Removing a `RuleParams` field is a breaking change requiring a migration pass over stored rows.
- `engineFor` dispatches on the decoded `RuleParams` subtype, not `template.kind` — a kind/paramsJson mismatch fails loudly at decode instead of silently running the wrong engine.
- When tuning the KeepInTouch interval, never write `cooldownMinHours` alone — go through `withIntervalHours` so `cooldownMaxHours` moves with it (see Behavior §Interval honesty).

### Not in scope (technical)

- Persisting evaluation results inside the engine. The engine itself is stateless; its output is persisted by *callers* to `ListMembership.nextDueAt` (and the denormalized `ListEntity.dueCount`) at choke points like mark-called, skip, and recompute.
- Caching per-contact state across evaluations. Stateless by design.
- Concurrency primitives. `RuleEngine.nextDue` is a plain pure function; callers handle threading.

### Open technical questions

- ~~Strategy subclasses named after templates, or one generic evaluator parameterized by config?~~ Resolved: one sealed engine per template (`engineFor` dispatcher).
- ~~Where does `CallHistory` precompute live?~~ Resolved: use cases build `RuleContext` from the latest call event per contact (`CallEventRepository.observeLatestPerContactInList`) — the engine stays dependency-free.
