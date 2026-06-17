# ADR 0003 — Biometric lock via androidx.biometric with device-credential fallback

**Status:** SUPERSEDED — biometric lock removed from v1 scope (whole-app review 2026-04-28).
**Date:** 2026-04-22
**Superseded:** 2026-04-28
**Deciders:** the maintainer
**Supersedes:** none

> **Why superseded.** The whole-app review on 2026-04-28 cut biometric lock from v1.
> Toggle-driven privacy is anti-pattern — users who'd benefit are exactly the users who'd
> forget to flip it before the moment of risk. Quick-hide on focus loss (always-on, no
> toggle, ADR-0002 + PrivacyCurtain composable) covers the realistic threat surface
> without adding a config surface to test, document, and explain. SQLCipher at-rest
> encryption (ADR 0002) is independent of this decision and stays.
>
> Net code removed: `androidx.biometric` catalog entry, `USE_BIOMETRIC` manifest line,
> `OnboardingBiometric{Screen,ViewModel}`, Settings toggle row, AppPrefs keys, and
> `AppViewModel.isBiometricLockEnabled`. The `AppViewModel.minimalModeActive` flow was
> renamed to `privacyCurtainActive` and now derives solely from foreground state.
>
> Reopening this ADR (e.g., post-launch user feedback signaling demand for an in-app
> lock) requires a successor ADR — do not edit this one back into "accepted".

---

## Context (historical, prior to supersession)

## Context

PRD §Privacy & Security specifies an optional biometric lock (off by default). Must gate app open at `MainActivity.onStart`. Must handle devices without biometric hardware gracefully — failure here is a user-locked-out scenario, not acceptable.

## Options considered

1. **androidx.biometric** (`BiometricPrompt`, authenticator `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`) — Jetpack-native, handles Class 3 biometrics plus system PIN/pattern/password fallback in one API.
2. **FingerprintManager** — deprecated since API 28. Not viable.
3. **App-level PIN** — rolls our own. Adds auth UI surface, duplicate of platform affordance, burdens the user with another secret to remember.

## Decision

**`androidx.biometric` with `DEVICE_CREDENTIAL` fallback.**

Authenticator flag: `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. `BiometricPrompt` auto-routes to device credential (PIN/pattern/password) when biometric is unavailable, missing, or fails.

Integration with ADR 0002: the Keystore key protecting the SQLCipher passphrase is created with `setUserAuthenticationRequired(true)` when biometric lock is on, `false` when off. The toggle regenerates the key and re-wraps the passphrase. Biometric is a gate on the Keystore key, not the holder of the passphrase itself.

## Consequences

- Add `androidx.biometric:biometric` to `libs.versions.toml`.
- `AndroidManifest.xml`: declare `<uses-permission android:name="android.permission.USE_BIOMETRIC" />` (permission-free at runtime on API 28+ but declared for Play Console clarity).
- `MainActivity.onStart` checks `biometricEnabled` DataStore flag; if true and not recently authenticated, shows `BiometricPrompt` before emitting state.
- Failed authentication → finish activity (user cannot enter app without auth when lock is on).
- Enabling the lock from Settings triggers Keystore key regeneration with `setUserAuthenticationRequired(true)`; disabling it regenerates with `false`. The passphrase round-trips both times.
- "Recently authenticated" window: 60 seconds — prevents re-prompting if the user briefly backgrounds the app. Configurable in Settings (future).

## Non-consequences

- Does not add a custom PIN surface — the system credential prompt is sufficient.
- Does not expose biometric enrollment UI in-app. Users enroll via system settings.
- Does not unlock the widget separately. When lock is on, widget shows "Contact" (minimal-mode visual) until app is opened and authenticated.

## Related

- ADR 0002 (SQLCipher — passphrase protected by the Keystore key this ADR gates)
- `features/privacy-and-lock/README.md`
