# ADR 0004 — Notification scheduling via WorkManager (only)

**Status:** accepted
**Date:** 2026-04-22
**Deciders:** the maintainer
**Supersedes:** none

## Context

PRD §Notifications requires three notification classes:
1. Daily digest at user-chosen time.
2. Time-of-day list prompts (per list with active-hours configured).
3. Incoming call follow-up (reactive, not scheduled).

(1) and (2) are periodic; (3) is event-driven. WorkManager was flagged as a pending dependency.

## Options considered

1. **WorkManager + AlarmManager hybrid** — WorkManager for digest, AlarmManager for list prompts if doze batching causes unacceptable drift. Adds `SCHEDULE_EXACT_ALARM` permission complexity on Android 13+ (user-revocable, Play Store scrutinizes).
2. **WorkManager only** — periodic work with `setInitialDelay` per schedule. Doze may delay firing by up to ~15 minutes.
3. **AlarmManager only** — precise but requires `SCHEDULE_EXACT_ALARM` on Android 13+, and rebuilds rescheduling logic on device reboot manually.

## Decision

**WorkManager only.**

Rationale: the app's voice and pacing philosophy (`features/_foundations/mission.md`, `features/_foundations/voice.md`) explicitly eschews urgency. A daily digest firing at 8:07am instead of 8:00am is not a bug — it matches the warm, unhurried feel. Avoiding `SCHEDULE_EXACT_ALARM` keeps the Data Safety surface smaller and sidesteps user-revocable permission fragility. Incoming call follow-up (case 3) is handled by a `BroadcastReceiver` inside call-detection and does not touch this scheduling path.

## Consequences

- Add `androidx.work:work-runtime-ktx` to `libs.versions.toml`.
- `PeriodicWorkRequest` for the daily digest, keyed to user-selected time via `setInitialDelay` on first schedule.
- One `PeriodicWorkRequest` per active-hours-configured list, keyed to the list's active-hours start.
- Integration with Hilt (ADR 0001): `HiltWorkerFactory` + `@HiltWorker` on each `CoroutineWorker`. `WorkManager.initialize` called from `OrbitApp.onCreate` with the custom factory.
- Documented user-facing behavior: "digest fires around your chosen time; may be a few minutes late on low-power days." Voice: matter-of-fact, not apologetic.
- Deleting all app data (`features/settings/README.md`) must cancel all enqueued work.

## Non-consequences

- Does not add `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` to the manifest.
- Does not preempt future adoption of AlarmManager if user feedback demands precision — a second ADR would be opened.
- Does not schedule anything per-contact. Per-person nags are forbidden by `features/_foundations/mission.md` (principle: no shame-based nudges).

## Related

- ADR 0001 (Hilt — supplies `HiltWorkerFactory`)
- `features/notifications/README.md`
- `features/call-detection/README.md` (incoming call follow-up lives here, not scheduling)

---

## Amendment (2026-06-09)

**Status:** amended — the following supersedes the original decision's specific implementation
details. The original status field ("accepted") and rationale remain intact.

### 1. Self-re-enqueueing OneTimeWork replaces PeriodicWorkRequest for nudges (D-07, NOTIF-12)

`PeriodicWorkRequest` is no longer used for nudge scheduling. The original decision planned
"One `PeriodicWorkRequest` per active-hours-configured list" — this approach has cross-day
drift documented by Pietro Maggi / Android Developers: a 5:00 am nudge becomes 5:25 am,
then 5:15 am across successive days because `PeriodicWorkRequest` measures intervals from the
start of each execution, not from an absolute wall-clock anchor.

The current design replaces it with a **self-re-enqueueing `OneTimeWork`** pattern:

- `NudgeScheduler` (a Hilt `@Singleton`) owns the schedule logic. Its `schedule(listId, NudgeSchedule)` method computes the next (day × time) slot as an absolute `ZonedDateTime` using the user's `ZoneId.systemDefault()`, then enqueues a `OneTimeWorkRequestBuilder<ListPromptWorker>` with `setInitialDelay` set to the duration until that slot.
- `ListPromptWorker` — the new nudge worker — checks fire-time gates (notifications enabled, DND, active hours, due count ≥ 1) and posts the notification if all pass. It then unconditionally re-enqueues its own next slot as the **last step, placed in a `finally` block** to guarantee execution even if an exception occurs. This re-enqueue resets any drift that accumulated during Doze batching.
- Unique work name: `nudge_list_{listId}`.
- The `NudgeSchedule` data class (`days: Set<DayOfWeek>, times: List<LocalTime>`) allows multi-day × multi-time configurations. A fixed repeat interval cannot model this shape — another reason `PeriodicWorkRequest` is unsuitable.

### 2. ExistingWorkPolicy.REPLACE semantics — cold-start re-anchor and config-save (D-08)

All calls to enqueue a nudge use `ExistingWorkPolicy.REPLACE`. This policy cancels any
in-flight pending `OneTimeWork` of the same unique name and replaces it with the freshly
computed request.

Two external triggers call REPLACE in addition to the worker itself:
- **`OrbitApp.onCreate` re-anchor:** `NudgeScheduler.reAnchorAll()` re-enqueues all active,
  non-archived lists on every cold start. This is the safety net if the worker chain ever
  breaks (crash before the `finally` block, WorkManager DB corruption, install-after-wipe).
- **Config save:** `ListConfigViewModel` calls `NudgeScheduler.schedule()` when the user saves
  a new schedule or toggles notifications for a list. The REPLACE policy ensures the new
  schedule takes effect immediately.

### 3. ExistingPeriodicWorkPolicy.REPLACE deprecation note

`ExistingPeriodicWorkPolicy.REPLACE` was deprecated in WorkManager 2.8.0. The project's
`DailyDigestWorker` (which used `UPDATE` correctly before deletion) was the last consumer of
any periodic work. After `DailyDigestWorker` was deleted,
**no `PeriodicWorkRequest` remains in the codebase**. Any future worker added
to this project must use `OneTimeWorkRequest` with the self-re-enqueueing pattern, or use
`ExistingPeriodicWorkPolicy.UPDATE` if a truly interval-based worker is introduced.

### 4. Follow-up trigger is a CallLogSyncWorker post-reconcile hook, not a BroadcastReceiver (D-11)

The original ADR states: "Incoming call follow-up (case 3) is handled by a `BroadcastReceiver`
inside call-detection." This is outdated. The `ACTION_PHONE_STATE`
`BroadcastReceiver` was replaced with a `ContentObserver` registered against `CallLog.Calls.CONTENT_URI`.

The follow-up trigger is a **code hook inside `CallLogSyncWorker.doWork()`**: after `CallLogReconciler.reconcile()` classifies a new `INCOMING` call event for a tracked,
non-ignored, non-paused contact within the last 10 minutes, it enqueues `IncomingFollowUpWorker`
as an expedited `OneTimeWork` (unique name `follow_up_{contactId}`, `ExistingWorkPolicy.REPLACE`).
There is no new `BroadcastReceiver`.

### 5. Doze tolerance remains ±15 minutes

This commitment from the original decision is unchanged. Both nudge slots and expedited
follow-up workers operate within the ±15 minute Doze batching window. Expedited work reduces
latency on API 31+ but does not guarantee sub-minute delivery during deep Doze. This is
acceptable per the app's warm, unhurried voice philosophy.

### 6. No ACTION_BOOT_COMPLETED receiver — WorkManager persistence re-enqueues after reboot

WorkManager persists its queue in an internal Room database and re-enqueues pending
`OneTimeWork` after reboot automatically. The cold-start re-anchor in `OrbitApp.onCreate`
(point 2 above) additionally idempotently replaces all nudge schedules. No
`ACTION_BOOT_COMPLETED` `BroadcastReceiver` is added to the manifest. The original
non-consequence ("Does not preempt future adoption of AlarmManager") remains unchanged.
