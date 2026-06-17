package app.orbit.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.nav.Routes
import app.orbit.testutil.MainDispatcherRule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Behavioral tests for [GlobalSearchViewModel] — quality push 2026-06-09
 * (#16 ranked matching via [app.orbit.domain.search.ContactSearch], #20
 * membership chips from existing repository observers).
 *
 * Pattern mirrors [BrowseViewModelTest]: pure JVM, fakes from
 * `app.orbit.domain.FakeRepositories`, turbine over the `uiState` StateFlow,
 * drain-until-predicate helpers for the combine pipeline's intermediate
 * emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private data class Setup(
        val vm: GlobalSearchViewModel,
        val contactRepo: FakeContactRepository,
        val listRepo: FakeListRepository,
        val callEventRepo: FakeCallEventRepository,
    )

    private fun makeVm(): Setup {
        val contactRepo = FakeContactRepository()
        val listRepo = FakeListRepository()
        val callEventRepo = FakeCallEventRepository()
        val vm = GlobalSearchViewModel(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            clock = TestClock(),
            savedStateHandle = SavedStateHandle(),
        )
        return Setup(vm, contactRepo, listRepo, callEventRepo)
    }

    // ========================================================================
    // #20 — membership chips
    // ========================================================================

    @Test
    fun `hits carry active list names for members and empty for non-members`() = runTest {
        val s = makeVm()
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "Maya Ahmed"),
                contactFixture(id = 2L, displayName = "Maya Brooks"),
            ),
        )
        s.listRepo.seed(
            listOf(
                listFixture(id = 1L, name = "In touch", sortOrder = 0),
                listFixture(id = 2L, name = "Late night", sortOrder = 1),
            ),
        )
        // Contact 1 is on both lists; contact 2 is on none.
        s.listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 1L, listId = 2L),
            ),
        )
        s.vm.onSearchChanged("maya")
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.results.size == 2 }
            val byName = ready.results.associateBy { it.contact.name }
            // Chip order follows the user's list sortOrder (observeActive is ASC).
            assertEquals(listOf("In touch", "Late night"), byName.getValue("Maya Ahmed").lists)
            assertEquals(emptyList(), byName.getValue("Maya Brooks").lists)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `archived list memberships are not surfaced as chips`() = runTest {
        val s = makeVm()
        s.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Maya")))
        s.listRepo.seed(
            listOf(
                listFixture(id = 1L, name = "In touch", sortOrder = 0),
                listFixture(id = 2L, name = "Old crowd", sortOrder = 1, isArchived = true),
            ),
        )
        s.listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 1L, listId = 2L),
            ),
        )
        s.vm.onSearchChanged("maya")
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.results.isNotEmpty() }
            assertEquals(listOf("In touch"), ready.results.single().lists)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // #16 — ranked matching via ContactSearch
    // ========================================================================

    @Test
    fun `accented name matches unaccented query`() = runTest {
        val s = makeVm()
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "José García"),
                contactFixture(id = 2L, displayName = "Maya"),
            ),
        )
        s.vm.onSearchChanged("jose")
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.query == "jose" }
            assertEquals(listOf("José García"), ready.results.map { it.contact.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `digit query matches normalized phone`() = runTest {
        val s = makeVm()
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "Maya", normalizedPhone = "+14045551234"),
                contactFixture(id = 2L, displayName = "Sam", normalizedPhone = "+15105550000"),
            ),
        )
        s.vm.onSearchChanged("5551234")
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.query == "5551234" }
            assertEquals(listOf("Maya"), ready.results.map { it.contact.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `word-start match ranks before substring match despite older last call`() = runTest {
        val s = makeVm()
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "Maria"),   // "ari" mid-word
                contactFixture(id = 2L, displayName = "Ariana"),  // "ari" word-start
            ),
        )
        // Maria called yesterday, Ariana never — recency alone would put Maria
        // first; rank band must win.
        s.callEventRepo.seed(
            listOf(
                callEventFixture(
                    id = 1L,
                    contactId = 1L,
                    occurredAt = Instant.parse("2025-12-31T12:00:00Z"),
                ),
            ),
        )
        s.vm.onSearchChanged("ari")
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.results.size == 2 }
            assertEquals(listOf("Ariana", "Maria"), ready.results.map { it.contact.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `within a rank band more recent last call sorts first`() = runTest {
        val s = makeVm()
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "Maya Ahmed"),
                contactFixture(id = 2L, displayName = "Maya Brooks"),
            ),
        )
        // Both are word-start hits for "maya"; Brooks called more recently.
        s.callEventRepo.seed(
            listOf(
                callEventFixture(
                    id = 1L,
                    contactId = 1L,
                    occurredAt = Instant.parse("2025-12-01T12:00:00Z"),
                ),
                callEventFixture(
                    id = 2L,
                    contactId = 2L,
                    occurredAt = Instant.parse("2025-12-31T12:00:00Z"),
                ),
            ),
        )
        s.vm.onSearchChanged("maya")
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.results.size == 2 }
            assertEquals(
                listOf("Maya Brooks", "Maya Ahmed"),
                ready.results.map { it.contact.name },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no matches emits NoMatches with the trimmed query`() = runTest {
        val s = makeVm()
        s.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Maya")))
        s.vm.onSearchChanged("zz")
        s.vm.uiState.test(timeout = 2.seconds) {
            while (true) {
                val item = awaitItem()
                if (item is SearchUiState.NoMatches) {
                    assertEquals("zz", item.query)
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // #20 — add-to-list routing contract
    // ========================================================================

    @Test
    fun `hit contact id routes to the list picker with the c-prefixed id`() = runTest {
        val s = makeVm()
        s.contactRepo.seed(listOf(contactFixture(id = 7L, displayName = "Maya")))
        s.vm.onSearchChanged("maya")
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.results.isNotEmpty() }
            val hit = ready.results.single()
            // The screen's "Add to list" affordance calls
            // onAddToLists(hit.contact.id); the nav host maps that through
            // Routes.pickLists. ListPickerViewModel strips the "c-" prefix
            // (`removePrefix("c-").toLongOrNull()`), so this route string is
            // the load-bearing contract.
            assertEquals("c-7", hit.contact.id)
            assertEquals("pick/lists?contactId=c-7", Routes.pickLists(hit.contact.id))
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Drains emissions until a [SearchUiState.Ready] satisfying [predicate]. */
    private suspend fun awaitReadyWhere(
        flow: app.cash.turbine.ReceiveTurbine<SearchUiState>,
        predicate: (SearchUiState.Ready) -> Boolean,
    ): SearchUiState.Ready {
        while (true) {
            val item = flow.awaitItem()
            if (item is SearchUiState.Ready && predicate(item)) return item
        }
    }
}
