# notifications

**Status:** shipped
**Last reviewed:** 2026-07-03
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/notify/` — full notification system. `OrbitNotifications` registers only the `orbit.list_prompt` (DEFAULT importance) channel on startup; the retired `orbit.digest`, `orbit.incoming_followup`, and `orbit.incoming_followup.v2` channels are deleted on every cold start. `DailyDigestWorker` and `IncomingFollowUpWorker` were both deleted (ADR 0009 — notifications are pull, never push). One notification worker remains: `ListPromptWorker`.
- `NudgeSchedule` — `@Serializable` per-list schedule model (days of week × times of day); stored as JSON in `ListEntity.nudgeScheduleJson` (schema v12 / `MIGRATION_11_12` backfill). `NudgeScheduler` (@Singleton) enqueues self-re-enqueueing `OneTimeWork` per list via `setInitialDelay` + `ExistingWorkPolicy.REPLACE`. `ListPromptWorker` (@HiltWorker) implements a 5-gate `doWork`: notifications-enabled check, DND check, list-muted check, due-count check, active-hours check; posts with title = list name, body = opportunity framing via `NotificationCopy.nudgeBody` (name-free; "Someone in {list} is ready when you are. Want to call?" / "A few people in {list} are ready when you are. Start with one?" — the exact due count is deliberately never shown); re-enqueues in `finally` block.
- **Missed / incoming calls fire NO notification** (ADR 0009, 2026-07-03). A missed inbound call instead surfaces the contact in the deck: the rule engine sets `nextDueAt` to the moment they rang (`KeepInTouchEngine` step 3c), so a call-back rises up the deck the longer it waits. `IncomingFollowUpWorker` and `FollowUpDedupStore` were deleted.
- Tap navigation: the nudge notification carries a `NAVIGATE_TO` extra read in `MainActivity` (cold start + `onNewIntent`); `OrbitNavHost` routes the tap to card view.
- List lifecycle hooks: deleting or archiving a list cancels its nudge chain (`ListsManagerViewModel`); unarchiving re-enqueues. Cold-start `OrbitApp.reAnchorAll()` re-anchors all chains.
- Tests: `CopyAuditTest` (voice gate), `NotificationIdsTest`, `NudgeScheduleTest`, `NudgeScheduleNextSlotTest`, `NudgeSchedulerEffectiveSlotsTest`, `ListPromptWorkerTest`. (`IncomingFollowUpWorkerTest` deleted with the worker.) `Migration11To12Test` / `Migration12To13Test` (androidTest — device-run deferred per phase precedent).

---

## Product

### Why it exists

The app's job is to reduce activation energy. Notifications are one of two vectors that bring the suggestion to the user (the other is the widget). Without them, the user must remember to open the app — which defeats the point.

**Non-negotiable:** never per-person nags. Notifications surface opportunity, never absence. And notifications are **pull, never push** — scheduled reminders the user owns (the per-list nudge), never a ping in reaction to an event; a real-world event (a missed call, a new contact) changes in-app surfacing, never fires a notification. Principle-level per `features/_foundations/mission.md` (principles 1 + 6), `features/_foundations/voice.md`, and **ADR 0009** (`0009-notifications-pull-not-push.md`).

### User story

As a user, my lists nudge me on the schedule I set — "someone in {list} is ready when you are" — gentle, name-free, and muteable per list. Nothing pings me because of an event: a missed call surfaces that person inside the app, not as a notification.

### Behavior

**Current state (2026-07-03).** Per-list nudge schedules are fully shipped; incoming-call follow-up notifications were removed (ADR 0009). The onboarding promise ("one quiet nudge per list per day") is honored: `ListPromptWorker` fires once per configured day × time slot when at least one list member is due. `DailyDigestWorker` and `IncomingFollowUpWorker` are both deleted; their legacy work names / channels are cancelled and deleted on every cold start to clean up older installs.

**Daily digest.** RETIRED 2026-04-28 (whole-app review). The legacy `DailyDigestWorker` was deleted. The `orbit.daily_digest` unique work name is cancelled on every `OrbitApp.onCreate` to clean up any remaining scheduled instances on existing installs.

**Time-of-day list prompts.** Only for lists with active-hours configured. One notification per list per active-hours window, never per contact. Example: late night list notifies at 10pm if anyone is due.

**Incoming / missed calls — no notification** (ADR 0009). A missed inbound call surfaces the contact in the deck (due since they rang — `KeepInTouchEngine` step 3c), not as a ping. Orbit never notifies you because of an event; the miss shows up in-app, silently.

**DND respected.** Android system DND suppresses all app notifications.

**Per-list opt-out.** Each list has its own notification toggle in list config.

**Voice enforcement.** Every notification body passes through a central formatter that applies voice rules (sentence case, no exclamation, no emoji, no "haven't called" framing).

**Never:** per-person nags, streak reminders, shame framing, re-engagement prompts.

### Acceptance criteria

- [ ] A list nudge fires within ±15 minutes of its scheduled slot (doze-compatible tolerance per ADR 0004).
- [ ] Content passes voice rules automatically via formatter.
- [ ] Per-list opt-out suppresses that list's nudge without affecting other lists.
- [ ] The nudge tap deep-links to that list's card view.
- [ ] No notification fires in reaction to an event — missed/incoming calls surface in-app only (ADR 0009).
- [ ] `POST_NOTIFICATIONS` requested on Android 13+ before first schedule.
- [ ] DND respected — manually verify by toggling system DND.
- [ ] Deleting a list cancels its scheduled work.

### Not in scope

- Per-contact notifications. Forbidden by mission principles.
- Reminder chains ("you still have 3 people due"). No escalation.
- Push notifications from a server. Fully local.
- Rich notifications with inline reply. Tap-through only.

### Open product questions

- Should list nudges become opt-in rather than default-ON? Default-ON is retained per mission principle 1 and ADR 0009 (a nudge is a muteable scheduled reminder, not an event push), but the "user-defined" reading is loosest here — revisit if default nudging feels pushy on dogfood.

_(Resolved: the daily digest and the incoming-call follow-up notification were both removed — see ADR 0009. Missed/incoming calls now surface in-app, never as a notification.)_

---

## Technical

### Architecture

As built: `OrbitNotifications` registers one channel (`orbit.list_prompt`) and deletes the retired channels (`orbit.digest`, `orbit.incoming_followup`, `orbit.incoming_followup.v2`) on every cold start. The self-re-enqueueing `OneTimeWork` pattern (ADR 0004 amendment) drives the nudge worker — `setInitialDelay` + `ExistingWorkPolicy.REPLACE`, no `BOOT_COMPLETED` receiver, doze-tolerant. `NudgeScheduler` is `@Singleton`-injected; `ListPromptWorker` is a `@HiltWorker` routed through `HiltWorkerFactory`. Tap navigation uses a `NAVIGATE_TO` String extra on the `PendingIntent` (`FLAG_IMMUTABLE`), read in `MainActivity` and dispatched via `OrbitNavHost`.

### Data model

No dedicated Room entity. Due counts derive from existing data via rule-engine. Scheduled work metadata lives in WorkManager's own DB.

### Permissions / integrations

- **Manifest:** `android.permission.POST_NOTIFICATIONS` (runtime on API 33+).
- **Manifest (conditional):** `android.permission.READ_PHONE_STATE` — only if `PHONE_STATE` broadcast proves necessary; prefer inferring from call-detection polling.
- Integrates with call-detection: a missed inbound call surfaces the contact in-app (no notification — ADR 0009).
- Integrates with orbit-lists for per-list toggle state.

### Known gotchas

- WorkManager on doze devices may fire up to ~15 minutes late. Acceptable per app's unhurried pacing (ADR 0004).
- Users can disable individual channels in system settings; code must not crash when posting to a disabled channel — wrap in try/catch.
- Boot completion re-schedules are handled by WorkManager automatically; do not add a custom `BOOT_COMPLETED` receiver for digest.

### Not in scope (technical)

- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`. Rejected in ADR 0004.
- Custom notification sounds. Use channel default.

### Open technical questions

- Per-list `NotificationChannel` (granular user control via system settings) vs shared "list prompts" channel? Shared is simpler; granular is more respectful. Leaning shared for v1.
