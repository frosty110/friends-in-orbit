# ADR 0001 — Dependency injection: Hilt

**Status:** accepted
**Date:** 2026-04-22
**Deciders:** the maintainer
**Supersedes:** none

## Context

Multiple ViewModels, a Repository layer, Room DAOs, and Android API wrappers need wiring. Current code uses manual constructor injection at `OrbitApp.kt` and `MainActivity.kt`. Without a DI framework, the composition root becomes a growing god-object as features are added.

## Options considered

1. **Hilt** — Google-supported, annotation-driven, native integration with Jetpack ViewModel and WorkManager. Compile-time validation via KSP.
2. **Manual constructor injection** — No new dependency. Grows composition-root boilerplate linearly with features. No scoping primitives.
3. **Koin** — Kotlin-native, runtime-resolved. Less Android-Jetpack-native; errors surface at runtime, not compile time.

## Decision

**Hilt.**

Rationale: 13 planned features × (UI → VM → Repository → DAO) means manual injection will dominate `OrbitApp.kt` within three features. Hilt has the strongest training-signal for AI code generation (principle III — Verifiability, indirectly: the likely code is the code most agents will write), compile-time validation catches wiring errors before runtime, and Jetpack ViewModel + WorkManager integration is first-class.

## Consequences

- Add `hilt-android` and `hilt-compiler` to `libs.versions.toml` and `android/app/build.gradle.kts`.
- `OrbitApp` annotated `@HiltAndroidApp`.
- `MainActivity` annotated `@AndroidEntryPoint`.
- Each `ViewModel` annotated `@HiltViewModel` with constructor injection.
- Room database, DAOs, repositories, and Android API wrappers (CallLogReader, ContactsReader) bound via `@Module @InstallIn(SingletonComponent::class)`.
- WorkManager integration via `HiltWorkerFactory` (see ADR 0004).
- On acceptance (already applied): row appears in the dependency catalog under "Accepted via ADR, pending wiring" with a link here.
- On wiring (future commit): move the row from "Accepted via ADR, pending wiring" to "Currently in catalog" in the same commit that adds Hilt to `libs.versions.toml`. Update `features/_foundations/stack.md` identically.

## Non-consequences

- Does not preclude manual wiring in pure-Kotlin test code — tests construct collaborators directly.
- Does not mandate `@Inject` on all domain classes; pure-Kotlin domain code (rule-engine) stays dependency-free.

## Related

- Dependency catalog (`android/gradle/libs.versions.toml`)
- `features/_foundations/stack.md`
- ADR 0004 (WorkManager — uses `HiltWorkerFactory`)
