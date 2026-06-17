# call-detection

**Status:** in-progress
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/calllog/` (`CallLogSyncWorker`, `CallLogReconciler`, `ContentObserverController`, `PhoneNumberNormalizer`), `android/app/src/main/java/app/orbit/data/android/CallLogReader.kt`
- Tests: `android/app/src/test/java/app/orbit/calllog/` (`CallLogSyncWorkerTest`, `CallLogReconcilerTest`, `ContentObserverControllerTest`, `PhoneNumberNormalizerTest`)

---

## Product

### Why it exists

The app's core value proposition is that *it watches*. You don't have to log anything. You call someone — from the app, from the system dialer, from anywhere — and Orbit notices. Without this, the rule engine has no input and the whole loop collapses. This feature is the silent backbone.

### User story

As a user, I grant CALL_LOG permission during onboarding. From then on, the app silently keeps my orbit in sync with my real call behavior. I never tap "mark as called."

### Behavior

**Permission.** `READ_CALL_LOG` required for core functionality. Manual-log entry fallback available when denied (degraded experience; `features/onboarding/README.md` covers the denial path).

**Import window.** First install imports the last 90 days by default. User-configurable in Settings (see `features/settings/README.md`).

**Manual sync.** Settings → "resync call log." Idempotent.

**Filter.** Missed calls, declined calls, and voicemails are NOT counted — not in the domain model. Only completed calls (incoming answered, outgoing connected) get ingested.

**Direction.** Outgoing and incoming stored separately.

**Cross-list propagation.** A new call updates last-call state on every list the contact belongs to — via `features/orbit-lists/README.md`.

**Cooldown weighting.** Incoming calls count as 50% cooldown reset by default; user-configurable per rule (see `features/rule-engine/README.md`).

**Incoming call follow-up.** When an incoming call from a tracked contact completes or is missed, `features/notifications/README.md` raises a "they called you, want to call back?" prompt. If notifications are disabled, the prompt surfaces on next app open instead.

### Acceptance criteria

- [ ] First-run import of 90 days completes in < 5s on a test device with ~1000 call log rows.
- [ ] Missed / declined / voicemail filtered at query level, not post-hoc.
- [ ] Resync is idempotent — running twice produces identical state.
- [ ] Permission denial produces a usable degraded app; no dead-end UX.
- [ ] All call history persisted through encrypted Room per ADR 0002.
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
- `CallLogSyncWorker` (`@HiltWorker`, WorkManager per ADR 0004) — runs on app open, on manual resync, and on content-observer trigger. Reads since the last-sync cursor (DataStore key `last_call_log_sync_at_ms`), hands rows to `CallLogReconciler`.
- `CallLogReconciler` — matches call-log numbers against the `contact_phones` table (every number per contact — multi-number matching), normalized via `PhoneNumberNormalizer`; writes `CallEventEntity` rows idempotently.
- `CallEventRepository` — UI never touches ContentResolver directly.
- `ContentObserverController` — observes `CallLog.Calls.CONTENT_URI` for live updates and triggers a sync. There is no `PHONE_STATE` BroadcastReceiver; the incoming-call follow-up notification (`features/notifications/README.md`) is unbuilt.

### Data model

`CallEventEntity` (as built):
```
id: Long,
contactId: Long,            // non-null — unmatched numbers are not persisted
occurredAt: Instant,
direction: CallDirection,   // OUTGOING | INCOMING
durationSeconds: Int,
source: CallSource          // CALL_LOG | MANUAL (manual "log a connection" entries)
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

- `PHONE_STATE` BroadcastReceiver. Content observation on `CallLog.Calls.CONTENT_URI` (`ContentObserverController`) covers live updates.
- Syncing call log across devices. Local-only per `features/privacy-and-lock/README.md`.

### Open technical questions

- ~~Live updates via `BroadcastReceiver` on `PHONE_STATE` vs poll-on-resume~~ — resolved: `ContentObserverController` observes `CallLog.Calls.CONTENT_URI`.
- ~~Cursor storage for incremental import~~ — resolved: DataStore key `last_call_log_sync_at_ms` in `AppPrefs`; no `ImportCursorEntity`.
