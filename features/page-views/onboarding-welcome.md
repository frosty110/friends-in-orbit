# Welcome

**Route:** `onboard/welcome`
**Group:** Onboarding
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [onboarding](../onboarding/README.md): ONB-23, ONB-31

---

## Reached from

- The first launch, before onboarding is complete (the app's start destination until then)
- After "Reset Orbit" in Settings

## What the user sees

- No app bar controls: no back arrow and no step counter (Welcome is not a counted step)
- The Orbit mark settling into place above the name "Orbit" (ONB-31); static when the phone's animations are off
- "Call the people you keep meaning to call."
- Three short lines: "Not every contact is a friend. You choose who matters.", "One name at a time, with enough context to say yes.", "Everything stays on your phone: no cloud, no tracking."
- "Let's go", the one accent element

## Actions and menus

- "Let's go": opens the Contacts permission step

## States

- No loading, empty or error state: everything on this screen is fixed copy
- Reduced motion: the mark is already in place
- Privacy curtain: nothing personal is on this screen

## Leads to

- Permission: Contacts (1 of 4); its back arrow returns here
- Welcome is the root of the back stack during onboarding: Back leaves the app

## Tests that pin it

- `OrbitNavHostTest` (added this round: Done lands on Home with nothing to go back to, so Welcome is never re-entered)
- Gallery preview: `OnboardingWelcomeScreenPreview`
