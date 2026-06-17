# Release human steps — Orbit v1.0.0

Consolidated checklist of every human-only action required to take the release
build to an upload-ready AAB. Work through these sections in order. All automation is
already done; each step below is something only you can do.

**Prerequisites (already landed by the release-prep work):**

- `android/app/build.gradle.kts` — graceful-absence signing config reads `keystore.properties`
  at repo root; `isMinifyEnabled = true`; `versionCode = 1`, `versionName = "1.0.0"`.
- `android/app/proguard-rules.pro` — keep rules for Room, Hilt, SQLCipher, WorkManager,
  kotlinx-serialization, Timber, libphonenumber, Glance, and crash-stack readability attrs.
- `scripts/smoke-test-release.sh` — R8-crash logcat filter; `--check` mode asserts 3 justified
  permissions and zero un-gated dev affordances.
- `docs/data-safety-checklist.md`, `docs/content-rating-responses.md`, `docs/store-listing.md`,
  `docs/release-notes/1.0.0.md` — Play Console form sources.
- `docs/privacy-policy.md` — policy draft; `PRIVACY_POLICY_URL` placeholder in `AboutSection.kt`
  awaits the live hosted URL (Step 3 below).

---

## Step 1 — Generate the upload keystore (RELEASE-03)

**This is a one-time, irrecoverable action. Losing the .jks file means you can never update
the app on Play Store unless Play App Signing (Step 6) is already enrolled.**

```bash
mkdir -p ~/.android-keystores

keytool -genkey -v \
  -keystore ~/.android-keystores/orbit-release.jks \
  -alias orbit \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Keysize 2048 is correct (not 4096). Google Play accepts
both; 2048 is universally compatible and faster to sign. 4096 adds no meaningful security for
an Android upload key.

When keytool prompts for Distinguished Name fields, fill in your name and country at minimum.
Choose a strong password for both the keystore and the key alias (they can be the same).

Verify the key was created:

```bash
keytool -list -v \
  -keystore ~/.android-keystores/orbit-release.jks \
  -alias orbit
```

Confirm the output shows the `orbit` alias fingerprint.

### Create keystore.properties at repo root

Create the file at the repo root — **not** inside `android/`, not inside any subdirectory.
This is where `android/app/build.gradle.kts` looks for it (the graceful-absence guard at
the top of that file: `rootProject.file("keystore.properties")`).

```properties
storeFile=../orbit-release.jks
storePassword=<your-keystore-password>
keyAlias=orbit
keyPassword=<your-key-password>
```

Note: `storeFile` is relative to `android/app/`, so `../orbit-release.jks` means the file
at `android/orbit-release.jks`. If you stored the .jks in `~/.android-keystores/`, use the
absolute path instead:

```properties
storeFile=/Users/<you>/.android-keystores/orbit-release.jks
storePassword=<your-keystore-password>
keyAlias=orbit
keyPassword=<your-key-password>
```

**Safety check — before any commit, run:**

```bash
git status
```

Confirm that neither `orbit-release.jks` nor `keystore.properties` appear in the output.
Both patterns are already in `.gitignore`. If either appears as untracked or staged, do NOT
commit — remove them from staging immediately.

```bash
# Should return nothing
git ls-files | grep -E 'keystore\.properties$|\.(jks|keystore)$'
```

---

## Step 2 — Back up the keystore before first use (RELEASE-03)

Do this **before** running `bundleRelease` for the first time. If the keystore is lost before
Play App Signing is enrolled, the app is orphaned.

**Backup to two independent encrypted locations:**

1. **Password manager (1Password / Bitwarden):** create a secure note, attach
   `orbit-release.jks` as a file attachment, and record the keystore password in the same
   entry.
2. **Second independent location:** an encrypted USB drive, iCloud Drive with FileVault,
   or another cloud provider with end-to-end encryption.

Both locations must be independent — losing one should not mean losing both.

**Record the backup in your release notes:**

```
Orbit release keystore generated 2026-06-11, backed up to 1Password + <second location>.
Play App Signing enrollment scheduled for the first-upload date.
```

**Test-restore:** after backing up, verify the restored file is intact:

```bash
keytool -list -v \
  -keystore /path/to/restored/orbit-release.jks \
  -alias orbit
```

The fingerprint must match what you saw in Step 1. If it does not, the backup is corrupted —
redo it before proceeding.

---

## Step 3 — Host the privacy policy and wire the live URL (RELEASE-05)

The privacy policy source lives at `docs/privacy-policy.md`. It must be hosted at a stable
public URL — not localhost, not a raw GitHub URL (Play Console rejects both).

**Recommended approach: GitHub Pages (default)**

1. Enable GitHub Pages on your repository (Settings → Pages → Source: main branch, `/docs`
   folder or a dedicated `gh-pages` branch).
2. The default URL will be `https://<username>.github.io/<repo>/privacy-policy` or similar.
3. Verify the page is reachable:

```bash
curl -sSfL https://frosty110.github.io/friends-in-orbit/privacy | head -20
```

The output should show the privacy policy content — not a 404, not a redirect to localhost,
not raw Markdown served as text/plain.

**Wire the live URL into the app:**

Open `android/app/src/main/java/app/orbit/ui/screens/settings/AboutSection.kt`.

Find line:

```kotlin
private const val PRIVACY_POLICY_URL = "https://frosty110.github.io/friends-in-orbit/privacy"
```

Replace the placeholder URL with the actual live URL you confirmed with `curl`.
Remove the `// PLACEHOLDER — RELEASE-05` comment once the URL is live and verified.

Rebuild and verify on device:

```bash
cd android && ./gradlew assembleDebug
```

Install, open the app, go to Settings → About → Privacy policy. The system browser should
open the live URL and display the policy content. If the browser shows a 404 or the old
placeholder, the `PRIVACY_POLICY_URL` constant is still wrong.

---

## Step 4 — Capture store assets (RELEASE-08)

Play Console requires screenshots to publish a store listing.

**Screenshots:**

- 2–8 real-device screenshots (not mockups, not Compose previews).
- Minimum 320px on the short edge; maximum 3840px. 1080×1920 (9:16) is the standard phone
  format.
- Blur real contact names and phone numbers before uploading.
- Save to `docs/store-screenshots/` following the screenshot-review naming convention where
  applicable: `<YYYY-MM-DD-HHMM>-<screen>-light.png` etc.

Recommended screens to capture (reference `docs/store-listing.md` for context):

- Home screen (mood picker visible)
- Card View (one contact surfaced, ready to call)
- Browse list (contacts list)
- Lists Manager
- Settings → About

**Feature graphic:**

- 1024×500 px PNG or JPG.
- Warm, design-system-aligned. May reuse assets from `design/assets/`.
- No text dump; one clear visual is better.

**App icon:**

- Verify the adaptive icon layers render correctly on the test device (round + square
  launchers). The adaptive icon XML is in `android/app/src/main/res/mipmap-anydpi-v26/`.
- Export a 512×512 PNG for Play Console upload (App icon field).

---

## Step 5 — Fill Play Console forms (RELEASE-06, RELEASE-07, RELEASE-08)

Use the docs produced during release prep as copy-paste sources.

### Data Safety form (Play Console → App content → Data safety)

Source: `docs/data-safety-checklist.md`

Key stance: Orbit transmits no data off-device. All three permissions (READ_CALL_LOG,
READ_CONTACTS, POST_NOTIFICATIONS) are accessed on-device only — Play's definition of
"collect" means transmitting off-device; Orbit does not meet that bar. Fill the form as
"no data collected or shared."

**On the same day you fill the Data Safety form, re-check the permission count:**

```bash
grep -c '<uses-permission' android/app/src/main/AndroidManifest.xml
```

The result must be 3. If it is not 3, do not submit until you understand the discrepancy
(form mismatch is a terminal issue that blocks future updates).

### Content rating questionnaire (Play Console → App content → Content ratings)

Source: `docs/content-rating-responses.md`

All answers are No. Expected rating: Everyone (ESRB) / PEGI 3.

### Store listing (Play Console → Main store listing)

Source: `docs/store-listing.md`

Paste the short description (73 chars, limit 80) and full description from the doc. Upload
the screenshots from Step 4 and the feature graphic. Voice audit: sentence case, no
exclamation marks, no gamification language.

### Release notes (Play Console → Release notes field when uploading the AAB)

Source: `docs/release-notes/1.0.0.md`

Use the Play-form version (206 chars, well under the 500-char limit).

---

## Step 6 — Play App Signing — deferred to first upload

**Do not enroll Play App Signing during release prep. It happens on the first AAB upload.**

On the first upload to Play Console, the upload flow will offer Play App Signing enrollment.
Accept. This makes Google hold the release signing key while you keep the upload key. If the
upload key is lost after enrollment, Google can reset it. Without enrollment, loss of the
keystore in Step 1 means the app can never be updated.

This is a one-click step during the first upload. It is listed here so it is not forgotten.

---

## Step 7 — RELEASE-10 release-build smoke test (mandatory gate)

**No release AAB ships without green smoke. This is a hard gate, not optional.**

### Build the release AAB

```bash
cd android
./gradlew bundleRelease
# Output: android/app/build/outputs/bundle/release/app-release.aab
```

### Install bundletool (if not already installed)

```bash
brew install bundletool
```

### Build APK set signed with the upload keystore

```bash
bundletool build-apks \
    --bundle=android/app/build/outputs/bundle/release/app-release.aab \
    --output=/tmp/orbit-release.apks \
    --ks="$HOME/.android-keystores/orbit-release.jks" \
    --ks-key-alias=orbit
```

Bundletool will prompt for the keystore password. Pass `--ks` and `--ks-key-alias` — these
flags are required; omitting either causes a silent signing failure.

### Install on the connected device

```bash
bundletool install-apks --apks=/tmp/orbit-release.apks
```

### Run the smoke-test logcat filter in a second terminal

```bash
scripts/smoke-test-release.sh
```

Leave this running while you walk every screen.

### Walk every screen (full coverage required)

Walk all of the following while the logcat filter is running:

- Onboarding (first-install flow — grant READ_CONTACTS and READ_CALL_LOG when prompted;
  also test the deny paths)
- Home screen (mood picker)
- Card View — swipe yes, swipe no, dial a contact, skip
- Browse list
- Contact Detail — view, add a note, edit a note, delete a note
- Ignore a contact (and verify it disappears from Card View)
- Pause a contact (and verify the unpause banner)
- Lists Manager — create a list, rename, reorder, archive
- List Config (edit list rules)
- Settings — all rows including Export and Privacy policy tap
- Notifications — trigger at least one list-prompt nudge and one incoming follow-up nudge;
  verify both channels post and tap-to-open works
- Both widgets (2×2 and 4×2) — install, verify rendering, tap to open app

### Pass criteria

**PASS** = zero of the following patterns in the logcat output across the full screen walk:

```
ClassNotFoundException
NoSuchMethodException
NoSuchFieldException
VerifyError
AbstractMethodError
IncompatibleClassChangeError
```

### If a crash appears — narrow ProGuard rule gap fix

Do NOT add a broad `-keep app.orbit.** { *; }` rule. That defeats R8's benefit entirely.

Instead:

1. Read the crash stack trace in logcat — find the specific class that failed.
2. Identify which library or reflection path owns that class.
3. Add a narrow keep rule for that class to `android/app/proguard-rules.pro`.
4. Rebuild with `./gradlew bundleRelease`, re-install with bundletool, re-run the smoke
   filter, and re-walk the affected screen.
5. Repeat until zero crashes.

Commit the updated `proguard-rules.pro` with a `fix(proguard): narrow keep for <ClassName>`
message before declaring the phase done.

---

## Step 8 — Optional post-smoke ProGuard trim (v1.1 optimization)

Once smoke is green, the keep rules in `android/app/proguard-rules.pro` contain some
broad wildcard blocks (Room and SQLCipher `**` wildcards) that R8 consumer rules now cover
natively. These can be trimmed to reduce APK size marginally.

**This is not a release blocker for v1.0.0.** Do not attempt this trim before green smoke.
If you decide to trim:

1. Remove or narrow one section at a time.
2. Re-run `./gradlew bundleRelease` and repeat the Step 7 smoke walk.
3. Only commit the trim if smoke is still green after removal.

---

## Resume signals

After completing Steps 1–3, reply: **"keystore + hosting done"** with confirmation that:

- `git status` shows neither `.jks` nor `keystore.properties` as tracked or staged
- `keytool -list -v` prints the orbit alias fingerprint
- `curl -sSfL <live-url> | head -20` returns policy content
- Tapping Settings → About → Privacy policy on device opens the browser to the live URL

After completing Steps 4–5 and the Step 7 smoke walk, reply: **"release smoke green"** with
confirmation that:

- Play Console forms are filled from the release-prep docs
- `bundletool install-apks` installed the release AAB on device
- The full screen walk produced zero R8 crashes in the smoke filter

If a screen crashed, paste the offending class from logcat — the orchestrator will plan a
narrow keep-rule fix before declaring the work done.

**Play App Signing enrollment (Step 6) happens on first upload — do not block on it here.**
