# Orbit

An Android app for calling the people you keep meaning to call.

Orbit organizes your contacts into mood and context-based lists and surfaces one person at a time with a simple yes-or-no decision — removing the "who should I call right now?" friction so reaching out happens more often.

> The call that makes your day can make someone else's. You deserve to be the one who reaches out.

## Download the app

Grab the latest sideloadable APK from the project's **[Releases page](https://github.com/frosty110/friends-in-orbit/releases)**.

On your phone, open the most recent release, tap the `.apk` asset, and allow installation from unknown sources once. No laptop, adb, or USB cable needed: release assets download without a login. These are debug builds (`io.github.frosty110.orbit.debug`) for testing, not the Play Store build, and they install beside it.

A fresh build is published automatically on every merge to `main` (the **Release APK**
workflow), so the newest release is always the latest code. Maintainers can also cut an
off-cycle build by hand from GitHub → Actions → "Release APK" → Run workflow.

### Staying up to date

Every dev build is signed with the same key, so each one installs over the last and keeps your lists and notes. Orbit has no update check of its own (it has no internet access at all), so let an updater watch the Releases page:

1. Install [Obtainium](https://github.com/ImranR98/Obtainium), a free, open-source app that installs and updates apps from their GitHub releases.
2. In Obtainium, tap **Add app** and enter `https://github.com/frosty110/friends-in-orbit`.
3. Turn on **Include prereleases**: dev builds are marked as prereleases so they never pose as a store release.
4. Tap **Add**. Obtainium installs the newest dev build and offers each new one as an update.

Each release's notes end with its signing certificate's SHA-256. Builds with the same fingerprint update each other; the shared key's fingerprint starts `f987f59f`.

**Switching from a build made before 2026-10-07:** those were each signed with a different throwaway key, so the first shared-key build cannot install over them. Once: Settings → Export your data, uninstall Orbit, install the new build, then Settings → Import backup.

**For maintainers:** the key lives in the `ORBIT_DEV_KEYSTORE_BASE64` repository secret (Settings → Secrets and variables → Actions), the base64 of a keystore that uses the standard debug credentials. Keep a copy of the keystore file somewhere safe: a new key means one more uninstall for everyone. If the secret is missing, builds still publish, signed with a throwaway key, and both the workflow run and the release notes say so. To make a new key:

```sh
keytool -genkeypair -keystore orbit-dev.keystore -storetype PKCS12 \
  -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=Orbit dev builds, O=Orbit"
base64 -w0 orbit-dev.keystore   # paste the output into the secret
```

## What it does

Orbit reads your call log and contacts (entirely on-device — nothing is transmitted off your phone) and lets you build lists around how you want to stay in touch. The rule engine surfaces who's "due" based on tunable cadences, and Card View serves them one at a time so you can call, skip, snooze, or pause without scrolling a giant address book.

### Status

Targeting a **v1.0.0** Play Store release. The UI shell and rule engine are in place; the Android integrations (Room persistence, call-log ingestion, contacts sync, notifications, widgets) are actively being wired. See [`features/INDEX.md`](features/INDEX.md) for the per-feature status map.

## Repository layout

| Path | What lives here |
| --- | --- |
| [`CLAUDE.md`](CLAUDE.md) | Working contract for AI sessions — read first if you're an agent. |
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

### How changes get made

Most work here happens in AI sessions, so the process is written down rather than assumed:

- [`features/_foundations/development-cycle.md`](features/_foundations/development-cycle.md) — the loop (orient → plan → change → verify → document → land) and the definition of done.
- [`features/_foundations/rules.md`](features/_foundations/rules.md) — the numbered rules source comments cite (`rules.md §Design 3`), the citation conventions, and the tracked documentation debt.
- [`CLAUDE.md`](CLAUDE.md) — the short working contract an agent reads first.

### Conventions check

Rule citations, PII-free layers, and doc links are enforced by a dependency-free script — the same one CI runs:

```sh
python3 scripts/check-conventions.py
```

It fails on a `rules.md` citation that names no rule, a logging call in a PII-bearing layer (`ui`, `domain`, `data`, `nav`), or a dangling relative link in a Markdown file. Undocumented requirement IDs are ratcheted against `scripts/conventions-baseline.json`: existing debt is tolerated, new debt is not. After documenting some, re-run with `--update-baseline` in the same PR.

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
