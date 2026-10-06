# Permission: Contacts / Call log

**Route:** `onboard/permissions/contacts` (step 1 of 4); `onboard/permissions/call-log` (2 of 4). `onboard/permissions/notifications` survives only for installs that saved it as their resume step before ONB-30: nothing navigates there now, and it continues to Reading your call history
**Group:** Onboarding
**Status:** active (the notifications route alone is legacy, resume only)
**Last reviewed:** 2026-10-06
**Spec:** [onboarding](../onboarding/README.md): ONB-14 (defined this round), ONB-15, ONB-30

---

## Reached from

- Welcome: "Let's go" (Contacts)
- Contacts: "Continue" or "Skip" (Call log)
- A relaunch mid-flow resumes at the step last saved; a resumed step is the first screen on the stack and shows no back arrow

## What the user sees

- App bar: a back arrow (Contacts goes back to Welcome, Call log to Contacts; none on a resumed step) and the step counter, "1 of 4" or "2 of 4"
- Contacts: "Build lists from your people" / "Orbit reads your phone contacts so you can pick who goes on each list by name."; a promise card, "Stays on your device" / "We don't upload your address book."
- Call log: "Read your call history" / "Orbit looks at when you last called or were called by each person, so it knows who's been quiet."; "Stays on your device" / "Read-only: Orbit can't make calls or change your call history."
- "Allow access", the one accent element, and under it "Continue without it"
- Once granted: the card reads "Allowed", the button reads "Continue", and "Continue without it" is gone
- Once the phone has refused twice: the card reads "Turned off in your phone's settings", the button reads "Open phone settings", and a line explains: "To turn this on later, open your phone's settings for Orbit and allow it under Permissions."; the note under the card says what still works: "You can still create lists. To add people, allow access in your phone's settings." / "You can still create lists. To use call history, allow access in your phone's settings."

## Actions and menus

- "Allow access": the phone's permission dialog; granting it switches the screen to its granted state
- "Continue": the next step
- "Continue without it": asks "Skip for now?" with what is lost ("Without contacts access, Orbit can't build your lists from your phone book." / "Without call log access, Orbit can't notice when you've already called someone, so the same person may keep coming up."), with "Skip" and "Go back" (ONB-15)
- "Open phone settings": Orbit's page in the phone's settings; the screen re-reads the permission when you come back

## States

- Not yet asked, granted, refused once (the ask can be repeated), turned off in the phone's settings (ONB-14); nothing loads here, so there is no loading or error state
- Resumed: the same screen with no back arrow
- Privacy curtain: nothing personal is on these screens

## Leads to

- Contacts: Call log. Call log: Reading your call history (3 of 4)
- The phone's permission dialog, and Orbit's page in the phone's settings
- Back: Welcome from Contacts, Contacts from Call log; a resumed step has nowhere to go back to, and shows no arrow rather than a tap that empties the screen

## Tests that pin it

- `OnboardingPermissionsViewModelTest` (the permission states)
- Gallery previews: `OnboardingPermContactsScreenPreview`, `OnboardingPermCallLogScreenPreview`, `OnboardingPermScreenPreview`, `OnboardingSkipDialogPreview`, and the granted preview added this round
