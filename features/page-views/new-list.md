# New list

**Route:** `lists/new`. The older `lists?openCreate=true` still lands here: it opens New list over Lists, once
**Group:** Lists
**Status:** active
**Last reviewed:** 2026-10-07
**Spec:** [orbit-lists](../orbit-lists/README.md): LIST-28 (the step-by-step flow), LIST-29 (the templates), LIST-30 (How often), LIST-27 (the People section); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Home: "New list" after the last card, and "Create your first list" on a fresh install
- Lists: the floating "New list" button, or the centred "New list" when there are no lists
- A route handed in from outside as `lists?openCreate=true` (written before this flow existed): Lists opens, with New list over it

## What the user sees

One decision per step, under one app bar.

- App bar: Back, "New list", then "Step 1 of 4" (a list that fills itself has 3 steps: "Step 1 of 3") and, from the second step on, a close control ("Close"). The step's question is a heading under it, and TalkBack hears it when the step changes
- **Step 1, "How do you want to start?"** (LIST-29), every tile full width, one radio group:
  - "Start from blank" "Choose your own rhythm.", on the plain surface
  - "Inner orbit" "Closest people, about weekly.", "Family" "Steady, every couple of weeks.", "Drifted" "Reconnect about once a month.", each tinted a step along one ramp, warmest for the most often; the subtitle says the rhythm, the tint only decorates it
  - under a small "Smart list" label: "Recently added, not called" "Auto-updates as you add people."
  - the chosen tile has an ink outline and a check
- **Step 2, "Name your list"**: one field, "List name", filled with the template's name ("Start from blank" leaves it empty), focused with the keyboard up; the keyboard's action is Next
- **Step 3, "How often"**: "You can change this any time in the list's settings." over the How often slider List settings uses ("Aim for every 7 days", "1 day / 2 weeks / 1 month / 2 months"), set from the template; every 2 days for "Start from blank" and the list that fills itself
- **Step 4, "Add people"** (not for the list that fills itself): with nobody chosen, "Choose the people you want to keep in touch with on this list."; once people are chosen, the People section List settings shows: "3 people" with "Add people" on its right, and each person with "Remove {name} from list"
- The footer: "Next"; "Create list" on the last step (How often, for the list that fills itself); on step 4 with nobody chosen, "Add people", with "Create without people" (quiet) above it
- The one accent element: the footer's main button ("Next", "Create list" or "Add people")

## Actions and menus

- Pick a tile: chooses how to start. The name follows the template while it is blank or still the last template's name, and How often follows it until the slider has been moved, so going back and picking another template keeps what you typed or set
- "Next": goes on once the step is answered: a template on step 1, a name that is not blank on step 2 (until then it is disabled; the keyboard's Next does nothing either)
- Back (the arrow, or the phone's Back): the previous step, with everything entered kept. On step 1 it leaves New list
- "Close" (steps 2 to 4), and Back on step 1: with something entered (a template picked counts) asks "Discard this list?" with "Keep going" and "Discard"; with nothing entered it just leaves
- "Add people" (step 4, the footer or the People header): opens the Add people picker to choose people for the list, with whoever is already chosen ticked; its button reads "Add 3 people", and it brings them back here. Back from the picker changes nothing
- "Remove {name} from list": takes them off before the list is made
- "Create list" / "Create without people": makes the list, its rhythm and its people in one go, then returns to the screen New list was opened from with "Created {name}." and the new list in place, last in the order. Nudges start as the create sheet's did: on, any time of day, the default schedule
- A Create that fails says "Couldn't save your change", leaves nothing half-made, and keeps you on the step with everything entered; nothing is pressable while it is in flight

## States

- No loading or error state of its own: everything entered lives in the flow, and the only read is the chosen people's names, which arrive with the picker's result
- A chosen person who has since left the phone's contacts drops out of step 4's list and is not added
- Privacy curtain: the name field reads "List" and the people on step 4 "Contact", with no faces (PRIV-03). "Created {name}." shows only while Orbit is on screen, which is when the curtain is up: the app's snackbar host stops when the app leaves the screen, which is when the curtain comes down
- The template tiles are not masked: they name templates, which are copy, and no name is entered on step 1

## Leads to

- The Add people picker (step 4), which returns here
- Home or Lists, whichever opened New list: after Create ("Created {name}."), after "Discard", or on Back from step 1 with nothing entered
- From the older `lists?openCreate=true`: Lists, under New list

## Tests that pin it

- `NewListViewModelTest` (added 2026-10-07: the steps in order for a regular and a smart template, Next refusing a missing template and a blank name, Back keeping every entry, the entries surviving a process death, a template filling in only what was not typed or set, Create writing the list, its rhythm and its people with "Created {name}.", the smart list made from How often with no people, a person who has left dropping out, a failed Create leaving nothing behind and keeping the step, Create only from the last step)
- `NewListContentTest` (added 2026-10-07: Back and Close and "Discard this list?", each step's button and progress, the People step's two ways on, nothing pressable while creating)
- `CreateListUseCaseTest` (added 2026-10-07: one transaction for list, rhythm and people; last in the order; a failure part way leaves nothing; the smart list's rule and no people; blank names and a missing template refused)
- `CreateListTemplateCatalogTest` (the order, the three intervals, the smart rule, no Mentors), `TemplateSelectionSemanticsTest` (the tiles are one radio group and hand over their names)
- `ThemeContrastTest` (the tiles' text on each tint, every theme, mode and hue)
- `OrbitNavHostTest` (added 2026-10-07: Home and Lists open New list and leaving returns to them, `lists?openCreate=true` opens it over Lists once, the picker's selection comes back), `RoutesTest`
- Gallery previews: `NewListStartWithPreview`, `NewListStartWithChosenPreview`, `NewListStartWithSmartPreview`, `NewListStartWithDiscardPreview`, `NewListNamePreview`, `NewListNameBlankPreview`, `NewListNameCurtainPreview`, `NewListHowOftenPreview`, `NewListHowOftenSmartPreview`, `NewListPeopleEmptyPreview`, `NewListPeoplePreview`, `NewListPeopleCurtainPreview`, `NewListCreatingPreview`, `NewListDiscardPreview`, light, dark and 200%, with the curtain pass (the first step's previews are exempt from it for their template names)
