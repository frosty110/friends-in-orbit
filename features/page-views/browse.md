# Browse people

**Route:** `browse/{listId}?focus={focus}` (`focus`, optional: the id of the person on the card when Card view's menu opened Browse)
**Group:** Core loop
**Status:** active
**Last reviewed:** 2026-10-08
**Spec:** [browse](../browse/README.md): BROWSE-01, BROWSE-02, BROWSE-04 to BROWSE-09, MOVE-01 to MOVE-07; BULK-05 in [orbit-lists](../orbit-lists/README.md); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Card view: "Browse people" in the list menu, which opens on the person the card shows (BROWSE-09); "Browse this list" on the All quiet state, with nobody to show
- Nothing else: Browse belongs to one list and is always one step from that list's deck

## What the user sees

- App bar: Back, the list's name ("Your people" before it loads, "List" under the curtain), a Select button ("Select people"), and "+" ("Add people"; not on smart lists)
- A search field, "Search your people", with its own clear control; two filter chips, "Recently called" (a call in the last 30 days) and "Not called yet" (BROWSE-02)
- When call log access is off, a notice: "Orbit can't see your calls, so call times are hidden." with "Open settings"
- "Next up": everyone in the order the card will bring them up, numbered, the first in ink (BROWSE-07). Each row says when on its second line: "Up now", "Later today", "Tomorrow", a weekday ("Thursday") or "In 2 weeks", by the day on the phone's calendar (tomorrow at any hour is "Tomorrow"), then "Last call: 3 days ago" or "Never called" ("Up now · Last call: 3 days ago"; the call part is left out when call log access is off)
- Under the order, one quiet line while rows can be dragged: "Drag to change who comes up next. A call, Later or Sooner moves people again."
- Then "Everyone else" (people the list's rule cannot place: an archived person, or everyone on a list with no rule; "On this list" when nobody is in the order), by name; "Paused", soonest back first, each with "Paused" beside the name and "Until 12 Oct" or "Until you unpause" on its second line; and "Ignored", by name, with "Ignored" beside the name
- Each row: a drag handle with the number (rows in the order only), face, name, the second line above, a small dot on someone worth a call now (TalkBack: "Worth a call now"; on a row in the order exactly when it says "Up now"), and a quiet, muted, labelled dial button ("Call Kai", BROWSE-05)
- Opened from the card: that person's row carries "On your card" over the name and is scrolled into view (BROWSE-09)
- In multi-select the app bar becomes the selection bar (MOVE-02): a close control ("Exit selection"), "3 selected", "Move to…", "Copy to…", "Remove", and a "More actions" overflow; every row shows a checkbox, and no row has a handle
- The one accent element: the dot that marks someone worth a call now; the dial buttons are muted (rules.md Design 6) and "On your card" is a quiet Stone chip

## Actions and menus

- Tap a row: opens the person's page; in multi-select it selects or deselects them
- The dial button: opens the dialer with the number filled in
- Drag a row by its handle (TalkBack: "Reorder Kai", "Reorder" under the curtain), in the order only and not while selecting: drop it anywhere in the order (BROWSE-08). To the top, they are "Up now" and first; between two people, between their times, and "Up now" when the person above is; to the end, just after the last. Then "Moved Kai earlier" or "Moved Kai later" with Undo, which puts every time back exactly. A drop that cannot be saved says "Couldn't save your change", offers no Undo, and the row goes back
- TalkBack, on a row in the order: "Call Kai", then "Move up" (not on the first row) and "Move down" (not on the last), the same move as a drag
- Search narrows by name (accents folded) or number as you type, keeping each group's order and numbers; the clear control empties it. The two filters combine with each other as either, and with search as both
- Long-press a row (a haptic; TalkBack: "Quick actions"), in order: "Call", "Select", "Pause" ("Unpause" when the person is paused), then "Ignore" (BROWSE-04)
  - "Pause" opens "Pause for how long?" with "1 week", "1 month", "Until you unpause"; then "Paused {name} for 1 week", "Paused {name} for 1 month" or "Paused {name} until you unpause", with Undo. "Unpause": "Unpaused {name}"
  - "Ignore": "Ignored {name}" with Undo
  - "Select" enters multi-select with that row selected (MOVE-01)
- The Select button enters multi-select with nothing selected; the bar's buttons and overflow stay disabled until something is
- Selection bar: "Move to…" and "Copy to…" open "Move to which list?" / "Copy to which list?", listing your other regular lists only (a smart list fills itself, so it is never a target, and this list is never offered); then "Moved 3 people to {list}" / "Copied 1 person to {list}" with Undo (MOVE-03, MOVE-04). "Remove": "Removed 3 people from {list}" with Undo. Remove and Move are not offered while browsing a smart list
- When no other regular list exists the sheet says "No other lists yet" / "Make another list first." with "Done"
- The overflow, in order: "Select all" (every row matching the current search and filters, including those not yet on screen; MOVE-05), "Pause all" (the same duration sheet; "Paused 3 people for 1 week" with Undo), then "Ignore all" ("Ignored 3 people" with Undo)
- Every bulk action can be undone from its snackbar (MOVE-07); a second tap while one is committing does nothing extra
- A move or copy that could not happen says "Couldn't save your change"; so does any write that fails (a bulk action, a row's Pause, Unpause or Ignore, a drag, an Undo), with no Undo and no success message, and the selection stays for another try
- Back, the bar's close, or deselecting the last row leaves multi-select (MOVE-06)
- "+" and the empty list's "Add people" open the Add people picker for this list (BULK-05)
- "Open settings" on the notice or the filter state: opens Orbit's Settings, where the Call log row hosts the grant

## States

- Loading: a quiet skeleton, never "No one here yet" for a list with people (BROWSE-06)
- Empty: "No one here yet" / "Add the people you'd like this list to bring up." with "Add people". On a smart list the body reads "This list fills itself from its rule, and no one matches it right now." with no action
- Filters match nobody: "No one matches these filters" / "Everyone on this list is hidden by the filters you chose." with "Clear filters"
- Search matches nobody: "Nothing matches “q”" / "Try a shorter name, or part of their number." with "Clear search"
- A call filter without call log access: "These filters need your call history" / "Orbit can't see your calls, so it can't tell who you've called. You can turn call log access on in Settings." with "Open settings" and "Clear filters"
- Error: "Couldn't load this list" / "Nothing is lost. Try again in a moment." with Try again. A malformed list id is this error, never an empty list, with "Go back" alone in place of Try again: there is nothing a retry could re-read
- Ready with call log access off: the notice above the rows, and no call times on them (each row in the order still says when)
- Opened from the card: the card's person marked and in view; when the deck moved between the card and Browse, they are marked where the order puts them, and once the first row changes (a drag here, a call) the mark moves to the new first row, the person the card shows now
- Privacy curtain: names read "Contact", the title "List", the handles "Reorder", and the typed search is masked (PRIV-03)

## Leads to

- Contact detail (tap a row); Back returns here
- The dialer (the dial button; "Call" in the quick actions)
- The Add people picker ("+"; the empty list); it returns here with "Added 3 people to {list}" and Undo
- Settings ("Open settings")
- Back returns to Card view, which shows the order as Browse left it

## Tests that pin it

- `BrowseViewModelTest` (states, filters and search, every bulk action with its snackbar and undo, smart-list targets refused, select all after a search, a second dispatch ignored, a write that throws reported with no Undo, a malformed id unchanged by Retry; added 2026-10-07: the sequence with its when words and groups, a filter and a search that keep the order and the numbers, the card's person marked where they are and the mark following the head, a drop's snackbar both ways and its Undo, a failed drop put back with no Undo, a drop for someone who left the order; added 2026-10-08: the when words by calendar day at an hour earlier than now's)
- `BrowseSequenceContentTest` (added 2026-10-07, Robolectric: the second lines and the group headings, "Reorder {name}" on rows in the order only, "Move up" and "Move down" after "Call {name}" and what each asks for, nothing moves while selecting, "Reorder" under the curtain, the card's person marked and scrolled into view)
- `SurfaceOrderTest` (added 2026-10-07: Browse's order is the card's for a mixed list; a stored time beats the rule's; never-scheduled people tie on one clock reading) and `ReorderSequenceUseCaseTest` (top, middle, end, equal times up now and in the future, people never scheduled as time passes, Undo exact, nothing written when nothing moves, a missing person or row, a failed write)
- `ComesUpTest` (added 2026-10-08: the when buckets are calendar days in the phone's zone, at off hours and across daylight-saving changes; shared with Card view)
- `BrowseErrorShellTest` (a failed feed offers Try again; a malformed id offers Go back and no Try again)
- `BrowseRowMenuTest` (quick-action order); `MultiSelectOverflowMenuTest` (Select all, Pause all, Ignore all)
- `MoveContactsUseCaseTest`, `CopyContactsUseCaseTest`, `BulkPauseUseCaseTest`, `BulkIgnoreUseCaseTest`
- `BrowseRowGestureTest` (instrumented: tap and long-press stay separate); the drag gesture itself has no test (it needs an emulator)
- `OrbitNavHostTest` ("Open settings" leads to Settings; Card view's "Browse people" opens Browse with the card's person as `focus`, "Browse this list" with none) and `RoutesTest`
- Gallery previews: `BrowseContentPreview` (the order with when, the handles and the line under it, the groups, the card's person), `BrowseCardPersonMovedPreview`, `BrowseNoCardPersonPreview`, `BrowseMultiSelectPreview`, `BrowseLoadingPreview`, `BrowseEmptyPreview`, `BrowseFilteredEmptyPreview`, `BrowseErrorPreview`, `BrowseBadLinkPreview`, `BrowseNoMatchesPreview`, `BrowseCallLogDeniedPreview`, `BrowseCallLogNoticePreview`, `BrowseSmartListPreview`, `BrowseSmartListEmptyPreview`, `ListSelectorSheetPreview`, `ListSelectorSheetNoTargetsPreview`, `MultiSelectActionBarPreview`, `BrowseRowPreview`, with the curtain pass
