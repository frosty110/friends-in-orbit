# Lists

**Route:** `lists`; `lists?openCreate=true` opens New list over it, once (an older route; nothing in the app builds it since LIST-28)
**Group:** Lists
**Status:** active
**Last reviewed:** 2026-10-08
**Spec:** [orbit-lists](../orbit-lists/README.md): LIST-20, LIST-22, LIST-23, LIST-28, LIST-30, BULK-05; PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Home: the Lists icon in the app bar
- New list, when it was opened from here: after Create, or on leaving it

## What the user sees

- App bar: Back and "Lists"
- One row per list, in Home's order: a drag handle ("Reorder list"), the name, a "Smart list" chip where the list fills itself, a second line: a regular list's rhythm as its interval, whatever rule it runs ("Every 14 days"; "Every 3 days" for a Late night list and "Every day" for Energize, LIST-30); a smart list's rule, not its interval ("Recently added · 30 days", "Never called"); or "Couldn't read this list's rhythm" when the stored rhythm cannot be decoded (the deck fails on the same data), a count badge (left out at zero), "+" ("Add people to {list}"; regular lists only) and "More actions for {list}"
- A foot note: "Drag to reorder. Lists higher up show first on home."
- "Archived (2)", which expands to the archived lists, each with "Restore", a delete control ("Delete {list}") and a settings control ("List settings for {list}")
- A floating "New list" button when lists exist (the one accent; LIST-20), centred in the body when there are none

## Actions and menus

- Tap a row: opens that list's Card view, as on Home (LIST-23)
- Drag the handle to reorder; the order is Home's order
- "More actions for {list}", in order: "Rename" (a dialog, "Rename list", with "Save" and "Cancel"; the name is masked under the curtain), "List settings", "Pause nudges" (or "Resume nudges" while they are paused; "Nudges paused." / "Nudges on."), "Move up", "Move down", then after a divider "Archive" with the line "Hides {list} from home. You can restore it.": "List archived." with Undo
- "+": opens the Add people picker for that list
- "New list" opens New list, step by step (LIST-28): how to start, the name, how often, the people, then "Create list". Create returns here with "Created {name}." and the new list last in the order, scrolled into view (not when the older `lists?openCreate=true` link opened New list before this screen had loaded its lists, since it cannot then tell the new list from the old). Until 2026-10-07 it opened a create sheet here, and Create opened the new list's List settings
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
- List settings (the menu, an archived row); Done or Back returns here
- New list ("New list"); Create, or leaving it, returns here
- Back returns to Home

## Tests that pin it

- `ListsManagerViewModelTest` (archive, restore, delete with undo, reorder, the nudge toggle, the failure path, the rhythm line for every rule type and its unreadable case), `RuleParamsResolutionTest` (the resolver shared with List settings)
- `ListRowMenuOrderTest` (menu order, and the same pause and resume words as Home)
- `ListsManagerScreenTest` (a row tap, "List settings" and "New list" go to different places; "New list" is handed to the caller, which opens New list; a new list is scrolled into view, and the lists first shown and a restored one are not)
- `OrbitNavHostTest` (a row tap opens the deck and "List settings" opens List settings, LIST-23; "New list" opens New list and leaving it returns here, and `lists?openCreate=true` opens it once, LIST-28)
- Gallery previews: `ListsManagerContentPreview`, `ListsManagerReadyPreview`, `ListRowPreviewLightStatic`, `ListRowPreviewDarkSmart`, `ArchivedListRowPreviewLight`, `RenameListDialogLightPreview`, `DeleteListDialogLightPreview`, the Loading, Error and archived-expanded previews, with the curtain pass
