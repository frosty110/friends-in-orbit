# Browse people

**Route:** `browse/{listId}`
**Group:** Core loop
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [browse](../browse/README.md): BROWSE-01, BROWSE-02, BROWSE-04, BROWSE-05, BROWSE-06, MOVE-01 to MOVE-07; BULK-05 in [orbit-lists](../orbit-lists/README.md); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Card view: "Browse people" in the list menu, and "Browse this list" on the All quiet state
- Nothing else: Browse belongs to one list and is always one step from that list's deck

## What the user sees

- App bar: Back, the list's name ("Your people" before it loads, "List" under the curtain), a Select button ("Select people"), and "+" ("Add people"; not on smart lists)
- A search field, "Search your people", with its own clear control; two filter chips, "Recently called" (a call in the last 30 days) and "Not called yet" (BROWSE-02)
- When call log access is off, a notice: "Orbit can't see your calls, so call times are hidden." with "Open settings"
- "Next up": the people Orbit will suggest, numbered in the order it will suggest them, the head of the queue emphasised (BROWSE-01); then "Everyone else" alphabetically, or "On this list" when nobody is queued
- Each row: face, name, "Last call: 3 days ago" or "Never called" (left out when call log access is off), the word "Paused" or "Ignored" where it applies, a small dot on someone worth a call now (TalkBack: "Worth a call now"), and a quiet, muted, labelled dial button ("Call Kai", BROWSE-05)
- In multi-select the app bar becomes the selection bar (MOVE-02): a close control ("Exit selection"), "3 selected", "Move to…", "Copy to…", "Remove", and a "More actions" overflow; every row shows a checkbox
- The one accent element: the dot that marks someone worth a call now; the dial buttons are muted (rules.md Design 6)

## Actions and menus

- Tap a row: opens the person's page; in multi-select it selects or deselects them
- The dial button: opens the dialer with the number filled in
- Search narrows by name (accents folded) or number as you type; the clear control empties it. The two filters combine with each other as either, and with search as both
- Long-press a row (a haptic; TalkBack: "Quick actions"), in order: "Call", "Select", "Pause" ("Unpause" when the person is paused), then "Ignore" (BROWSE-04)
  - "Pause" opens "Pause for how long?" with "1 week", "1 month", "Until you unpause"; then "Paused {name} for 1 week", "Paused {name} for 1 month" or "Paused {name} until you unpause", with Undo. "Unpause": "Unpaused {name}"
  - "Ignore": "Ignored {name}" with Undo
  - "Select" enters multi-select with that row selected (MOVE-01)
- The Select button enters multi-select with nothing selected; the bar's buttons and overflow stay disabled until something is
- Selection bar: "Move to…" and "Copy to…" open "Move to which list?" / "Copy to which list?", listing your other regular lists only (a smart list fills itself, so it is never a target, and this list is never offered); then "Moved 3 people to {list}" / "Copied 1 person to {list}" with Undo (MOVE-03, MOVE-04). "Remove": "Removed 3 people from {list}" with Undo. Remove and Move are not offered while browsing a smart list
- When no other regular list exists the sheet says "No other lists yet" / "Make another list first." with "Done"
- The overflow, in order: "Select all" (every row matching the current search and filters, including those not yet on screen; MOVE-05), "Pause all" (the same duration sheet; "Paused 3 people for 1 week" with Undo), then "Ignore all" ("Ignored 3 people" with Undo)
- Every bulk action can be undone from its snackbar (MOVE-07); a second tap while one is committing does nothing extra
- A move or copy that could not happen says "Couldn't save your change"
- Back, the bar's close, or deselecting the last row leaves multi-select (MOVE-06)
- "+" and the empty list's "Add people" open the Add people picker for this list (BULK-05)
- "Open settings" on the notice or the filter state: opens Orbit's Settings, where the Call log row hosts the grant

## States

- Loading: a quiet skeleton, never "No one here yet" for a list with people (BROWSE-06)
- Empty: "No one here yet" / "Add the people you'd like this list to bring up." with "Add people" (not on smart lists)
- Filters match nobody: "No one matches these filters" / "Everyone on this list is hidden by the filters you chose." with "Clear filters"
- Search matches nobody: "Nothing matches “q”" / "Try a shorter name, or part of their number." with "Clear search"
- A call filter without call log access: "These filters need your call history" / "Orbit can't see your calls, so it can't tell who you've called. You can turn call log access on in Settings." with "Open settings" and "Clear filters"
- Error: "Couldn't load this list" / "Nothing is lost. Try again in a moment." with Try again; a malformed list id is this error, never an empty list
- Ready with call log access off: the notice above the rows, and no call times on them
- Privacy curtain: names read "Contact", the title "List", and the typed search is masked (PRIV-03)

## Leads to

- Contact detail (tap a row); Back returns here
- The dialer (the dial button; "Call" in the quick actions)
- The Add people picker ("+"; the empty list); it returns here with "Added 3 people to {list}" and Undo
- Settings ("Open settings")
- Back returns to Card view

## Tests that pin it

- `BrowseViewModelTest` (states, queue order, filters and search, every bulk action with its snackbar and undo, smart-list targets refused, select all after a search, a second dispatch ignored)
- `BrowseRowMenuTest` (quick-action order); `MultiSelectOverflowMenuTest` (added this round: Select all, Pause all, Ignore all)
- `MoveContactsUseCaseTest`, `CopyContactsUseCaseTest`, `BulkPauseUseCaseTest`, `BulkIgnoreUseCaseTest`
- `BrowseRowGestureTest` (instrumented: tap and long-press stay separate)
- `OrbitNavHostTest` (added this round: "Open settings" leads to Settings)
- Gallery previews: `BrowseContentPreview`, `BrowseMultiSelectPreview`, `BrowseLoadingPreview`, `BrowseEmptyPreview`, `BrowseFilteredEmptyPreview`, `BrowseErrorPreview`, the no-matches and call-log-denied previews added this round, `ListSelectorSheetLightPreview`, `MultiSelectActionBarPreview`, with the curtain pass
