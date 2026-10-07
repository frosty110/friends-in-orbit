# Lists

**Route:** `lists`; `lists?openCreate=true` opens with the create sheet already up
**Group:** Lists
**Status:** active
**Last reviewed:** 2026-10-07
**Spec:** [orbit-lists](../orbit-lists/README.md): LIST-20, LIST-22, LIST-23, LIST-24, BULK-05; PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Home: the Lists icon in the app bar
- Home: "New list", or "Create your first list" on a fresh install (the create sheet opens at once)

## What the user sees

- App bar: Back and "Lists"
- One row per list, in Home's order: a drag handle ("Reorder list"), the name, a "Smart list" chip where the list fills itself, a second line with its rhythm as its interval, whatever rule it runs ("Every 14 days"; "Every 3 days" for a Late night list and "Every day" for Energize, LIST-24) or its rule ("Recently added · 30 days", "Never called"), or "Couldn't read this list's rhythm" when the stored rhythm cannot be decoded (the deck fails on the same data), a count badge (left out at zero), "+" ("Add people to {list}"; regular lists only) and "More actions for {list}"
- A foot note: "Drag to reorder. Lists higher up show first on home."
- "Archived (2)", which expands to the archived lists, each with "Restore", a delete control ("Delete {list}") and a settings control ("List settings for {list}")
- A floating "New list" button when lists exist (the one accent; LIST-20), centred in the body when there are none

## Actions and menus

- Tap a row: opens that list's Card view, as on Home (LIST-23)
- Drag the handle to reorder; the order is Home's order
- "More actions for {list}", in order: "Rename" (a dialog, "Rename list", with "Save" and "Cancel"; the name is masked under the curtain), "List settings", "Pause nudges" (or "Resume nudges" while they are paused; "Nudges paused." / "Nudges on."), "Move up", "Move down", then after a divider "Archive" with the line "Hides {list} from home. You can restore it.": "List archived." with Undo
- "+": opens the Add people picker for that list
- "New list" opens the create sheet: "Choose a template" ("Inner orbit" "Closest people, about weekly.", "Family" "Steady, every couple of weeks.", "Mentors" "Every couple of months.", "Drifted" "Reconnect about once a month.", "Recently added, not called" "Auto-updates as you add people.", "Start from blank" "Choose your own rhythm."), "Name your list", then "Cancel" or "Create" (available once a template and a name are chosen). Each template creates the rhythm its subtitle names, and Create opens the new list's List settings
- Archived lists: "Restore" ("List restored."); the delete control asks "Delete this list?" / "This removes the list. People stay in your contacts." with "Delete" and "Keep", then "List deleted." with Undo, the delete held until the snackbar goes; the settings control opens List settings
- A change that could not be saved says "Couldn't save your change" and shows no success message

## States

- Loading: a quiet skeleton, never "No lists yet" before the lists are known
- Empty: "No lists yet" / "Add a list to start grouping the people you want to stay in touch with." with "New list"
- Error: "Orbit couldn't load your lists" / "Nothing is lost. Try again in a moment." with Try again (LIST-22)
- Privacy curtain: list names read "List" in rows, in the archived section and in the rename dialog (PRIV-03)

## Leads to

- Card view (tap a row); Back returns here
- The Add people picker ("+"); it returns here with "Added 3 people to {list}" and Undo
- List settings (the menu, an archived row, and after "Create"); Done or Back returns here
- Back returns to Home

## Tests that pin it

- `ListsManagerViewModelTest` (archive, restore, delete with undo, reorder, the nudge toggle, the failure path, the rhythm line for every rule type and its unreadable case), `RuleParamsResolutionTest` (the resolver shared with List settings)
- `ListRowMenuOrderTest` (menu order, and the same pause and resume words as Home)
- `ListsManagerScreenTest` (added this round: a row tap, "List settings" and create go to different places)
- `CreateListTemplateCatalogTest` (each template makes the rhythm its subtitle names)
- `OrbitNavHostTest` (added this round: a row tap opens the deck and "List settings" opens List settings, LIST-23)
- Gallery previews: `ListsManagerContentPreview`, `ListRowPreviewLightStatic`, `ListRowPreviewDarkSmart`, `ArchivedListRowPreviewLight`, `CreateListBottomSheetLightPreview`, `RenameListDialogLightPreview`, `DeleteListDialogLightPreview`, the Loading, Error and archived-expanded previews added this round, with the curtain pass
