package app.orbit.ui.screens.browse

import androidx.compose.runtime.Immutable
import app.orbit.data.Contact
import app.orbit.ui.util.UiText

/**
 * Browse state contract. Sealed
 * interface; every variant `@Immutable` for Compose skipping.
 *
 * `Ready.contacts` carries the UI-domain [Contact] projection in the order
 * the screen draws it, group by group (BROWSE-07): first the sequence,
 * everyone in the order the card brings them up ([SurfaceQueueUseCase], the
 * card's own ordering), numbered by [Ready.queuePositions]; then anyone the
 * list's rule cannot place (an archived person, or everyone on a list with no
 * rule) alphabetically; then the paused people, soonest back first; then the
 * ignored ones alphabetically. The screen tells the groups apart by
 * `queuePositions` and `rowStatus`, so search and the filters narrow each
 * group without reordering it.
 *
 * `whenLabels` says when each person in the sequence comes up ("Up now",
 * "Tomorrow", "Thursday", "In 2 weeks"); `untilLabels` when each paused
 * person's pause ends ("Until 12 Oct", "Until you unpause"). `onYourCardId`
 * is the row marked "On your card" (BROWSE-09): the person the card showed
 * when Browse was opened from it, or, once the sequence's head has changed
 * since (a drag here, a call), the new head, which is who the card shows now.
 * Null when Browse was not opened from a person on the card.
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
 *   - `dueIds`: rows that are worth a call now; BrowseRow renders the quiet
 *     accent due dot (features/browse/README.md). For a row in the sequence
 *     that is exactly the rows whose when reads "Up now", so the dot and the
 *     words never disagree (BROWSE-07); a row outside it keeps the older test
 *     (membership `nextDueAt` null or past, not paused or ignored).
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
 * `Error.canRetry` is false for the unparseable id: the VM has no feed to
 * re-subscribe, so Try again could change nothing and the screen offers Go
 * back alone (a Try again that did nothing was the screen's accent until
 * 2026-10-06). Carried on the state so the screen never has to guess.
 *
 * Whether the browsed list is smart is not on this state: the app bar's "+"
 * and the Empty state's "Add people" render outside `Ready`, so the list's
 * type rides [BrowseViewModel.listType] beside [BrowseViewModel.listName],
 * from the same `BrowseFeed.lists` source.
 */
sealed interface BrowseUiState {

    @Immutable data object Loading : BrowseUiState

    @Immutable data class Error(val canRetry: Boolean = true) : BrowseUiState

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
        // Sequence positions (BROWSE-07). Key = UI-domain `Contact.id` String (`"c-$entityId"`),
        // value = 1-based place in the WHOLE sequence, so a filtered view still shows true numbers.
        // Only people in the sequence appear; paused, ignored and unplaceable members are absent,
        // which is how the screen knows a row is not in the sequence. Independent of
        // dueIds/rowStatus: a row can be in the sequence (position N) AND up now (dot) at once.
        val queuePositions: Map<String, Int> = emptyMap(),
        // BROWSE-07: when each person in the sequence comes up, and when a paused person's pause
        // ends; keyed like the maps above. Text, not times, so the screen formats nothing.
        val whenLabels: Map<String, UiText> = emptyMap(),
        val untilLabels: Map<String, UiText> = emptyMap(),
        // BROWSE-09: the row marked "On your card", or null.
        val onYourCardId: String? = null,
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
