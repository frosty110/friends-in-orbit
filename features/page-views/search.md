# Search

**Route:** `search`
**Group:** People
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [browse](../browse/README.md): BROWSE-03, BROWSE-05, BROWSE-06; BULK-06 in [orbit-lists](../orbit-lists/README.md); LAUNCH-01 in [widgets](../widgets/README.md); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Home: the Search icon in the app bar
- The "Search" launcher shortcut ("Search your people")

## What the user sees

- App bar: Back and "Search"
- A search field, "Search people", focused with the keyboard up as the screen opens, with its own clear control
- Before typing: "Find someone" / "Search everyone in your contacts by name or number."
- Results, one row per person: face, name, "Last call: 3 days ago" or "Never called" (left out when call log access is off), the lists they are on or "Not on any list", an "Add to lists" action, and a quiet, muted, labelled dial button ("Call Kai")
- When call log access is off, a notice above the results: "Orbit can't see your calls, so call times are hidden."
- No control on this screen is in the accent; the dial buttons are muted (rules.md Design 6)

## Actions and menus

- Typing searches everyone in your contacts by name (accents folded) or number, word-start matches first (BROWSE-03); the clear control empties the field
- Tap a row: opens the person's page
- The dial button: opens the dialer with the number filled in (BROWSE-05)
- "Add to lists": opens the Add to lists picker for that person (BULK-06)

## States

- Loading: a quiet skeleton while contacts load, never a false "Nothing matches" (BROWSE-06)
- Nothing typed yet: "Find someone" / "Search everyone in your contacts by name or number."
- No hits: "Nothing matches “q”" / "Try a shorter name, or part of their number." with "Clear search"
- Error: "Couldn't search right now" / "Nothing is lost. Try again in a moment." with Try again
- Call log access off: the notice, and no call times on the rows; "Never called" is never said about someone Orbit cannot see
- Privacy curtain: names read "Contact", list chips "List", and the typed search is masked (PRIV-03)

## Leads to

- Contact detail (tap a row); Back returns here
- The Add to lists picker ("Add to lists"); it returns here with "Added to 2 lists" and Undo
- The dialer
- Back returns to Home

## Tests that pin it

- `GlobalSearchViewModelTest` (ranking, states, the call-log-denied flag added this round)
- `ContactSearchTest` (the matcher: phone digits, folded accents)
- `OrbitNavHostTest` (added this round: "Open settings" leads to Settings)
- Gallery previews: `GlobalSearchContentPreview`, `GlobalSearchResultsPreview`, `GlobalSearchLoadingPreview`, `GlobalSearchNoMatchesPreview`, `GlobalSearchErrorPreview`, with the curtain pass
