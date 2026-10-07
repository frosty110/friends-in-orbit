# List settings

**Route:** `lists/{listId}/config`
**Group:** Lists
**Status:** active
**Last reviewed:** 2026-10-07
**Spec:** [orbit-lists](../orbit-lists/README.md): LIST-21, LIST-22, LIST-24, LIST-25, LIST-26, LIST-27, BULK-05; [rule-engine](../rule-engine/README.md); ADR [0010](../_foundations/ADRs/0010-interval-slider-floor-and-scale.md); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Lists: "List settings" in a row's menu, the settings control on an archived row, and straight after "Create"
- Home: "List settings" in a card's long-press menu
- Card view: "List settings" in the list menu, and on a smart list's "No one matches this rule right now." deck
- Make your first list (onboarding) reuses this screen's body; see that page view for the differences

## What the user sees

- App bar: Back, the list's name with a pencil beside it as the title ("List" under the curtain, or before it loads; LIST-26), and "Done" (the one accent; LIST-21). TalkBack hears the title as a button, "Rename list, Inner orbit" ("Rename list" under the curtain), and the screen is still announced by the list's name
- While renaming: the title is a single-line field labelled "List name", with "Cancel" and "Save list name" where Done was, and no back arrow
- Sections, top to bottom:
  - "How often": "Aim for every 14 days", a slider from 1 to 60 days with "1 day", "2 weeks", "1 month" and "2 months" under it, for every list (LIST-24). A Late night list shows "Aim for every 3 days" and an Energize list "Aim for every day", their real base intervals; a smart list has it too. A list whose rhythm cannot be read says "This list has no rhythm yet. Move the slider to set one." over the slider
  - "Time of day": one choice of "Any time", "Mornings", "Afternoons", "Evenings" or "Nights" (LIST-25), and under it one line: "Nudges can come at any time of day." or "Nudges for this list come only in the morning, from 7am to 12pm." ("in the afternoon, from 12pm to 5pm", "in the evening, from 5pm to 9pm", "at night, from 9pm to 7am"), in the phone's 12 or 24 hour format. A window set before this that is none of the parts shows as one more chip, selected: "Custom: 9am to 5pm", with "Nudges for this list come only from 9am to 5pm."
  - "Nudges": "Send nudges" ("A gentle nudge when someone here is worth a call."), and "Nudges paused" while they are off
  - "When to nudge": seven 48dp day toggles that wrap on a narrow phone rather than shrink; one or more times with "Add time", "Change time" and a remove control; the plan as one line ("Weekdays at 10am", "Every day at 9am and 6pm", "No days selected: nudges off", "No time set: tap “Add time”")
  - "Smart rule" (smart lists): the rule as a sentence with its setting ("Added in the last 30 days", "No call in the last 90 days", "The top 20% of the people you call"), or "Nothing to set. This list shows everyone you have never called."; the day field under "No call in the last 90 days" announces that sentence to TalkBack as its name, and saves once per Done or focus loss
  - "People": a header row with the count ("11 people") and, on its right, "Add people" with a plus (regular lists only; LIST-27); then who is on the list, "Showing 20 of 48" with "Show all" on long lists, and a remove control per person ("Remove {name} from list")
  - "Make this a regular list" with the note "The people here now stay, and the list stops adding people by itself. This can't be undone." (smart lists)
  - A second "Done" at the foot
- Nothing but Done is in the accent: the selected time of day, day toggles, switches and sliders use ink or the soft tint (LIST-21)

## Actions and menus

- Everything saves as you change it; "Done" and Back both return to where you came from
- Rename from the title: tap the name or the pencil, type, then "Save list name" or the keyboard's Done; "Cancel" or Back leaves the name as it was (Back first leaves the edit, then the screen). A blank name keeps the old one
- Move "How often": a Keep in touch list takes the new interval; a Late night or Energize list becomes an ordinary list at the interval chosen (LIST-24). Letting go where it was changes nothing
- Pick a time of day: the list's nudges are held to that part from then on and its nudge is rescheduled at once. A custom window stays as it is until another part is picked
- The time picker follows the phone's 12 or 24 hour setting, as do the hours under Time of day
- Remove a person: "Removed {name}" with Undo, which puts them back
- "Add people": opens the Add people picker for this list
- "Make this a regular list": asks "Make this a regular list?" / "The 5 people here now stay, and the list stops adding people by itself. This can't be undone." (or "No one is on this list right now, and it stops adding people by itself. This can't be undone.") with "Make it regular" and "Cancel"; then "This is now a regular list."; a list with no rhythm gets Keep in touch
- A change that could not be saved, a rename included, says "Couldn't save your change" and shows no success message

## States

- Loading: the app bar alone, quietly, until the list is known; Done and the rename control appear only once there is a list
- Not found: "List not found" / "It may have been deleted." with "Go back"
- Error: "Orbit couldn't load this list" / "Nothing is lost. Try again in a moment." with Try again (LIST-22)
- A smart list with no matches: "No one matches this rule right now." under People; a regular list with no one: "No one in this list yet."
- Renaming: the title is a field, as above, until it is saved or cancelled
- During onboarding (Make your first list): the name is an editable field at the top, and the nudge days and time show as a read-only summary with "Change the days or time any time in this list's settings."
- Privacy curtain: the title reads "List" and TalkBack "Rename list", people's names read "Contact", and the rename field's text is masked while typing (PRIV-03)

## Leads to

- The Add people picker ("Add people"); it returns here with "Added 3 people to {list}" and Undo
- Done or Back returns to Lists, Home or Card view, whichever opened the screen

## Tests that pin it

- `ListConfigViewModelTest` (saves as you go; How often for every rule type, the Late night and Energize conversion and the no-change release; each time of day's window, Any time's nulls and a custom window left alone; rename, blank and a failed rename; remove with undo, convert, the failure path, Error and recovery, Not found)
- `ListSettingsControlsTest` (Time of day is one radio group, Custom included; the title's rename button, field, Save, Cancel, Back and curtain; Add people in the People header, not at the foot)
- `TimeOfDayMappingTest`, `RuleIntervalTest`, `IntervalScaleLabelsTest`, `NudgeScheduleTest`, `NudgeScheduleNextSlotTest`, `SmartRuleEditIntegrationTest`, `SmartRuleEditorTest` (the day field commits once per Done or focus loss and is named by its sentence), `RuleParamsResolutionTest`, `ListRepositoryConvertTest`, `RuleOverrideRoundTripTest`
- Gallery previews: `ListConfigContentPreview`, `ListConfigScreenStaticReadyLightPreview`, `ListConfigScreenStaticLateNightPreview`, `ListConfigScreenSmartReadyDarkPreview`, `ListConfigCustomWindowPreview`, `ListConfigNoRhythmPreview`, `ListConfigRenamingPreview`, `ListConfigErrorPreview`, `ListConfigNotFoundPreview`, `HowOftenSliderEveryDayPreview`, `HowOftenSliderThreeDaysPreview`, `TimeOfDayPickerAnyTimePreview`, `TimeOfDayPickerNightsPreview`, `TimeOfDayPickerCustomPreview`, `NudgeScheduleSectionLightPreview`, `NudgeScheduleSectionEmptyPreview`, `MembersPreviewPopulatedPreview`, `MembersPreviewSmartEmptyLightPreview`, `ConvertToStaticDialogLightPreview`, with the curtain pass
