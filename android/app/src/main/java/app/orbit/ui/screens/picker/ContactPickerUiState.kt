package app.orbit.ui.screens.picker

import androidx.compose.runtime.Immutable
import app.orbit.domain.search.ContactSearch
import java.time.Instant

/**
 * ContactPicker state contract (PICK-01..08).
 *
 * Single ground-truth UiState the picker screen renders against. Every
 * interaction in [ContactPickerScreen] is a callback into
 * [ContactPickerViewModel]; every render reads from this `@Immutable`
 * snapshot. Derived properties ([filteredContacts], [selectionCount],
 * [canSelectAllMatching]) are computed inline so the unit tests can assert
 * the load-bearing predicates without VM scaffolding.
 *
 * **Filter semantics (PICK-02):** `activeFilters.all { it.matches(c) }` —
 * AND across chips. Wired via [filteredContacts] below.
 *
 * **Search composes (PICK-05):** search goes through
 * the shared [ContactSearch] matcher — diacritic folding ("jose" finds
 * "José"), phone-digit fragments (3+ digits), word-start ranking — AND-composed
 * with chips and the ignored-default exclusion.
 *
 * **Ignored exclusion (PICK-08):** ignored contacts are excluded by
 * default; toggling [showIgnored] reveals them.
 *
 * **canSelectAllMatching (PICK-03):** the "select all
 * matching" affordance is valid whenever the list is narrowed — a non-blank
 * search query OR at least one active filter — and at least one filtered
 * contact is not yet selected. Capped at [SELECT_ALL_MAX] matches;
 * [selectAllCapExceeded] drives a quiet note instead when the narrowed set
 * is still too large to select in one tap.
 */
/**
 * ONB-21 — picker sort mode. Default `ByName` matches the original behavior
 * (DAO emits `ORDER BY displayName COLLATE NOCASE ASC`).
 * Onboarding's first-list flow defaults to `ByRecency` so freshly-synced
 * recent callers surface first.
 */
@Immutable
sealed class PickerSort {
    /** Alphabetical (A→Z). Matches the DAO's `ORDER BY displayName COLLATE NOCASE ASC`. */
    @Immutable data object ByName : PickerSort()

    /** Most recently *called* first (null lastCallAt sorts last). Onboarding default. */
    @Immutable data object ByRecency : PickerSort()

    /** Most-called first — highest `callCount` at the top, name as tiebreak. */
    @Immutable data object ByMostCalled : PickerSort()

    /** Most recently *saved to the device* first — newest `firstSeenByAppAt` on top. */
    @Immutable data object ByRecentlySaved : PickerSort()
}

@Immutable
data class ContactPickerUiState(
    val phase: Phase,
    val mode: PickerMode,
    val targetListName: String,
    val searchQuery: String,
    val activeFilters: Set<PickerFilter>,
    val showIgnored: Boolean,
    val allContacts: List<PickerContact>,
    val selectedIds: Set<Long>,
    /**
     * Non-archived lists available for the "In list…" filter chip
     * (PICK-01). Sourced from
     * [app.orbit.data.repository.ListRepository.observeAll], filtered by
     * `!isArchived`, projected to [PickerListSummary] (id + name).
     */
    val availableLists: List<PickerListSummary> = emptyList(),
    /**
     * ONB-21 — picker sort mode. Default `ByName` (DAO order);
     * onboarding flips to `ByRecency` so most-recently-called surface first.
     */
    val sortBy: PickerSort = PickerSort.ByName,
) {
    /**
     * Phase enum — drives which surface [ContactPickerScreen] renders:
     *
     * - [LoadingPermission]: initial state before [ContactPickerViewModel.refreshPermission]
     *   completes its first read. Renders a spinner.
     * - [PermissionRationale]: READ_CONTACTS not granted; show a rationale + "Allow" CTA.
     * - [PermissionDenied]: user dismissed the system dialog or set "Don't ask again".
     *   Show a settings deep-link.
     * - [EmptyDevice]: permission granted but the device address book is empty
     *   (zero phone-bearing contacts). Show empty-state copy.
     * - [Ready]: the list, search, filter, and bulk-action surface are all live.
     * - [Committing]: a Move/Copy/Add use case is in flight; surface a non-blocking
     *   loading indicator on the action bar but keep the list rendered.
     * - [NotFound]: the `targetListId` nav arg was missing or not parseable as a
     *   Long. Renders a terminal "List not found" empty state. Guards against
     *   VM-construction crashes when a stale deep-link or programmer error
     *   delivers a malformed id.
     */
    enum class Phase {
        LoadingPermission,
        PermissionRationale,
        PermissionDenied,
        EmptyDevice,
        Ready,
        Committing,
        NotFound,
    }

    /**
     * Domain-side filtered list — AND of (showIgnored ∨ !isIgnored) ∧ search ∧
     * AND-of-filters, ordered by [sortBy] then re-banded by search rank.
     *
     * Computed ONCE at construction (body `val`, not a `get()`).
     * The previous getter re-ran the filter+sort on every access, and the
     * screen read it 3–4 times per composition. [ContactPickerViewModel]'s
     * combine pipeline constructs exactly one state instance per emission, so
     * this initializer runs once per emission. (`data class .copy()` re-runs
     * it — the VM pipeline therefore avoids chained `.copy()` stages.)
     *
     * Search dispatches through [ContactSearch.filterRanked]
     * (name + the row's phone): diacritic-folded, digit-aware, word-start
     * matches rank above mid-word ones. Within a rank band the [sortBy] order
     * applied below survives, per the matcher's contract.
     */
    val filteredContacts: List<PickerContact> = run {
        val filtered = allContacts.filter { c ->
            (showIgnored || !c.isIgnored) &&
                c.phone.isNotBlank() &&
                activeFilters.all { it.matches(c) }
        }
        val sorted = when (sortBy) {
            PickerSort.ByName -> filtered
            PickerSort.ByRecency -> {
                // Most-recently-called first; null lastCallAt sorts last.
                val recencyComparator: Comparator<PickerContact> =
                    compareBy(nullsLast(reverseOrder())) { c -> c.lastCallAt }
                filtered.sortedWith(recencyComparator.thenBy { it.displayName })
            }
            PickerSort.ByMostCalled ->
                filtered.sortedWith(
                    compareByDescending<PickerContact> { it.callCount }
                        .thenBy { it.displayName },
                )
            PickerSort.ByRecentlySaved ->
                filtered.sortedWith(
                    compareByDescending<PickerContact> { it.firstSeenByAppAt }
                        .thenBy { it.displayName },
                )
        }
        // Within the Unsorted triage view, Android favorites
        // (hand-curated closest people) float to the top. `sortedByDescending`
        // is stable, so the [sortBy] order above survives within each band.
        // Deliberately scoped to the Unsorted chip — no recommendation engine.
        val starredFirst = if (PickerFilter.Unsorted in activeFilters) {
            sorted.sortedByDescending { it.isStarred }
        } else {
            sorted
        }
        ContactSearch.filterRanked(
            items = starredFirst,
            query = searchQuery,
            name = { it.displayName },
            phone = { it.phone },
        )
    }

    /**
     * Per-chip badge counts and the ignored tally, computed in a
     * SINGLE pass over [allContacts] at construction. Replaces six independent
     * O(n) [countFor] walks per recomposition. Semantics (Pitfall 4):
     * each count respects search + ignored-default exclusion but IGNORES other
     * active filters, so toggling chip A never changes chip B's badge.
     */
    private val derivedCounts: PickerDerivedCounts = run {
        var commonly = 0
        var rarely = 0
        var never = 0
        var recentlyAdded = 0
        var longGap = 0
        var unsorted = 0
        var starred = 0
        var ignored = 0
        for (c in allContacts) {
            if (c.isIgnored) ignored++
            if (!showIgnored && c.isIgnored) continue
            if (c.phone.isBlank()) continue
            if (searchQuery.isNotBlank() &&
                ContactSearch.match(searchQuery, c.displayName, c.phone) == null
            ) {
                continue
            }
            if (c.isCommonlyCalled) commonly++
            if (c.isRarelyCalled) rarely++
            if (c.callCount == 0) never++
            if (c.isRecentlyAdded) recentlyAdded++
            if (c.isLongGap) longGap++
            if (c.listIds.isEmpty()) unsorted++
            if (c.isStarred) starred++
        }
        PickerDerivedCounts(
            filterCounts = mapOf(
                PickerFilter.CommonlyCalled to commonly,
                PickerFilter.RarelyCalled to rarely,
                PickerFilter.NeverCalled to never,
                PickerFilter.RecentlyAdded to recentlyAdded,
                PickerFilter.LongGap to longGap,
                PickerFilter.Unsorted to unsorted,
                PickerFilter.Starred to starred,
            ),
            ignoredCount = ignored,
        )
    }

    /** Pre-computed badge counts for the six stateless filters. */
    val filterCounts: Map<PickerFilter, Int> = derivedCounts.filterCounts

    /**
     * Total ignored contacts in [allContacts] regardless of search/filters —
     * drives the "Show ignored" entry's visibility.
     */
    val ignoredCount: Int = derivedCounts.ignoredCount

    val selectionCount: Int get() = selectedIds.size

    /** True when a search query or an active filter narrows the list. */
    private val isNarrowed: Boolean
        get() = searchQuery.isNotBlank() || activeFilters.isNotEmpty()

    val canSelectAllMatching: Boolean =
        isNarrowed &&
            filteredContacts.size <= SELECT_ALL_MAX &&
            filteredContacts.any { it.contactId !in selectedIds }

    /**
     * The narrowed set is still larger than [SELECT_ALL_MAX];
     * the screen shows a quiet "narrow the search" note instead of the
     * select-all affordance.
     */
    val selectAllCapExceeded: Boolean =
        isNarrowed && filteredContacts.size > SELECT_ALL_MAX

    companion object {
        /**
         * Upper bound for one-tap select-all. Keeps a stray two-letter
         * query from staging a near-whole-phonebook selection.
         */
        const val SELECT_ALL_MAX: Int = 200
    }
}

/**
 * Private holder for the single-pass count computation above — keeps the two
 * public properties ([ContactPickerUiState.filterCounts],
 * [ContactPickerUiState.ignoredCount]) backed by one traversal.
 */
private data class PickerDerivedCounts(
    val filterCounts: Map<PickerFilter, Int>,
    val ignoredCount: Int,
)

/**
 * Sealed (not enum) because [InList] carries `listId: Long` state. The
 * derivation-flag chips ([CommonlyCalled], [RarelyCalled], [NeverCalled],
 * [RecentlyAdded], [LongGap], [Unsorted], [Starred]) are stateless
 * `data object`s.
 *
 * Per-chip predicates read pre-derived flags from [PickerContact] —
 * percentile / gap / recency math is computed once in
 * [ContactPickerViewModel.buildPickerContacts] from the threshold flow,
 * not re-derived per chip evaluation.
 *
 * - **PICK-04 invariant:** [NeverCalled] reads `callCount == 0` directly — the
 *   user-visible label is "Never called" (first-class). The zero-count phrasing
 *   is forbidden in copy or code (voice gate enforces this).
 */
sealed class PickerFilter {
    abstract fun matches(c: PickerContact): Boolean

    data object CommonlyCalled : PickerFilter() {
        override fun matches(c: PickerContact) = c.isCommonlyCalled
    }

    data object RarelyCalled : PickerFilter() {
        override fun matches(c: PickerContact) = c.isRarelyCalled
    }

    data object NeverCalled : PickerFilter() {
        override fun matches(c: PickerContact) = c.callCount == 0
    }

    data object RecentlyAdded : PickerFilter() {
        override fun matches(c: PickerContact) = c.isRecentlyAdded
    }

    data object LongGap : PickerFilter() {
        override fun matches(c: PickerContact) = c.isLongGap
    }

    // "Called recently" is no longer a filter — its intent (surface people you've
    // called recently) moved to the sort control as PickerSort.ByRecency
    // ("Recently called"), which orders rather than hides. Removed 2026-06-08.

    data class InList(val listId: Long) : PickerFilter() {
        override fun matches(c: PickerContact) = listId in c.listIds
    }

    /**
     * Android favorites (ContactsContract STARRED). Hand-curated
     * closest people; ingested into `contacts.isStarred` (schema v=11) and
     * refreshed by the delta-sync like displayName.
     */
    data object Starred : PickerFilter() {
        override fun matches(c: PickerContact) = c.isStarred
    }

    /**
     * Contacts not on any list — the affirmative/negative pair to [InList].
     * Lives as a picker filter (not a Home tile) so the v1 PRD's "no smart
     * lists" line (`features/orbit-lists/README.md:51`) stays intact while
     * users still have a triage path for unsorted people.
     */
    data object Unsorted : PickerFilter() {
        override fun matches(c: PickerContact) = c.listIds.isEmpty()
    }
}

/**
 * The picker mode — drives both the entry-point copy ("Add to {list}",
 * "Move to {list}", "Copy to {list}") and the [ContactPickerViewModel.onCommit]
 * dispatch:
 *
 * - [Add]: bare list-membership insert. No source-list context required.
 * - [Move]: requires a `sourceListId` nav arg; dispatches
 *   [app.orbit.domain.usecase.MoveContactsUseCase]. A move route without a
 *   source lands on [Phase.NotFound];
 *   candidates are restricted to source-list members.
 * - [Copy]: additive copy via [app.orbit.domain.usecase.CopyContactsUseCase].
 *   Idempotent — a contact already on the target list is silently kept.
 */
enum class PickerMode { Add, Move, Copy }

/**
 * UI-domain projection of a contact for the picker. Carries the pre-derived
 * filter flags ([isCommonlyCalled], [isRarelyCalled], [isRecentlyAdded],
 * [isLongGap]) so each chip's predicate is O(1) and percentile math is
 * computed once per combine emission, not once per chip × contact.
 */
@Immutable
data class PickerContact(
    val contactId: Long,
    val displayName: String,
    val phone: String,
    val photoUri: String?,
    val isIgnored: Boolean,
    val callCount: Int,
    val lastCallAt: Instant?,
    val firstSeenByAppAt: Instant,
    val listIds: Set<Long>,
    val listNames: List<String>,
    val isCommonlyCalled: Boolean,
    val isRarelyCalled: Boolean,
    val isRecentlyAdded: Boolean,
    val isLongGap: Boolean,
    /**
     * Mirrors Android's hand-curated favorite flag
     * (ContactsContract STARRED) via `ContactEntity.isStarred`. Defaulted so
     * preview/test fixtures that predate the flag stay valid.
     */
    val isStarred: Boolean = false,
)

/**
 * Tiny projection of [app.orbit.data.entity.ListEntity] for the picker's
 * "In list…" DropdownMenu (PICK-01). Carries only what
 * the chip needs (id + name); avoids leaking Room entities into the UI layer.
 */
@Immutable
data class PickerListSummary(
    val id: Long,
    val name: String,
)

/**
 * Pitfall 4 mitigation — chip-count semantics.
 *
 * The chip's badge shows: "count of contacts that WOULD match if THIS chip
 * were the only active filter, RESPECTING the current search query and
 * ignored-default exclusion."
 *
 * Critically, this count IGNORES other active filters. Toggling chip A
 * MUST NOT change chip B's badge — otherwise the user can't tell whether
 * activating B will broaden or narrow the set, and chips become
 * interdependent in a way the UI cannot signal.
 *
 * Lives at the top level (not VM) so the unit test can call it without
 * any VM scaffolding.
 *
 * The six stateless filters resolve from the single-pass
 * [ContactPickerUiState.filterCounts] map. Only [PickerFilter.InList] (whose
 * count no chip displays today) falls through to a direct walk. The
 * search leg of the predicate matches [ContactPickerUiState.filteredContacts]:
 * both go through [ContactSearch].
 */
fun PickerFilter.countFor(state: ContactPickerUiState): Int =
    state.filterCounts[this]
        ?: state.allContacts.count { c ->
            (state.showIgnored || !c.isIgnored) &&
                c.phone.isNotBlank() &&
                (
                    state.searchQuery.isBlank() ||
                        ContactSearch.match(state.searchQuery, c.displayName, c.phone) != null
                    ) &&
                this.matches(c)
        }
