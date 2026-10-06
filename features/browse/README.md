# browse

**Status:** in-progress
**Last reviewed:** 2026-10-06
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/browse/` (`BrowseListScreen.kt`, `BrowseViewModel.kt`, `BrowseUiState.kt`, `GlobalSearchScreen.kt`, `ListSelectorSheet.kt`, `MultiSelectActionBar.kt`, `MultiSelectOverflowMenu.kt`); shared row `android/app/src/main/java/app/orbit/ui/components/BrowseRow.kt`; the shared pause sheet `android/app/src/main/java/app/orbit/ui/components/PauseDurationSheet.kt`
- Tests: `android/app/src/test/java/app/orbit/ui/screens/browse/BrowseViewModelTest.kt`, `android/app/src/test/java/app/orbit/ui/screens/browse/BrowseErrorShellTest.kt` (Robolectric: the error shell's actions), `android/app/src/test/java/app/orbit/ui/screens/browse/GlobalSearchViewModelTest.kt`, `android/app/src/test/java/app/orbit/ui/screens/browse/BrowseRowMenuTest.kt`, `android/app/src/test/java/app/orbit/ui/screens/browse/MultiSelectOverflowMenuTest.kt`; the Move, Copy and Pause-all use cases in `android/app/src/test/java/app/orbit/domain/usecase/{MoveContactsUseCaseTest,CopyContactsUseCaseTest,BulkPauseUseCaseTest}.kt`; instrumented: `android/app/src/androidTest/java/app/orbit/ui/components/BrowseRowGestureTest.kt`
- Feed: `android/app/src/main/java/app/orbit/data/feed/BrowseFeed.kt` (process-scoped per-list snapshot; ADR 0006)

---

## Product

### Why it exists

Sometimes the user wants the full list, not one person at a time. Browse is the escape hatch from the card-view constraint: scan everyone on a list, search by name, act directly without the swipe gesture. It also doubles as the surface for managing list membership (remove from list via long-press or multi-select).

### User story

As a user, I open a list and see everyone on it, the people Orbit will suggest first at the top in the order it will suggest them, then everyone else by name. I can search by name, see at a glance who is worth a call now, and long-press for quick actions.

### Behavior

- **Row content:** queue position, photo, name, last-called timestamp (relative, "3 days ago"), subtle "due" dot when the rule-engine marks the contact ready to surface, trailing phone icon (tap dials). The phone icon is muted (`fgMuted`), not accent: it repeats on every row, and an accent icon per row spent the screen's one accent element many times (rules.md Design 5). The due dot is the screen's one accent element (it marks who is ready, which is what Browse is for); the queue head's number is ink since 2026-10-05, and active filters use the cluster-tier tint.
- **Sections:** the queue sits under "Next up" (the numbers are the order Orbit will suggest people, vision BROWSE-1; the heading is the glossary's spelling, the same as Home's cards, and read "Up next" until 2026-10-06); members outside the rotation (paused, out of hours, no rhythm) follow under "Everyone else", or "On this list" when nobody is queued. Both labels are headings for TalkBack.
- **Sort:** queue order (`SurfaceQueueUseCase`, carried by `BrowseFeedSnapshot.queueOrder`) for the queued people, then everyone else alphabetically by display name (BROWSE-01). Search ranks within that order without reordering it. No user-facing sort control in v1.
- **Search:** sticky box at top. Matches by name or number (the shared `ContactSearch` matcher). Filters live without scroll-jump. The box grows with large text instead of clipping it. Under the privacy curtain what the user typed is masked like any other name (PRIV-03; `OrbitSearchField` draws the mask over its buffer and never saves it).
- **Filters:** "Recently called" (the glossary's spelling, the same as the picker's sort; "Called recently" until 2026-10-06) and "Not called yet", the shared `OrbitFilterChip` (checkbox semantics, a check when on), in a row that scrolls sideways rather than breaking a label at 200% font scale.
- **Call log access off:** the rows keep their names and drop the call-time line ("Never called" would be a false claim), and a notice above them, "Orbit can't see your calls, so call times are hidden.", carries "Open settings" (the shared `OrbitInlineNotice`, as on Card view and Call history). A call filter in that state is the `CallLogDenied` message with "Open settings" as the primary action and "Clear filters" under it. Until 2026-10-06 both named Settings and gave no path. Search shows the same notice above its results and drops the same line (BROWSE-03).
- **Long-press row:** opens the quick-action menu: Call, Select, Pause, Ignore, each with an icon. A paused row offers Unpause in place of Pause (snackbar "Unpaused {name}" with Undo). Pause opens the one shared `PauseDurationSheet` ("1 week", "1 month", "Until you unpause"); the snackbar reads "Paused {name} for 1 week", "... for 1 month" or "... until you unpause". Multi-select's Pause all opens the same sheet and words it the same way ("Paused 3 people until you unpause"; until 2026-10-06 it had its own dialog whose third option read "Indefinitely"). Ignore-all reads "Ignored 3 people", Remove "Removed 3 people from {list}" (the noun was missing until 2026-10-06). A Move or Copy that could not happen (the list was archived or became a smart list meanwhile) says "Couldn't save your change" instead of an empty snackbar with Undo. A write that throws (any bulk action, a row's Pause, Unpause or Ignore, or an Undo) says the same "Couldn't save your change", offers no Undo and announces nothing else; the selection stays so the action can be tried again. Until 2026-10-06 those paths had no catch at all: with no `CoroutineExceptionHandler` in the app a failing write took the process down, and the user was told nothing either way (rules.md Code 3).
- **Tap row:** routes to contact-detail (`features/contact-detail/README.md`). In multi-select, tap toggles the row instead.
- **Multi-select:** entered from a row's "Select" (with that row selected) or from the app bar's Select button ("Select people", with nothing selected). Each row is a checkbox for TalkBack with Orbit's own check mark (`OrbitCheckbox`, not Material's); the dial button is hidden rather than left inert. The bar above has two rows, exit / "N selected" / "More actions", then Move to…, Copy to…, Remove, which wrap whole when the text is large (on one 56dp row, Remove and the overflow fell off a 411dp screen and the buttons were 40dp). The three buttons, and the overflow's Pause all and Ignore all, are disabled while nothing is selected and while a bulk write is in flight; "Select all" in the overflow stays live so an empty entry has somewhere to go.
- **Smart lists:** a smart list's rows are written by `SmartListMembershipSync`, not by the user (`features/orbit-lists/README.md`), so while browsing one there is no "+", the Empty state has no "Add people" (its body says the rule fills the list), and the selection bar offers Copy only, not Remove or Move. The Move and Copy sheets never offer a smart list as a target, and the use cases refuse one (see MOVE-03). The same rule hides the "+" on a smart list's row in Lists manager.
- **Empty list state:** "No one here yet" with "Add people", which opens the picker for this list (the "+" in the app bar says the same to TalkBack; both said "Add contacts" until 2026-10-05). No urgency, no hustle.
- **Loading and errors (BROWSE-06):** a quiet skeleton until the list's first data arrives, never "No one here yet" for a list that has people; "Couldn't load this list" with the shared error body and "Try again" if reading fails. A route whose list id does not parse is this error too, not an empty list (until 2026-10-06 it was `Empty`, whose "Add people" opened the picker for a list that does not exist), but it offers "Go back" alone: Try again cannot re-read an id that never parsed, and it was offered there anyway, as the accent, and did nothing (rules.md Code 3). The state carries the difference (`Error.canRetry`).
- **Manual call-trigger** available from the quick-action menu, per PRD §Browse & Search.

### Requirements

Defined 2026-10-05 from what the code already cites for them (they were cited in source with no spec behind them; see rules.md "Known documentation debt"):

- **BROWSE-01: One list, in queue order.** Browse shows one list's members, queued people first in the order Orbit will suggest them (numbered, `SurfaceQueueUseCase`), then everyone else alphabetically.
- **BROWSE-02: Search and two filters.** A debounced (250ms) search box and two filters, "Recently called" (a call in the last 30 days) and "Not called yet". Filters combine with each other as a union and with search as an intersection.
- **BROWSE-03: Search everyone.** Global Search (`GlobalSearchScreen`, from Home) searches all contacts by name or number, ranks word-start matches first, shows each person's active lists ("Not on any list" when none) and offers "Add to lists" (the glossary's one name for the list picker; it said "Add to list" until 2026-10-06). When call log access is off it shows the denied notice with "Open settings" and drops the rows' call-time line, as Browse does. "Nothing matches" offers "Clear search", as Browse does.
- **BROWSE-04: Quick actions on long-press.** Long-pressing a row opens Call, Select, Pause (Unpause when paused) and Ignore, with a haptic; "Select" enters multi-select.
- **BROWSE-05: Dial from the row.** Each row's trailing phone icon dials (`ACTION_DIAL`) and is a separate 48dp target; TalkBack also gets "Call {name}" and "Open details" actions.
- **BROWSE-06: Never a false empty.** Browse and Search show a quiet skeleton until their data has arrived, and an error with "Try again" when a source fails. Before 2026-10-05 Browse started at Empty (its feed's placeholder snapshot had no members), so a full list flashed "No one here yet"; Search could flash its hint or "Nothing matches" before contacts loaded; and a failed feed crashed the app from `@ApplicationScope`.
- **MOVE-01: Enter multi-select.** "Select" in a row's quick actions enters multi-select with that row selected, with a haptic; the app bar's Select button ("Select people") enters with nothing selected, so the power is not gesture-only (vision BROWSE-2). An empty entry stays until Back, the bar's close or a selection; the bar's actions wait until something is selected.
- **MOVE-02: The selection bar replaces the app bar.** In multi-select the app bar cross-fades (250ms) to the selection bar, in place rather than floating.
- **MOVE-03: Move.** "Move to…" picks a target list in a sheet and moves the selection in one transaction, with Undo. Targets are regular lists only: the sheet lists non-archived static lists other than this one, and `MoveContactsUseCase` refuses a missing, archived or smart destination with a count of 0, which the screen reports as "Couldn't save your change". Move is not offered while browsing a smart list.
- **MOVE-04: Copy.** "Copy to…" adds the selection to a target list as well, with Undo. The same targets as MOVE-03 (regular lists other than this one; copying onto the current list reported a copy that did nothing), and `CopyContactsUseCase` refuses the same destinations.
- **MOVE-05: Select all.** "Select all", the first item of the selection bar's overflow, covers every row that matches the current search and filters, including rows not yet on screen, by id (the screen passes the ids of `Ready.contacts`).
- **MOVE-06: Leave multi-select.** Back, the bar's close, or deselecting the last row leaves multi-select.
- **MOVE-07: Every bulk action can be undone.** Remove, Move, Copy, Ignore all and Pause all each show a snackbar with Undo backed by the shared `UndoStack`. One write per tap: each handler claims `isCommitting` with a compare-and-set before reading the selection, and the bar is disabled meanwhile, so a second tap cannot write again and replace the only Undo with a no-op.

### Acceptance criteria

- [x] Search filters in-place: no scroll jump when query changes (the `LazyColumn` keys rows by contact id; `BrowseViewModelTest` covers the filtering).
- [x] Due dot uses the theme's accent token, never hardcoded (`BrowseRow.kt`, `OrbitTheme.colors.accent`).
- [x] Long-press uses the system long-press delay (`combinedClickable`, so the accessibility "Touch & hold delay" setting is honoured) and fires a haptic.
- [x] Tap opens the contact, long-press opens quick actions, and in multi-select the row is a checkbox that tap toggles (`BrowseRowGestureTest`, instrumented).
- [x] Row phone icon is muted, never accent (`BrowseRow.kt`, `tint = OrbitTheme.colors.fgMuted`).
- [x] A list that hasn't loaded is Loading, never Empty; a failed feed is an error and Retry recovers (BROWSE-06; `BrowseViewModelTest`, `GlobalSearchViewModelTest`). A list id that never parsed offers Go back and no Try again (`BrowseViewModelTest`, `BrowseErrorShellTest`).
- [x] A write that throws says "Couldn't save your change" with no Undo, and the selection bar is enabled again (`BrowseViewModelTest`).
- [x] Swipe-to-dismiss is explicitly NOT implemented, reserved for card-view semantics (no swipe modifier in `BrowseListScreen.kt`).
- [ ] Dark mode + 200% font scale + TalkBack pass.
- [ ] List of 500+ contacts scrolls smoothly (virtualized).

### Not in scope

- Editing contact details. That lives in `features/contact-detail/README.md` and deep-links to the system Contacts app.
- Global cross-list search on this screen. Browse stays scoped to the current list; the global surface is `GlobalSearchScreen` (reached from Home).
- Custom sort. User cannot reorder rows manually.

### Open product questions

- "Filter: only due" toggle: add, or rely on the due-dot visual alone? Leaning rely on the dot; don't add chrome until a user asks.
- ~~Phone icon on every row vs rules.md Design 6?~~ Resolved 2026-10-05 (UX rubric decision 9): Design 6 now allows a quiet, muted dial icon on each row of a list of people (Browse, Search); the accent call action stays one per screen.
- ~~Does search stay scoped to the current list, or expand to global?~~ Resolved: browse search stays list-scoped; global search ships as its own surface (`GlobalSearchScreen`, reached from Home).

---

## Technical

### Architecture

`BrowseViewModel` exposes `StateFlow`s for UI state, search query, active filters, the list's name and the list's type (`listName` and `listType`, both from the process-scoped `BrowseFeed.lists`; the type rides beside the state contract rather than on `Ready` because the app bar's "+" and the Empty state render outside `Ready`). Its state starts at Loading and maps the feed's `BrowseFeedSnapshot.NotLoaded` placeholder to Loading and `Failed` to Error; a route whose list id does not parse is `Error(canRetry = false)` from the start, with no feed behind it. Retry bumps a counter that re-calls `BrowseFeed.forList`, which evicted the failed list. Every bulk handler claims `_isCommitting` with `compareAndSet(false, true)` before reading the selection and releases it in `finally`; the screen passes `!isCommitting` to the selection bar as `enabled`. Every write runs inside `runMutation` (the `HomeViewModel` shape: rethrow `CancellationException`, otherwise emit "Couldn't save your change" and return false), with the Undo, the success snackbar and the exit from multi-select inside the block, so nothing is announced unless the write returned. `GlobalSearchViewModel` shows its hint for an empty query at once, Loading for a typed query until contacts arrive, and Error on failure, with the same retry counter; its `Ready` carries `callLogPermissionDenied`, pushed from the screen on every ON_RESUME (ARCH-04). The screens debounce query input (250ms) before forwarding committed strings to `vm::onSearchChanged`; filtering happens in the VM so the DAO isn't round-tripped on every keystroke. Global cross-list search lives in its own surface (`GlobalSearchScreen` + `GlobalSearchViewModel`, route `Routes.GlobalSearch`).

### Data model

Reads, through `BrowseFeed.forList(listId)`: `ListMembershipEntity` rows for the list, every `ContactEntity`, the recent `CallEventEntity` rows for the list's contacts, and the queue order from `SurfaceQueueUseCase`, combined into one `BrowseFeedSnapshot`. Per row, the VM derives: the last call from the newest event; due when the membership's `nextDueAt` is null or past and the person is neither paused nor ignored (the same rule as `ListRepository.recomputeDueCountForList`); a "Paused" or "Ignored" status word from the contact's flags.

### Permissions / integrations

- None directly. Relies on data populated by call-detection and contacts-ingestion.
- Call action from the quick-action menu follows the same dial mechanism as card-view; see `features/card-view/README.md` §Permissions.

### Known gotchas

- `LazyColumn` items must use stable keys (contact ID); using index as key causes flash-of-row during search filter changes.
- The row gesture has one owner. Browse wraps each row in its own `combinedClickable` (tap, long-press, multi-select toggle) and passes `BrowseRow(onTap = null)`, which means "the caller owns the gesture": the row then adds no click handler. When the row carried its own handler, it consumed the press before Browse's handler saw it, so taps and long-presses did nothing. Global Search passes a real `onTap`, so its rows still open the contact.
- A bulk write guards against its own double dispatch. Two quick taps on Remove once launched two coroutines before `onExitMultiSelect` cleared the selection; the second found no memberships left, re-inserted nothing on undo, yet reported the full count, so a second snackbar appeared and `UndoStack.put` (depth 1) replaced the real inverse with a no-op. The `compareAndSet` on `_isCommitting` is the fix and `BrowseViewModelTest` parks `listRepo.getById` to reproduce the overlap; disabling the bar is the belt to that brace.
- Smart lists are read-only to the user on this screen. `SmartListMembershipSync` re-adds whoever matches and removes whoever does not on each reconcile, so any user write into a smart list (Add people, Remove, Move, Copy) is reverted silently after the Undo window. The screen hides those controls and the sheet filters those targets; the use cases refuse them anyway (rules.md Code 3).

### Not in scope (technical)

- Background refresh of the row list. Flow-driven; updates arrive when the underlying data changes.
- Pagination. 500-contact lists are within Compose's smooth-scrolling envelope without paging.

### Open technical questions

- None open.
