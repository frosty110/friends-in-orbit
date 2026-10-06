# Reading your call history

**Route:** `onboard/sync` (step 3 of 4)
**Group:** Onboarding
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [onboarding](../onboarding/README.md): the sync gate; [call-detection](../call-detection/README.md)

---

## Reached from

- Permission: Call log, on "Continue" or "Skip"
- System back from Make your first list
- A relaunch mid-flow that saved this step

## What the user sees

- App bar: the step counter "3 of 4" and no back arrow (the reading cannot be skipped past)
- "Reading your call history" / "Reading your last 90 days of calls (never leaves your device)."; a quiet progress bar in ink; a live count, "Counted 142 calls over 32 people"
- "How far back should Orbit look?" with chips "1 month", "3 months", "6 months", "1 year", the current one chosen
- "Some phones have years of call history. We'll get there."
- "Continue", the one accent element, available once the reading is done

## Actions and menus

- A range chip changes how far back Orbit reads, here and in Settings, and restarts the reading
- "Continue": on to the suggested first list, or straight back into the first list already under way (back from it, or a relaunch mid-setup), never a second copy
- On failure, "Try again"; after a failed retry, "Try one more time" and "Continue anyway"

## States

- Reading: progress and the live count; Continue waits until both your contacts and your calls have been read
- Done: the final count and "We'll learn as you go."
- Nothing found: "No calls found in the last 90 days. That's okay." and "We'll learn as you go."
- Call log access skipped: "Starting fresh" / "Without call history, Orbit starts from what you tell it." and "Orbit doesn't have call history access. You can grant it any time in Settings."; Continue is available at once. A failed read (Room or the saved settings throwing) without call-log access is still this state, with Continue available, never Failed: there was no sync to fail, and Try again could not start one
- Failed: "Couldn't finish the sync. Try again?" with "Try again"; after that: "Couldn't finish the sync." / "We'll try again later in the background." with "Try one more time" and "Continue anyway"
- Privacy curtain: only counts are shown, so nothing changes

## Leads to

- Preview your first list, when Orbit has three or more people to suggest; otherwise straight to a blank Make your first list
- The first list already under way, when there is one
- No back

## Tests that pin it

- `OnboardingSyncViewModelTest` (rewritten this round: no permission reads as Skipped, success with and without calls, failure and retry, a thrown read with and without the permission, the initial state)
- `OnboardingListStarterTest` (the list under way is reused, never duplicated)
- `CallLogSyncWorkerTest`, `ContactsIngestWorkerTest`
- `OrbitNavHostTest` (added this round: Continue with a list under way goes straight into it with no Preview between; without one, Preview, and back from the first-list step lands here)
- Gallery previews: `OnboardingSyncScreenPreview`, and the Empty, Succeeded, Skipped and both Failed previews added this round
