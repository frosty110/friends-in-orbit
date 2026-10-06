package app.orbit.ui.screens.browse

import androidx.compose.runtime.Immutable
import app.orbit.data.Contact

/**
 * Browse state contract. Sealed
 * interface; every variant `@Immutable` for Compose skipping.
 *
 * `Ready.contacts` carries the UI-domain [Contact] projection. Queued
 * contacts come first, in [SurfaceQueueUseCase] order; non-queued members (paused /
 * out-of-active-hours / no-template / engine-null) follow, sorted alphabetically by
 * `displayName`. Per-row queue position is carried in [Ready.queuePositions] (absent
 * entry → non-queued row, rendered without a position number). This ordering is
 * independent of the `dueIds`/`rowStatus` orientation — they still apply per row.
 *
 * `searchQuery` is echoed back so
 * the TextField rendering doesn't need a second `collectAsState()` on the VM's
 * `searchQuery` Flow — one subscription per screen keeps the configuration-change
 * story simple.
 *
 * `activeFilters` reflects the toggled chip set (per BROWSE-02 chip-composition
 * rule: chip×chip = UNION, search×chip = AND).
 *
 * `callLogPermissionDenied` is a banner toggle inside Ready — when true, the
 * screen renders a quiet notice and rows skip the call-time meta line (the
 * "Never called" claim would be dishonest without READ_CALL_LOG). Wired to the
 * real permission check via [BrowseViewModel.onCallLogPermissionChanged].
 *
 * `dueIds` / `rowStatus` carry per-row orientation keyed by
 * the UI contact id ("c-<entityId>"):
 *   - `dueIds` — rows whose membership `nextDueAt` is null or past now AND
 *     that aren't paused/ignored; BrowseRow renders the quiet accent due dot
 *     (features/browse/README.md:23,34).
 *   - `rowStatus` — paused/ignored rows get a muted treatment + status word.
 * Both live on Ready (not only on [Contact]) because [Contact.equals] compares
 * `id` alone — flags riding the Contact copy would not survive StateFlow
 * deduplication.
 *
 * `Empty` = list has zero members.
 * `FilteredEmpty` = the list has members but the active filter chips exclude
 * everyone; the screen renders "No one matches these filters." + a
 * clear-filters action (Empty's "No one here yet." was false
 * for a fully-filtered non-empty list).
 * `NoMatches(query)` = the search query returned zero matches against the
 * filtered set; the screen renders `Nothing matches "{query}".`.
 * `CallLogDenied` = hard gate: READ_CALL_LOG is denied AND a call-history
 * filter chip is active — the chips cannot be answered honestly without the
 * call log, so the screen explains instead of showing a false result set.
 * `Loading` = the list's feed has not emitted yet (BROWSE-06); the screen
 * shows a skeleton. Before 2026-10-05 this variant was "retired" and the VM
 * started at `Empty`, so a full list flashed "No one here yet" first.
 * `Error` = a source flow failed (BROWSE-06), or the route's list id did not
 * parse (a loud guard, rules.md Code 3: until 2026-10-06 that case was `Empty`,
 * whose "Add people" opened the picker for a list that does not exist).
 *
 * Whether the browsed list is smart is not on this state: the app bar's "+"
 * and the Empty state's "Add people" render outside `Ready`, so the list's
 * type rides [BrowseViewModel.listType] beside [BrowseViewModel.listName],
 * from the same `BrowseFeed.lists` source.
 */
sealed interface BrowseUiState {

    @Immutable data object Loading : BrowseUiState

    @Immutable data object Error : BrowseUiState

    @Immutable
    data class Ready(
        val contacts: List<Contact>,
        val searchQuery: String,
        val activeFilters: Set<BrowseFilter>,
        val callLogPermissionDenied: Boolean,
        // Multi-select widening (MOVE-01, MOVE-02, MOVE-06).
        // `isMultiSelect` toggles via the app bar's Select, a row's long-press entry,
        // BackHandler exit, or the empty-selection auto-exit (deselecting the last
        // row; an entry with nothing selected does not trip it).
        // `selectedIds` carries domain entity ids (Long) — UI rows convert "c-$id" strings to Longs.
        // `isCommitting` is true while a bulk write (Remove/Ignore/Pause/Move/Copy) is in
        // flight. The VM's handlers take it with `compareAndSet(false, true)`, so a
        // second tap while one runs returns before reading the selection, and the
        // selection bar reads it to disable its controls meanwhile (browse-2: two quick
        // taps on Remove once wrote twice, and the second, empty inverse replaced the
        // only Undo).
        val isMultiSelect: Boolean = false,
        val selectedIds: Set<Long> = emptySet(),
        val isCommitting: Boolean = false,
        // Per-row orientation, keyed by UI contact id ("c-<id>").
        val dueIds: Set<String> = emptySet(),
        val rowStatus: Map<String, BrowseRowStatus> = emptyMap(),
        // Queue-position map. Key = UI-domain `Contact.id` String (`"c-$entityId"`).
        // Only queued contacts appear in the map; non-queued members (paused / out-of-active-hours /
        // no-template / engine-null) are absent. Browse uses null lookup as the "non-queued" signal
        // and renders them in the "Other members" section without a position number. Independent of
        // dueIds/rowStatus — a row can be queued (position N) AND due (dot) at once.
        val queuePositions: Map<String, Int> = emptyMap(),
    ) : BrowseUiState

    @Immutable data object Empty : BrowseUiState

    @Immutable data object FilteredEmpty : BrowseUiState

    @Immutable data class NoMatches(val query: String) : BrowseUiState

    @Immutable data object CallLogDenied : BrowseUiState
}

/**
 * Row-level lifecycle status surfaced in Browse. Ignored wins
 * over Paused when both apply (an ignored contact is invisible to surfacing
 * regardless of its pause window).
 */
enum class BrowseRowStatus { Paused, Ignored }

/**
 * Browse filter set (BROWSE-02).
 *
 * - [CalledRecently]: contact has at least one CallEvent in the last 30 days.
 * - [NotCalledYet]:   contact has zero CallEvents.
 *
 * Chip × chip composition is UNION (OR) per the BROWSE-02 chip-composition
 * rule — their AND-intersection is always empty, so OR is the only useful
 * composition.
 */
enum class BrowseFilter { CalledRecently, NotCalledYet }
