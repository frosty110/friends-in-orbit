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
   long call: `adb shell am kill app.orbit.debug` (or swipe it from recents,
   then `adb shell am force-stop app.orbit.debug`).
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

- [ ] `:app:compileDebugKotlin` passes
- [ ] `:app:testDebugUnitTest` green (esp. the tests in §1)
- [ ] Manual 2a reproduced: backgrounded/killed-process completed call appears on next foreground
- [ ] Manual 2b: contacts resync runs and surfaces a new contact
- [ ] Manual 2c: deleting a device contact does not lose Orbit history/notes
