# List settings

**Route:** `lists/{listId}/config`
**Group:** Lists
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [orbit-lists](../orbit-lists/README.md): LIST-21, LIST-22, BULK-05; [rule-engine](../rule-engine/README.md); ADR [0010](../_foundations/ADRs/0010-interval-slider-floor-and-scale.md); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Lists: "List settings" in a row's menu, the settings control on an archived row, and straight after "Create"
- Home: "List settings" in a card's long-press menu
- Card view: "List settings" in the list menu, and on a smart list's "No one matches this rule right now." deck
- Make your first list (onboarding) reuses this screen's body; see that page view for the differences

## What the user sees

- App bar: Back, the list's name ("List" under the curtain, or before it loads), and "Done" (the one accent; LIST-21)
- Sections, top to bottom:
  - "Name": the name with a pencil; tap to rename in place
  - "Rhythm": "Keep in touch" ("Surfaces each person on a steady rhythm you set."), "Late night" ("A slower, more patient rhythm for people you reach at night."), "Energize" ("A quicker rhythm that brings people back sooner after a call."); smart lists too
  - "How often": "Aim for every 14 days", a slider from 1 to 60 days with week and month ticks; Late night and Energize say instead that they run on their own, with nothing to set
  - "Active hours": "Always active" ("Nudges can come at any time of day"), or a start and an end time with a bar showing the window, and "Overnight list: active across midnight." when it crosses midnight; they limit when a nudge may post, never which days
  - "Nudges": "Send nudges" ("A gentle nudge when someone here is worth a call."), and "Nudges paused" while they are off
  - "When to nudge": seven 48dp day toggles that wrap on a narrow phone rather than shrink; one or more times with "Add time", "Change time" and a remove control; the plan as one line ("Weekdays at 10am", "Every day at 9am and 6pm", "No days selected: nudges off", "No time set: tap “Add time”")
  - "Smart rule" (smart lists): the rule as a sentence with its setting ("Added in the last 30 days", "No call in the last 90 days", "The top 20% of the people you call"), or "Nothing to set. This list shows everyone you have never called."; the day field under "No call in the last 90 days" announces that sentence to TalkBack as its name, and saves once per Done or focus loss
  - "People": who is on the list, "Showing 20 of 48" with "Show all" on long lists, a remove control per person ("Remove {name} from list"), and "Add people" (regular lists only)
  - "Make this a regular list" with the note "The people here now stay, and the list stops adding people by itself. This can't be undone." (smart lists)
  - A second "Done" at the foot
- Nothing but Done is in the accent: selected rhythms, day toggles, switches and sliders use ink or the soft tint (LIST-21)

## Actions and menus

- Everything saves as you change it; "Done" and Back both return to where you came from
- Rename in place: tap the pencil, type, "Save list name"; a blank name reverts to the old one
- The time picker and the active-hours bar's ticks follow the phone's 12 or 24 hour setting ("12a 6a 12p 6p" or "00 06 12 18")
- Remove a person: "Removed {name}" with Undo, which puts them back
- "Add people": opens the Add people picker for this list
- "Make this a regular list": asks "Make this a regular list?" / "The 5 people here now stay, and the list stops adding people by itself. This can't be undone." (or "No one is on this list right now, and it stops adding people by itself. This can't be undone.") with "Make it regular" and "Cancel"; then "This is now a regular list."; a list with no rhythm gets Keep in touch
- A change that could not be saved says "Couldn't save your change" and shows no success message

## States

- Loading: the app bar alone, quietly, until the list is known; Done appears only once there is a list to be done with
- Not found: "List not found" / "It may have been deleted." with "Go back"
- Error: "Orbit couldn't load this list" / "Nothing is lost. Try again in a moment." with Try again (LIST-22)
- A smart list with no matches: "No one matches this rule right now." under People; a regular list with no one: "No one in this list yet."
- During onboarding (Make your first list): the name is an editable field at the top, and the nudge days and time show as a read-only summary with "Change the days or time any time in this list's settings."
- Privacy curtain: the title and the name read "List", people's names "Contact", and the name field's text is masked while typing (PRIV-03)

## Leads to

- The Add people picker ("Add people"); it returns here with "Added 3 people to {list}" and Undo
- Done or Back returns to Lists, Home or Card view, whichever opened the screen

## Tests that pin it

- `ListConfigViewModelTest` (saves as you go, rename, remove with undo, convert, the failure path, Error and recovery, Not found)
- `ActiveHoursFormatterTest`, `IntervalScaleLabelsTest`, `NudgeScheduleTest`, `NudgeScheduleNextSlotTest`, `SmartRuleEditIntegrationTest`, `SmartRuleEditorTest` (the day field commits once per Done or focus loss and is named by its sentence), `RuleParamsResolutionTest`, `ListRepositoryConvertTest`, `RuleOverrideRoundTripTest`
- Gallery previews: `ListConfigContentPreview`, `ListConfigScreenStaticReadyLightPreview`, `ListConfigScreenStaticLateNightPreview`, `ListConfigScreenSmartReadyDarkPreview`, `ActiveHoursEditorLightNormalPreview`, `ActiveHoursEditorDarkOvernightPreview`, `NudgeScheduleSectionLightPreview`, `NudgeScheduleSectionEmptyPreview`, `MembersPreviewPopulatedLightPreview`, `MembersPreviewSmartEmptyLightPreview`, `ConvertToStaticDialogLightPreview`, the Error, Not found and active-hours-set previews added this round, with the curtain pass
