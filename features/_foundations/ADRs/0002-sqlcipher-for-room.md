# ADR 0002 — Encrypted Room via SQLCipher, passphrase bound to Android Keystore

**Status:** accepted
**Date:** 2026-04-22
**Deciders:** the maintainer
**Supersedes:** none

## Context

Project convention requires encrypted storage for user-private data (call history, notes, list membership). Room ships unencrypted by default. Must be resolved before the first real user data lands — i.e., before call-detection (ingests call log) and contacts-ingestion (ingests contact metadata) ship. The Play Store Data Safety form will claim encryption at rest; the claim must be true.

## Options considered

1. **SQLCipher + androidx.sqlite** — industry standard. Room integration supported via `SupportFactory`. Adds ~2MB to APK. Passphrase management is the load-bearing design question.
2. **Jetpack Security (`EncryptedFile`)** — encrypts flat files only, not Room. Emulating this for Room requires export-on-write; fails under concurrent access.
3. **Ship unencrypted, rely on device-level full-disk encryption** — simplest. Violates stated rule; fails Data Safety promise on devices without FDE enabled (e.g., rooted devices, older hardware).

## Decision

**SQLCipher, with the database passphrase held in Android Keystore.**

Passphrase flow:
1. On first install, generate 32 random bytes via `SecureRandom`.
2. Wrap those bytes with a Keystore-bound AES-GCM key (created via `KeyGenParameterSpec` with `setUserAuthenticationRequired(false)` by default).
3. Store the wrapped ciphertext in DataStore (encrypted at rest regardless).
4. On DB open, unwrap the passphrase using the Keystore key, pass to `SupportFactory`.
5. When biometric lock is enabled (see ADR 0003), the Keystore key is regenerated with `setUserAuthenticationRequired(true)`, re-wrapping the same passphrase. The DB passphrase itself does not rotate; only the key protecting it does.

## Consequences

- Add `net.zetetic:android-database-sqlcipher` and `androidx.sqlite:sqlite` to `libs.versions.toml`.
- Room builder uses `SupportFactory(passphrase)`.
- First-run DB creation must occur *after* Keystore key generation.
- Loss scenarios are explicit: device reset → Keystore key lost → passphrase unrecoverable → DB unreadable. This is the correct security behavior; export flow (see `features/privacy-and-lock/README.md`) is the user's recourse.
- Data Safety form: "Data encrypted at rest: yes, AES-256 via SQLCipher with Android Keystore-bound key."
- On acceptance (already applied): row appears in the dependency catalog under "Accepted via ADR, pending wiring."
- On wiring: move to "Currently in catalog" in the same commit that adds SQLCipher to `libs.versions.toml`.

## Non-consequences

- Does not mandate encrypting DataStore prefs (settings flags only — no PII).
- Does not introduce a user-chosen master passphrase. Keystore is the user's authentication surrogate.
- Does not preclude future migration to a user-passphrase-wrapped key for export interop — that is a separate ADR when it becomes needed.

## Threat model — residual risks (accepted)

**Plaintext passphrase residue in the JVM heap during DB open.** `DatabaseKeyProvider.getOrCreatePassphrase` returns the plaintext `ByteArray` to `DatabaseFactory.create`, which passes it to `SupportOpenHelperFactory` (SQLCipher). `SupportOpenHelperFactory(..., clearPassphrase = true)` zeros the caller-owned reference after the DB opens, and `DatabaseFactory.create` wraps the open in a `finally { passphrase.fill(0) }` belt-and-braces. However, the JCE `Cipher.doFinal(plaintext)` call in `unwrap()` internally copies the array into JCE-owned buffers that are **not** zeroed on caller instruction — those copies persist in the heap until GC runs.

This is textbook "Java crypto can't truly zero memory" territory. Android does not expose `mlock`, so even if we zeroed the byte array, pages could have already been swapped. Mitigations we do apply:

- Passphrase lifetime is bounded to a single `DatabaseFactory.create` call at process start.
- App runs under standard Android process isolation (per-UID sandboxing).
- `SupportOpenHelperFactory(..., clearPassphrase = true)` clears SQLCipher's own copy.
- `finally { passphrase.fill(0) }` zeros the caller reference.

**What we're NOT doing:** migrating to a `CharArray`-based API path. SQLCipher's public surface is ByteArray; switching costs maintenance for no meaningful defense gain on Android (the JCE copy step is the load-bearing leak, not the caller buffer).

Accepted as residual. Recipients of this risk: the threat model for a compromised device (rooted, post-jailbreak, or with unrestricted adb) is already "lose the Keystore key → lose the DB" per §Decision; heap residue during the ~1–3 ms open window is a negligible amplification.

## Related

- ADR 0003 (Biometric — uses `setUserAuthenticationRequired(true)` to gate the same Keystore key)
- `features/privacy-and-lock/README.md`
- Dependency catalog (`android/gradle/libs.versions.toml`)
