# browse

**Status:** in-progress
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/browse/` (`BrowseListScreen.kt`, `BrowseViewModel.kt`, `BrowseUiState.kt`, `GlobalSearchScreen.kt`, multi-select components)
- Tests: `android/app/src/test/java/app/orbit/ui/screens/browse/BrowseViewModelTest.kt`, `android/app/src/test/java/app/orbit/ui/screens/browse/GlobalSearchViewModelTest.kt`

---

## Product

### Why it exists

Sometimes the user wants the full list, not one person at a time. Browse is the escape hatch from the card-view constraint: scan everyone on a list, search by name, act directly without the swipe gesture. It also doubles as the surface for managing list membership (remove from list via long-press).

### User story

As a user, I open a list and see everyone on it, sorted most-recently-contacted first. I can search by name, see at a glance who's due, and long-press for quick actions.

### Behavior

- **Row content:** photo, name, last-called timestamp (relative — "3 days ago"), subtle "due" dot when the rule-engine marks the contact ready to surface, chevron.
- **Sort:** last-contact descending. No user-facing sort control in v1.
- **Search:** sticky box at top. Matches by display name. Filters live without scroll-jump.
- **Long-press row:** opens a bottom sheet with quick actions — call, pause, remove from list.
- **Tap row:** routes to contact-detail (`features/contact-detail/README.md`).
- **Empty list state:** quiet prompt to bulk-add — no urgency, no hustle.
- **Manual call-trigger** available from the quick-action sheet, per PRD §Browse & Search.

### Acceptance criteria

- [ ] Search filters in-place — no scroll jump when query changes.
- [ ] Due dot uses design token `--accent` from theme, never hardcoded.
- [ ] Long-press triggers after ~300ms with haptic feedback.
- [ ] Swipe-to-dismiss is explicitly NOT implemented — reserved for card-view semantics.
- [ ] Dark mode + 200% font scale + TalkBack pass.
- [ ] List of 500+ contacts scrolls smoothly (virtualized).

### Not in scope

- Editing contact details. That lives in `features/contact-detail/README.md` and deep-links to the system Contacts app.
- Global cross-list search on this screen. Browse stays scoped to the current list; the global surface is `GlobalSearchScreen` (reached from Home).
- Custom sort. User cannot reorder rows manually.

### Open product questions

- "Filter: only due" toggle — add, or rely on the due-dot visual alone? Leaning rely on the dot; don't add chrome until a user asks.
- ~~Does search stay scoped to the current list, or expand to global?~~ Resolved: browse search stays list-scoped; global search ships as its own surface (`GlobalSearchScreen`, reached from Home).

---

## Technical

### Architecture

`BrowseViewModel` exposes `StateFlow`s for UI state, search query, and active filters. The screen debounces query input (250ms) before forwarding committed strings to `vm::onSearchChanged`; filtering happens in the VM so the DAO isn't round-tripped on every keystroke. Global cross-list search lives in its own surface (`GlobalSearchScreen` + `GlobalSearchViewModel`, route `Routes.GlobalSearch`).

### Data model

Reads: `ListMembershipEntity` joined with `ContactEntity` and the latest `CallEntity` timestamp per contact. Computed `due: Boolean` via rule-engine per row (`features/rule-engine/README.md`).

### Permissions / integrations

- None directly. Relies on data populated by call-detection and contacts-ingestion.
- Call action from the quick-action sheet follows the same dial mechanism as card-view — see `features/card-view/README.md` §Permissions.

### Known gotchas

- `LazyColumn` items must use stable keys (contact ID) — using index as key causes flash-of-row during search filter changes.

### Not in scope (technical)

- Background refresh of the row list. Flow-driven; updates arrive when the underlying data changes.
- Pagination. 500-contact lists are within Compose's smooth-scrolling envelope without paging.

### Open technical questions

- None open.
