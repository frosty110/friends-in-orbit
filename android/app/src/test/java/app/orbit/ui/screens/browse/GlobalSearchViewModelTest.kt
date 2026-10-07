package app.orbit.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.ContactRepository
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
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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

    /** [contactsFlow], when set, replaces the contact stream the VM reads. */
    private fun makeVm(
        contactsFlow: ((FakeContactRepository) -> Flow<List<ContactEntity>>)? = null,
    ): Setup {
        val contactRepo = FakeContactRepository()
        val listRepo = FakeListRepository()
        val callEventRepo = FakeCallEventRepository()
        val vmContacts: ContactRepository = if (contactsFlow == null) {
            contactRepo
        } else {
            object : ContactRepository by contactRepo {
                override fun observeAll(): Flow<List<ContactEntity>> = contactsFlow(contactRepo)
            }
        }
        val vm = GlobalSearchViewModel(
            contactRepo = vmContacts,
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

    // ========================================================================
    // BROWSE-06: no false states while loading; Error with Retry
    // ========================================================================

    @Test
    fun `a typed query before contacts load is Loading, never NoMatches`() = runTest {
        val gate = MutableStateFlow(false)
        val s = makeVm(contactsFlow = { fake -> gate.filter { it }.flatMapLatest { fake.observeAll() } })
        s.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Maya")))
        s.vm.onSearchChanged("maya")
        s.vm.uiState.test(timeout = 2.seconds) {
            var item = awaitItem()
            while (item == SearchUiState.Empty) item = awaitItem()
            assertEquals(SearchUiState.Loading, item)
            expectNoEvents()
            gate.value = true
            assertEquals(listOf("Maya"), awaitReadyWhere(this) { true }.results.map { it.contact.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty query shows the hint without waiting for contacts`() = runTest {
        val s = makeVm(contactsFlow = { emptyFlow() })
        s.vm.uiState.test(timeout = 2.seconds) {
            assertEquals(SearchUiState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failing contact stream is Error, and Retry recovers`() = runTest {
        var failing = true
        val s = makeVm(
            contactsFlow = { fake ->
                flow {
                    if (failing) throw IOException("disk")
                    emitAll(fake.observeAll())
                }
            },
        )
        s.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Maya")))
        s.vm.onSearchChanged("maya")
        s.vm.uiState.test(timeout = 2.seconds) {
            var item = awaitItem()
            while (item != SearchUiState.Error) item = awaitItem()
            failing = false
            s.vm.onRetry()
            assertEquals(listOf("Maya"), awaitReadyWhere(this) { true }.results.map { it.contact.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // browse-6: call log access off rides Ready, so rows can drop "Never called"
    // ========================================================================

    @Test
    fun `call log denied rides Ready and clears when access returns`() = runTest {
        // Regression: Search had no permission input, so with READ_CALL_LOG
        // denied every result read "Never called", the false claim Browse
        // already avoided.
        val s = makeVm()
        s.contactRepo.seed(listOf(contactFixture(id = 1L, displayName = "Maya")))
        s.vm.onCallLogPermissionChanged(denied = true)
        s.vm.onSearchChanged("maya")
        s.vm.uiState.test(timeout = 2.seconds) {
            val denied = awaitReadyWhere(this) { it.callLogPermissionDenied }
            assertEquals(listOf("Maya"), denied.results.map { it.contact.name })
            // Back from Settings with access granted: the flag clears on the
            // next ON_RESUME push, same query, same results.
            s.vm.onCallLogPermissionChanged(denied = false)
            awaitReadyWhere(this) { !it.callLogPermissionDenied }
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
