# Add people

**Route:** `pick/contacts?targetListId={listId}` (Add mode, the default); `pick/contacts?mode=relink&relinkContactId={contactId}` (Re-link mode, which takes no list). The route also knows `mode=move` and `mode=copy`, but no screen opens them: moving and copying happen from Browse's selection bar
**Group:** Lists
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [orbit-lists](../orbit-lists/README.md): BULK-05, PICK-01, PICK-02, PICK-03 (defined this round), PICK-04, PICK-05, PICK-06, PICK-08, PICK-09; CONTACT-07 in [contact-detail](../contact-detail/README.md); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Home: "Add people" in a card's long-press menu
- Card view: "Add people" in the list menu, and on the empty deck
- Browse: "+" in the app bar, and "Add people" on the empty list
- Lists: "+" on a row
- List settings: "Add people" under People
- Make your first list (onboarding): "Add people"
- Contact detail: "Re-link" on the orphan banner (Re-link mode)
- Never for a smart list: its people come from its rule, so none of the surfaces above offer Add people on one

## What the user sees

- App bar: Back, and "Add people" or "Re-link contact"
- A search field, "Search name or number", with one clear control
- "Sort: Alphabetical", a 48dp control whose menu offers Alphabetical, Most called, Recently called and Recently added, the current order ticked; beside it "Show ignored" (or "Hide ignored")
- Filter chips: "Starred"; one of "Commonly called", "Rarely called", "Never called"; "Long gap"; "Not on a list"; and "On a list", which opens a menu of your other lists (or says "No lists yet"). Applied filters sit in their own always-visible row, each with its count ("Rarely called · 7") and an x; filters with no matches are greyed and pushed to the end, with "Grayed-out filters have no matches right now." and, before Orbit has your call history, "Filters like “long gap” wake up once Orbit has your call history."
- "Select all 14 matches" whenever search or a filter narrows the list and at least one match is unselected; over 200 matches it reads "Over 200 matches. Narrow the search to select them all." (PICK-03)
- The people, under sticky A to Z headers with a fast-scroll rail at the edge: face, name, "Last called 3 days ago · 4 calls" or "Never called", "On Inner orbit, Late night" (PICK-04), a check mark, and a "More actions for {name}" button; ignored people, when shown, are muted and tagged "Ignored"
- A bar docked under the list once anything is selected: "3 selected", "Clear", and the commit button, "Add 3 people to {list}" or "Re-link {name}" (PICK-06); it never hides the last row
- The one accent element: the commit button

## Actions and menus

- Tap a row to select or deselect; an ignored row opens its menu instead (PICK-08)
- Search narrows whatever the filters left, by name with accents folded or by phone digits (PICK-05); filters narrow together (PICK-02); "On a list" reads "On {list}" once chosen (PICK-01)
- "More actions for {name}" (also a long-press, with a haptic): "Open in Contacts" ("See their call and message history in your phone's contacts app."), then "Ignore" ("Hide {name} from Orbit. They stay in your phone's contacts."; "Ignored {name}" with Undo) or "Unignore" ("Unignored {name}" with Undo)
- "Show ignored" reveals the people you ignore, muted and tagged, offering Unignore instead of selection
- "Clear" empties the selection
- The commit button closes the picker, and the snackbar shows on the screen you came from: "Added 3 people to {list}" with Undo, or "Couldn't save that" when nothing could be written (a smart list is refused the same way)
- Re-link mode: pick exactly one person (a new pick replaces the old); only other people mirrored from the phone who are not orphaned, ignored or archived are listed, and there is no "Select all". "Re-link {name}" merges them into the orphan, history kept (CONTACT-07): "Re-linked to {phone contact}" with Undo, which splits them again, or "Couldn't save that" with nothing changed

## States

- Loading: a quiet skeleton, never a blank page
- Contacts permission not yet granted: "Allow access to your contacts" / "Orbit reads your phone contacts so you can add them to lists. Nothing is uploaded: your contacts stay on this device." with "Grant access" and "Not now"
- Turned off in the phone's settings: "Contacts access is off" / "Turn it on in your phone's settings to add people to your lists. Your contacts stay on this device." with "Open phone settings"
- No contacts on the phone: "No contacts on this device" / "Add people to your phone's contacts, then come back here."
- Everyone is already on the list: "Everyone in your contacts is already on {list}", with a way back
- Everyone left is ignored: "Everyone here is ignored" with "Show ignored"
- Re-link with no one to link to: "No other phone contacts to link to"
- Nothing matches: "Nothing matches “q”" / "Try a shorter name, part of a number, or one filter fewer.", or "Nothing matches these filters" / "Try removing a filter."
- The list is gone: "List not found" / "It may have been removed." with "Go back". The person is gone (Re-link): "This person isn't in Orbit anymore" / "They may have been removed." with "Go back"
- Error: "Couldn't load your contacts" / "Nothing is lost. Try again in a moment." with Try again; the selection survives it (PICK-09)
- Committing: the bar waits, and a second tap does nothing extra
- Privacy curtain: names read "Contact", list names "List" ("On 2 lists"), the title drops the person's name, and the search text is masked (PRIV-03)

## Leads to

- Back to the screen that opened it, on Back or on commit (the snackbar follows)
- Your phone's contacts app ("Open in Contacts"); the phone's settings ("Open phone settings")

## Tests that pin it

- `ContactPickerViewModelTest` (candidates, filters, sort, select all and its cap, commit and undo, the smart-list guard, Re-link, Error with the selection kept and recovery)
- `ContactPickerUiStateTest` (filters, counts, the empty reasons), `PickerCandidatesTest` (added this round), `PickerModeTitleTest`, `PickerRowMenuTest` (added this round: Open in Contacts, Ignore destructive, Unignore)
- `ContactSearchTest`, `RelinkContactUseCaseTest`, `UnignoreContactUseCaseTest`
- Gallery previews: `ContactPickerReadyPreviewLight`, `ContactPickerReadyPreviewDark`, `ContactPickerContentPreview`, `ContactPickerRationalePreviewLight`, `ContactPickerDeniedPreviewLight`, `EmptyDeviceContactsPreviewLight`, `FilterChipsRowPreview`, `SelectAllMatchingChipPreviewLight`, `PickerContactRowPreviewLight`, `BatchCounterAddPreviewLight`, the Error, not-found, no-matches, committing and Re-link previews added this round, with the curtain pass
