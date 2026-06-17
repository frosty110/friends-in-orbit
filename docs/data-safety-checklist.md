# Data Safety checklist — Play Console form

**Source of truth for filling the Play Console → App content → Data safety form.**
Generated from `android/app/src/main/AndroidManifest.xml`.
Human: copy each answer verbatim into the corresponding Play Console field.

---

## Top-level stance

**"Does your app collect or share any of the required user data types?"**
Answer: **No.**

Play's official definition of "collect" (support.google.com/googleplay/android-developer/answer/10787469):
> "User data accessed by your app that is only processed locally on the user's device and not sent off device does not need to be disclosed."

Orbit transmits zero bytes off the device. All reading of contacts, call history, and notes is local
processing only. The correct top-level answer is: **no data collected, no data shared.**

> Note: the project's play-store checklist contains an
> older table that lists these fields as "Collected: Yes, Processed on-device only." That framing
> pre-dates the Play Help Center clarification about the definition of "collect." **This document
> supersedes that table.** Do not re-introduce "Collected: Yes" — it is factually incorrect under
> Play's current definition and would misrepresent the app to users.

---

## Per-permission mapping

Each row below maps one manifest permission to its Data Safety answer and cites the manifest entry
that backs it. There are exactly **three** dangerous permissions declared in the manifest.

### 1. `READ_CALL_LOG`

**Manifest entry (line 6):**
```xml
<uses-permission android:name="android.permission.READ_CALL_LOG" />
```

| Play Console field | Answer |
|--------------------|--------|
| Data type | Phone call history |
| Collected (transmitted off device)? | No — accessed on-device only |
| Shared with third parties? | No |
| Used for app functionality? | Yes — auto-marks contacts as called so users do not have to log calls manually |
| Processed on-device only? | Yes |

Justification note for Permissions Declaration Form: "Orbit's core value is reducing friction around
deciding who to call. `READ_CALL_LOG` auto-marks contacts as called so users do not have to manually
log every outgoing call — without it, the app requires manual logging for every call, which defeats the
purpose. The manual-log fallback path (CALL-06) is available if the user denies the permission."

### 2. `READ_CONTACTS`

**Manifest entry (line 7):**
```xml
<uses-permission android:name="android.permission.READ_CONTACTS" />
```

| Play Console field | Answer |
|--------------------|--------|
| Data type | Contacts (name, phone number) |
| Collected (transmitted off device)? | No — accessed on-device only |
| Shared with third parties? | No |
| Used for app functionality? | Yes — bulk add during onboarding; contact photo display |
| Processed on-device only? | Yes |

### 3. `POST_NOTIFICATIONS`

**Manifest entry (line 8):**
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

| Play Console field | Answer |
|--------------------|--------|
| Data type | Not a data type — notification delivery permission only |
| Collected (transmitted off device)? | No |
| Shared with third parties? | No |
| Used for app functionality? | Yes — daily digest and per-list nudge notifications |
| Processes personal data? | No personal data is transmitted; notification content is generated locally |

---

## Data types not applicable

| Data type | Answer | Why |
|-----------|--------|-----|
| Notes (user-entered) | N/A — not collected | Stored locally in encrypted SQLCipher database; never transmitted |
| Photos / contact avatars | N/A — not collected | Read from system contacts via READ_CONTACTS (system-provided); no camera or file-picker access; never transmitted |
| Crash logs / analytics | No | Orbit has zero telemetry. No analytics SDK, no crash-reporting SDK. Confirm before each submission. |
| Location | N/A | No location permission declared or used |
| Financial info | N/A | No payment or financial data |
| Health / fitness | N/A | Not applicable |

---

## Encryption and deletion

| Question | Answer |
|----------|--------|
| Is all user data encrypted in transit? | N/A — no data is transmitted off-device |
| Is user data encrypted at rest? | **Yes** — all app data (contacts metadata, call history, notes) is stored in a SQLCipher-encrypted Room database |
| Do you provide a way for users to request that their data be deleted? | **Yes** — (1) uninstalling the app deletes all data; (2) Settings → Export gives the user a local copy before uninstall; (3) the Ignored Contacts screen provides per-contact hide/remove from lists |

---

## Third-party SDKs and libraries

Orbit uses no third-party SDK that transmits data. All libraries (Room, Hilt, Coil, WorkManager,
Jetpack Compose) are local-only. There is no analytics, crash-reporting, advertising, or social SDK
in the dependency graph.

Answer to "Does your app share data with third parties?": **No.**

---

## Verification command

Run before each Play Console form fill to confirm no new permissions have been added:

```bash
grep 'uses-permission' android/app/src/main/AndroidManifest.xml
# Expected output: exactly READ_CALL_LOG, READ_CONTACTS, POST_NOTIFICATIONS
```

If output differs from the three permissions above, update this checklist before filling the form.
