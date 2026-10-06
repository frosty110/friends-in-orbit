# Done

**Route:** `onboard/done`
**Group:** Onboarding
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [onboarding](../onboarding/README.md): ONB-23, ONB-30; [notifications](../notifications/README.md)

---

## Reached from

- Make your first list: "Done"

## What the user sees

- No back arrow and no step counter
- "You're set up." / "Orbit hands you one name at a time. Call them, or choose Later and they'll come back around."
- A small picture of the swipe (TalkBack: "Swipe left for later, swipe right for sooner.")
- The nudge card (ONB-30), in one of three states: "Want a gentle nudge when someone is worth a call?" with "Allow nudges"; "Nudges are on. Each list can change when they come."; "No nudges for now. You can turn them on in Settings."
- "Open Orbit", the one accent element, disabled until your setup is saved

## Actions and menus

- "Allow nudges": the phone's notification permission dialog; the card then reads on or declined. If Orbit cannot record that it asked, "Couldn't save your change" shows over the content
- "Open Orbit": opens Home

## States

- Saving: "Open Orbit" is disabled until the completion is written, so a relaunch never lands back in onboarding half-done
- The nudge card: asked, on, or declined
- Save failed: "Couldn't save your change", over the content and above "Open Orbit", when the asked-once flag could not be written; the card still shows the answer
- Privacy curtain: nothing personal is on this screen

## Leads to

- Home, with the whole onboarding stack cleared: Back from Home leaves the app rather than returning here (ONB-23)

## Tests that pin it

- `OnboardingDoneViewModelTest` (the single completion write; the nudge flag and its failed write)
- `OnboardingDoneScreenTest` (added this round: with notifications denied the ask shows, Open Orbit waits for the save, and a snackbar has a place over the content)
- `OrbitNavHostTest` (added this round: Done lands on Home with an empty stack)
- Gallery previews: `OnboardingDoneContentPreview`, `OnboardingDoneNudgesOnPreview`
