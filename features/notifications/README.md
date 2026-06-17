# notifications

**Status:** shipped
**Last reviewed:** 2026-06-10
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/notify/` — full notification system. `OrbitNotifications` registers `orbit.list_prompt` (DEFAULT importance) and `orbit.incoming_followup.v2` (HIGH importance) channels on startup; old `orbit.digest` and `orbit.incoming_followup` channels are deleted on every cold start. `DailyDigestWorker` was deleted. Two workers remain: `ListPromptWorker` and `IncomingFollowUpWorker`.
- `NudgeSchedule` — `@Serializable` per-list schedule model (days of week × times of day); stored as JSON in `ListEntity.nudgeScheduleJson` (schema v12 / `MIGRATION_11_12` backfill). `NudgeScheduler` (@Singleton) enqueues self-re-enqueueing `OneTimeWork` per list via `setInitialDelay` + `ExistingWorkPolicy.REPLACE`. `ListPromptWorker` (@HiltWorker) implements a 5-gate `doWork`: notifications-enabled check, DND check, list-muted check, due-count check, active-hours check; posts with title = list name, body = "{N} due in {list name}."; re-enqueues in `finally` block.
- `IncomingFollowUpWorker` (@HiltWorker) — expedited; fires after `CallLogSyncWorker` detects a new tracked incoming call; 30-minute dedup via `FollowUpDedupStore` (DataStore `orbit_followup_state`); title = "{Name} called you.", body = "Want to call back?"; tap opens contact detail.
- Tap navigation: notifications carry a `NAVIGATE_TO` extra read in `MainActivity` (cold start + `onNewIntent`); `OrbitNavHost` routes to card view (nudge) or contact detail (follow-up).
- List lifecycle hooks: deleting or archiving a list cancels its nudge chain (`ListsManagerViewModel`); unarchiving re-enqueues. Cold-start `OrbitApp.reAnchorAll()` re-anchors all chains.
- Tests: `CopyAuditTest` (4/4 — voice gate), `NotificationIdsTest` (5/5), `NudgeScheduleTest` (3/3), `NudgeScheduleNextSlotTest` (5/5), `NudgeSchedulerEffectiveSlotsTest` (5/5), `ListPromptWorkerTest` (8/8), `IncomingFollowUpWorkerTest` (7/7). `Migration11To12Test` (androidTest — device-run deferred per phase precedent).

---

## Product

### Why it exists

The app's job is to reduce activation energy. Notifications are one of two vectors that bring the suggestion to the user (the other is the widget). Without them, the user must remember to open the app — which defeats the point.

**Non-negotiable:** never per-person nags. Notifications surface opportunity, never absence. This is principle-level per `features/_foundations/mission.md` and `features/_foundations/voice.md`.

### User story

As a user, I get one gentle digest a day at a time of my choosing: "you have 3 people due today." Late-night list prompts me at 10pm if I've configured it that way. If a tracked contact calls me, I get a "want to call back?" follow-up.

### Behavior

**Current state (2026-06-10).** Per-list nudge schedules and incoming-call follow-ups are fully shipped. The onboarding promise ("one quiet nudge per list per day") is honored: `ListPromptWorker` fires once per configured day × time slot when at least one list member is due. `DailyDigestWorker` is deleted; its legacy work name (`orbit.daily_digest`) is cancelled on every cold start to clean up older installs.

**Daily digest.** RETIRED 2026-04-28 (whole-app review). The legacy `DailyDigestWorker` was deleted. The `orbit.daily_digest` unique work name is cancelled on every `OrbitApp.onCreate` to clean up any remaining scheduled instances on existing installs.

**Time-of-day list prompts.** Only for lists with active-hours configured. One notification per list per active-hours window, never per contact. Example: late night list notifies at 10pm if anyone is due.

**Incoming call follow-up.** After a completed or missed incoming call from a tracked contact: "X called you, want to call back?" Tap to call, or open contact-detail for context. If notifications are disabled globally, the follow-up surfaces on next app open instead (banner).

**DND respected.** Android system DND suppresses all app notifications.

**Per-list opt-out.** Each list has its own notification toggle in list config.

**Voice enforcement.** Every notification body passes through a central formatter that applies voice rules (sentence case, no exclamation, no emoji, no "haven't called" framing).

**Never:** per-person nags, streak reminders, shame framing, re-engagement prompts.

### Acceptance criteria

- [ ] Digest fires within ±15 minutes of user-chosen time (doze-compatible tolerance per ADR 0004).
- [ ] Content passes voice rules automatically via formatter.
- [ ] Per-list opt-out suppresses its list prompt without affecting the digest.
- [ ] Taps deep-link correctly: home for digest, card-view-for-list for list prompt, contact-detail for incoming follow-up.
- [ ] `POST_NOTIFICATIONS` requested on Android 13+ before first schedule.
- [ ] DND respected — manually verify by toggling system DND.
- [ ] Deleting a list cancels its scheduled work.

### Not in scope

- Per-contact notifications. Forbidden by mission principles.
- Reminder chains ("you still have 3 people due"). No escalation.
- Push notifications from a server. Fully local.
- Rich notifications with inline reply. Tap-through only.

### Open product questions

- If digest fires during DND and the user opens the phone later, should the notification remain in the tray? Leaning yes (Android default).
- Incoming follow-up: fire on missed + completed, or missed only? PRD says both — completed may feel redundant; verify on first dogfood.

---

## Technical

### Architecture

As built: `OrbitNotifications` registers two channels (`orbit.list_prompt`, `orbit.incoming_followup.v2`) and deletes two retired channels on every cold start. The self-re-enqueueing `OneTimeWork` pattern (ADR 0004 amendment) drives both workers — `setInitialDelay` + `ExistingWorkPolicy.REPLACE`, no `BOOT_COMPLETED` receiver, doze-tolerant. `NudgeScheduler` is `@Singleton`-injected; `ListPromptWorker` and `IncomingFollowUpWorker` are `@HiltWorker` classes routed through `HiltWorkerFactory`. Tap navigation uses a `NAVIGATE_TO` String extra on the `PendingIntent` (`FLAG_IMMUTABLE`), read in `MainActivity` and dispatched via `OrbitNavHost`.

### Data model

No dedicated Room entity. Due counts derive from existing data via rule-engine. Scheduled work metadata lives in WorkManager's own DB.

### Permissions / integrations

- **Manifest:** `android.permission.POST_NOTIFICATIONS` (runtime on API 33+).
- **Manifest (conditional):** `android.permission.READ_PHONE_STATE` — only if `PHONE_STATE` broadcast proves necessary; prefer inferring from call-detection polling.
- Integrates with call-detection for follow-up trigger.
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
