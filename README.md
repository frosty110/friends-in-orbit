# Orbit

An Android app for calling the people you keep meaning to call.

Orbit organizes your contacts into mood and context-based lists and surfaces one person at a time with a simple yes-or-no decision — removing the "who should I call right now?" friction so reaching out happens more often.

> The call that makes your day can make someone else's. You deserve to be the one who reaches out.

## Download the app

Grab the latest sideloadable APK from the project's **[Releases page](https://github.com/frosty110/friends-in-orbit/releases)**.

On your phone, open the most recent release, tap the `.apk` asset, and allow installation from unknown sources once. No laptop, adb, or USB cable needed — release assets download without a login. These are unsigned debug builds (`app.orbit.debug`) intended for testing, not the Play Store build.

A fresh build is published automatically on every merge to `main` (the **Release APK**
workflow), so the newest release is always the latest code. Maintainers can also cut an
off-cycle build by hand from GitHub → Actions → "Release APK" → Run workflow.

## What it does

Orbit reads your call log and contacts (entirely on-device — nothing is transmitted off your phone) and lets you build lists around how you want to stay in touch. The rule engine surfaces who's "due" based on tunable cadences, and Card View serves them one at a time so you can call, skip, snooze, or pause without scrolling a giant address book.

### Status

Targeting a **v1.0.0** Play Store release. The UI shell and rule engine are in place; the Android integrations (Room persistence, call-log ingestion, contacts sync, notifications, widgets) are actively being wired. See [`features/INDEX.md`](features/INDEX.md) for the per-feature status map.

## Repository layout

| Path | What lives here |
| --- | --- |
| [`android/`](android/) | The app itself — native Kotlin + Jetpack Compose. See [`android/README.md`](android/README.md). |
| [`features/`](features/) | Canonical product + technical spec, one folder per feature. Start at [`features/INDEX.md`](features/INDEX.md). |
| [`design/`](design/) | Design system — color/type tokens, UI kits, fonts, icons. |
| [`docs/`](docs/) | Release process, store listing, privacy policy, data-safety + content-rating sources. |
| [`scripts/`](scripts/) | Build/release helper scripts (e.g. `smoke-test-release.sh`). |
| `package.json` | Convenience `yarn` shortcuts for on-device dev (`phone:build`, `phone:run`, `phone:logcat`, …). |

## Tech stack

Android-only (iOS can't access call logs), min SDK 31 (Android 12), Kotlin 2.0 + Jetpack Compose (Material 3), Room for storage, JDK 17 / AGP 8.7 / Gradle 8.11. Full summary in [`features/_foundations/stack.md`](features/_foundations/stack.md), with the catalog as ground truth in `android/gradle/libs.versions.toml`.

## Development

Build and run instructions live in [`android/README.md`](android/README.md). Quick on-device loop from the repo root:

```sh
yarn phone:run      # assemble debug, install over USB, launch
yarn phone:logcat   # tail the app's logs
```

### Code style (ktlint)

Kotlin is formatted with [ktlint](https://github.com/pinterest/ktlint). Enable the
auto-format pre-commit hook once per clone so staged files are formatted automatically:

```sh
git config core.hooksPath .githooks
```

Manual commands (run from `android/`): `./gradlew ktlintFormat` to fix, `./gradlew ktlintCheck` to verify.
A few opinionated, non-auto-correctable rules are relaxed in `android/.editorconfig`, and CI reports
ktlint issues without failing the build.

### Test coverage (Kover)

Unit-test coverage is reported via [Kover](https://github.com/Kotlin/kotlinx-kover) (from `android/`):

```sh
./gradlew :app:koverHtmlReportDebug   # browsable report
./gradlew :app:koverXmlReportDebug    # single machine-readable value
```

CI posts the unified number as a PR comment. Instrumented (`androidTest`) coverage is not yet included.
