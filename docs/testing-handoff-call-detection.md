# Testing handoff — call-detection resume sync & manual contacts resync

**Branch:** `claude/folder-structure-testing-fup7hz`
**Why this exists:** changes were authored in a remote environment with **no
Android SDK**, so they were never compiled or run. This doc is the checklist to
validate them locally before merge.

## What changed (and the files to look at)

| Area | Change | Key files |
| --- | --- | --- |
| Resume sync | Re-read the call log on every app foreground (TTL-gated) so a call that completed while the process was dead still surfaces | `calllog/ContentObserverController.kt` (`enqueueResumeSyncIfStale`), `MainActivity.kt` (ON_START) |
| Manual contacts resync | New Settings → Contacts → "Sync contacts" row | `ContentObserverController.kt` (`enqueueImmediateContactsIngest`), `SettingsViewModel.kt` (`onManualContactsResync`), `SettingsUiState.kt`, `SettingsScreen.kt`, `CallSyncStatusRow.kt` (`ContactsSyncRow`) |
| Reconcile-not-overwrite | No code change — verified existing delta-sync never deletes; documented | `features/call-detection/README.md`, existing `IngestPhoneContactsUseCase.kt` |
| Real-time exploration | Design memo, no code | `features/call-detection/real-time-detection-exploration.md` |

## 1. Build + unit tests (do this first)

The repo has no `ANDROID_HOME` wired in fresh clones. One-time setup:

```sh
# Point Gradle at your SDK (or set ANDROID_HOME in your env)
cp android/local.properties.example android/local.properties   # then edit sdk.dir=
```

From `android/`:

```sh
./gradlew :app:compileDebugKotlin          # fastest "does it compile" gate
./gradlew :app:testDebugUnitTest           # full JVM/Robolectric suite
./gradlew ktlintCheck                       # style (CI reports, doesn't fail)
```

New/changed tests to confirm pass:

- `app/orbit/calllog/ContentObserverControllerTest.kt`
  - `enqueueResumeSyncIfStale_withoutCallLogPermission_enqueuesNothing`
  - `enqueueResumeSyncIfStale_withPermission_enqueuesCallLogSync`
  - `enqueueResumeSyncIfStale_isTtlGated_withinWindow`
  - `enqueueImmediateContactsIngest_enqueuesContactsWork`
  - `companion_constants_are_canonical` (now also asserts `RESUME_SYNC_TTL_MS`)
- `app/orbit/ui/screens/settings/SettingsViewModelTest.kt`
  - `onManualContactsResync requires granted contacts permission`

Run just the call-detection slice while iterating:

```sh
./gradlew :app:testDebugUnitTest --tests "app.orbit.calllog.ContentObserverControllerTest" \
  --tests "app.orbit.ui.screens.settings.SettingsViewModelTest"
```

If compilation fails, the most likely spots (review first): the staged
`combine(...)` in `SettingsViewModel` (arity), the new `SettingsUiState.Ready`
fields (defaulted, so fixtures should still build), and the `PermissionStatus`
enum comparison in `SettingsScreen` (`==`, not `is`).

## 2. Manual verification on device/emulator

Install (scripts in root `package.json`):

```sh
yarn emu:run     # emulator
# or
yarn phone:run   # USB device
yarn phone:logcat   # watch app.orbit logs while testing
```

Useful log tags (Timber): `calllog` and `contacts_ingest`.

### 2a. Resume sync closes the process-death gap (the original bug)

1. Grant Contacts + Call log permissions; let the first import finish.
2. Background Orbit. Kill its process to simulate the OS reclaiming it during a
   long call: `adb shell am kill io.github.frosty110.orbit.debug` (or swipe it from recents,
   then `adb shell am force-stop io.github.frosty110.orbit.debug`).
3. Place a real **completed** call to a tracked contact from the **system
   dialer** (must connect; missed/declined are intentionally ignored). Hang up.
4. Foreground Orbit. **Expected:** logcat shows `enqueued_resume_sync` then a
   `calllog sync_complete … inserted=1`, and the call appears in
   Settings → Call history within a few seconds — no manual tap.
5. Rotate the device or toggle dark mode a few times quickly. **Expected:** no
   repeated `enqueued_resume_sync` within ~60s (the in-memory TTL absorbs the
   `ON_START` churn).

### 2b. Manual contacts resync

1. Settings → Contacts → "Sync contacts". **Expected:** button shows
   "Syncing…" + spinner, logcat shows `enqueued_contacts_ingest forced=true
   expedited=true` then `ingest_complete`. The "Last synced …" line updates.
2. Add a brand-new contact in the system Contacts app, then tap "Sync contacts".
   **Expected:** the new contact becomes pickable in Orbit immediately (this is
   the forced path bypassing the 24h ingest TTL).
3. Revoke READ_CONTACTS in system settings, return to Orbit. **Expected:** the
   "Sync contacts" button is disabled.

### 2c. Reconcile-not-overwrite (the "new phone" concern)

No new code, but worth a live sanity check:

1. Note a contact that has Orbit call history and an ignore flag / note.
2. Delete that contact from the **system** address book, then tap "Sync
   contacts". **Expected:** the contact is **not** removed from Orbit — it shows
   as orphaned/ "(ignored)" as applicable, and its call history + note survive.
3. Re-add the contact (same number) in the system address book and resync.
   **Expected:** it re-links to the same Orbit row (no duplicate), orphan flag
   clears. This is the new-phone restore path.

## 3. Known limitations (by design, not bugs)

- An **in-progress** call never shows until it ends — Android writes the
  call-log row on hang-up. See `features/call-detection/real-time-detection-exploration.md`.
- Resume sync is incremental; if you need a full re-read use Settings → Call
  history → "Sync now".

## 4. Sign-off checklist

- [x] `:app:compileDebugKotlin` passes
- [x] `:app:testDebugUnitTest` green (esp. the tests in §1)
- [x] Manual 2a reproduced: backgrounded/killed-process completed call appears on next foreground
- [x] Manual 2b: contacts resync runs and surfaces a new contact
- [x] Manual 2c: deleting a device contact does not lose Orbit history/notes

## 5. Validation results — 2026-06-19 (emulator `orbit`, Android 14 / API 34)

Validated on `main` (the branch merged via PR #4: commits `fd0bd85` + `4370cf9`).

**§1 — build + unit tests:** `compileDebugKotlin` clean (warnings only). Full
`testDebugUnitTest` **648 tests, 0 failures, 0 errors** (2 skipped), 97 suites.
The §1-named tests all present and passing — `ContentObserverControllerTest`
7/7 (incl. the three resume-sync TTL tests + `companion_constants_are_canonical`)
and `SettingsViewModelTest` 11/11 (incl. `onManualContactsResync requires
granted contacts permission`).

**§2a — resume sync closes the process-death gap (the original bug):** PASS.
With the process killed (`am kill`, process confirmed dead), seeded a completed
outgoing call to a tracked contact, then foregrounded:
```
D/calllog  enqueued_resume_sync
I/calllog  reconcile_complete scanned=1 inserted=1 skipped=0 propagated=1
I/calllog  sync_complete full=false scanned=1 inserted=1 skipped=0 propagated=1
```
`full=false` confirms the incremental path. UI: the contact flipped from "Never
called" → **"Last call: today"** with no manual tap. TTL gate: 4 dark-mode
toggle cycles forced repeated activity recreation (IME + renderer churn visible
in logcat) yet produced **0** additional `enqueued_resume_sync` — the in-memory
TTL absorbed the ON_START churn.

**§2b — manual contacts resync:** PASS. The Settings → Contacts row ("Sync now")
fires `enqueued_contacts_ingest forced=true expedited=true` → `ingest_complete`
(the manual path is `expedited=true`; the observer path is `expedited=false` —
the distinguishing signature). A newly-inserted contact ("Zelda Testbird")
produced `ingest_complete … inserted=1` and became pickable in in-app search
("Never called / Add to list"). Revoking READ_CONTACTS disables the button
(scoped correctly — the Call-history "Sync now" stayed enabled); re-granting via
the in-app Allow flow restores it.
  - Minor observation (not a blocker): re-granting READ_CONTACTS via the in-app
    flow did not visibly re-trigger a contacts ingest in logcat ("Last synced"
    did not reset). The enqueue-on-grant contract is covered by the unit test
    `onPermissionResult Granted … enqueues work`; it was not observed firing
    on device. Worth a glance next pass.

**§2c — reconcile-not-overwrite (the "new phone" concern):** PASS. A contact
with Orbit call history, hard-deleted from the device address book, was marked
`ingest_complete … orphaned=1` (NOT removed) — it still appeared in Orbit with
its call history intact. Re-adding the same number produced
`ingest_complete … inserted=0 … restored=1`: the orphaned row re-linked with
**no duplicate** (in-app search showed exactly one row, history preserved).
  - Note: verified history survival (the relational data on the same Room rows);
    did not separately add/verify a per-contact note.

**Test-state left on the emulator:** added contact "Zelda Testbird"
(+15550199001); the original "Matt Kinni" (account_id=3, raw_contact 5001) was
deleted and re-added under a temporary `app.orbit.test` account (raw_contact
5332); one seeded completed call-log row to +12035853645. Re-seed/clean if a
pristine provider state is needed.
