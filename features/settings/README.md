# settings

**Status:** in-progress
**Last reviewed:** 2026-10-06
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/settings/` (`SettingsScreen.kt`, `SettingsViewModel.kt`, `SettingsUiState.kt`, `AppearanceSection.kt`, `PermissionsRow.kt`, `CallSyncStatusRow.kt`, `PickerThresholdsRow.kt`, `PickerThresholdsDialog.kt`, `ThresholdStepperRow.kt`, `AboutSection.kt`, `LicensesDialog.kt`, `ResetDataRow.kt`, `ResetConfirmDialog.kt`, `ImportConfirmDialog.kt`; `export/` for export + import: `ExportViewModel`, `ImportViewModel`, `ExportPassphraseSheet`, `ImportPassphraseSheet`, `ExportUiState`, `ImportUiState`; `ignored/` for the Ignored screen: `SettingsIgnoredScreen`, `SettingsIgnoredViewModel`, `SettingsIgnoredUiState`); the saved values in `android/app/src/main/java/app/orbit/data/AppPrefs.kt`; reset behavior in `android/app/src/main/java/app/orbit/data/repository/ResetService.kt` and `android/app/src/main/java/app/orbit/widget/WidgetUpdateScheduler.kt` (`refreshNow`); the export pipeline in `android/app/src/main/java/app/orbit/domain/export/` (`ExportService`, `ImportService`, `PassphraseEncryptor`, `ExportEnvelope`); copy in `android/app/src/main/res/values/strings_settings.xml`
- Tests: `android/app/src/test/java/app/orbit/ui/screens/settings/` (`SettingsViewModelTest`, `SettingsDataRowsTest`, `PermissionRowActionTest`, `PickerThresholdsValidationTest`, `export/ExportViewModelTest`, `export/ImportViewModelTest`, `ignored/SettingsIgnoredViewModelTest`), `android/app/src/test/java/app/orbit/data/AppPrefsTest.kt`, `android/app/src/test/java/app/orbit/data/repository/ResetServiceTest.kt`, `android/app/src/test/java/app/orbit/AppViewModelTest.kt` (the reset outcome it hands the Activity), `android/app/src/test/java/app/orbit/widget/WidgetUpdateSchedulerTest.kt`, `android/app/src/test/java/app/orbit/data/dao/ContactDaoIgnoredTest.kt`, `android/app/src/test/java/app/orbit/domain/export/ImportServiceTest.kt`; the per-test DataStore fixture every one of these uses is `android/app/src/test/java/app/orbit/testutil/TestDataStore.kt`

---

## Product

### Why it exists

A single place for app-wide configuration: appearance, permissions, what Orbit reads from the phone, who it leaves out, the data itself, and about. Kept quiet and small, not a power-user dashboard. The user should come here rarely; when they do, every action should feel safe and reversible (or explicitly not).

### User story

As a user, I come to Settings rarely: to pick a theme, to fix a permission, to resync the call log, to manage the people I have ignored, to export my data, or to reset.

### Behavior

The screen is seven groups, top to bottom: Appearance, Permissions, Contacts, Call history, People, Data, About. Each is described below in that order. Until 2026-10-06 the picker-groups row and the Ignored entry sat under Call history, where they read as sync settings; they are now the People group.

**Appearance** (2026-10-05).
- Theme (Warm, Cool, Forest, Plum, Mono, Wallpaper), light or dark (or the phone's setting), and the accent dial. Each choice is written as it is made; there is no save button. Six themes: the five curated ones and Wallpaper (`DESIGN.md`), spaced so the sixth peeks in at phone width. The accent dial is the shared `OrbitSlider` and tells TalkBack a colour name; "Match theme" is a 48dp target.
- **SET-09: No flash of wrong values.** Until the saved settings load, the screen shows only its app bar. It used to render defaults first (Warm theme, "Not allowed" for every permission, 90 days) and then jump to the real values (UX rubric D6). Same quiet-chrome policy as Home (ADR 0006).
- **SET-11: Error state.** If saved settings cannot be read, the screen says "Orbit couldn't load your settings", that nothing is lost, and offers Try again, instead of staying on its app bar forever.

> Superseded 2026-06-22 (THEMING): the "Theme pickers. OrbitTheme is one-way per design system." exclusion below described the first design system. Appearance is a shipped section; the exclusion is kept for the record.

**Permissions** (SET-07, SET-12, SET-14).
- One status row per permission (Contacts, Call log, Notifications). The trailing action matches the actual state: **Allowed** reads as a quiet green label, no button; **Not allowed** offers an "Allow" button that fires the runtime permission launcher directly; **Off in your phone's settings** offers "Open phone settings" (the only honest action once the OS auto-denies).
- "Off in your phone's settings" appears only after the OS has been asked once (SET-12). A fresh install reads "Not allowed" with Allow on every row.
- A permission flipped in the phone's settings while Orbit was in the background behaves, on return, like one flipped from the row: a Contacts grant registers the contacts observer and mirrors the address book; a Call log grant starts the observer and imports the window; a revocation stops the observer.
- Notifications read Allowed only when Android will actually show them (SET-14).

**Contacts** (SET-04).
- "Last synced 5 minutes ago" (or "Never synced") over a "Sync now" button that runs one forced address-book mirror. Disabled without the Contacts permission or while a sync runs.

**Call history** (SET-04).
- The same sync row for the call log: "Sync now" runs a full resync of the configured window.
- Import range: 1 month, 3 months, 6 months, 1 year. Widening it with the call log readable runs a full resync at once, so the new months show up without a second tap; narrowing changes nothing on screen. If the choice cannot be saved, "Couldn't save your change" shows, the range stays as it was and no resync runs for it.
- Call history entry: opens the log, "Calls with the people in your contacts". The log holds every call Orbit matched to a contact, on a list or not (`features/call-history/README.md`), and the row says so.

**People** (SET-10, PICK-07, IGNORE-06).
- "Groups when adding people", with "Where Commonly called, Rarely called and the others begin". Its dialog explains the groups in a sentence and shows each one as a whole sentence over a stepper: "Commonly called: the top 20%", "Rarely called: the bottom 50%", "Recently added: the last 30 days", "Long gap: no call in 90 days". Save commits all four; Cancel discards; the two percentages may not overlap ("These two groups overlap: together they can't be more than 100%."). The dialog and its values survive rotation. It used to read "Picker thresholds / Edit chip-match thresholds".
- Ignored entry: "{N} ignored" or "No one ignored". Archived people are not counted, so N always equals the rows on Settings > Ignored: both read one query, `ContactDao.observeIgnored`, which excludes archived rows.
- **Settings > Ignored** lists everyone ignored, newest first, under one line that says what ignoring means ("Ignored people don't come up on your lists or in nudges. Their calls and notes are kept."), with "Unignore" on each row and an "Unignored {name}" snackbar whose Undo re-ignores; if either write fails, "Couldn't save your change" shows, with no Undo, and nothing changes. While the list loads it shows the shared list skeleton; empty, it shows "No one ignored" and the same line; if the list cannot be read it says "Couldn't load ignored people" with Try again.

Ignoring, end to end (these IDs record what the code already cites; the surfaces that ignore and un-ignore are Contact detail, Browse, the contact picker and this screen):

- **IGNORE-01: A person starts un-ignored.** `ContactEntity.isIgnored` defaults to false and the default survives a database round trip.
- **IGNORE-02: Ignore one person.** From Contact detail's overflow or Browse's row menu (CONTACT-04, BROWSE-04). One atomic write sets `isIgnored`, `ignoredAt` and a snapshot of the person's list memberships, so the three can never disagree (`IgnoreContactUseCase`, `ContactDao.markIgnored`).
- **IGNORE-03: One tap, undoable.** Browse's long-press menu ignores in one tap and says "Ignored {name}" with Undo; the single-row path keeps the copy singular, apart from the bulk variant.
- **IGNORE-04: Ignored people never surface.** Not on Home or Card view's queue (`SurfaceNextUseCase`, `SurfaceQueueUseCase`), and not through a smart list's rule; to leave someone out of a smart list, ignore them.
- **IGNORE-05: Ignoring is live.** Someone ignored while they are surfaced drops out at once; the surface flow reacts to the flag, nothing waits for a refresh. (Archive mirrors this.)
- **IGNORE-06: Settings > Ignored.** The list above: sorted by `ignoredAt` descending, one-tap Unignore, Undo.
- **IGNORE-07: Un-ignore restores.** Memberships come back from the snapshot taken at ignore time, with drift detection for lists that changed meanwhile (`UnignoreContactUseCase`); nothing a person had is lost by ignoring them.
- **IGNORE-08: Widgets skip them.** The home-screen widgets never show an ignored (or archived) person (`WidgetSurfaceUseCase`).
- **IGNORE-09: History is truth, and stays quiet.** An ignored person's calls are still recorded, but never advance a list or a nudge (`CallLogReconciler` inserts the event and skips propagation). Where history lists them, Call history and Contact detail, they stay visible, greyed (50% avatar, subtle text, "(ignored)") and tappable. Un-ignoring from Settings > Ignored is undoable.
- **IGNORE-10: The words.** Factual and without shame framing, people not contacts: "No one ignored", "Ignored people don't come up on your lists or in nudges. Their calls and notes are kept.", "Unignore". Sentence case, no exclamation (`voice.md`).

**Data** (SET-05, SET-06, EXPORT-01 to EXPORT-03).
- Export your data: "An encrypted file, protected by a password". Tap opens the password sheet (the password typed twice), then the system's save dialog (SAF `ACTION_CREATE_DOCUMENT`); the file is written and "Saved your encrypted backup." shows. A failure says "Couldn't save the file."; the row is the way to try again.
- Import backup: SAF open-document, one password field, then an explicit replace-confirmation dialog ("4 lists and 52 people"), then the restore. Outcomes surface as snackbars ("Backup restored." / unreadable / version-too-new / apply-failed, nothing changed).
- While a backup is being written, checked or restored, or Orbit is being reset, the three Data rows wait and none of them responds to a tap. The busy row says what is happening ("Saving…", "Checking the file…", "Restoring…", "Resetting…") and the other two read "Waiting for the other backup step to finish" in place of their usual line, so a muted title is never the only sign that a row is waiting (UX rubric D8: colour is never the only signal). A second export mid-write, or a reset mid-restore, would race the file or the tables.
- Reset Orbit: destructive, confirmation required ("Every list, person, note, and call record on this phone will be erased. Your phone's own contacts and call log are not touched."). It cannot be interrupted by leaving the screen, and Back is held while it runs. Its outcome is kept until the app has acted on it: the restart into onboarding happens even if Settings was left or Orbit was in the background when the wipe finished, and if it fails, "Couldn't finish the reset. Try again." shows on whatever screen is up (see Known gotchas for the decided Keystore behavior).

**About** (SET-06, SET-13; every row either does something real or is plainly a statement).
- Version (from BuildConfig).
- "Everything stays on your phone: no cloud, no tracking." (SET-13). Not tappable: the sentence onboarding's welcome screen makes, repeated where a person checking the app's privacy looks.
- Send feedback: `ACTION_SENDTO` mailto to the in-app support contact.
- Privacy policy: opens the hosted policy in the browser (RELEASE-05 publishes it; `docs/RELEASE-HUMAN-STEPS.md`).
- Source code: link to the public repository (source-available per PRD).
- Open source licenses: opens `LicensesDialog`, a static list kept honest by hand against `libs.versions.toml`.
- "Support the developer" (one-time purchase, optional, no feature gating): still planned, not built.

### Requirements

The IDs the code cites, one definition each (rules.md, Citing conventions). SET-09 to SET-11 are defined inline above; IGNORE-01 to IGNORE-10 beside the Ignored entry.

- **SET-04: Sync status rows.** Contacts and Call history each show "Last synced {time}" ("Never synced" before the first) worded by the app's one time formatter at a fine grain (just now, minutes, hours, then days), over a "Sync now" button. Both are Secondary, not Primary: a sync is maintenance, not the screen's main action, and two Primary buttons spent the accent twice (rules.md Design 5). The time is taken when the state is built, never read in composition. Widening the call-log import window runs a full resync; narrowing does not.
- **SET-05: Encrypted export and import.** Export asks for a password twice in a bottom sheet, then hands off to SAF and writes the file through `ExportService`; import opens a file through SAF, asks once, validates the file before any destructive dialog, then confirms and applies. Each flow guards its own state (a repeat tap or a late SAF result cannot restart it) and the Data rows wait while a write runs. The export and the apply run on the application scope so leaving Settings cannot truncate a file or roll back a restore half-way (rules.md Code 6).
- **SET-06: Reset Orbit, and the About rows.** The reset cancels every unique WorkManager job, stops the content observers, wipes Room and DataStore, runs one undebounced widget refresh (WIDGET-06) and restarts the task into onboarding. It runs on the application scope, so it finishes even if the user leaves Settings mid-way. Its outcome, completed or failed, is sticky state on `ResetService` that the Activity reads through `AppViewModel`, so the restart lands after Settings is gone or when the app comes back from the background, and a failure is told to the user wherever they are (rules.md Code 3). While it runs, Settings disables the Data rows and holds Back. The Keystore key is kept (Known gotchas). About carries the version, the privacy promise, feedback, privacy policy, source code and licenses.
- **SET-07: State-matched permission rows.** Allowed / Allow / Open phone settings as the row's trailing action, from the OS reading refreshed on every resume and after every launcher result; a grant or a revocation noticed on refresh runs the same side effects as one made from the row (contacts observer + one ingest; call-log observer + full resync; stop). Permission state is read directly from the OS (ARCH-04).
- **SET-12: Off only after the OS was asked.** `shouldShowRequestPermissionRationale` is false both before the first ask and after a permanent refusal, so "Off in your phone's settings" shows only once the OS has been asked once for that permission (`AppPrefs.hasAskedFor`, written by every launcher callback); until then the row reads "Not allowed" with Allow, because a request will show the system dialog.
- **SET-13: The privacy promise in About.** "Everything stays on your phone: no cloud, no tracking.", the same sentence as onboarding, as a plain row.
- **SET-14: Notifications mean notifications.** The Notifications row reads Allowed only when `NotificationManagerCompat.areNotificationsEnabled()` is true and, on Android 13+, the permission is held. With the permission held but the app's notifications switched off (and on Android 12 and 12L, where there is no runtime permission), the row reads "Off in your phone's settings" and its button opens the app's notification settings (`ACTION_APP_NOTIFICATION_SETTINGS`), where that switch is. No channel check and no deeper link: the switch is the thing that is off.
- **EXPORT-01: The export pipeline.** Snapshot every repository (lists, memberships, contacts, phones, call events, notes, rule templates), build the envelope, serialize to JSON, encrypt, write the bytes to the SAF `Uri`. Every step throws on failure and the ViewModel turns that into one snackbar.
- **EXPORT-02: A portable, versioned envelope.** `ExportEnvelope` carries a version and the table contents, and by construction excludes the SQLCipher passphrase and the Keystore key: a backup never holds key material. A newer version than this build knows is refused with its own message.
- **EXPORT-03: One crypto site.** `PassphraseEncryptor`: PBKDF2WithHmacSHA256 (120k iterations, the count stored in the header so it can rise) to an AES-256-GCM key, the `OrbiExp1` binary format, platform `javax.crypto` only. Wrong password, tampered bytes and a truncated file each fail closed.
- **PICK-07: The picker's four thresholds.** Commonly called (top N% of call counts, 5 to 50, default 20), Rarely called (bottom N%, 10 to 90, default 50), Recently added (N days, default 30) and Long gap (N days without a call, default 90), stored in `AppPrefs`, clamped at write time, aggregated as `pickerThresholds` so the picker fans every chip from one flow. Edited in the Settings dialog above; Save commits all four, Cancel discards.

### Acceptance criteria

- [x] Chips and the accent dial persist on change; the groups dialog commits on Save and discards on Cancel. (Appearance write-throughs: `SettingsViewModelTest`; the dialog's local state: `PickerThresholdsDialog`, reviewed in the gallery.)
- [x] Destructive actions show a confirmation dialog with explicit copy: not "are you sure?" but "Every list, person, note, and call record on this phone will be erased. Your phone's own contacts and call log are not touched."
- [x] Export saves an encrypted file through the system's save dialog (SAF). (`ExportViewModelTest` for the handoff and the password's lifetime; `ImportServiceTest` for the bytes; the SAF write itself is device-only.)
- [ ] Import restores an exported file on a second install after password + explicit confirmation. JVM round-trip (`ImportServiceTest`, `ImportViewModelTest`); not verified on a second device.
- [x] Voice rules: sentence case, no exclamation, calm copy; people, not contacts (`voice.md` glossary), audited 2026-10-06.
- [x] Dark mode + 200% font scale render in the preview gallery with the 48dp and label audit clean; TalkBack itself is not verified on a device.
- [x] Reset cancels all enqueued WorkManager jobs and stops content observers **before** wiping Room + DataStore, then refreshes the widgets at once (`ResetServiceTest`). The SQLCipher passphrase and Keystore key are deliberately **kept**: see Known gotchas.
- [x] SET-11: a failing read shows the error state and Try again recovers (`SettingsViewModelTest`); the same for Settings > Ignored (`SettingsIgnoredViewModelTest`).
- [x] SET-12: a permission never asked for reads "Not allowed", and "Off in your phone's settings" only once asked (`SettingsViewModelTest`).
- [x] SET-06: the reset's outcome is readable after the fact and a failing step becomes Failed, not a crash (`ResetServiceTest`); the screen reads it as in flight and a failure never reaches the screen's own snackbar (`SettingsViewModelTest`); `AppViewModel` mirrors and clears it (`AppViewModelTest`); the waiting Data rows say why (`SettingsDataRowsTest`). The task restart itself is not verified on a device.
- [x] Code 3 on the small writes: a failed import-range write says "Couldn't save your change" and runs no resync (`SettingsViewModelTest`); a failed unignore or Undo says the same with no Undo (`SettingsIgnoredViewModelTest`).
- [x] SET-14: notifications switched off read "Off in your phone's settings" on Android 12 and on 13 with the permission held (`SettingsViewModelTest`).

### Not in scope

- Account settings. No accounts.
- ~~Theme pickers. OrbitTheme is one-way per design system.~~ Superseded 2026-06-22; see Appearance above.
- Per-contact notification overrides. Forbidden by mission principles.
- Custom icon set selection. Phosphor is the one voice.
- A global digest setting. Nudges are list-scoped; each list's nudge is configured inside the list (`features/orbit-lists/README.md`; whole-app review 2026-04-28).
- Biometric lock and the minimal-mode toggle (removed 2026-04-28). Quick-hide on focus loss survives as auto-only behavior; see `features/privacy-and-lock/README.md`.

### Open product questions

- "Support the developer": place here or in About? Leaning About (more honest; less commercial-feeling on a settings page).
- Per-list notification controls also shown here as a flat list, or only reachable via list config? Currently only the latter; reconsider if users report friction.

---

## Technical

### Architecture

`SettingsViewModel` observes DataStore flags and the OS permission reads; it exposes one `StateFlow<SettingsUiState>` (Loading / Ready / Error, ARCH-02) built from staged `combine`s of at most five flows each: the raw permission readings and the "asked once" flags resolve into the row states (SET-12, SET-14), the WorkManager unique-work flows give the two in-flight spinners, and the thresholds, ignored count and appearance ride alongside. Each appearance or threshold choice writes straight back to DataStore; there is no intermediate mutable state. Destructive actions route through a confirmation dialog composable. The reset, the export write and the import apply run on `@ApplicationScope` (rules.md Code 6). `ResetService` owns the reset's result as `ResetOutcome`, a sticky `StateFlow` cleared once acted on, rather than a one-shot event: `MainActivity` collects it through `AppViewModel` while resumed, restarts the task on Completed and publishes "Couldn't finish the reset" on the app-level `PickerCommitBus` host on Failed, so both land after the Settings ViewModel that started the reset is gone, or when the app returns from the background. Settings itself only shows the reset as in flight (`isResetting`), which disables the Data rows and holds Back. The small preference writes (`onImportDaysChanged`, the Ignored screen's unignore and Undo) are guarded the same way as the bigger ones: a failure becomes the shared "Couldn't save your change" snackbar, cancellation passes through (Code 5).

Settings > Ignored is its own route with its own ViewModel (`SettingsIgnoredViewModel`, Loading / Ready / Empty / Error), reading `ContactRepository.observeIgnored` and writing through `UnignoreContactUseCase` and `IgnoreContactUseCase` with the shared `UndoStack`.

### Data model

DataStore only (`AppPrefs`, file `orbit_prefs`): `onboarding_complete`; `call_log_import_days` (default 90, clamped 1 to 3650); `last_call_log_sync_at_ms`; `last_contacts_ingested_at`; `last_due_count_recompute_at`; the picker thresholds `commonly_called_top_pct`, `rarely_called_bottom_pct`, `recently_added_days`, `long_gap_days` (PICK-07); `color_theme`, `dark_mode`, `accent_hue` (-1 means the theme's own accent); `minimal_mode_enabled` (widget masking only, WIDGET-04); `has_asked_contacts`, `has_asked_call_log`, `has_asked_notifications` (SET-12); `last_onboarding_step` and `onboarding_list_id` (crash-resume); `nudge_last_named_{listId}` (NOTIF-15). Removed 2026-04-28: `biometricEnabled`, `minimalMode` toggle, `lastAuthMs`. Removed 2026-10-06: `call_log_sync_enabled`, which nothing read; the OS permission is the only switch (ARCH-04).

Room: untouched except by Reset Orbit (which truncates all tables) and a restore (which replaces them in one transaction).

### Permissions / integrations

- Export: `Intent.ACTION_CREATE_DOCUMENT` (SAF); no manifest permission needed.
- Import: `Intent.ACTION_OPEN_DOCUMENT` (SAF) to `ImportService` (password decrypt, version check, confirm-then-apply). The mime filter is `*/*` because providers report the `.bin` inconsistently; validation is on the bytes.
- Permission rows: runtime permission launcher (Not allowed) or `ACTION_APPLICATION_DETAILS_SETTINGS` (Off in your phone's settings); the Notifications row opens `ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE` instead (SET-14).
- Reset Orbit: `ResetService` cancels WorkManager (`features/notifications/README.md`), stops content observers, truncates Room, clears DataStore, then `WidgetUpdateScheduler.refreshNow` (`features/widgets/README.md`, WIDGET-06). Keystore key intentionally kept (see Known gotchas).

### Known gotchas

- Reset Orbit must cancel scheduled WorkManager jobs before truncating Room, otherwise a worker fires against an empty DB and crashes. `ResetService.resetAll` orders this explicitly: cancel unique works, stop content observers, `clearAllTables()`, `AppPrefs.resetAll()`, `refreshNow`.
- **Keystore key is deliberately NOT revoked on reset** (decided in `ResetService.kt`; supersedes the earlier "revoke the Keystore key" spec line). The Room connection stays open for the rest of the process and the encrypted DB file persists across the reset; deleting the Keystore key or the wrapped passphrase would make that file permanently unreadable on next launch, a bricked app, not a reset. Every table is empty after the wipe, so the key protects nothing sensitive. Rotating key + passphrase + DB file together would require a close-and-reopen flow v1 does not have.
- Reset does not touch phone contacts or the system call log, only Orbit's mirror tables; the confirmation dialog says so. After reset, the next cold start re-enters onboarding (the flag was wiped).
- The reset's outcome is a `StateFlow`, not a `SharedFlow` with replay. Until 2026-10-06 it was a `SharedFlow` without replay collected only by the Settings screen, so a user who backed out or backgrounded the app during the wipe stayed in a live app over an empty database with the onboarding flag cleared, and a failure in that window reached no one. `replay = 1` alone would not do either: the process survives the task restart, so a replayed completion would reach the next subscriber and restart the app a second time. The collector clears the state before it restarts.
- The widget refresh after a reset is the one undebounced refresh in the app (`WidgetUpdateScheduler.refreshNow`, REPLACE, no delay). It is safe only there, because `cancelAll` ran moments before so no debounced work is in flight to clobber; everywhere else the 30 second debounce (WIDGET-05) is the contract.
- "Last synced" is worded against the `now` in the state, taken when the state is built. It refreshes when a sync starts or finishes (the in-flight flag and the timestamp both rebuild the state), not on a timer; a screen left open for ten minutes keeps saying "5 minutes ago" until something changes. The row reads no clock itself, so the gallery renders a fixed label.
- Tests: `AppPrefs` takes its `DataStore` in the primary constructor so a test can give each method its own store on a scope it cancels (`testutil/TestDataStore.kt`). The production delegate is one store per process that is never closed, and the unit tests fork one JVM per class, so sharing it across methods let one stranded write block every later one (the suite's 30 second timeouts, patched three times before the fixture changed on 2026-10-06).

### Not in scope (technical)

- Settings search. Flat page with clear sections is sufficient.
- Remote config / feature flags. No remote surface.

### Open technical questions

- None open.
