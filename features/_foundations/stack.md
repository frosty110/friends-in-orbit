# Technical stack

**Status:** active
**Last reviewed:** 2026-04-22
**Canonical for:** stack summary (platform, language, catalog overview)
**Ground truth:** `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`
**Related:** `features/_foundations/ADRs/0001`–`0004`

This doc is a **summary**. When it disagrees with the catalog, the catalog wins and this doc is wrong (principle III — Verifiability).

## Platform

- **Target:** Android only (iOS doesn't allow call log access per §Platform & Stack)
- **Min SDK:** 31 (Android 12)
- **Target / Compile SDK:** 35
- **JDK:** 17

## Language & UI

- **Language:** Kotlin 2.0.21
- **UI:** Jetpack Compose — BOM 2024.12.01, Material 3, Compose plugin 2.0.21
- **Navigation:** Jetpack Navigation Compose 2.8.5
- **Image loading:** Coil 2.7.0 (Coil 2 — `coil.**` packages, not Coil 3)
- **Async:** Kotlin Coroutines + Flow (transitive via lifecycle-runtime-ktx 2.8.7)

## Storage

- **Database:** Room 2.6.1 (relational data — contacts, calls, lists, notes)
- **Key-value prefs:** DataStore Preferences 1.1.1 — small flags only (onboarding complete, digest hour, picker thresholds, call-log sync state). Never duplicate what's in Room.

## Build

- **AGP:** 8.7.2
- **KSP:** 2.0.21-1.0.28
- **Gradle wrapper:** 8.11

## Accepted via ADR, pending wiring

| Library | ADR | Summary |
|---|---|---|
| Hilt | [0001](ADRs/0001-hilt-vs-manual-di.md) | DI across UI → VM → Repo → DAO |
| SQLCipher + androidx.sqlite | [0002](ADRs/0002-sqlcipher-for-room.md) | Encrypted Room; passphrase in Android Keystore |
| WorkManager | [0004](ADRs/0004-workmanager-scheduling.md) | Periodic scheduling for digest + list prompts |

> ADR 0003 (androidx.biometric) was superseded 2026-04-28 — biometric lock is out of v1.

Until wired, treat as decided but not yet compiled against. Catalog adds land in the same commit as the code that uses them.

## Still pending (no ADR yet)

| Library | Purpose | Note |
|---|---|---|
| androidx.lifecycle-runtime-compose | `collectAsStateWithLifecycle()` | Use `collectAsState()` until decided. |
| kotlinx-immutable-collections | Stable collections for Compose skipping | Prefer `@Immutable` on data classes until needed. |

## Rationale (from PRD)

Native Kotlin was chosen over Flutter/React Native because:
1. Call log and contacts APIs are Android-native; abstraction layers add bug surface.
2. AI code generation has strong training signal for Kotlin + Jetpack Compose + Android APIs.
3. Cross-platform value proposition is moot since iOS can't access call logs anyway.
4. Background services and notification APIs are first-class native.

## Architecture layers

```
UI (Compose screens)
  ↓ event callbacks, state-hoisting
ViewModel (per screen)
  ↓ Flow<State>
Repository
  ↓ suspend / Flow
DAO (Room) + Android API wrappers (CallLog, Contacts)
```

One source of truth per piece of state. UI never touches Room directly.
