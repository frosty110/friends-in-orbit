# Browse people

**Route:** `browse/{listId}`
**Group:** Core loop
**Status:** active
**Last reviewed:** 2026-10-05
**Index:** [Page views](../PAGE_VIEWS.md)

What a user expects to see or do here:

- Search the list by name or number (debounced) and clear the query quickly
- Filter by "Called recently" / "Not called yet"
- See who's "Up next", in the order Orbit will suggest them, and "Everyone else"
- Tap a person to open their detail (in multi-select, tap selects or deselects); tap the phone icon to dial
- Phone icons are muted, not accent (rules.md Design 6 allows a quiet one on each row); the due dot is the screen's one accent
- Long-press a row for quick actions: Call, Ignore, Pause, Select (Unpause in place of Pause when the person is paused)
- Multi-select to Move / Copy / Remove, or Ignore-all / Pause-all; every action fits on a narrow phone and at large text
- A calm skeleton while the list loads, never "No one here yet" for a list with people; "Couldn't load this list" with Try again if reading fails
- Undo any bulk action via snackbar
- Add people via the "+" in the app bar
