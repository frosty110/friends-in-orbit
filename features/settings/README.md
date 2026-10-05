# settings

**Status:** in-progress
**Last reviewed:** 2026-10-05
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/settings/` (`SettingsScreen.kt`, `PermissionsRow.kt`, `AboutSection.kt`, `LicensesDialog.kt`, `ResetDataRow.kt`/`ResetConfirmDialog.kt`, `export/` for export + import); reset behavior in `android/app/src/main/java/app/orbit/data/repository/ResetService.kt`
- Tests: `android/app/src/test/java/app/orbit/ui/screens/settings/` (`SettingsViewModelTest`, `PermissionRowActionTest`, `PickerThresholdsValidationTest`, `export/ImportViewModelTest`, `ignored/SettingsIgnoredViewModelTest`), `android/app/src/test/java/app/orbit/data/repository/ResetServiceTest.kt`, `android/app/src/test/java/app/orbit/data/dao/ContactDaoIgnoredTest.kt`

---

## Product

### Why it exists

A single place for app-wide configuration — privacy toggles, notification preferences, data controls, about. Kept quiet and small, not a power-user dashboard. The user should come here rarely; when they do, every action should feel safe and reversible (or explicitly not).

### User story

As a user, I come to Settings rarely: to manage ignored contacts, to resync the call log, to export my data, or to reset.

### Behavior

**Appearance and loading** (2026-10-05).
- **SET-09: No flash of wrong values.** Until the saved settings load, the screen shows only its app bar. It used to render defaults first (Warm theme, "Not allowed" for every permission, 90 days) and then jump to the real values (UX rubric D6). Same quiet-chrome policy as Home (ADR 0006).
- **SET-10: Plain words.** The picker-groups row reads "Groups when adding people", with "Where Commonly called, Rarely called and the others begin"; its dialog explains the groups in a sentence. It used to read "Picker thresholds / Edit chip-match thresholds". The accent dial is the shared `OrbitSlider` and tells TalkBack a colour name; "Match theme" is a 48dp target. Six themes: the five curated ones and Wallpaper (`DESIGN.md`), spaced so the sixth peeks in at phone width.

**Permissions section.**
- One status row per permission (Contacts, Call log, Notifications). The trailing action matches the actual state: **Granted** → quiet "Allowed" label, no button; **Denied** → "Allow" button that fires the runtime permission launcher directly; **Permanently denied** → "Open Android Settings" deep link (the only honest action once the OS auto-denies).

**Privacy section.**
- Ignored contacts entry — opens the management surface (sources: `features/privacy-and-lock/README.md`). Its subtitle reads "{N} ignored" or "No ignored contacts". Archived contacts are not counted, so N always equals the rows on Settings > Ignored: both read one query, `ContactDao.observeIgnored`, which excludes archived rows.

> Removed 2026-04-28 (whole-app review): biometric-lock toggle and minimal-mode toggle.
> Both features are out of v1. Quick-hide on focus loss survives as auto-only behavior
> (no toggle); see `features/privacy-and-lock/README.md`.

**Notifications section.**
- Per-list notification surfaces are configured inside each list (`features/orbit-lists/README.md`).
- No global digest setting; notifications are list-scoped only (whole-app review 2026-04-28).

**Data section.**
- Call log sync status row + "how far back to import" range row + manual resync.
- Contacts and Call history each have a "Sync now" button. Both are Secondary, not Primary: a sync is maintenance, not the screen's main action, and two Primary buttons spent the accent twice (rules.md Design 5).
- Export my data — passphrase-encrypted backup via SAF (`ACTION_CREATE_DOCUMENT`), passphrase entered in a bottom sheet.
- Import backup — SAF open-document → passphrase sheet → explicit replace-confirmation dialog → restore. Outcomes surface as snackbars ("Backup restored." / unreadable / version-too-new / apply-failed).
- Delete all data — destructive, confirmation required (see Known gotchas for the decided Keystore behavior).

**About section** (every row either does something real or says plainly that it can't yet).
- Version (from BuildConfig).
- Send feedback — `ACTION_SENDTO` mailto to the in-app support contact.
- Privacy policy — reads "Coming soon", no dead tap target (no published URL yet; RELEASE-05 owns publishing it).
- Source code — link to the public repository (source-available per PRD).
- Open source licenses — opens `LicensesDialog`, a static list kept honest by hand against `libs.versions.toml`.
- "Support the developer" (one-time purchase, optional, no feature gating) — still planned, not built.

### Acceptance criteria

- [ ] Every toggle persists immediately. No save button.
- [ ] Destructive actions show a confirmation dialog with explicit copy — not "are you sure?" but "this removes all your lists, notes, and call history from this device."
- [ ] Export button produces a file the user shares via system share sheet (SAF).
- [ ] Import restores an exported file on a second install after passphrase + explicit confirmation.
- [ ] Voice rules: sentence case, no exclamation, calm copy.
- [ ] Dark mode + 200% font scale + TalkBack pass.
- [x] Delete-all cancels all enqueued WorkManager jobs and stops content observers **before** wiping Room + DataStore. The SQLCipher passphrase and Keystore key are deliberately **kept** — see Known gotchas.

### Not in scope

- Account settings. No accounts.
- Theme pickers. OrbitTheme is one-way per design system.
- Per-contact notification overrides. Forbidden by mission principles.
- Custom icon set selection. Phosphor is the one voice.

### Open product questions

- "Support the developer" — place here or in About? Leaning About (more honest; less commercial-feeling on a settings page).
- Per-list notification controls also shown here as a flat list, or only reachable via list config? Currently only the latter; reconsider if users report friction.

---

## Technical

### Architecture

`SettingsViewModel` observes DataStore flags; exposes `Flow<SettingsState>` to the UI. Each toggle writes straight back to DataStore — no intermediate mutable state. Destructive actions route through a confirmation dialog composable.

### Data model

DataStore only: `digestHour: Int`, `callLogImportDays: Int`, `lastCallLogSyncAtMs: Long`, `isCallLogSyncEnabled: Boolean`, `pickerThresholds`. Removed 2026-04-28: `biometricEnabled`, `minimalMode`, `lastAuthMs`.

Room: untouched except by the delete-all action (which truncates all tables).

### Permissions / integrations

- Export: `Intent.ACTION_CREATE_DOCUMENT` (SAF) — no manifest permission needed.
- Import: `Intent.ACTION_OPEN_DOCUMENT` (SAF) → `ImportService` (passphrase decrypt, version check, confirm-then-apply).
- Permission rows: runtime permission launcher (Denied) or `ACTION_APPLICATION_DETAILS_SETTINGS` deep link (Permanently denied).
- Delete-all: `ResetService` — cancels WorkManager (`features/notifications/README.md`), stops content observers, truncates Room, clears DataStore. Keystore key intentionally kept (see Known gotchas).

### Known gotchas

- Delete-all must cancel scheduled WorkManager jobs before truncating Room — otherwise a digest worker fires against an empty DB and crashes. `ResetService.resetAll` orders this explicitly: cancel unique works → stop content observers → `clearAllTables()` → `AppPrefs.resetAll()`.
- **Keystore key is deliberately NOT revoked on delete-all** (decided in `ResetService.kt` — supersedes the earlier "revoke the Keystore key" spec line). The Room connection stays open for the rest of the process and the encrypted DB file persists across the reset; deleting the Keystore key or the wrapped passphrase would make that file permanently unreadable on next launch — a bricked app, not a reset. Every table is empty after the wipe, so the key protects nothing sensitive. Rotating key + passphrase + DB file together would require a close-and-reopen flow v1 does not have.
- Reset does not touch phone contacts or the system call log — only Orbit's mirror tables; the confirmation dialog says so. After reset, the next cold start re-enters onboarding (the flag was wiped).

### Not in scope (technical)

- Settings search. Flat page with clear sections is sufficient.
- Remote config / feature flags. No remote surface.

### Open technical questions

- None open.
