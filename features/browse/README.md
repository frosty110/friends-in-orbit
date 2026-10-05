# browse

**Status:** in-progress
**Last reviewed:** 2026-10-05
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/browse/` (`BrowseListScreen.kt`, `BrowseViewModel.kt`, `BrowseUiState.kt`, `GlobalSearchScreen.kt`, multi-select components); shared row `android/app/src/main/java/app/orbit/ui/components/BrowseRow.kt`
- Tests: `android/app/src/test/java/app/orbit/ui/screens/browse/BrowseViewModelTest.kt`, `android/app/src/test/java/app/orbit/ui/screens/browse/GlobalSearchViewModelTest.kt`, `android/app/src/test/java/app/orbit/ui/screens/browse/BrowseRowMenuTest.kt`; instrumented: `android/app/src/androidTest/java/app/orbit/ui/components/BrowseRowGestureTest.kt`
- Feed: `android/app/src/main/java/app/orbit/data/feed/BrowseFeed.kt` (process-scoped per-list snapshot; ADR 0006)

---

## Product

### Why it exists

Sometimes the user wants the full list, not one person at a time. Browse is the escape hatch from the card-view constraint: scan everyone on a list, search by name, act directly without the swipe gesture. It also doubles as the surface for managing list membership (remove from list via long-press).

### User story

As a user, I open a list and see everyone on it, sorted most-recently-contacted first. I can search by name, see at a glance who's due, and long-press for quick actions.

### Behavior

- **Row content:** queue position, photo, name, last-called timestamp (relative, "3 days ago"), subtle "due" dot when the rule-engine marks the contact ready to surface, trailing phone icon (tap dials). The phone icon is muted (`fgMuted`), not accent: it repeats on every row, and an accent icon per row spent the screen's one accent element many times (rules.md Design 5). The due dot is the screen's one accent element (it marks who is ready, which is what Browse is for); the queue head's number is ink since 2026-10-05, and active filters use the cluster-tier tint.
- **Sections:** the queue sits under "Up next" (the numbers are the order Orbit will suggest people, vision BROWSE-1); members outside the rotation (paused, out of hours, no rhythm) follow under "Everyone else", or "On this list" when nobody is queued. Both labels are headings for TalkBack.
- **Sort:** last-contact descending. No user-facing sort control in v1.
- **Search:** sticky box at top. Matches by name or number (the shared `ContactSearch` matcher). Filters live without scroll-jump. The box grows with large text instead of clipping it.
- **Filters:** "Called recently" and "Not called yet", the shared `OrbitFilterChip` (checkbox semantics, a check when on), in a row that scrolls sideways rather than breaking a label at 200% font scale.
- **Long-press row:** opens quick actions: Call, Select, Pause, Ignore. A paused row offers Unpause in place of Pause (snackbar "Unpaused {name}" with Undo). The pause snackbar reads "Paused {name} for 1 week", "... for 1 month" or "... indefinitely"; multi-select's Pause-all words it the same way ("Paused 3 contacts indefinitely").
- **Tap row:** routes to contact-detail (`features/contact-detail/README.md`). In multi-select, tap toggles the row instead.
- **Multi-select:** each row is a checkbox for TalkBack with Orbit's own check mark (`OrbitCheckbox`, not Material's); the dial button is hidden rather than left inert. The bar above has two rows, exit / "N selected" / more, then Move to…, Copy to…, Remove, which wrap whole when the text is large (on one 56dp row, Remove and the overflow fell off a 411dp screen and the buttons were 40dp).
- **Empty list state:** "No one here yet" with "Add contacts", which opens the picker for this list. No urgency, no hustle.
- **Loading and errors (BROWSE-06):** a quiet skeleton until the list's first data arrives, never "No one here yet" for a list that has people; "Couldn't load this list" with "Try again" if reading fails.
- **Manual call-trigger** available from the quick-action sheet, per PRD §Browse & Search.

### Requirements

Defined 2026-10-05 from what the code already cites for them (they were cited in source with no spec behind them; see rules.md "Known documentation debt"):

- **BROWSE-01: One list, in queue order.** Browse shows one list's members, queued people first in the order Orbit will suggest them (numbered, `SurfaceQueueUseCase`), then everyone else alphabetically.
- **BROWSE-02: Search and two filters.** A debounced (250ms) search box and two filters, "Called recently" (a call in the last 30 days) and "Not called yet". Filters combine with each other as a union and with search as an intersection.
- **BROWSE-03: Search everyone.** Global Search (`GlobalSearchScreen`, from Home) searches all contacts by name or number, ranks word-start matches first, shows each person's active lists ("Not on any list" when none) and offers "Add to list".
- **BROWSE-04: Quick actions on long-press.** Long-pressing a row opens Call, Select, Pause (Unpause when paused) and Ignore, with a haptic; "Select" enters multi-select.
- **BROWSE-05: Dial from the row.** Each row's trailing phone icon dials (`ACTION_DIAL`) and is a separate 48dp target; TalkBack also gets "Call {name}" and "Open details" actions.
- **BROWSE-06: Never a false empty.** Browse and Search show a quiet skeleton until their data has arrived, and an error with "Try again" when a source fails. Before 2026-10-05 Browse started at Empty (its feed's placeholder snapshot had no members), so a full list flashed "No one here yet"; Search could flash its hint or "Nothing matches" before contacts loaded; and a failed feed crashed the app from `@ApplicationScope`.
- **MOVE-01: Enter multi-select.** "Select" in a row's quick actions (or its long-press path) enters multi-select with that row selected, with a haptic.
- **MOVE-02: The selection bar replaces the app bar.** In multi-select the app bar cross-fades (250ms) to the selection bar, in place rather than floating.
- **MOVE-03: Move.** "Move to…" picks a target list in a sheet and moves the selection in one transaction, with Undo.
- **MOVE-04: Copy.** "Copy to…" adds the selection to a target list as well, with Undo.
- **MOVE-05: Select all.** Selecting all covers every row that matches the current search and filters, including rows not yet on screen, by id.
- **MOVE-06: Leave multi-select.** Back, the bar's close, or deselecting the last row leaves multi-select.
- **MOVE-07: Every bulk action can be undone.** Remove, Move, Copy, Ignore all and Pause all each show a snackbar with Undo backed by the shared `UndoStack`.

### Acceptance criteria

- [ ] Search filters in-place — no scroll jump when query changes.
- [ ] Due dot uses design token `--accent` from theme, never hardcoded.
- [ ] Long-press triggers after ~300ms with haptic feedback.
- [ ] Tap opens the contact, long-press opens quick actions, and in multi-select tap toggles (`BrowseRowGestureTest`).
- [ ] Row phone icon is muted, never accent.
- [x] A list that hasn't loaded is Loading, never Empty; a failed feed is an error and Retry recovers (BROWSE-06; `BrowseViewModelTest`, `GlobalSearchViewModelTest`).
- [ ] Swipe-to-dismiss is explicitly NOT implemented — reserved for card-view semantics.
- [ ] Dark mode + 200% font scale + TalkBack pass.
- [ ] List of 500+ contacts scrolls smoothly (virtualized).

### Not in scope

- Editing contact details. That lives in `features/contact-detail/README.md` and deep-links to the system Contacts app.
- Global cross-list search on this screen. Browse stays scoped to the current list; the global surface is `GlobalSearchScreen` (reached from Home).
- Custom sort. User cannot reorder rows manually.

### Open product questions

- "Filter: only due" toggle — add, or rely on the due-dot visual alone? Leaning rely on the dot; don't add chrome until a user asks.
- ~~Phone icon on every row vs rules.md Design 6?~~ Resolved 2026-10-05 (UX rubric decision 9): Design 6 now allows a quiet, muted dial icon on each row of a list of people (Browse, Search); the accent call action stays one per screen.
- ~~Does search stay scoped to the current list, or expand to global?~~ Resolved: browse search stays list-scoped; global search ships as its own surface (`GlobalSearchScreen`, reached from Home).

---

## Technical

### Architecture

`BrowseViewModel` exposes `StateFlow`s for UI state, search query, and active filters. Its state starts at Loading and maps the feed's `BrowseFeedSnapshot.NotLoaded` placeholder to Loading and `Failed` to Error; Retry bumps a counter that re-calls `BrowseFeed.forList`, which evicted the failed list. `GlobalSearchViewModel` shows its hint for an empty query at once, Loading for a typed query until contacts arrive, and Error on failure, with the same retry counter. The screen debounces query input (250ms) before forwarding committed strings to `vm::onSearchChanged`; filtering happens in the VM so the DAO isn't round-tripped on every keystroke. Global cross-list search lives in its own surface (`GlobalSearchScreen` + `GlobalSearchViewModel`, route `Routes.GlobalSearch`).

### Data model

Reads: `ListMembershipEntity` joined with `ContactEntity` and the latest `CallEntity` timestamp per contact. Computed `due: Boolean` via rule-engine per row (`features/rule-engine/README.md`).

### Permissions / integrations

- None directly. Relies on data populated by call-detection and contacts-ingestion.
- Call action from the quick-action sheet follows the same dial mechanism as card-view — see `features/card-view/README.md` §Permissions.

### Known gotchas

- `LazyColumn` items must use stable keys (contact ID) — using index as key causes flash-of-row during search filter changes.
- The row gesture has one owner. Browse wraps each row in its own `combinedClickable` (tap, long-press, multi-select toggle) and passes `BrowseRow(onTap = null)`, which means "the caller owns the gesture": the row then adds no click handler. When the row carried its own handler, it consumed the press before Browse's handler saw it, so taps and long-presses did nothing. Global Search passes a real `onTap`, so its rows still open the contact.

### Not in scope (technical)

- Background refresh of the row list. Flow-driven; updates arrive when the underlying data changes.
- Pagination. 500-contact lists are within Compose's smooth-scrolling envelope without paging.

### Open technical questions

- None open.
