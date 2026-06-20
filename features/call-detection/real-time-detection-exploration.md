# Exploration: real-time / in-call detection

**Status:** exploration (no code committed for the real-time path)
**Raised by:** user report — "I made a call outside the app; an hour in, still on
the call, the app hasn't updated."
**Last reviewed:** 2026-06-20

This note captures *why* an in-progress call is invisible today, what a real-time
path would cost, and a recommendation. It is a design memo, not a commitment.

---

## Why an active call shows nothing today

Orbit detects calls by observing the **system call log**
(`CallLog.Calls.CONTENT_URI`) via `ContentObserverController`, then reconciling
new rows in `CallLogSyncWorker` → `CallLogReconciler`.

The Android telephony stack writes a `CallLog.Calls` row **when a call ends**, not
when it starts or while it is connected. So during a live call there is no row to
read — nothing is "missing", the data simply does not exist yet. This is by
design and documented in `README.md` ("Real-time in-call overlays or
notifications" → Not in scope; "There is no `PHONE_STATE` BroadcastReceiver").

Two consequences:

1. **In-call:** impossible to reflect with the call-log approach, full stop.
2. **Just-ended:** detection fires only if a live process holds the observer
   registration at the moment the row lands. During a long backgrounded call the
   OS often kills Orbit's process, so the row is written with no observer
   listening.

Consequence (2) is now mitigated **without** real-time detection by the
resume-sync added alongside this note (`ContentObserverController.enqueueResumeSyncIfStale`,
fired from `MainActivity` ON_START): the next time the app is foregrounded it
re-reads the call log incrementally and picks up the completed call. The manual
"Sync now" button already forces a full re-read.

## What real (live) in-call detection would require

To reflect a call *while it is happening*, Orbit would have to observe phone
state directly rather than the call log:

| Option | Mechanism | Cost / risk |
| --- | --- | --- |
| `READ_PHONE_STATE` + `PHONE_STATE` `BroadcastReceiver` | OS broadcasts `RINGING` / `OFFHOOK` / `IDLE` transitions | New **sensitive** runtime permission; manifest receiver; background-start limits on modern Android make a reliable foreground reaction hard. |
| `TelephonyCallback` / `PhoneStateListener` (registered from a foreground service) | Live callbacks while registered | Requires a **foreground service** (persistent notification) to survive — heavy for a "call your friends" app; battery + UX cost. |
| `READ_PHONE_NUMBERS` / `CallScreeningService` / `InCallService` | Default-dialer-class APIs | Requires becoming (or integrating with) the dialer role; far out of scope. |

### Hard blockers, not just effort

- **Play Store policy.** `READ_PHONE_STATE` / call-related permissions are
  restricted. The Data Safety form and a permissions-declaration review would
  both need updating, and "show the user their own call activity" is a weak
  justification when the call log already provides it post-hoc. High rejection
  risk for marginal value.
- **The number isn't guaranteed.** `PHONE_STATE` broadcasts do not reliably
  include the remote number on modern Android without additional sensitive
  permissions, so we might detect "a call is happening" but not *who* — useless
  for Orbit's contact-centric model.
- **Privacy stance.** `privacy-and-lock/README.md` and the PRD lean hard on
  "minimum sensitive permissions, local-only." A live phone-state listener cuts
  against that positioning.

## Recommendation

**Do not build live in-call detection for v1.** The user-visible problem
("completed calls don't show up reliably") is the part worth fixing, and it is
addressed by resume-sync + manual resync on the existing, already-justified
`READ_CALL_LOG` permission — no new permission, no policy exposure.

If a genuine "they're calling you right now" feature is ever prioritized, scope
it as its own PRD item with: an explicit user opt-in, a foreground-service
design, a `READ_PHONE_STATE` Data Safety justification, and a fallback for when
the remote number is withheld. Until then, "Orbit notices after the call ends"
is the contract, and it should be stated plainly in onboarding copy so users
don't expect a live indicator.

## Acceptance for the shipped (non-real-time) mitigation

- [x] Completed call made with the app backgrounded/killed appears after the
      next foreground (resume-sync), without a manual tap.
- [x] Resume-sync is incremental and TTL-gated so rotation/theme churn does not
      spam WorkManager.
- [x] Manual "Sync now" (call log) and "Sync contacts" both force a re-read.
- [ ] Onboarding copy sets the "detected after the call ends" expectation
      (follow-up; copy change in `features/onboarding`).
