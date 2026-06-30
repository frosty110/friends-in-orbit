# Planning for go-live — Orbit on Google Play

Master checklist for taking Orbit from "first upload" to **Production** on Google
Play. It mirrors the Play Console *"Get ready to publish your app"* flow, records
current status, and gives an ETA per task plus a total.

**Last updated:** 2026-06-22

## Legend

| Mark | Meaning |
|------|---------|
| ✅ | Done |
| 🟡 | In progress / partially done |
| ⬜ | Not started |
| 🔵 | **Decision required** before work can proceed |
| 🤖 | Repo / automatable (can be done in this codebase) |
| 👤 | Human-only action in Play Console or on your machine |

> **ETA convention:** "Effort" = hands-on working time. "Calendar" = wall-clock
> time including Google-imposed waits (review queues, the mandatory 14-day closed
> test). The calendar total is dominated by waits, not by effort.

---

## 0. Critical-path decisions

| # | Decision | Status | Notes |
|---|----------|--------|-------|
| 0.1 | **applicationId** (permanent) | ✅ Decided | `io.github.frosty110.orbit` — set in `android/app/build.gradle.kts`. Code namespace stays `app.orbit` (no source churn). Debug builds: `io.github.frosty110.orbit.debug`. |
| 0.2 | **`READ_CALL_LOG` policy path** | 🔵 **Decision required** | See [§1.1](#11-call-log-permission--the-one-real-policy-risk). Blocks final policy sign-off and the AAB content. **Recommended: ship v1.0 without `READ_CALL_LOG`.** |

---

## 1. App policy compliance (Developer Program Policies)

Confirming "my app meets the Developer Program Policies" is a checkbox, but ticking
it untruthfully is what gets apps suspended. Status of each policy surface:

| Policy area | Status | Evidence / action |
|-------------|--------|-------------------|
| Ads | ✅ Compliant | No ad SDKs. Declare "No ads". |
| In-app purchases | ✅ Compliant | None in v1.0 (`docs/content-rating-responses.md`). |
| Analytics / tracking | ✅ Compliant | Zero telemetry, no third-party data SDKs (`docs/data-safety-checklist.md`). |
| Data handling / Data Safety | ✅ Prepared | All processing on-device; form answers ready in `docs/data-safety-checklist.md`. |
| Privacy policy | 🟡 In progress | Hosting wired (§3.1); URL already in `AboutSection.kt`. Needs Pages toggle + verify. |
| Encryption (export) | ✅ Compliant | SQLCipher/AES; see §3 US export note. |
| Content rating | ✅ Prepared | Everyone/PEGI 3; answers in `docs/content-rating-responses.md`. |
| Target audience / children | ✅ Compliant | Targets 18+; does not target children. |
| **Restricted permission: `READ_CALL_LOG`** | 🔵 **At risk** | See below — the one item that is not automatically compliant. |

### 1.1 Call Log permission — the one real policy risk

Google Play's *"Use of SMS and Call Log Permission Groups"* policy only permits
`READ_CALL_LOG` for apps that are the **default Phone/Assistant handler**, or whose
core use matches a fixed exception list (caller-ID, spam, backup, device
automation…). Orbit is none of these, and "auto-mark a contact as called" is not a
listed exception — so keeping the permission risks rejection/suspension.

Orbit's core loop does **not** strictly require it: the app hands off via
`ACTION_DIAL` and already advances the card on return, so it can record "you called
this person" without reading the log. The log only *enriches* (calls made outside
Orbit, exact timestamps).

| Option | Effort | Launch risk | Outcome |
|--------|--------|-------------|---------|
| **A. Drop `READ_CALL_LOG` for v1.0** *(recommended)* | ~1–2 days eng | Low | Compliant now; lose only "detects calls made outside Orbit". Re-add later via declared exception if desired. |
| B. Keep it, file a Permissions Declaration Form | ~0.5 day docs | High | Likely rejection / review back-and-forth; can stall launch. |
| C. Re-architect as default dialer | ~1–2 weeks eng | Low (policy) | Large scope, changes app category, delays launch. |

**Action:** confirm Option A/B/C. If A, the manifest permission removal + making the
dial-return path the default is a tracked code change (🤖, can be done here).

---

## 2. Internal testing — *optional, fast smoke distribution*

| Task | Owner | Status | Effort |
|------|-------|--------|--------|
| Generate upload keystore + back up (irreversible) | 👤 | ⬜ | 0.5–1 h |
| Build signed **release AAB** (`./gradlew bundleRelease`) | 👤 | ⬜ | 0.5 h |
| R8 release smoke walk (`scripts/smoke-test-release.sh`) | 👤 | ⬜ | 2–4 h |
| Select internal testers (your own devices/people) | 👤 | ⬜ | 0.25 h |
| Create + roll out internal release; preview & confirm | 👤 | ⬜ | 0.5 h |

> Full step-by-step already exists in [`docs/RELEASE-HUMAN-STEPS.md`](RELEASE-HUMAN-STEPS.md)
> (keystore, backup, AAB build, smoke gate). This plan does not duplicate it.

---

## 3. Finish setting up your app

### 3.1 Privacy policy URL  🤖🟡

- ✅ Policy written (`docs/privacy-policy.md`) and rendered to a web page (`docs/privacy/index.html`).
- ✅ Deploy workflow added (`.github/workflows/pages.yml`) — publishes **only** the
  privacy page to `https://frosty110.github.io/friends-in-orbit/privacy`.
- ✅ App already points at that URL (`AboutSection.kt`, no placeholder remaining).
- ⬜ **One-time toggle (👤):** repo **Settings → Pages → Source: "GitHub Actions"**.
  The workflow runs on push to `main`. Then verify:
  ```bash
  curl -sSfL https://frosty110.github.io/friends-in-orbit/privacy | head -20
  ```
- ⬜ Paste that URL into Play Console → App content → **Privacy policy**.

> Note: the Pages workflow triggers on `main`. It activates once this branch is
> merged (or run manually via "Run workflow" after the Pages source is set).

### 3.2 "Let us know about the content of your app"

| Play Console item | Source / answer | Owner | Status | Effort |
|-------------------|-----------------|-------|--------|--------|
| Privacy policy | §3.1 hosted URL | 👤 | 🟡 | 0.1 h |
| Sign-in details | No account/login required — provide "no login" note | 👤 | ⬜ | 0.1 h |
| Ads | No ads | 👤 | ⬜ | 0.1 h |
| Content rating | `docs/content-rating-responses.md` (all "No" → Everyone) | 👤 | ⬜ | 0.25 h |
| Target audience | 18+; not directed to children | 👤 | ⬜ | 0.2 h |
| Data safety | `docs/data-safety-checklist.md` ("no data collected/shared") | 👤 | ⬜ | 0.35 h |
| Government apps | No | 👤 | ⬜ | 0.05 h |
| Financial features | No | 👤 | ⬜ | 0.05 h |
| Health | No | 👤 | ⬜ | 0.05 h |

### 3.3 Manage how your app is presented

| Play Console item | Source | Owner | Status | Effort |
|-------------------|--------|-------|--------|--------|
| App category + contact details | Category **Communication** (`docs/store-listing.md`); set support email | 👤 | ⬜ | 0.25 h |
| Store listing copy | `docs/store-listing.md` (short + full description) | 👤 | ⬜ | 0.5 h |
| Store assets: 2–8 screenshots, 1024×500 feature graphic, 512×512 icon | Capture per `RELEASE-HUMAN-STEPS.md` Step 4 | 👤 | ⬜ | 2–3 h |

---

## 4. Closed testing  *(mandatory before production)*

| Task | Owner | Status | Effort |
|------|-------|--------|--------|
| Set up closed test track | 👤 | ⬜ | 0.5 h |
| Select countries / regions | 👤 | ⬜ | 0.1 h |
| **Recruit ≥ 12 testers** and collect opt-ins | 👤 | ⬜ | variable (recruiting) |
| Create + roll out closed release; preview & confirm | 👤 | ⬜ | 0.5 h |
| Send release to Google for review | 👤 | ⬜ | 0.1 h |
| **Run closed test ≥ 14 days with ≥ 12 opted-in testers** | 👤 | ⬜ | **14 days calendar (Google-mandated)** |

> Google now requires a personal developer account to run a closed test with **≥ 12
> testers for ≥ 14 continuous days** before it can apply for production. This is the
> single biggest calendar item and cannot be shortened. **Start tester recruitment
> early** — it usually gates everything else.

---

## 5. Production

| Task | Owner | Status | Effort |
|------|-------|--------|--------|
| Apply for production access (answer closed-test questions) | 👤 | ⬜ | 0.5 h |
| Create production release (reuse the closed-test AAB) | 👤 | ⬜ | 0.5 h |
| Google production review | 👤 (wait) | ⬜ | **~2–7 days calendar** |
| Enroll Play App Signing (offered on first upload — accept) | 👤 | ⬜ | 0.1 h |
| Accept US export-laws self-certification | 👤 | ⬜ | 0.1 h |

> **US export laws:** Orbit bundles SQLCipher (AES), so it falls under encryption
> export rules. You can self-certify, but apps incorporating encryption typically
> owe a one-time/annual **ENC self-classification report** emailed to BIS + the NSA
> ENC coordinator (EAR §740.17). Low burden for a free app — but a real obligation,
> not just the checkbox.

---

## ETA summary

### Hands-on effort (excludes Google waits)

| Phase | Effort |
|-------|--------|
| Decisions (call-log path) | 0.5 h |
| Call-log compliance code (Option A, if chosen) | 1–2 days |
| Keystore + AAB + R8 smoke | 3–5 h |
| Store assets (screenshots/graphic/icon) | 2–3 h |
| Play Console content forms | ~1.5 h |
| Store listing + category/contact | ~1.25 h |
| Closed-test setup + production application | ~2.5 h |
| **Total effort** | **≈ 2–3 focused working days** (≈ 3.5–4 days if Option A call-log work is included) |

### Calendar to Production (critical path)

| Stage | Calendar |
|-------|----------|
| Prep, forms, assets, first AAB | 2–4 days (effort-bound) |
| Recruit ≥ 12 testers | 1–7 days (start early; often the bottleneck) |
| **Mandatory closed test** | **14 days (fixed)** |
| Production review | 2–7 days |
| **Total calendar to live** | **≈ 3–4 weeks**, assuming testers are lined up while prep is underway |

> **Biggest lever:** recruit your 12 testers and begin the 14-day closed test as
> early as possible — ideally the moment internal testing produces a working AAB.
> Everything in §3 (forms, listing, assets) can be finished *in parallel* with the
> 14-day clock running.

---

## What's already done in this repo (so you don't re-check it)

- ✅ Permanent `applicationId` chosen and set: `io.github.frosty110.orbit`.
- ✅ Dev tooling updated to the new install id (`package.json`, `README.md`).
- ✅ Privacy policy hosting wired (Pages workflow); app already links the live URL.
- ✅ Play Console form sources written: data safety, content rating, store listing,
  release notes, privacy policy.
- ✅ Release build plumbing documented end-to-end in `RELEASE-HUMAN-STEPS.md`.

## Open items needing you

1. 🔵 **Decide the `READ_CALL_LOG` path** (§1.1) — recommended: drop for v1.0.
2. 👤 Flip **Settings → Pages → Source: GitHub Actions** to make the privacy URL live.
3. 👤 Generate + back up the upload keystore (irreversible).
4. 👤 Recruit ≥ 12 closed-test testers — start now; it gates the 14-day clock.
