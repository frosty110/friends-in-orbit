# call-detection

**Status:** in-progress
**Last reviewed:** 2026-10-07 (the call-log trigger that wakes Orbit after a call, for NOTIF-16)
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/calllog/` (`CallLogSyncWorker`, `CallLogReconciler`, `ContentObserverController`, `CallLogTriggerWorker`, `PhoneNumberNormalizer`), `android/app/src/main/java/app/orbit/data/android/CallLogReader.kt`
- Tests: `android/app/src/test/java/app/orbit/calllog/` (`CallLogSyncWorkerTest`, `CallLogReconcilerTest`, `ContentObserverControllerTest`, `CallLogTriggerWorkerTest`, `PhoneNumberNormalizerTest`)

---

## Product

### Why it exists

The app's core value proposition is that *it watches*. You don't have to log anything. You call someone — from the app, from the system dialer, from anywhere — and Orbit notices. Without this, the rule engine has no input and the whole loop collapses. This feature is the silent backbone.

### User story

As a user, I grant CALL_LOG permission during onboarding. From then on, the app silently keeps my orbit in sync with my real call behavior. I never tap "mark as called."

### Behavior

**Permission.** `READ_CALL_LOG` required for core functionality. Manual-log entry fallback available when denied (degraded experience; `features/onboarding/README.md` covers the denial path).

**Import window.** First install imports the last 90 days by default. User-configurable in Settings (see `features/settings/README.md`).

**Manual sync.** Settings → "Sync now" (call log) and Settings → Contacts → "Sync contacts" (address book). Both idempotent — the call-log path is insert-only and the contacts path is delta-sync (insert + refresh + orphan), so neither overwrites or deletes existing data. A new phone re-links contacts by normalized number and keeps prior call history, notes, and ignore/pause flags.

**Resume sync.** On every app foreground the call log is re-read incrementally (TTL-gated) so a call completed while the app was backgrounded/killed still surfaces without a manual tap. See `real-time-detection-exploration.md` for why live in-call detection is deliberately not built.

**Woken after a call (2026-10-07).** The resume sync reads a finished call only when Orbit next comes to the front, which is too late to ask about the call in the notification shade (NOTIF-16 in `features/notifications/README.md`). So Orbit also arms a WorkManager content URI trigger on `CallLog.Calls.CONTENT_URI` (`CallLogTriggerWorker`): when the call log changes, Android starts Orbit even if its process was killed during the call, and the worker runs the ordinary incremental sync and arms itself again. No new permission: it rides on READ_CALL_LOG, is armed only while that is granted, and is disarmed with the observers (a reset, a revoked permission). The content observer stays, because it reacts at once while Orbit is alive (the card's return from the dialer counts on that, CORE-04), while JobScheduler batches the trigger and Doze can defer it. Both enqueue the same unique sync with KEEP, so a change heard twice is still one sync, and there is no second ingest path.

**Filter.** Inbound non-events are not counted and never enter the domain model: missed calls, declined calls, voicemails and blocked calls, because the user did not reach out. An incoming call is ingested once it was answered (a duration of 1 second or more). An outgoing call is always ingested: one that connected (1 second or more) as a call (`source = CALL_LOG`), and one nobody answered (under 1 second) as an attempt (`source = ATTEMPT`), a reach-out the user placed that Call history shows as "Attempted" and the stats leave out (`features/call-history/README.md`, the glossary's "Attempt" in `voice.md`). `CallLogReconciler.isIngestable` and `toCallSource` are the mechanism. Until 2026-10-06 this paragraph said unanswered calls were never ingested, which the reconciler had not done for some time.

**Direction.** Outgoing and incoming stored separately.

**Cross-list propagation.** A new call updates last-call state on every list the contact belongs to — via `features/orbit-lists/README.md`.

**Cooldown weighting.** Incoming calls count as 50% cooldown reset by default; user-configurable per rule (see `features/rule-engine/README.md`).

**After a call.** A missed call raises no notification: it surfaces the person in the deck (ADR 0009; the "they called you, want to call back?" follow-up was removed on 2026-07-03, and this paragraph described it until 2026-10-07). A connected call of a minute or more with someone on a list waits for a note on Home (NOTE-05), and, when it ends while Orbit is closed, the sync that reads it posts "How was your call with Kai?" (NOTIF-16).

### Acceptance criteria

- [ ] First-run import of 90 days completes in < 5s on a test device with ~1000 call log rows.
- [x] Missed, declined, voicemail and blocked rows never reach Room. The filter runs in Kotlin after the read, not in the query: `CallLogReader` selects by date only, and `CallLogReconciler.isIngestable` drops those types (and an incoming call under 1 second) before anything is written (`CallLogReconcilerTest.type_mapping_skips_missed_rejected_voicemail_blocked`, `outgoing_no_answer_is_an_attempt_incoming_zero_duration_is_skipped`).
- [ ] Resync is idempotent — running twice produces identical state.
- [ ] Permission denial produces a usable degraded app; no dead-end UX.
- [x] All call history persisted through encrypted Room per ADR 0002 (`DatabaseFactory` opens the store with SQLCipher's `SupportOpenHelperFactory`; `features/call-history/README.md` checks the same fact).
- [ ] Data Safety form justification for `READ_CALL_LOG` matches actual usage.

### Not in scope

- Recording call audio. Never.
- Analyzing call content. Orbit knows when and who, not what was said.
- Real-time in-call overlays or notifications.
- Carrier billing integration.

### Open product questions

- Resync semantics: deep-clean (delete & reimport) or diff-merge? Diff-merge is safer but slower. Leaning diff-merge.
- Calls to contacts not on any list — ingest & shelve (so adding them later produces history), or skip? Leaning ingest & shelve.
- Dual-SIM: surface SIM info in call history ("called from work SIM")? Out of v1 scope but capture the data.

---

## Technical

### Architecture

- `CallLogReader` — thin wrapper over `CallLog.Calls` ContentResolver query. Pure Android, no Room dependency.
- `CallLogSyncWorker` (`@HiltWorker`, WorkManager per ADR 0004) runs on five triggers, all funnelling through the `orbit.call_log_sync` unique work: (1) **app foreground**: `MainActivity` ON_START calls `ContentObserverController.enqueueResumeSyncIfStale()`, an incremental, TTL-gated re-read that closes the process-death gap (a call that completes while Orbit's process is dead is never observed live, so the next foreground catches it); (2) **content-observer trigger** (debounced); (3) **the call-log trigger** (`CallLogTriggerWorker`, 2026-10-07), which enqueues the observer's own debounced request from a process WorkManager started; (4) **manual resync** (Settings → "Sync now", full window); (5) **first-run import**. Reads since the last-sync cursor (DataStore key `last_call_log_sync_at_ms`), hands rows to `CallLogReconciler`, and after a pass that inserted rows hands the new calls to `PostCallNotifier` (NOTIF-16; never for a first import or a full resync, and a failure there never fails the sync).
- `CallLogTriggerWorker` (`@HiltWorker`, unique work `orbit.call_log_trigger`) is a one-time request constrained by `Constraints.Builder().addContentUriTrigger(CallLog.Calls.CONTENT_URI, true)`. `ContentObserverController.start()` arms it with KEEP whenever READ_CALL_LOG is granted (app start and the permission grant); each run re-arms it with APPEND_OR_REPLACE (a content URI trigger fires once per enqueue, and the running worker still holds the unique name, so KEEP would add nothing and REPLACE would cancel it) and enqueues the sync; without the permission it does neither and the chain ends. `ContentObserverController.stop()` cancels it.
- `CallLogReconciler` — matches call-log numbers against the `contact_phones` table (every number per contact — multi-number matching), normalized via `PhoneNumberNormalizer`; writes `CallEventEntity` rows idempotently.
- `CallEventRepository` — UI never touches ContentResolver directly.
- `ContentObserverController` observes `CallLog.Calls.CONTENT_URI` for live updates and triggers a sync, and arms and disarms the call-log trigger. There is no `PHONE_STATE` BroadcastReceiver; the incoming-call follow-up notification was removed (ADR 0009).

### Data model

`CallEventEntity` (as built):
```
id: Long,
contactId: Long,            // non-null — unmatched numbers are not persisted
occurredAt: Instant,
direction: CallDirection,   // OUTGOING | INCOMING
durationSeconds: Int,
source: CallSource          // CALL_LOG (a connected call) | MANUAL (a connection logged by hand)
                            // | ATTEMPT (an unanswered outgoing call, or "Couldn't reach them" logged by hand)
```

No `CallStatEntity` — stats (last-call, count, avg-duration, longest-gap) are computed in Kotlin from `CallEventEntity` rows.

### Permissions / integrations

- **Manifest:** `android.permission.READ_CALL_LOG` (dangerous, runtime request).
- `android.permission.READ_PHONE_STATE` is NOT declared — live detection uses the content observer instead.
- **ContentResolver:** `CallLog.Calls.CONTENT_URI`.
- **Storage:** encrypted Room per ADR 0002.

### Known gotchas

- Dual-SIM devices: `CallLog.Calls.PHONE_ACCOUNT_ID` differs per SIM; ingest both, surface later.
- Number matching to contact: `PhoneNumberNormalizer` + the `contact_phones` snapshot (every number per contact). `CallLogReconciler` deliberately does NOT use `ContactDao.getByPhoneNumber`.
- `READ_CALL_LOG` is a sensitive permission on Play Store — Data Safety form must justify it truthfully.
- `CallLog.Calls.DATE` is provider-time, not true call-time on some carriers; acceptable for this app's tolerance.

### Not in scope (technical)

- `PHONE_STATE` BroadcastReceiver. Content observation on `CallLog.Calls.CONTENT_URI` (`ContentObserverController`) covers live updates, and the call-log trigger (`CallLogTriggerWorker`) covers a dead process.
- Syncing call log across devices. Local-only per `features/privacy-and-lock/README.md`.

### Open technical questions

- ~~Live updates via `BroadcastReceiver` on `PHONE_STATE` vs poll-on-resume~~ — resolved: `ContentObserverController` observes `CallLog.Calls.CONTENT_URI`.
- ~~Cursor storage for incremental import~~ — resolved: DataStore key `last_call_log_sync_at_ms` in `AppPrefs`; no `ImportCursorEntity`.
