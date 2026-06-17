package app.orbit.ui.screens.picker

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure-Kotlin unit tests for [ContactPickerUiState] derived
 * predicates. No Hilt, no Compose, no Room.
 *
 * Coverage:
 *  - PICK-02 AND-semantics: filteredContacts is the AND of activeFilters.
 *  - PICK-05 search composition: shared ContactSearch matcher (diacritic
 *    folding, phone digits, word-start ranking) AND-composed with
 *    chips and ignored exclusion.
 *  - select-all under search-only narrowing + the 200 cap.
 *  - filteredContacts / filterCounts are construction-time
 *    body vals — one computation per state emission.
 *  - PICK-08 ignored exclusion: showIgnored=false hides ignored contacts;
 *    showIgnored=true reveals them.
 *  - PICK-04 NeverCalled first-class: matches callCount==0 directly.
 *  - PICK-03 canSelectAllMatching: gated on filter-active AND remaining
 *    unselected matches.
 *  - countFor: chip count IGNORES other active filters but
 *    RESPECTS search + ignored — load-bearing for chip-badge correctness.
 */
class ContactPickerUiStateTest {

    private fun contact(
        id: Long,
        name: String = "Person $id",
        isIgnored: Boolean = false,
        callCount: Int = 0,
        isCommonlyCalled: Boolean = false,
        isRarelyCalled: Boolean = false,
        isRecentlyAdded: Boolean = false,
        isLongGap: Boolean = false,
        listIds: Set<Long> = emptySet(),
        phone: String = "+1555000$id",
        isStarred: Boolean = false,
    ) = PickerContact(
        contactId = id,
        displayName = name,
        phone = phone,
        photoUri = null,
        isIgnored = isIgnored,
        callCount = callCount,
        lastCallAt = if (callCount > 0) Instant.parse("2026-01-01T00:00:00Z") else null,
        firstSeenByAppAt = Instant.parse("2026-01-01T00:00:00Z"),
        listIds = listIds,
        listNames = emptyList(),
        isCommonlyCalled = isCommonlyCalled,
        isRarelyCalled = isRarelyCalled,
        isRecentlyAdded = isRecentlyAdded,
        isLongGap = isLongGap,
        isStarred = isStarred,
    )

    // ─── Starred filter ─────────────────────────────────────

    @Test
    fun starred_filter_matches_only_starred_contacts() {
        val all = listOf(
            contact(1, isStarred = true),
            contact(2, isStarred = false),
            contact(3, isStarred = true, isIgnored = true),
        )
        val s = stateOf(all, filters = setOf(PickerFilter.Starred))
        assertEquals(
            listOf(1L),
            s.filteredContacts.map { it.contactId },
            "Starred AND ignored-default exclusion — ignored starred contact stays hidden",
        )
        assertEquals(
            1,
            PickerFilter.Starred.countFor(s),
            "chip count respects the ignored-default exclusion (countFor semantics)",
        )
    }

    @Test
    fun starred_count_lands_in_single_pass_filterCounts() {
        val all = listOf(
            contact(1, isStarred = true),
            contact(2, isStarred = true),
            contact(3),
        )
        val s = stateOf(all)
        assertEquals(2, s.filterCounts[PickerFilter.Starred])
    }

    @Test
    fun unsorted_view_floats_starred_first_preserving_inner_order() {
        val all = listOf(
            contact(1, name = "Alex"),
            contact(2, name = "Bea", isStarred = true),
            contact(3, name = "Cory"),
            contact(4, name = "Dana", isStarred = true),
            contact(5, name = "Eli", listIds = setOf(1L)), // sorted — excluded by Unsorted
        )
        val s = stateOf(all, filters = setOf(PickerFilter.Unsorted))
        assertEquals(
            listOf(2L, 4L, 1L, 3L),
            s.filteredContacts.map { it.contactId },
            "starred float first; stable sort preserves name order within each band",
        )
    }

    @Test
    fun starred_first_is_scoped_to_the_unsorted_view() {
        val all = listOf(
            contact(1, name = "Alex"),
            contact(2, name = "Bea", isStarred = true),
        )
        val s = stateOf(all)
        assertEquals(
            listOf(1L, 2L),
            s.filteredContacts.map { it.contactId },
            "without the Unsorted chip the default order is untouched",
        )
    }

    private fun stateOf(
        contacts: List<PickerContact>,
        filters: Set<PickerFilter> = emptySet(),
        search: String = "",
        showIgnored: Boolean = false,
        selected: Set<Long> = emptySet(),
    ) = ContactPickerUiState(
        phase = ContactPickerUiState.Phase.Ready,
        mode = PickerMode.Add,
        targetListName = "Inner orbit",
        searchQuery = search,
        activeFilters = filters,
        showIgnored = showIgnored,
        allContacts = contacts,
        selectedIds = selected,
    )

    // ─── AND-semantics (PICK-02) ───────────────────────────────────────────

    @Test
    fun filteredContacts_with_no_filters_returns_all_non_ignored() {
        val all = listOf(contact(1), contact(2), contact(3, isIgnored = true))
        val s = stateOf(all)
        assertEquals(2, s.filteredContacts.size, "ignored excluded by default (PICK-08)")
        assertEquals(setOf(1L, 2L), s.filteredContacts.map { it.contactId }.toSet())
    }

    @Test
    fun filteredContacts_combines_filters_with_AND() {
        val all = listOf(
            contact(1, isCommonlyCalled = true, isRecentlyAdded = false),
            contact(2, isCommonlyCalled = true, isRecentlyAdded = true),
            contact(3, isCommonlyCalled = false, isRecentlyAdded = true),
        )
        val s = stateOf(all, filters = setOf(PickerFilter.CommonlyCalled, PickerFilter.RecentlyAdded))
        assertEquals(1, s.filteredContacts.size, "AND of CommonlyCalled + RecentlyAdded")
        assertEquals(2L, s.filteredContacts[0].contactId)
    }

    // ─── Search composes (PICK-05) ─────────────────────────────────────────

    @Test
    fun search_composes_with_filters() {
        val all = listOf(
            contact(1, name = "Alex", isCommonlyCalled = true),
            contact(2, name = "Sam", isCommonlyCalled = true),
            contact(3, name = "Alex", isCommonlyCalled = false),
        )
        val s = stateOf(all, filters = setOf(PickerFilter.CommonlyCalled), search = "alex")
        assertEquals(1, s.filteredContacts.size)
        assertEquals(1L, s.filteredContacts[0].contactId, "AND: name contains 'alex' AND CommonlyCalled")
    }

    @Test
    fun search_is_case_insensitive() {
        val all = listOf(contact(1, name = "Alex"), contact(2, name = "ZARA"))
        val s = stateOf(all, search = "ALEX")
        assertEquals(1, s.filteredContacts.size)
        assertEquals(1L, s.filteredContacts[0].contactId)
    }

    // ─── Ranked search via ContactSearch ─────────────────────

    @Test
    fun search_folds_diacritics() {
        val all = listOf(contact(1, name = "José Silva"), contact(2, name = "Maya"))
        val s = stateOf(all, search = "jose")
        assertEquals(listOf(1L), s.filteredContacts.map { it.contactId })
    }

    @Test
    fun search_matches_phone_digits() {
        val all = listOf(
            contact(1, name = "Alex", phone = "+14045551234"),
            contact(2, name = "Sam", phone = "+15125550000"),
        )
        val s = stateOf(all, search = "404555")
        assertEquals(listOf(1L), s.filteredContacts.map { it.contactId })
    }

    @Test
    fun search_ranks_word_start_above_substring() {
        // "ari" starts "Ariana" (WORD_START) and sits mid-word in "Maria"
        // (SUBSTRING) — the word-start hit must come first even though
        // "Maria" precedes alphabetically.
        val all = listOf(contact(1, name = "Maria"), contact(2, name = "Ariana"))
        val s = stateOf(all, search = "ari")
        assertEquals(listOf(2L, 1L), s.filteredContacts.map { it.contactId })
    }

    // ─── Single computation per emission ─────────────────────

    @Test
    fun filteredContacts_is_precomputed_once_per_state_instance() {
        val all = listOf(contact(1), contact(2), contact(3))
        val s = stateOf(all, search = "person")
        // Body val, not a getter: repeated reads return the SAME list
        // instance. The old `get()` allocated a fresh list per access (3-4x
        // per composition).
        assertSame(s.filteredContacts, s.filteredContacts)
        assertSame(s.filterCounts, s.filterCounts)
    }

    @Test
    fun filterCounts_match_countFor_for_all_stateless_filters() {
        val all = listOf(
            contact(1, isCommonlyCalled = true, callCount = 9),
            contact(2, isRarelyCalled = true, callCount = 1),
            contact(3, callCount = 0),
            contact(4, isLongGap = true, callCount = 2),
            contact(5, isIgnored = true, isCommonlyCalled = true, callCount = 7),
        )
        val s = stateOf(all)
        assertEquals(1, PickerFilter.CommonlyCalled.countFor(s), "ignored excluded")
        assertEquals(1, PickerFilter.RarelyCalled.countFor(s))
        assertEquals(1, PickerFilter.NeverCalled.countFor(s))
        assertEquals(1, PickerFilter.LongGap.countFor(s))
        assertEquals(4, PickerFilter.Unsorted.countFor(s), "no memberships seeded")
    }

    @Test
    fun ignoredCount_counts_all_ignored_regardless_of_search() {
        val all = listOf(
            contact(1, name = "Alex"),
            contact(2, name = "Sam", isIgnored = true),
            contact(3, name = "Zara", isIgnored = true),
        )
        val s = stateOf(all, search = "alex")
        assertEquals(2, s.ignoredCount)
    }

    // ─── Ignored exclusion (PICK-08) ───────────────────────────────────────

    @Test
    fun showIgnored_true_reveals_ignored_contacts() {
        val all = listOf(contact(1), contact(2, isIgnored = true), contact(3, isIgnored = true))
        val hidden = stateOf(all, showIgnored = false)
        val revealed = stateOf(all, showIgnored = true)
        assertEquals(1, hidden.filteredContacts.size)
        assertEquals(3, revealed.filteredContacts.size)
    }

    // ─── NeverCalled is first-class (PICK-04) ──────────────────────────────

    @Test
    fun NeverCalled_filter_matches_zero_call_contacts() {
        val all = listOf(
            contact(1, callCount = 0),
            contact(2, callCount = 5),
            contact(3, callCount = 0),
        )
        val s = stateOf(all, filters = setOf(PickerFilter.NeverCalled))
        assertEquals(setOf(1L, 3L), s.filteredContacts.map { it.contactId }.toSet())
    }

    // ─── Unsorted (membership-empty predicate) ─────────────────────────────

    @Test
    fun Unsorted_filter_matches_contacts_with_no_list_memberships() {
        val all = listOf(
            contact(1, listIds = emptySet()),
            contact(2, listIds = setOf(10L)),
            contact(3, listIds = emptySet()),
            contact(4, listIds = setOf(10L, 20L)),
        )
        val s = stateOf(all, filters = setOf(PickerFilter.Unsorted))
        assertEquals(
            setOf(1L, 3L),
            s.filteredContacts.map { it.contactId }.toSet(),
            "Unsorted matches iff listIds.isEmpty()",
        )
    }

    @Test
    fun Unsorted_AND_composes_with_other_filters() {
        val all = listOf(
            contact(1, listIds = emptySet(), isCommonlyCalled = true),
            contact(2, listIds = emptySet(), isCommonlyCalled = false),
            contact(3, listIds = setOf(10L), isCommonlyCalled = true),
        )
        val s = stateOf(all, filters = setOf(PickerFilter.Unsorted, PickerFilter.CommonlyCalled))
        assertEquals(1, s.filteredContacts.size, "AND of Unsorted + CommonlyCalled")
        assertEquals(1L, s.filteredContacts[0].contactId)
    }

    // ─── canSelectAllMatching (PICK-03) ────────────────────────────────────

    @Test
    fun canSelectAllMatching_false_when_no_filters_active() {
        val all = listOf(contact(1, isCommonlyCalled = true), contact(2, isCommonlyCalled = true))
        val s = stateOf(all, filters = emptySet())
        assertFalse(s.canSelectAllMatching, "no filter = no select-all-matching affordance")
    }

    @Test
    fun canSelectAllMatching_false_when_all_matches_already_selected() {
        val all = listOf(contact(1, isCommonlyCalled = true), contact(2, isCommonlyCalled = true))
        val s = stateOf(
            all,
            filters = setOf(PickerFilter.CommonlyCalled),
            selected = setOf(1L, 2L),
        )
        assertFalse(s.canSelectAllMatching, "all 2 matches selected = no remaining work")
    }

    @Test
    fun canSelectAllMatching_true_when_filter_active_and_unselected_matches_remain() {
        val all = listOf(contact(1, isCommonlyCalled = true), contact(2, isCommonlyCalled = true))
        val s = stateOf(
            all,
            filters = setOf(PickerFilter.CommonlyCalled),
            selected = setOf(1L),
        )
        assertTrue(s.canSelectAllMatching, "1 of 2 selected = affordance visible")
    }

    // ─── canSelectAllMatching under search ───────────────────

    @Test
    fun canSelectAllMatching_true_when_search_narrows_without_filters() {
        val all = listOf(
            contact(1, name = "Sarah Smith"),
            contact(2, name = "Sam Smith"),
            contact(3, name = "Priya"),
        )
        val s = stateOf(all, search = "smith")
        assertTrue(
            s.canSelectAllMatching,
            "a search query alone narrows the list — 9 matches must not cost 9 taps",
        )
    }

    @Test
    fun canSelectAllMatching_false_when_nothing_narrows_the_list() {
        val all = listOf(contact(1), contact(2))
        val s = stateOf(all)
        assertFalse(s.canSelectAllMatching, "no query, no filters = no affordance")
        assertFalse(s.selectAllCapExceeded)
    }

    @Test
    fun selectAll_cap_hides_affordance_and_raises_note_flag_over_200_matches() {
        val all = (1L..201L).map { contact(it, name = "Alex $it") }
        val s = stateOf(all, search = "alex")
        assertEquals(201, s.filteredContacts.size)
        assertFalse(s.canSelectAllMatching, "over the cap — no one-tap select-all")
        assertTrue(s.selectAllCapExceeded, "quiet note flag raised instead")
    }

    @Test
    fun selectAll_cap_boundary_exactly_200_still_selectable() {
        val all = (1L..200L).map { contact(it, name = "Alex $it") }
        val s = stateOf(all, search = "alex")
        assertTrue(s.canSelectAllMatching)
        assertFalse(s.selectAllCapExceeded)
    }

    // ─── countFor — other-filter independence ──────────────────────────────

    @Test
    fun countFor_respects_search_and_ignored_but_ignores_other_filters() {
        // 4 commonly-called rows ('Alex' x3, 'Sam' x1), 1 ignored Alex.
        // Active filter: RecentlyAdded — irrelevant to countFor(CommonlyCalled).
        // Search: "alex" — matches ids 1, 2, 4, 5 (id 5 is ignored, default-excluded).
        val all = listOf(
            contact(1, name = "Alex", isCommonlyCalled = true, isRecentlyAdded = true),
            contact(2, name = "Alex", isCommonlyCalled = true, isRecentlyAdded = false),
            contact(3, name = "Sam", isCommonlyCalled = true, isRecentlyAdded = true),
            contact(4, name = "Alex", isCommonlyCalled = false),
            contact(5, name = "Alex", isCommonlyCalled = true, isIgnored = true),
        )
        val s = stateOf(
            all,
            filters = setOf(PickerFilter.RecentlyAdded),
            search = "alex",
            showIgnored = false,
        )

        // countFor(CommonlyCalled) should count: name contains 'alex' AND !isIgnored AND CommonlyCalled.
        // Matches: 1 (yes), 2 (yes), 4 (no — not CommonlyCalled), 5 (no — ignored excluded).
        // Expected count = 2.
        assertEquals(
            2,
            PickerFilter.CommonlyCalled.countFor(s),
            "countFor must respect search + ignored, but ignore the OTHER active filter (RecentlyAdded)",
        )
    }

    @Test
    fun countFor_with_showIgnored_true_includes_ignored() {
        val all = listOf(
            contact(1, isCommonlyCalled = true),
            contact(2, isCommonlyCalled = true, isIgnored = true),
        )
        val s = stateOf(all, showIgnored = true)
        assertEquals(2, PickerFilter.CommonlyCalled.countFor(s))
    }
}
