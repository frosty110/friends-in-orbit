# List settings

**Route:** `lists/{listId}/config`
**Group:** Lists
**Status:** active
**Last reviewed:** 2026-10-08
**Spec:** [orbit-lists](../orbit-lists/README.md): LIST-21, LIST-22, LIST-25, LIST-26, LIST-27, LIST-30, BULK-05; [rule-engine](../rule-engine/README.md); ADRs [0010](../_foundations/ADRs/0010-interval-slider-floor-and-scale.md) and [0011](../_foundations/ADRs/0011-number-wheel-for-day-and-count-settings.md); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Lists: "List settings" in a row's menu, the settings control on an archived row, and straight after "Create"
- Home: "List settings" in a card's long-press menu
- Card view: "List settings" in the list menu, and on a smart list's "No one matches this rule right now." deck
- Make your first list (onboarding) reuses this screen's body; see that page view for the differences

## What the user sees

- App bar: Back, the list's name with a pencil beside it as the title ("List" under the curtain, or before it loads; LIST-26), and "Done" (the one accent; LIST-21). TalkBack hears the title as a button, "Rename list, Inner orbit" ("Rename list" under the curtain), and the screen is still announced by the list's name
- While renaming: the title is a single-line field (`OrbitTextField`) named "List name" for TalkBack, with "List name" as its placeholder when emptied and the cursor after the name, with "Cancel" and "Save list name" where Done was, no back arrow, and no Done at the foot of the form either
- Sections, top to bottom:
  - "How often": "Aim for every 14 days" over a day wheel, 1 to 60 days, with "1 week", "2 weeks", "3 weeks", "1 month" and "2 months" under those days; flick or tap to choose, and it saves once it comes to rest (ADR 0011). For every list (LIST-30): a Late night list shows "Aim for every 3 days" and an Energize list "Aim for every day", their real base intervals; a smart list has it too. A list whose rhythm cannot be read says "This list has no rhythm yet. Choose how often below to set one." over the wheel
  - "Nudges": "Send nudges" ("A gentle nudge when someone here is worth a call."), and "Nudges paused" while they are off
  - "When to nudge": the one place the list's nudge timing is set (LIST-25). Seven 48dp day toggles that wrap on a narrow phone rather than shrink; one or more times with "Add time", "Change time" and a remove control; and the schedule as one line, which is when the nudge comes ("Weekdays at 10am", "Every day at 9am and 6pm", "No days selected: nudges off", "No time set: tap “Add time”"). There is no "Time of day" section above it since 2026-10-08: a list that had one now shows the times it really nudged at (an Evenings list on the default 10am shows 5pm)
  - "Smart rule" (smart lists): the rule as a sentence with its setting ("Added in the last 30 days", "No call in the last 90 days", "The top 20% of the people you call"), or "Nothing to set. This list shows everyone you have never called."; each setting is a number wheel under its sentence, named by that sentence for TalkBack, and saves once per gesture; no setting raises the keyboard (ADR 0011)
  - "People": a header row with the count ("11 people") and, on its right, "Add people" with a plus (regular lists only; LIST-27); a smart list says under the count "Orbit fills this list from its rule. To keep someone off it, ignore them." and has no remove control; then who is on the list, "Showing 20 of 48" with "Show all" on long lists, and a remove control per person ("Remove {name} from list")
  - "Make this a regular list" with the note "The people here now stay, and the list stops adding people by itself. This can't be undone." (smart lists)
  - A second "Done" at the foot
- Nothing but Done is in the accent: day toggles, switches and wheels use ink or the soft tint (LIST-21)

## Actions and menus

- Everything saves as you change it; "Done" and Back both return to where you came from
- Rename from the title: tap the name or the pencil, type, then "Save list name" or the keyboard's Done; "Cancel" or Back leaves the name as it was (Back first leaves the edit, then the screen). A blank name keeps the old one. Neither Done is offered during the edit, so it cannot close the screen with the name unsaved
- Turn "How often": a Keep in touch list takes the new interval; a Late night or Energize list becomes an ordinary list at the interval chosen (LIST-30). Settling where it was changes nothing
- Change a nudge day or time: saved at once, the list's nudge is rescheduled, and the line under it says the new schedule
- The time picker follows the phone's 12 or 24 hour setting, as do the times beside it. It says "Choose a time" and has a keyboard button ("Type the time", then "Use the clock") that swaps the dial for typed hours and minutes
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

- `ListConfigViewModelTest` (saves as you go; How often for every rule type, the Late night and Energize conversion and the no-change release; the nudge schedule read back as stored, or the default when missing or unreadable; rename, blank and a failed rename; remove with undo, convert, the failure path, Error and recovery, Not found)
- `ListSettingsControlsTest` (no Time of day section here or in Make your first list, "When to nudge" once, and its line says the days and times as they are; the title's rename button, field, Save, Cancel, Back and curtain, and no Done while renaming; Add people in the People header, not at the foot), `MembersPreviewTest` (the smart-list line on smart lists only), `FoldActiveWindowTest` and `Migration13To14FoldTest` (an old time of day folded into the times it really nudged at)
- `RuleIntervalTest`, `IntervalDaysPickerTest`, `OrbitWheelPickerTest`, `NudgeScheduleTest`, `NudgeScheduleNextSlotTest`, `SmartRuleEditIntegrationTest`, `SmartRuleEditorTest` (each wheel commits once per gesture, is named by its sentence, and shows a stored value outside its usual range as it is), `OrbitTextFieldTest` (the name field), `RuleParamsResolutionTest`, `ListRepositoryConvertTest`, `RuleOverrideRoundTripTest`
- Gallery previews: `ListConfigContentPreview`, `ListConfigScreenStaticReadyLightPreview`, `ListConfigScreenStaticLateNightPreview`, `ListConfigScreenSmartReadyDarkPreview`, `ListConfigNudgesPausedPreview`, `ListConfigNoRhythmPreview`, `ListConfigRenamingPreview`, `ListConfigErrorPreview`, `ListConfigNotFoundPreview`, `IntervalDaysPickerDefaultPreview`, `IntervalDaysPickerTwoWeeksPreview`, `NudgeScheduleSectionLightPreview`, `NudgeScheduleSectionEmptyPreview`, `NudgeScheduleSectionDarkMutedPreview`, `MembersPreviewPopulatedPreview`, `MembersPreviewSmartEmptyLightPreview`, `ConvertToStaticDialogLightPreview`, with the curtain pass
