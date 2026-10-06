# Search

> **Intent**: The direct line. Search exists for the moment you already know who you want: you do not need the loop to pick for you, you just need to get to *that one person* and act, from anywhere in the app. Speed and a clean path to "call / add to lists" are the entire job.

**Mission tie**: The escape hatch from the loop. The loop answers "who should I call?"; search answers "I already know, let me at them." Both must feel effortless.

---

## Today (as of 2026-10-05)

<img src="./actual-search.png" width="300" alt="Search: a back arrow and the title Search; a search pill containing maya with a clear button; two results. Maya Ahmed, Last call: 3 days ago, with Inner orbit and Late night chips and an Add to list action; Maya Brooks, Never called, Not on any list, Add to list. Each row has a muted phone button." />

*JVM gallery render (`PreviewGalleryTest`, Robolectric, light, 411dp, font scale 1.0) at `783a964`, 2026-10-05, not a device capture (`GlobalSearchScreen.GlobalSearchResultsPreview`).*

- A **Search** title and a 48dp search pill ("Search people") with one clear control.
- Before you type: **Find someone**, "Search everyone in your contacts by name or number." The matcher is the one the picker uses (`ContactSearch`: names with accents folded, or phone digits), so a half-remembered number works here too.
- Results: face, name, the last call ("Last call: 3 days ago", "Never called"), the lists they are on as chips or **Not on any list**, a muted dial (rules.md Design 6), and **Add to lists** ("Add to list" in the render; one word from this round), which opens the list picker.
- Honest states (BROWSE-06): a skeleton while contacts load, "Nothing matches" only when the query really has no hits, "Couldn't search right now" with Try again.

Functional and tidy. The one remaining gap is the empty screen before you type.

---

## Where it's going

### `SEARCH-1` · A useful empty state · **Next**
Before you type, the screen says "Find someone" and nothing more. That is prime space for the most valuable shortcut in the app: **people not on any list**, the contacts you actually talk to but have not filed. Surfacing them here ("People you've called who aren't on a list") turns a quiet screen into the fastest on-ramp to organising your orbits, and it directly feeds the core loop (more filed people means more the card can surface). Same idea as `PICK-2`.

### `SEARCH-2` · Search by number too · **Shipped 2026-10-05 (`f2918f8`)**
The contact picker matched on name *or* number; search matched name only. Both now use the shared `ContactSearch` matcher, so "search" means the same thing everywhere.

### `SEARCH-3` · A touch more context per result · **Shipped 2026-10-05 (`f2918f8`)**
Each row carries the one fact that makes it actionable: the last call, and the lists the person is on (or "Not on any list"), so you can act straight from search without a detour into Contact Detail. One line each; the results stay scannable.
