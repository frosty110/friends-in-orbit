# Browse people

**Route:** `browse/{listId}`
**Group:** Core loop
**Status:** active
**Last reviewed:** 2026-10-05
**Index:** [Page views](../PAGE_VIEWS.md)

What a user expects to see or do here:

- Search the list (debounced) and clear the query quickly
- Filter by "Called recently" / "Not called yet"
- Tap a person to open their detail (in multi-select, tap selects or deselects); tap the phone icon to dial
- Phone icons are muted, not accent; one on every row conflicts with rules.md Design 6 (open question in [browse](../browse/README.md))
- Long-press a row for quick actions: Call, Ignore, Pause, Select (Unpause in place of Pause when the person is paused)
- Multi-select to Move / Copy / Remove, or Ignore-all / Pause-all
- Undo any bulk action via snackbar
- Add people via the "+" in the app bar
