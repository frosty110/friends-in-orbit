# Make your first list

**Route:** `onboard/first-list/{listId}` (step 4 of 4)
**Group:** Onboarding
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [onboarding](../onboarding/README.md): the first-list gate, mid-flow resume, ONB-23; [orbit-lists](../orbit-lists/README.md) for the controls; PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Preview your first list: "Make this my first list" or "Start blank"
- Reading your call history: "Continue", when a first list is already under way (back from here, or a relaunch mid-setup)
- "Add another list" on this screen
- The Add people picker, on its way back

## What the user sees

- App bar: "4 of 4", no back arrow (a first list is required to finish)
- A helper line while Done is not yet available: "Add a name and pick at least 3 people to finish." (or, without contacts access, "You can add people once Orbit can see your contacts. Grant access any time in Settings.", and with no name either, "Give your list a name to finish. You can add people once Orbit can see your contacts.")
- The same controls as List settings, with two differences: the name is an editable field at the top ("Name"), and the nudge days and time show as a read-only summary ("Weekdays at 10am") with "Change the days or time any time in this list's settings." Sections: Name, Rhythm, How often, Active hours, Nudges, People
- "Done", the one accent element, and "Add another list"

## Actions and menus

- Everything saves as you go, as in List settings; the typed name is written to the list
- "Add people": opens the Add people picker and returns here; "Added 3 people to {list}" with Undo shows on return
- Remove a person: "Removed {name}" with Undo, which puts them back
- "Done": available once the list has a name and at least three people; finishes the counted steps and opens Done
- "Add another list": keeps this list and opens a new, empty one in its place

## States

- Loading: a quiet skeleton
- Not yet finishable: the helper line, and Done disabled
- Error: "Orbit couldn't load this list" / "Nothing is lost. Try again in a moment." with Try again
- Not found (the list is gone): a message with "Start again", which returns to Reading your call history
- Privacy curtain: the name field reads "List" and people's names "Contact" (PRIV-03)

## Leads to

- Done
- The Add people picker, which returns here
- A new first list ("Add another list"); the finished one is kept
- System back: Reading your call history, whose Continue comes straight back to this same list, never a second copy

## Tests that pin it

- `OnboardingFirstListGateTest` (Done needs a name and three people; the fallback branch always offers an action)
- `OnboardingListStarterTest` (resume, not duplicate; add another)
- `ListConfigViewModelTest` (the shared body)
- `OrbitNavHostTest` (added this round: back lands on the Sync step, never Preview; "Start again" returns to Sync)
- Gallery previews: `OnboardingFirstListScreenPreview`, `OnboardingFirstListLoadingPreview`, `OnboardingFirstListContactsDeniedPreview`, `OnboardingNudgeSummaryLightPreview`, the Error and Not found previews added this round, with the curtain pass
