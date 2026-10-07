package app.orbit.ui.screens.picker

import androidx.compose.runtime.Immutable
import app.orbit.domain.search.ContactSearch
import app.orbit.domain.smart.contactAddedAt
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
 * The picker's sort mode. Alphabetical ([ByName], the DAO's `ORDER BY
 * displayName COLLATE NOCASE ASC`) on every route; only the user changes it,
 * from the Sort control, and the choice persists through SavedStateHandle.
 * (Until 2026-10-06 these comments described an onboarding flow that opened
 * on [ByRecency]; no code ever did.)
 */
@Immutable
sealed class PickerSort {
    /** Alphabetical (A to Z). Matches the DAO's `ORDER BY displayName COLLATE NOCASE ASC`. */
    @Immutable data object ByName : PickerSort()

    /** Most recently *called* first (null lastCallAt sorts last): "Recently called". */
    @Immutable data object ByRecency : PickerSort()

    /** Most-called first — highest `callCount` at the top, name as tiebreak. */
    @Immutable data object ByMostCalled : PickerSort()

    /**
     * "Recently added" — newest first by the earlier of [PickerContact.firstSeenByAppAt]
     * and [PickerContact.deviceUpdatedAt] (the device's last-updated timestamp
     * captured at first sight). Genuinely new contacts have a recent timestamp and
     * float; pre-existing ones carry their older device timestamp and sink — which
     * is what keeps this from collapsing to alphabetical after a bulk import.
     */
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
     * The current sort. Alphabetical by default; the user changes it from
     * the Sort control ([ContactPickerViewModel.setSortBy]).
     */
    val sortBy: PickerSort = PickerSort.ByName
) {
    /**
     * Phase enum — drives which surface [ContactPickerScreen] renders:
     *
     * - [LoadingPermission]: the stateIn initial value, held until the first
     *   pipeline emission (the permission read, then the address-book read
     *   and the Room joins). The screen draws
     *   [app.orbit.ui.components.OrbitListSkeleton] so a large address book
     *   never shows a blank page (it drew nothing until 2026-10-06, on the
     *   belief that the system dialog covered it; no dialog is up then).
     *   Also the phase while the first contacts ingest after a grant made on
     *   this screen is still in flight and Room has no candidates yet
     *   (`resolvePickerPhase`): the skeleton, not an empty Ready that would
     *   read as "everyone is already on the list".
     * - [PermissionRationale]: READ_CONTACTS not granted; show a rationale + "Allow" CTA.
     * - [PermissionDenied]: user dismissed the system dialog or set "Don't ask again".
     *   Show a settings deep-link.
     * - [EmptyDevice]: permission granted but the device address book is empty
     *   (zero phone-bearing contacts). Show empty-state copy.
     * - [Ready]: the list, search, filter, and bulk-action surface are all live.
     * - [Committing]: a Move/Copy/Add use case is in flight; surface a non-blocking
     *   loading indicator on the action bar but keep the list rendered.
     * - [NotFound]: the route's list or person is not there: the id was
     *   missing or unparseable, or the row is gone (`observeById` emitted
     *   null, as for a stale deep link or a list deleted from another screen).
     *   Terminal; the screen says so and offers Go back.
     */
    enum class Phase {
        LoadingPermission,
        PermissionRationale,
        PermissionDenied,
        EmptyDevice,
        Ready,
        Committing,
        NotFound,
        // PICK-09: a data stream failed; the picker shows Retry.
        Error
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
                        .thenBy { it.displayName }
                )
            PickerSort.ByRecentlySaved ->
                filtered.sortedWith(
                    // Sort key = the earlier of first-sight and the device's
                    // last-updated (min), so a pre-existing contact's older device
                    // timestamp — not the uniform bulk-import instant — decides its
                    // place. Falls back to firstSeenByAppAt when the device gave none.
                    // The smart rule "Added in the last N days" reads the same
                    // function, so the two "Recently added" surfaces cannot drift.
                    compareByDescending<PickerContact> { c ->
                        contactAddedAt(c.firstSeenByAppAt, c.deviceUpdatedAt)
                    }.thenBy { it.displayName }
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
            phone = { it.phone }
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
                PickerFilter.Starred to starred
            ),
            ignoredCount = ignored
        )
    }

    /** Pre-computed badge counts for the six stateless filters. */
    val filterCounts: Map<PickerFilter, Int> = derivedCounts.filterCounts

    /**
     * Total ignored contacts in [allContacts] regardless of search/filters —
     * drives the "Show ignored" entry's visibility.
     */
    val ignoredCount: Int = derivedCounts.ignoredCount

    /**
     * Why the list area is empty while the phase is Ready, or null when it is
     * not. One predicate so the screen can word every empty state honestly
     * (rubric G4: an empty state tells the truth and offers the next step).
     * Until 2026-10-06 the screen said "No contacts on this device" whenever
     * [allContacts] was empty (false once everyone is on the list, or a
     * Re-link has no one to link to) and "Try removing a filter" with no
     * filter active (false when everyone left is ignored).
     *
     * Precedence: an empty candidate set is explained by the mode; then a
     * search query; then a filter; then the ignored-default exclusion. Move
     * and Copy are route-only (see [PickerMode]) and share Add's wording.
     */
    val emptyReason: EmptyReason? = when {
        filteredContacts.isNotEmpty() -> null
        allContacts.isEmpty() && mode == PickerMode.Relink -> EmptyReason.NoRelinkTargets
        allContacts.isEmpty() -> EmptyReason.EveryoneOnList
        searchQuery.isNotBlank() -> EmptyReason.NoSearchMatches
        activeFilters.isNotEmpty() -> EmptyReason.NoFilterMatches
        !showIgnored && ignoredCount > 0 -> EmptyReason.EveryoneIgnored
        // Only rows with no number are left, which ingest never writes
        // (matching is number-first): say the nearest true thing.
        mode == PickerMode.Relink -> EmptyReason.NoRelinkTargets
        else -> EmptyReason.EveryoneOnList
    }

    /** See [emptyReason]. */
    enum class EmptyReason {
        /** Add mode: every candidate is already on the target list. */
        EveryoneOnList,

        /** Re-link mode: no other live phone contact to merge into. */
        NoRelinkTargets,

        /** No query and no filter, but everyone left is ignored; offer "Show ignored". */
        EveryoneIgnored,

        /** The search query matches nobody. */
        NoSearchMatches,

        /** The active filters match nobody. */
        NoFilterMatches
    }

    val selectionCount: Int get() = selectedIds.size

    /**
     * True when a search query or an active filter narrows the list. Never in
     * [PickerMode.Relink]: re-link picks exactly one contact, so there is
     * nothing to select all of.
     */
    private val isNarrowed: Boolean
        get() = mode != PickerMode.Relink && (searchQuery.isNotBlank() || activeFilters.isNotEmpty())

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
    val ignoredCount: Int
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
 * - [Relink]: CONTACT-07. Picks the ONE phone contact an orphan is re-linked
 *   to; requires a `relinkContactId` nav arg instead of a target list and
 *   dispatches [app.orbit.domain.usecase.RelinkContactUseCase]. Selection is
 *   single (a new pick replaces the old one) and candidates are restricted to
 *   [app.orbit.domain.usecase.RelinkContactUseCase.isRelinkTarget].
 *
 * Note (2026-10-06): [Move] and [Copy] are route-only. Every caller opens the
 * picker in Add mode (`Routes.pickContacts(listId)`), and moving or copying
 * people happens from Browse's multi-select sheet (`ListSelectorSheet` with
 * `MoveContactsUseCase` / `CopyContactsUseCase`), which took the job. The
 * branches, strings, previews and tests stay for now: they carry the "a zero
 * count is a failed save" behaviour (rules.md Code 3) and removing them is a
 * change of its own. They are a removal candidate; the page view describes
 * the picker as adding and re-linking only.
 */
enum class PickerMode { Add, Move, Copy, Relink }

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
    /**
     * `ContactsContract.Contacts._ID` for this row, mirrored from
     * `ContactEntity.phoneContactId`. Drives the row's "Open in Contacts"
     * action — the device contact card is where an unrecognised number's call
     * and message history lives. Null for call-log-only rows that never
     * matched an address-book entry; the row hides the action in that case
     * rather than opening a dead URI. Defaulted so existing fixtures stay
     * valid.
     */
    val phoneContactId: Long? = null,
    val isIgnored: Boolean,
    val callCount: Int,
    val lastCallAt: Instant?,
    val firstSeenByAppAt: Instant,
    // Device's CONTACT_LAST_UPDATED_TIMESTAMP captured at first sight and frozen.
    // For pre-existing contacts this is older than firstSeenByAppAt (import time);
    // the "Recently added" sort takes min(firstSeen, this) so old contacts sink.
    // Null when the device gave no timestamp or the row predates the field.
    val deviceUpdatedAt: Instant? = null,
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
    val isStarred: Boolean = false
)

/**
 * Tiny projection of [app.orbit.data.entity.ListEntity] for the picker's
 * "In list…" DropdownMenu (PICK-01). Carries only what
 * the chip needs (id + name); avoids leaking Room entities into the UI layer.
 */
@Immutable
data class PickerListSummary(
    val id: Long,
    val name: String
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
fun PickerFilter.countFor(state: ContactPickerUiState): Int = state.filterCounts[this]
    ?: state.allContacts.count { c ->
        (state.showIgnored || !c.isIgnored) &&
            c.phone.isNotBlank() &&
            (
                state.searchQuery.isBlank() ||
                    ContactSearch.match(state.searchQuery, c.displayName, c.phone) != null
                ) &&
            this.matches(c)
    }
