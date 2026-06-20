# Orbit

An Android app for calling the people you keep meaning to call.

Orbit organizes your contacts into mood and context-based lists and surfaces one person at a time with a simple yes-or-no decision — removing the "who should I call right now?" friction so reaching out happens more often.

## Development

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

