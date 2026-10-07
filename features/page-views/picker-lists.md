# Add to lists

**Route:** `pick/lists?contactId={contactId}`
**Group:** People
**Status:** active
**Last reviewed:** 2026-10-07
**Spec:** [orbit-lists](../orbit-lists/README.md): BULK-06, PICK-06, PICK-09; PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Contact detail: "Add to lists"
- Search: "Add to lists" on a result

## What the user sees

- App bar: Back, and "Add {name} to lists" ("Add to lists" under the curtain)
- One row per regular list, with a checkbox (a smart list fills itself from its rule, so it is not offered as a row); a list the person is already on says "Already added" and cannot be picked
- A bar docked under the list once anything is selected: "2 selected", "Clear", and "Add" (TalkBack hears "Add to 2 lists"; the one accent; PICK-06)

## Actions and menus

- Tap a row to pick or unpick a list
- "Clear" empties the selection
- "Add" closes the picker, and the snackbar shows on the screen you came from: "Added to 2 lists" with Undo, which removes only what was just added and never a membership the person already had; "Couldn't save that" when nothing could be written (a smart list is refused the same way)
- "New list", offered only when you have no lists yet (BULK-06): a dialog, "New list", with "Name this list", "Create" and "Cancel" (a blank name cancels); the new list appears, ready to pick; "Couldn't create the list" if it could not be made

## States

- Loading: a quiet skeleton, never a blank page
- No lists yet: "No lists yet" / "Make one here and add this person in one more tap." with "New list"
- The person is gone: "This person isn't in Orbit anymore" / "They may have been removed." with "Go back"
- Error: "Couldn't load your lists" / "Nothing is lost. Try again in a moment." with Try again (PICK-09)
- Privacy curtain: the title reads "Add to lists" and list names read "List" (PRIV-03)

## Leads to

- Back to the screen that opened it, on Back or on commit (the snackbar follows)

## Tests that pin it

- `ListPickerViewModelTest` (membership flags, a member list cannot be added again and undo never removes it, counts from what was written, the save-failed path, create inline, not found, Error and recovery)
- `PickerCommitSnackbarHostViewModelTest`
- Gallery previews: `ListPickerReadyPreviewLight`, `ListPickerReadyPreviewDark`, `ListPickerContentPreview`, `CreateListNameDialogPreview`, the Loading, not-found, Error and no-lists previews added this round, with the curtain pass
