# onboarding

**Status:** in-progress
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/onboarding/`, routes + wiring in `android/app/src/main/java/app/orbit/nav/OrbitNavHost.kt` and `Routes.kt`
- Tests: `android/app/src/test/java/app/orbit/ui/screens/onboarding/` (`OnboardingFirstListGateTest`, `OnboardingPermissionsViewModelTest`)

---

## Product

### Why it exists

Onboarding is where the app earns the permissions it needs and gets the user to their first useful state. Three sensitive permissions (`READ_CONTACTS`, `READ_CALL_LOG`, plus `POST_NOTIFICATIONS` on Android 13+) must be explained in plain language. If the user declines, the app must degrade gracefully, not dead-end.

### User story

As a first-time user, I'm framed by a quiet welcome screen, walked through one permission ask at a time with concrete copy explaining what each unlocks, watch Orbit read my recent call history, review a suggested first list of the people I've actually been in touch with, and finish by shaping that list in the same configuration screen I'll use forever. In under three minutes I have something useful — and at no point am I stuck on a disabled Continue button.

### Behavior

**Steps (live in code as the 5-counted-step `OnboardingStep` enum + 8 routes; Welcome and Done are uncounted framing screens):**
1. **Welcome** — brand frame and value prop. Not a counted step.
2. **Permission · Contacts** — rationale + system prompt. Most-impactful permission first. Denial → lists can still be created; the first-list gate relaxes (see below).
3. **Permission · Call log** — rationale + system prompt. Denial → the sync gate renders its Skipped state and Orbit starts from what the user tells it.
4. **Permission · Notifications** (API 33+ only; auto-skipped below) — rationale + system prompt.
5. **Sync gate** ("Reading your call history") — blocking gate over `CallLogSyncWorker`. Indeterminate progress bar + live "Counted N calls over M contacts" while in progress. States: InProgress, Succeeded, Empty ("We'll learn as you go."), **Skipped** (call-log permission not granted — Continue stays enabled so the "Continue without it" path never dead-ends), Failed (inline retry; after one failed retry, "Continue anyway"). A slow-sync tip card appears after 10s.
6. **Preview** — H/β recency × frequency suggested first list ("Here are 5 to 10 people you've been in touch with"). Rows start all-selected; per-row checkbox opt-out plus Select all / Deselect all. Auto-skips to a blank first list when fewer than 3 candidates match. While the rank settles it renders a quiet loading skeleton under disabled CTAs ("Start blank" stays live as an exit). Both "Make this my first list" and "Start blank" create a real STATIC list at navigate-time (`createOnboardingFirstList` in `OrbitNavHost.kt`).
7. **First list** — the production List Configuration body (`ListConfigBody` + `ListConfigViewModel`) wrapped in the onboarding scaffold. Done/Add-another gate: with contacts granted, non-blank name AND ≥3 members; **with contacts denied, a non-blank name alone is enough** (the previous ≥3-member gate hard-stuck users on a step with no back and no skip). Helper text above the body names the threshold, or in the denied state sets the expectation for the empty picker. A loading skeleton covers the Room-settle window after the preview commit.
8. **Done** — confirmation screen. Owns the single transactional `setOnboardingComplete(true)` write via `OnboardingDoneViewModel.init`. Teaches the core loop ("Orbit hands you one name at a time. Call, or pass — they'll come back around.") and the swipe mechanic via a static mini-card hint flanked by "Later" (left) and "Sooner" (right). Not a counted step.

**Skip path (ONB-15).** Each permission screen has a secondary "Continue without it" CTA that opens the calm `OnboardingSkipDialog` ("Skip for now?") naming what won't work, before advancing. The sync gate is non-skippable (G2) but never blocks: Empty/Skipped/retry-failed states all leave a forward path. First-list creation is required for activation (E1) — no skip there, only the relaxed denied-state gate.

**Post-onboarding.** Done routes to Home clearing the whole back stack (A2/ONB-23 — back from Home never re-enters onboarding). `onboardingComplete` flag flips true. Subsequent cold-launches go straight to Home.

**Mid-flow resume (F-3, 2026-04-30).** Each step persists `AppPrefs.lastOnboardingStep` on entry; `AppViewModel.resolveOnboardingResume` maps it back to a start destination on relaunch. A persisted `FirstList` step resumes at the sync gate (its `{listId}` arg is not recoverable from prefs).

**Degraded-mode routing.** If the user denies CALL_LOG, the sync gate shows Skipped and onboarding completes; manual-log entry surfaces in `features/call-detection/README.md`. If the user denies CONTACTS, the preview auto-skips (no candidates) and the first-list step finishes on a name-only list; granting access later in Settings re-tightens the gate and fires the contacts ingest.

**Permanent denial.** Detected via `ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) == false` while granted is also false. CTA flips to "Open settings" → `Intent.ACTION_APPLICATION_DETAILS_SETTINGS`. Edge case: this fires false-positive on first launch with no prior denial; mitigation is accepting the noise since Android short-circuits the launcher in that case anyway.

### Acceptance criteria

- [x] Welcome screen frames the app before any permission ask.
- [x] Each permission has its own screen with concrete `effect` copy before the system dialog.
- [x] Each permission screen offers "Continue without it" with a skip-confirmation dialog naming what's lost; the sync gate and first-list step never dead-end (Skipped state / relaxed denied gate).
- [x] Permission denial produces a usable forward path; no dead-end disabled Continue — including contacts-denied, which finishes on a name-only first list.
- [x] Permanently denied permissions surface a Settings deep-link.
- [x] Voice rules: sentence case, no exclamation, no hustle framing — verified per screen.
- [x] Real list persistence via `ListRepository.create` + `addMember` (`createOnboardingFirstList`), with a due-count recompute so the first Home render is consistent.
- [x] Single transactional `setOnboardingComplete(true)` write in `OnboardingDoneViewModel.init`.
- [x] `./gradlew compileDebugKotlin` clean.
- [ ] First-useful-state reached in under 3 minutes on fresh install (needs device run).
- [x] Mid-flow crash/relaunch resumes at the persisted step via `AppPrefs.lastOnboardingStep` (F-3, 2026-04-30).
- [ ] Dark mode + 200% font scale + TalkBack pass (needs device run; `/review-change onboarding/*` is the gate).
- [ ] Welcome illustration (deferred; no asset infrastructure).
- [x] Per-list config in onboarding — the first-list step reuses the production List Configuration screen (`ListConfigBody`), superseding the old ONB-12 deferral.

### Not in scope

- Interactive tutorial after onboarding ends. The first surfaced card speaks for itself.
- Returning-user re-onboarding. Once `onboardingComplete`, the flow doesn't re-enter.
- Account creation or cloud linkage. Local-only per `features/privacy-and-lock/README.md`.
- Multi-list authoring in one pass. "Add another list" loops the first-list step one list at a time; bulk multi-list drafting was retired with the old Lists/Bulk-add steps.
- Digest-time and biometric steps. Both were removed in the post-2026-04-28 rewrite (biometric lock cut from v1; notifications are list-scoped).

### Open product questions

- Should the Welcome screen carry a visual asset (illustration, animated wordmark)? Currently text-only, which is on-voice but visually thin compared to peer onboarding (Reflectly, Day One). Defer to a later art pass — not blocking ship.

---

## Technical

### Architecture

**Two layers of shared code, then per-screen specifics.**

- **Shared chrome:** `OnboardingScaffold(step, onBack, primary, secondary, content)` provides app bar with progress + scroll body + sticky bottom CTA stack. `step = null` skips the progress indicator (used by Welcome and Done).
- **Step ordinality:** `OnboardingStep` enum (5 counted steps: PermContacts, PermCallLog, PermNotifications, Sync, FirstList) is the single source of truth for progress counters. Reordering or inserting steps is one edit, not five.
- **Per-screen ViewModels:** one VM per screen per project convention. `OnboardingPermissionsViewModel` (shared by the three permission screens and the first-list gate), `OnboardingSyncViewModel`, `OnboardingPreviewViewModel`, `OnboardingDoneViewModel`; the first-list step reuses the production `ListConfigViewModel`.

**Routes (in `Routes.kt`):**
```
OnboardWelcome       = "onboard/welcome"
OnboardPermContacts  = "onboard/permissions/contacts"
OnboardPermCallLog   = "onboard/permissions/call-log"
OnboardPermNotifs    = "onboard/permissions/notifications"
OnboardSync          = "onboard/sync"
OnboardPreview       = "onboard/preview"
OnboardFirstList     = "onboard/first-list/{listId}"
OnboardDone          = "onboard/done"
```

The picker is no longer routed through the nav graph during onboarding — the first-list step consumes the production picker via `ListConfigViewModel` inside `OnboardingFirstListScreen` / `ListConfigBody`. `OnboardFirstList` carries a `{listId}` path arg so the screen hydrates the production `ListConfigViewModel` via `SavedStateHandle`.

### Data model

- **DataStore:** `onboardingComplete: Boolean` (written once, by `OnboardingDoneViewModel.init`) and `lastOnboardingStep: String` (per-step resume breadcrumb, F-3). The old `digestHour` / `isBiometricLockEnabled` onboarding writes are gone with their steps.
- **Room:** the preview/first-list path creates exactly one `ListEntity` per pass (STATIC, `ruleTemplateId = null` — `ListConfigViewModel` seeds the "Keep in touch" template on first read) plus ≥0 `ListMembershipEntity` rows for accepted preview candidates, then recomputes the list's denormalized `dueCount`.

### Permissions / integrations

- `READ_CONTACTS` — step 1 (also gates the first-list Done threshold and triggers `ContactsIngestWorker` on grant).
- `READ_CALL_LOG` — step 2 (gates whether the sync step runs or renders Skipped).
- `POST_NOTIFICATIONS` — step 3 (API 33+ only; `OnboardingPermNotificationsScreen` auto-`onContinue()`s on `Build.VERSION.SDK_INT < TIRAMISU`).
- WorkManager — the sync gate observes `CallLogSyncWorker` WorkInfo; the contacts grant enqueues `ContactsIngestWorker`.

### Known gotchas

- "Don't ask again" is permanent for the install; detect via `ActivityCompat.shouldShowRequestPermissionRationale` returning `false` after a denial. Permanently-denied state surfaces a Settings deep-link CTA. Accept the false-positive on cold-start (no API to disambiguate first-launch from permanently-denied).
- Android 13+ requires `POST_NOTIFICATIONS` runtime request even though manifest declares it; the screen self-skips on older API levels via `LaunchedEffect(Unit) { onContinue() }`.
- First-install permission dialogs can be dismissed by swipe-away on some OEM launchers — treated as denial; the secondary "Continue without it" path covers this.
- The `setOnboardingComplete(true)` write is single-source — `OnboardingDoneViewModel.init` only. The duplicate write that lived in `OrbitNavHost`'s old BulkAdd onContinue handler was removed in the rewrite. **Do not** re-introduce parallel writes.
- The first-list gate must be permission-aware: gating Done on ≥3 members while contacts are denied hard-sticks the user (empty picker, no back, no skip). `firstListCanFinish` relaxes to name-only when `READ_CONTACTS` is denied, and `LifecycleResumeEffect` re-reads the permission so a mid-flow grant in Android Settings re-tightens the gate.
- "Start blank" / preview-accept / "Add another list" each create a real list at navigate-time. The blank path passes `name = ""`; the user's typed name is written by `ListConfigViewModel.setName` inside the first-list screen.

### Not in scope (technical)

- Telemetry on drop-off at each step. No analytics in v1.
- A/B testing different permission explainers.
- Resuming a `FirstList` step exactly in place after process death — its `{listId}` is not persisted; resume lands on the sync gate, which re-creates a list forward (deemed acceptable, no incident).

### Open technical questions

- **Welcome illustration / brand asset.** When asset infrastructure exists, swap the text-only Welcome for a centered wordmark + small mark. Until then, the text composition is on-voice and ships.

---

## Reference

- **Component reference (reused production list config):** `android/app/src/main/java/app/orbit/ui/screens/lists/ListConfigScreen.kt` (`ListConfigBody`)
- **Voice contract:** `README.md` §Content fundamentals
