# Preview your first list

**Route:** `onboard/preview` (shown as step 4 of 4, like Make your first list)
**Group:** Onboarding
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [onboarding](../onboarding/README.md): the suggested first list; PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Reading your call history: "Continue", when three or more people qualify (otherwise this step skips itself)

## What the user sees

- App bar: "4 of 4", no back arrow
- "Here are the people you've been in touch with" (3 to 10 of them, the most recent first; the heading carries no count) / "Untick anyone you'd rather not include. You can edit anything before saving."
- "5 selected" with "Select all" or "Deselect all"
- One row per person: face, name, "Called 4 days ago", a checkbox
- "Make this my first list", the one accent element, and "Start blank"

## Actions and menus

- Tap a row to tick or untick; "Select all" / "Deselect all" switch everyone
- "Make this my first list": creates a list named "In touch" with the ticked people and opens it in Make your first list
- "Start blank": a new, empty list, opened in the same place
- If a first list is already under way, either button continues it: a typed name is kept and people are only added, never a second list

## States

- Loading: a quiet skeleton of rows, with "Start blank" live so no one has to wait
- Fewer than three people qualify: the step skips itself to a blank first list
- Error: says the suggestions could not be read, with Try again (the accent) and "Start blank" still live
- Privacy curtain: names read "Contact" and no photos are shown (PRIV-03)

## Leads to

- Make your first list, with the suggested people or blank
- No back arrow; system back returns to Reading your call history

## Tests that pin it

- `OnboardingPreviewViewModelTest` (three candidates surface, fewer skip, ranking, Error and retry added this round)
- `OnboardingListStarterTest` (one first list, resumed rather than duplicated)
- Gallery previews: `OnboardingPreviewScreenPreview`, `OnboardingPreviewLoadingPreview`, the Error preview added this round, with the curtain pass
