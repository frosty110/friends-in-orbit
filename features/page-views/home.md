# Home

**Route:** `home`
**Group:** Core loop
**Status:** active
**Last reviewed:** 2026-10-07
**Spec:** [home](../home/README.md): HOME-6, HOME-7, HOME-8, HOME-9, HOME-10, HOME-11, HOME-14 (added 2026-10-07); NOTE-04 and NOTE-05 in [contact-detail](../contact-detail/README.md); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- The start destination once onboarding is complete: every cold launch lands here, and Done clears the way back so Back from Home leaves the app
- Card view: "Go home" or Back, when the deck was opened from Home or by a nudge, a widget or the "Call next" shortcut
- The note page: "Not now", Back, or a saved note, when it was opened from here or from the notification after a call

## What the user sees

- App bar: no back arrow; the Search, Lists and Settings icons
- "Today" and the date ("Wednesday 3 June"), with no count of people "due" and nothing that reads as a cleared backlog (HOME-6)
- At the top of the list, the calls waiting for a note (HOME-14): every call of a minute or more in the last day with someone on a list, with nothing written about them since and not dismissed, one per person, newest first. One is a card: the face, "You called Kai" or "Kai called you", "14 min · 2 hours ago", "Add a note" and "Dismiss". Two or more lie in a pile: the newest card on top, the edges of one or two more under it, and "3 calls to write about"
- One full-width card per list, in the order set on Lists: the list's name and size ("4 people", "No one yet"), a glyph on lists that fill themselves (TalkBack: "Smart list"), then "Next up": the person this list would suggest first, with their face, their name, a warm line on when you last spoke ("You spoke today", "You spoke yesterday", "You spoke 3 weeks ago", "You haven't spoken yet") and a quiet, muted, labelled call button ("Call Kai", HOME-9). A card with nobody to suggest reads "All quiet for now"
- Under each card the "Last 7 days" rhythm strip (HOME-7): one bar per call of three minutes or more, its outline showing who called ("You" / "Them"); today's letter is in ink; a day with calls can be tapped (HOME-8)
- "New list" after the last card
- A short, calm reflection line at the foot of the screen (the ReflectionFooter in vision/00-home)
- The one accent element: "Create your first list" on a fresh install. With lists on the page nothing is in the accent: the call buttons are muted (rules.md Design 6), today's letter is ink, and the waiting calls' "Add a note" is quiet (Secondary)

## Actions and menus

- Tap a card: opens that list's Card view
- "Call {first name}" on a card: opens the dialer with the number filled in; Orbit never places the call itself (PRIV-05)
- Tap a day with calls ("See this day"): a sheet headed "Today", "Yesterday" or "Wednesday 3 June", a summary line ("You called 2 · They called 1"), then one row per call: face, name, "You called" or "They called", the length and the time ("You called · 14 min · 4:30pm"). A row opens that person. Someone who has since left the list reads "Someone"
- Long-press a card (a haptic; TalkBack: "Quick actions") for the list menu, in order:
  - "Add people": opens the Add people picker for this list. Not offered on smart lists, whose people come from their rule
  - "List settings": opens List settings
  - "Pause nudges", or "Resume nudges" while they are paused: changes in place, confirmed by "Nudges paused." or "Nudges on."
  - a divider, then "Archive" with the line "Hides {list} from home. You can restore it.": hides the list; "List archived." with Undo
  - "Delete": asks "Delete this list?" / "This removes the list. People stay in your contacts." with "Delete" and "Keep"; then "List deleted." with Undo, and the delete is held until the snackbar goes
- "New list", or "Create your first list": opens Lists with the create sheet already open
- The calls waiting for a note: "Add a note" opens the note page for that call (NOTE-04); the call waits until a note is saved. "Dismiss" closes one call, "Dismissed 1 call" with Undo. A tap on the pile (TalkBack: "3 calls to write about, Collapsed") opens it into one row per call, each with "Add a note" and "Dismiss", then "Dismiss all" ("Dismissed 3 calls" with Undo); the count line at the top ("Expanded") folds it. TalkBack names the person on each button ("Add a note about your call with Kai", "Dismiss your call with Kai")
- Search, Lists and Settings in the app bar open those screens
- A change that could not be saved says "Couldn't save your change" and shows no success message

## States

- Loading: quiet chrome (the app bar and the date) while the lists arrive from the cache-first feed; never a false "Create your first list" (ADR 0006)
- Empty, no lists yet: "Start with the people you keep meaning to call." with "Create your first list" (HOME-11: only when no list exists)
- Error: "Orbit couldn't load your lists" / "Nothing is lost. Try again in a moment." with Try again, which re-subscribes the feed (HOME-10)
- A list with people but nobody to suggest: "All quiet for now" in its Next up row. A list with no people: "No one yet"
- A quiet day on the strip is inert and announces "No calls"
- No calls waiting for a note: nothing at the top, the cards start under the date
- Privacy curtain: names read "Contact", the call button "Call Someone", list names "List", the day sheet's names "Someone", the waiting calls read "You called someone" / "Someone called you" with "Add a note about this call" and "Dismiss this call", and no photos are shown (PRIV-03)

## Leads to

- Card view (tap a card); Back returns here
- The dialer (the call button)
- A person's page (a day-sheet row)
- The note page (a waiting call's "Add a note"); Back, "Not now" or a saved note ("Note saved") returns here
- The Add people picker (menu); it returns here with "Added 3 people to {list}" and Undo
- List settings (menu); Done or Back returns here
- Lists (app bar), and Lists with the create sheet open ("New list", "Create your first list")
- Search and Settings (app bar)
- Home is the root of the back stack: Back leaves the app

## Tests that pin it

- `HomeViewModelTest` (states, the menu's snackbars, archive and delete with undo, the nudge toggle, the failure path, nudges cancelled on archive and delete, the why line's four forms)
- `WhyLineVoiceTest` (added this round: the rendered why line, for one gap in every bucket, breaks no voice rule)
- `HomeTileMenuTest` (added this round: menu order for paused and unpaused nudges, Add people absent on smart lists, Archive's supporting line, Archive and Delete the only destructive items)
- `HomeContentTest` (added this round: menu labels in order, "Call Kai" and "Call Someone" under the curtain, the full weekday in a day column's label, a quiet day announces "No calls")
- `RhythmDaySheetTest`, `HomeFeedRhythmTest`
- `HomeNotesWaitingTest` (added 2026-10-07: one call is a card whose buttons name the person, three are a closed pile that says how many and opens and folds, Dismiss and Dismiss all, the curtain), `AppViewModelTest` (the stack's state, its window and floor, a dismissal with Undo and a failed one), `WaitingCallsTest` (which calls wait, NOTE-05)
- `OrbitNavHostTest` (added 2026-10-07: "Add a note" on a waiting call opens the note page for that call, and leaving returns here)
- Gallery previews: `HomeContentPreview`, `HomeContentLongNamesPreview`, `HomeContentNotesWaitingPreview`, `HomeContentEmptyPreview`, `HomeContentLoadingPreview`, `HomeContentErrorPreview`, `RhythmDaySheetBodyPreview`, and the stack's `NotesWaitingStackOnePreview`, `NotesWaitingStackPilePreview`, `NotesWaitingStackPileOfTwoPreview`, `NotesWaitingStackOpenPreview`, `NotesWaitingStackCurtainPreview`, with the curtain pass
