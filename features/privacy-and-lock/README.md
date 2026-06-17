# privacy

**Status:** in-progress (was `privacy-and-lock` until 2026-04-28)
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/data/keystore/DatabaseKeyProvider.kt` (Keystore + SQLCipher passphrase wrapping), `android/app/src/main/java/app/orbit/ui/components/PrivacyCurtain.kt` (quick-hide composable), `android/app/src/main/java/app/orbit/AppViewModel.kt` (privacy-curtain flow), `android/app/src/main/java/app/orbit/domain/export/` (`ExportService`, `ImportService`, `PassphraseEncryptor`, `ExportEnvelope`)
- Tests: `android/app/src/test/java/app/orbit/domain/export/ImportServiceTest.kt`, `android/app/src/test/java/app/orbit/ui/screens/settings/export/ImportViewModelTest.kt`

> **2026-04-28 scope change.** Biometric lock and the user-toggled "minimal mode" are removed from v1. ADR 0003 is superseded; the manifest permission, catalog entry, onboarding step, settings toggles, and DataStore keys are all deleted. Privacy substrate is now: encrypted-at-rest (SQLCipher + Keystore) plus auto quick-hide on focus loss. No user-facing privacy toggles.

---

## Product

### Why it exists

Orbit holds sensitive signal: who matters to the user, how often they reach out, their private notes. Recovery-context users in particular need to trust that a shoulder-surf, a lost phone, or a curious friend won't expose their relationships. Privacy is not a feature here — it is the substrate on which the rest of the app rests. If this fails, the product fails.

### User story

As a user, I trust that my data is encrypted on my device and never leaves it. When I switch apps or my phone is taken from me, list and contact names auto-anonymize so a glance can't reveal my relationships. I can export my data at any time, encrypted with my own passphrase.

### Behavior

**Encrypted storage at rest.** All Room data encrypted via SQLCipher with a passphrase wrapped by an Android Keystore key. See ADR 0002. Notes, call history, list memberships all covered. Settings DataStore prefs are not PII and are not additionally encrypted.

**Quick-hide on focus loss.** When the app loses focus (home button, app switcher, incoming call), list names and contact names auto-anonymize everywhere they render — contact names to "Contact", list names to "List" (2026-06-09: list names stay masked because they are user-authored and relationship-revealing, but "Contact" was the wrong noun for them). Avatar inputs are masked alongside the text: initials derive from the masked name and contact photos are suppressed. Restores on focus regain. No config — always on. Implementation: `AppViewModel.privacyCurtainActive` (a `StateFlow<Boolean>` derived from foreground state) provides through `LocalPrivacyCurtain`; consumer composables apply the masking when `.current` is true.

**Manual export.** Encrypted JSON file, protected by a user-chosen export passphrase (distinct from the Keystore-bound DB passphrase). User-owned, shareable to another device for re-import. No automatic cloud backup.

**No cloud sync. No analytics. No telemetry.** Per PRD §v1 Scope and §Privacy & Security.

### Acceptance criteria

- [ ] Quick-hide triggers on `onPause` / `ON_STOP`, restores on `onResume` / `ON_START`, verified manually and via `adb shell input keyevent HOME`.
- [ ] Encrypted Room DB file is unreadable by `sqlite3` CLI on a rooted device.
- [ ] Export produces a file that re-imports cleanly on a second install.
- [ ] Data Safety form reflects: local-only, encrypted, user-exportable, no cloud sync.
- [ ] Widget renders in privacy-curtain visual ("Contact") when the app is backgrounded (per `features/widgets/README.md`).
- [ ] FLAG_SECURE is set on `MainActivity` in release builds so the recents thumbnail can't leak names. (Already shipped.)

### Not in scope

- In-app PIN or biometric lock. Removed 2026-04-28; reopening this requires a successor ADR.
- User-toggled "minimal mode". Removed 2026-04-28 — quick-hide on focus loss is the always-on equivalent.
- Self-destruct / panic-wipe feature. Deletion is explicit from Settings only.
- Cloud backup. Local-only in v1.
- Telemetry, even anonymous. Zero in v1.
- Per-list encryption passphrase. One DB passphrase covers all.

### Open product questions

- Export passphrase UI: require confirmation re-entry, or single-entry with visibility toggle? Leaning single-entry with visibility toggle.

---

## Technical

### Architecture

- **`DatabaseKeyProvider`** — wraps + unwraps the SQLCipher passphrase via an Android Keystore AES-GCM key. `setUserAuthenticationRequired(false)` (no biometric gate). Single class in the codebase that ever handles the plaintext passphrase. See ADR 0002.
- **`PrivacyCurtain` (composable + `LocalPrivacyCurtain` CompositionLocal)** — renders names as the literal string "Contact" when active. Driven by `AppViewModel.privacyCurtainActive`, which is `StateFlow<Boolean>` over `MainActivity`'s lifecycle (`ON_STOP` → true, `ON_START` → false). `staticCompositionLocalOf` because the value changes infrequently.
- **`EncryptedDatabase`** — SQLCipher `SupportFactory` wired to Room. Passphrase unwrapped from DataStore via Keystore key on DB open per ADR 0002.
- **`ExportService`** — snapshots all Room tables into an `ExportEnvelope` (kotlinx-serialization JSON), encrypts via `PassphraseEncryptor` (PBKDF2WithHmacSHA256, 120k iterations → AES-256-GCM, versioned `OrbiExp1` binary format — raw `javax.crypto`, no Jetpack Security dependency), writes via SAF (`Intent.ACTION_CREATE_DOCUMENT`).
- **`ImportService`** — inverse: SAF Uri + passphrase → decrypt → version check → explicit confirm → apply. Wrong passphrase / unreadable file / newer envelope version each surface distinct outcomes.

### Data model

No new Room entities. No DataStore flags introduced by this feature in v1.

### Permissions / integrations

- **Android Keystore:** AES-GCM key wrapping the SQLCipher passphrase.
- **`javax.crypto` (platform):** PBKDF2 + AES-GCM for export encryption — Jetpack Security was not adopted; `PassphraseEncryptor` is the single crypto site for exports.
- **Storage Access Framework:** export / import file pickers.

### Known gotchas

- The privacy-curtain `CompositionLocal` value must be threaded from `MainActivity.setContent` so every screen reads the same source. A per-screen ViewModel state flow would create a one-frame "flash of name" before the flow update arrives.
- The recents screenshot: `FLAG_SECURE` on `MainActivity` (release builds only — debug screenshots keep flowing for the screenshot-review workflow).
- Deleting all data must cancel all enqueued WorkManager jobs **before** the wipe. The Keystore key and wrapped passphrase are deliberately **not** revoked (decided in `ResetService.kt`): the Room connection stays open for the rest of the process and the encrypted DB file persists, so revoking the key would brick the next launch rather than reset it — and post-wipe the key protects empty tables. Rotating key + passphrase + DB file together needs a close-and-reopen flow v1 doesn't have. See `features/settings/README.md` §Known gotchas.

### Not in scope (technical)

- Custom crypto. Use SQLCipher + Jetpack Security — no hand-rolled AES.
- Syncing the Keystore key across devices. One device, one key.
- Hardware-backed attestation. Not needed for a local-first app.

### Open technical questions

- ~~Export encryption library: Jetpack Security `EncryptedFile` vs raw `Cipher` with AES-GCM?~~ Resolved: raw `Cipher` AES-GCM via `PassphraseEncryptor` (versioned binary format, no extra dependency).
- ~~PBKDF2 iteration count?~~ Resolved: 120,000 (`PassphraseEncryptor.DEFAULT_ITERATIONS`); the count is stored in the file header so it can rise without breaking old exports.
