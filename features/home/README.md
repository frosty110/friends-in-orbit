# home

**Status:** in-progress
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/ui/screens/home/` (`HomeScreen.kt`, `HomeViewModel.kt`, `HomeUiState.kt`)
- Tests: `android/app/src/test/java/app/orbit/ui/screens/home/HomeViewModelTest.kt`

---

## Product

### Why it exists

Home is the entry point after app unlock. Its job is to let the user pick *where* they want to reach out from (which list / which mood) without presenting more than one decision at a time. When the user doesn't want to choose, "surprise me" picks a single contact across every non-archived list and routes the user to that person's Card View. The screen embodies principle #1 of the app (reduce activation energy): you open it and within one tap you're surfacing someone.

### User story

As a user, I open the app and either pick a mood-shaped list to start surfacing from, or tap "surprise me" to skip the choice entirely and go straight to the person who is most overdue across everyone I track.

### Behavior

- Grid or stack of list tiles; each tile shows list name, a "due count" badge (contacts ready to surface), and a member-count subtitle ("4 people"; the subtitle stays quiet while the count is still hydrating).
- Header shows the **distinct** due-contact count across visible lists ("N people ready" / "1 person ready") — a union count derived in `HomeViewModel`, because summing per-tile badges double-counts a contact due on more than one list (#24, 2026-06-09). When zero are due, the header reads "All caught up".
- "Surprise me" tile at the top. Selection algorithm: cross-list contact pick — for each non-archived list, take its head-of-queue contact, score by overdueness (`now - nextDueAt`), de-duplicate by contact id (keep the list where the contact is most overdue), tie-break by call count ASC then contact id ASC. Deterministic for fixed inputs. See ADR 0007 (`features/_foundations/ADRs/0007-surprise-me-cross-list.md`) for the full algorithm.
- Due-count badge updates live when call-detection records a call on a member contact, when a contact is swiped, paused, or added/removed from the list.
- "Surprise me" tile is disabled (reduced opacity, non-clickable) when no list has a due contact — the surrounding "All caught up" heading carries the warm framing.
- If no lists exist, home renders its Empty state: a warm line plus a "Create your first list" button (ADR 0006 reserves that CTA for the genuinely-empty case; a Loading state covers the slow-SQLCipher cold-open window so the CTA never flashes at a user who has lists). Home does **not** route to onboarding — the cold-start destination is decided by `AppViewModel` from the `onboardingComplete` flag before Home composes.
- Tap a list tile → card-view for that list. Tap "surprise me" → algorithm picks a (contact, list) pair, routes to card-view for that list (Card View naturally surfaces the same contact because it reads from the same `SurfaceNextUseCase` queue the picker sourced).

**List tile long-press — quick actions (added 2026-06-08).**

Long-press on a list tile opens an anchored menu of manage-this-list actions. The point is to surface the actions otherwise buried two screens deep in Lists Manager without leaving home. Tap is unchanged (it routes to Card View for the list) and the menu never duplicates it.

- Menu items, in order:
  1. **Add people** — routes to the contact picker scoped to this list (`Routes.pickContacts(listId)`).
  2. **Mute prompts** / **Unmute prompts** — toggles the list's notification reminders *in place*, no navigation. Label and effect reflect the list's current `notificationsEnabled`. Confirms with a brief snackbar ("Prompts muted." / "Prompts on.").
  3. **List settings** — opens List Configuration (`Routes.listConfig(listId)`).
  4. — divider —
  5. **Archive** — removes the list from home, reversible. Reuses the existing "List archived." + Undo snackbar.
  6. **Delete** — destructive. Opens the existing confirmation dialog ("this removes the list. people stay in your contacts."), then deletes **with an Undo snackbar** (see decision below).
- Long-press fires a single haptic tick on entry. Only one menu open at a time; long-pressing another tile (or tapping out) dismisses the current one.
- **"Start this list" is intentionally not an item.** A plain tap already routes to Card View scoped to the list, so a menu entry would duplicate the primary gesture. Resolved against ground truth, not assumption (`HomeScreen.kt` tile `onClick` → `Routes.card(listId)`).
- **Delete is reachable directly here** (not gated behind archive-first as it is in Lists Manager's archived section). Removing that archive buffer is why this surface's Delete carries an Undo — see Open product questions.

### Acceptance criteria

- [ ] Tiles render list name, due count, and member count from live flows (`HomeFeed.tiles` + `ListRepository.observeMemberCountsByListId`).
- [ ] Due count reflects current rule-engine evaluation, not a cached snapshot.
- [ ] "Surprise me" deterministic within session, varies across sessions.
- [ ] "All caught up" header (with Surprise me disabled) renders when every list's due count is 0; the Empty state (create-first-list CTA) renders only when no lists exist.
- [ ] Header due count is the distinct-contact union across lists, never the sum of per-tile badges.
- [ ] Voice rules pass: sentence case, no exclamation, no gamification copy.
- [ ] Dark mode + 200% font scale + TalkBack pass.
- [ ] Tap target minimum 48×48dp for every tile.
- [ ] Long-press a list tile opens the quick-actions menu; a plain tap still routes to Card View (unchanged).
- [ ] Menu order is exactly: Add people · Mute/Unmute prompts · List settings · (divider) · Archive · Delete.
- [ ] "Mute prompts" / "Unmute prompts" label matches the list's current `notificationsEnabled`; tapping it flips the flag in place with a confirming snackbar and no navigation.
- [ ] Delete opens the confirmation dialog, then deletes with an Undo snackbar; Archive's existing Undo is unchanged.
- [ ] Only one quick-actions menu is open at a time; long-pressing another tile or tapping out dismisses it.
- [ ] Long-press carries an `onLongClickLabel` ("Quick actions") and every menu item is ≥ 48dp with a TalkBack-readable label.
- [ ] Voice: every menu label is sentence case, no exclamation, no gamification.

### Not in scope

- Notifications. Covered by `features/notifications/README.md`.
- Widgets. Covered by `features/widgets/README.md`.
- Per-list configuration UI. Lives in `features/orbit-lists/README.md`.
- Sorting lists by urgency or alphabetically. List order is user-controlled via Lists Manager.

### Open product questions

- ~~Should "surprise me" weight by most-overdue-across-all-lists, or most-recently-interacted list?~~ Resolved 2026-05-04: most-overdue across non-archived lists, picked per-contact (not per-list). See ADR 0007.
- If only one list exists, does home auto-skip to card-view, or still render the single tile? Leaning render for consistency.
- ~~Should "Start this list" be the hero long-press action?~~ Resolved 2026-06-08: no. A plain tap already routes to Card View scoped to the list, so the menu is manage-only and omits it.
- **Delete recoverability.** Resolved 2026-06-08: long-press Delete uses the confirmation dialog **plus** an Undo snackbar (soft-delete window). Rationale: surfacing Delete directly on home removes the archive-first buffer that previously justified Lists Manager's no-Undo delete. With the buffer gone, a fast confirm-tap must still be recoverable — and the warm/unhurried voice expects destructive actions to feel safe. (Lists Manager's archived-section Delete should adopt the same Undo so the two surfaces stay consistent.)
- **Browse-from-home.** Should the menu also offer "Browse list" (scan the whole roster) now that tap goes to the decide-loop rather than a list view? Left out for v1 to keep the menu to manage-actions only; revisit if users want a non-loop way to view members from home.

---

## Technical

### Architecture

UI-only screen. `HomeViewModel` exposes `StateFlow<HomeUiState>` constructed from repository flows. No DAO access from UI. Uses Navigation Compose for route into card-view.

State shape (`ui/screens/home/HomeUiState.kt` — sealed interface):
```
HomeUiState.Loading                  // pre-first-database-answer window only (quiet chrome, no skeleton)
HomeUiState.Empty                    // zero lists in Room → create-first-list CTA
HomeUiState.Ready(
    lists: List<ListTileState>,      // id, name, dueCount, type, notificationsEnabled, memberCount
    hasPermissions: Boolean,
    surpriseAvailable: Boolean,      // true when any list has due > 0
    dueContactCount: Int,            // distinct contacts due across visible lists (header count)
)
```
There is no `allCaughtUp` field — the renderer derives the "All caught up" header from `dueContactCount == 0` on a `Ready` state.

**Long-press quick-actions menu.** The list tile becomes `combinedClickable(onClick = …, onLongClick = …)` — reuse the existing precedent in `BrowseListScreen.kt` (combinedClickable with `onLongClickLabel`, a haptic tick on long-press entry, and an anchored Material3 `DropdownMenu` whose open-state is a single nullable `menuAnchorListId` so only one menu shows at a time). The menu's five/six items dispatch event callbacks the stateless `HomeContent` receives and `OrbitNavHost` wires up:

- `onAddPeople(listId)` → `Routes.pickContacts(listId)`
- `onToggleMute(listId)` → flips `notificationsEnabled` in place
- `onOpenSettings(listId)` → `Routes.listConfig(listId)`
- `onArchive(listId)` and `onDelete(listId)` → list mutations + snackbar feedback

Navigation callbacks stay in the NavHost. The two non-navigation actions (mute toggle, archive, delete) are list mutations: `HomeViewModel` gains methods that call `ListRepository` directly — `updateNotificationsEnabled`, `setArchived`, `delete` — mirroring `ListsManagerViewModel`'s existing methods (which already wrap each in a `runMutation` try/catch + snackbar). All three repo functions already exist; no data-layer work is needed beyond surfacing `notificationsEnabled` into the home tile (see Data model).

### Data model

Reads: `ListEntity` (name, order, archived flag) joined with computed due-count. Due-count computed by rule-engine (`features/rule-engine/README.md`) over `ContactEntity` × `CallStatEntity` for members of each list.

The home `ListTileState` (`ui/screens/home/HomeUiState.kt`) carries `notificationsEnabled: Boolean` (sourced straight from `ListEntity.notificationsEnabled`, so the long-press menu renders "Mute prompts" vs "Unmute prompts" without a second read) and `memberCount: Int?` (hydrated from `ListRepository.observeMemberCountsByListId()` for the tile subtitle; null = not yet hydrated, subtitle stays quiet).

### Permissions / integrations

None directly. Depends on call-detection and contacts-ingestion having populated Room.

### Known gotchas

- **Terminology — one name for the config screen.** The same destination is labeled **"Configure"** in the Lists Manager overflow menu (`ui/screens/lists/ListRow.kt`, the `DropdownMenuItem` text) and referenced as "Configure" in `ArchivedListRow.kt` (param name + a11y contentDescription). This feature introduces "List settings" on home. Rename "Configure" → **"List settings"** in the same change so home and Lists Manager never name one action two ways. "List settings" (not bare "Settings") avoids colliding with the app-level Settings gear in the home app bar.
- **Snackbar sequencing — the swallowed-toast trap.** Home's action snackbars share the bug found 2026-06-08 in Lists Manager (quick-task `260608` and the rename fix that followed): an archive snackbar carries an "Undo" action, which makes Material3's `showSnackbar` default to `Indefinite` duration; if the host collects events with a plain `collect`, the collector stays suspended in that indefinite snackbar and a following "List deleted." (or any next event) is buffered and never shown — leaving a stale "Undo" pointing at a list that's already gone. Home's snackbar host **must** collect with `collectLatest` (latest action's feedback wins and cancels the prior snackbar), and any Undo payload must ride the event (survive navigation) rather than a screen-scoped `launch`.
- **Delete-with-Undo needs deferred delete.** The current `ListsManagerViewModel.deleteList` hard-deletes immediately with no Undo, relying on FK `ON DELETE CASCADE` for memberships. An Undo window means either deferring the actual purge until the snackbar dismisses (soft-delete) or snapshot-and-reinsert — and reinsert is awkward because the cascade already dropped memberships. Prefer **deferred delete**: hide the tile optimistically, purge when the Undo window closes. Apply the same change to the Lists Manager archived-section Delete so both surfaces behave identically.
- **Long-press vs drag.** Home tiles do not currently support drag-reorder (that lives in Lists Manager behind drag handles), so long-press is free to mean "quick actions" here with no gesture conflict. If drag-to-reorder is ever added to home, this decision has to be revisited.

### Not in scope (technical)

- Caching due-counts. Recompute per collect — rule-engine is pure and cheap.
- Pre-fetching card-view state. Card-view owns its own state.

### Open technical questions

- ~~"Surprise me" determinism: session-key from process start time, or from app-state version?~~ Resolved 2026-05-04: pure-deterministic, no session seed. See ADR 0007.
