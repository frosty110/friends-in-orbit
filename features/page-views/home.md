# Home

**Route:** `home`
**Group:** Core loop
**Status:** active
**Last reviewed:** 2026-10-08
**Spec:** [home](../home/README.md): HOME-5 and HOME-9 (amended 2026-10-08), HOME-6, HOME-7, HOME-8, HOME-10, HOME-11, HOME-13 and HOME-14 (added 2026-10-07); NOTE-04 and NOTE-05 in [contact-detail](../contact-detail/README.md); LIST-28 in [orbit-lists](../orbit-lists/README.md); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- The start destination once onboarding is complete: every cold launch lands here, and Done clears the way back so Back from Home leaves the app
- Card view: "Go home" or Back, when the deck was opened from Home or by a nudge, a widget or the "Call next" shortcut
- The note page: "Not now", Back, or a saved note, when it was opened from here or from the notification after a call
- New list, when it was opened from here: after Create ("Created {name}.", the new list's card last), or on leaving it

## What the user sees

- App bar: no back arrow; the Search, Lists and Settings icons
- "Today" and the date ("Wednesday 3 June"), with no count of people "due" and nothing that reads as a cleared backlog (HOME-6)
- At the top of the list, the calls waiting for a note (HOME-14): every call of a minute or more in the last day with someone on a list, with nothing written about them since and not dismissed, one per person, newest first. One is a card: the face, "You called Kai" or "Kai called you", "14 min · 2 hours ago", "Add a note" and "Dismiss". Two or more lie in a pile: the newest card on top, the edges of one or two more under it, and "3 calls to write about"
- One full-width card per list, in the order set on Lists, in three zones (HOME-5). Across the top, a compact row in the list's own band colour: the list's name, a glyph on lists that fill themselves (TalkBack: "Smart list"), and at the end of the same line its size ("4 people", "No one yet"); a long name is cut short with an ellipsis, the size never is, and at large text the name may take two lines. Under it, on the card's lighter wash, "Next up": the person this list would suggest first, with their face, their name and a short line on when you last spoke ("Spoke today", "Spoke yesterday", "Spoke 3 weeks ago", "No calls yet"). No button on that row: the whole card opens the list's deck, where Call is (HOME-9; until 2026-10-08 the row ended in a "Call Kai" button). A card with nobody to suggest reads "All quiet for now"
- Under each card the 7-day rhythm strip (HOME-7): one bar per call of three minutes or more, its outline showing who called ("You" / "Them"), a longer call a taller bar. On a busy day the calls share the day's column: each keeps a bar with its outline and some of the person's colour, none is pushed out, and a long call still stands taller than a short one; today's letter is in ink; a day with calls can be tapped (HOME-8). Its header line is "See your week" with a chevron, the "You" / "Them" key beside it (under it at large text); until 2026-10-07 the line read "Last 7 days" (HOME-13)
- "New list" after the last card
- A short, calm reflection line at the foot of the screen (the ReflectionFooter in vision/00-home)
- The one accent element: "Create your first list" on a fresh install. With lists on the page nothing is in the accent: today's letter is ink, and the waiting calls' "Add a note" is quiet (Secondary)

## Actions and menus

- Tap a card, its Next up row included: opens that list's Card view
- Tap a day with calls ("See this day"): a sheet headed "Today", "Yesterday" or "Wednesday 3 June", a summary line ("You called 2 · They called 1"), then one row per call: face, name, "You called" or "They called", the length and the time ("You called · 14 min · 4:30pm"). A row opens that person. Someone who has since left the list reads "Someone". At the foot, "See the whole week" closes the sheet and opens the list's Week screen on this week (HOME-13)
- "See your week" on a card's strip: opens that list's Week screen on this week, the seven days the strip shows (HOME-13)
- Long-press a card (a haptic; TalkBack: "Quick actions") for the list menu, in order:
  - "Add people": opens the Add people picker for this list. Not offered on smart lists, whose people come from their rule
  - "List settings": opens List settings
  - "Pause nudges", or "Resume nudges" while they are paused: changes in place, confirmed by "Nudges paused." or "Nudges on."
  - a divider, then "Archive" with the line "Hides {list} from home. You can restore it.": hides the list; "List archived." with Undo
  - "Delete": asks "Delete this list?" / "This removes the list. People stay in your contacts." with "Delete" and "Keep"; then "List deleted." with Undo, and the delete is held until the snackbar goes
- "New list", or "Create your first list": opens New list, step by step (LIST-28); Create returns here with "Created {name}." and the new list's card after the others
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
- Privacy curtain: names read "Contact", list names "List", the day sheet's names "Someone", the waiting calls read "You called someone" / "Someone called you" with "Add a note about this call" and "Dismiss this call", and no photos are shown (PRIV-03)

## Leads to

- Card view (tap a card); Back returns here
- A person's page (a day-sheet row)
- The Week screen ("See your week" on a strip, or "See the whole week" on a day sheet); Back returns here
- The note page (a waiting call's "Add a note"); Back, "Not now" or a saved note ("Note saved") returns here
- The Add people picker (menu); it returns here with "Added 3 people to {list}" and Undo
- List settings (menu); Done or Back returns here
- Lists (app bar)
- New list ("New list", "Create your first list"); Create, or leaving it, returns here
- Search and Settings (app bar)
- Home is the root of the back stack: Back leaves the app

## Tests that pin it

- `HomeViewModelTest` (states, the menu's snackbars, archive and delete with undo, the nudge toggle, the failure path, nudges cancelled on archive and delete, the why line's four forms)
- `WhyLineVoiceTest` (the rendered why line, for one gap in every bucket, breaks no voice rule; since 2026-10-08 it pins "Spoke 3 weeks ago" and "No calls yet", and Home's line as the card's without the full stop)
- `HomeTileMenuTest` (added this round: menu order for paused and unpaused nudges, Add people absent on smart lists, Archive's supporting line, Archive and Delete the only destructive items)
- `HomeContentTest` (menu labels in order, the full weekday in a day column's label, a quiet day announces "No calls"; added 2026-10-07: "See your week" on each card opens that list's week, and the day sheet's "See the whole week" does too; added 2026-10-08: the name row holds the name and the size on one line above Next up, and Next up has no call button and opens the deck)
- `RhythmDaySheetTest`, `HomeFeedRhythmTest`
- `RhythmBarsTest` (added 2026-10-08: on the busiest day a short call after a long one keeps its floor and whole outline; one long and two short calls each keep the whole outline, the long one taller in either order; five and thirty calls each keep a bar inside the column; a week that fits keeps its natural heights)
- `HomeNotesWaitingTest` (added 2026-10-07: one call is a card whose buttons name the person, three are a closed pile that says how many and opens and folds, Dismiss and Dismiss all, the curtain), `AppViewModelTest` (the stack's state, its window and floor, a dismissal with Undo and a failed one), `WaitingCallsTest` (which calls wait, NOTE-05)
- `OrbitNavHostTest` (added 2026-10-07: "Add a note" on a waiting call opens the note page for that call, and leaving returns here; "See your week" opens the Week screen for that list, and Back returns here; "New list" opens New list, and leaving it returns here, LIST-28)
- `HomeFeedRhythmTest` (added 2026-10-07: the Week screen's this week is the strip, call for call)
- Gallery previews: `HomeContentPreview`, `HomeContentLongNamesPreview`, `HomeContentBusyDaysPreview`, `HomeContentNotesWaitingPreview`, `HomeContentEmptyPreview`, `HomeContentLoadingPreview`, `HomeContentErrorPreview`, `RhythmDaySheetBodyPreview`, and the stack's `NotesWaitingStackOnePreview`, `NotesWaitingStackPilePreview`, `NotesWaitingStackPileOfTwoPreview`, `NotesWaitingStackOpenPreview`, `NotesWaitingStackCurtainPreview`, with the curtain pass
