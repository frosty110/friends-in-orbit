package app.orbit.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.orbit.data.dao.ListDao
import app.orbit.data.dao.RecordingContactDao
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.data.feed.BrowseFeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.SurfaceQueueUseCase
import app.orbit.domain.usecase.BulkIgnoreUseCase
import app.orbit.domain.usecase.BulkPauseUseCase
import app.orbit.domain.usecase.BulkRemoveFromListUseCase
import app.orbit.domain.usecase.CopyContactsUseCase
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.MoveContactsUseCase
import app.orbit.domain.usecase.PauseContactUseCase
import app.orbit.testutil.MainDispatcherRule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Behavioral tests for [BrowseViewModel] — the Browse VM widened with
 * multi-select state + 11 dispatch methods.
 *
 * Pattern mirrors [app.orbit.ui.screens.card.CardViewViewModelTest] and
 * [app.orbit.ui.screens.home.HomeViewModelTest]. Asserts terminal state per
 * ARCH-02 observable contract; UnconfinedTestDispatcher + stateIn initialValue
 * collapse is the documented test-side behavior.
 *
 * **Fixture repair:** the constructor widened with 6 new injected deps (5 bulk
 * use cases + UndoStack). Existing tests pass these via the fakes wired in
 * [makeVm]. Two carryover failures (`seeded contacts emits Ready...`,
 * `onSearchChanged filters contacts...`) are forensically allowlisted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Pass-through transaction runner for JVM unit tests (Pitfall 3 — no dispatcher switch). */
    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    /**
     * Minimum-viable [ListDao] stub for the @Ignore'd allowlist.
     * Returns no-ops for every method — the class is `@Ignore`d so no test ever
     * exercises this stub at runtime; it exists only to satisfy the
     * `MoveContactsUseCase` / `CopyContactsUseCase` ctor type signature so the
     * unit-test compile target stays green.
     */
    private val noopListDao: ListDao = object : ListDao {
        override fun observeActive(): Flow<List<ListEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<ListEntity>> = flowOf(emptyList())
        override fun observeById(id: Long): Flow<ListEntity?> = flowOf(null)
        override suspend fun get(id: Long): ListEntity? = null
        override suspend fun insert(list: ListEntity): Long = 1L
        override suspend fun update(list: ListEntity): Int = 1
        override suspend fun delete(list: ListEntity): Int = 1
        override suspend fun deleteList(listId: Long): Int = 1
        override suspend fun updateSortOrder(id: Long, sortOrder: Int) {}
        override suspend fun updateArchived(id: Long, archived: Boolean) {}
        override suspend fun updateSmartRuleJson(id: Long, json: String?) {}
        override suspend fun updateRuleParamsOverrideJson(id: Long, json: String?) {}
        override suspend fun updateTypeAndSmartRuleJson(
            id: Long,
            type: ListType,
            smartRuleJson: String?,
        ) {}
        override suspend fun updateRuleTemplate(id: Long, templateId: Long) {}
        override suspend fun updateActiveHours(
            id: Long,
            start: java.time.LocalTime?,
            end: java.time.LocalTime?,
        ) {}
        override suspend fun updateNotificationsEnabled(id: Long, enabled: Boolean) {}
        override suspend fun updateName(id: Long, name: String) {}
        override suspend fun getActive(): List<ListEntity> = emptyList()
        override suspend fun recomputeDueCount(listId: Long, nowMs: Long) {}
        override suspend fun recomputeDueCountForActive(nowMs: Long) {}
        override suspend fun updateNudgeScheduleJson(id: Long, json: String?) {}
        override suspend fun dueCountForList(id: Long): Int? = null
    }

    private fun makeVm(
        savedStateListId: String? = "1",
        ruleTemplateRepo: FakeRuleTemplateRepository = FakeRuleTemplateRepository(),
    ): Setup {
        val contactRepo = FakeContactRepository()
        val listRepo = FakeListRepository()
        val callEventRepo = FakeCallEventRepository()
        val clock = app.orbit.domain.clock.TestClock()
        val recDao = RecordingListMembershipDao()
        val recContactDao = RecordingContactDao()
        val undoStack = UndoStack()
        val savedState = SavedStateHandle(mapOf("listId" to savedStateListId))
        // Real [BrowseFeed] over the existing fakes; the
        // singleton's `forList(...)` projection re-derives from the same
        // `contactRepo.observeAll()` / `listRepo.observeMembersOfList(...)` /
        // `callEventRepo.observeRecentForListContacts(...)` flows the legacy
        // VM combine consumed, so the existing seeded-state tests assert on
        // the same observable contract through the new singleton.
        //
        // BrowseFeed gains SurfaceQueueUseCase. The default
        // ruleTemplateRepo is seeded empty so the use case always emits emptyList()
        // (no template found → all members fall into the "Other members" tail, sorted
        // alphabetically). The existing `.contacts.map { it.name }` assertions use
        // alphabetical seeds so the ordering is unchanged. An empty queue is the
        // lowest-risk fixture for these membership-driven tests; queue-order assertions
        // live in the dedicated queue-order test below (which passes a seeded
        // ruleTemplateRepo via the parameter above).
        val surfaceQueueUseCase = SurfaceQueueUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            ruleTemplateRepo = ruleTemplateRepo,
            clock = clock,
            json = JsonProvider.json,
        )
        val browseFeed = BrowseFeed(
            listRepo = listRepo,
            contactRepo = contactRepo,
            callEventRepo = callEventRepo,
            surfaceQueueUseCase = surfaceQueueUseCase,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        val vm = BrowseViewModel(
            contactRepo = contactRepo,
            listRepo = listRepo,
            browseFeed = browseFeed,
            clock = clock,
            moveUseCase = MoveContactsUseCase(passThruTx, recDao, noopListDao, listRepo, clock),
            copyUseCase = CopyContactsUseCase(passThruTx, recDao, noopListDao, listRepo, clock),
            bulkRemoveFromListUseCase = BulkRemoveFromListUseCase(passThruTx, recDao, listRepo, clock),
            bulkIgnoreUseCase = BulkIgnoreUseCase(passThruTx, recContactDao),
            bulkPauseUseCase = BulkPauseUseCase(passThruTx, recContactDao, clock),
            // Single-row Ignore + Pause use cases. IgnoreContactUseCase
            // takes the same passThruTx + recDao so the inverse closure round-trips
            // through the FakeContactRepository state without a real Room transaction.
            ignoreContactUseCase = IgnoreContactUseCase(passThruTx, contactRepo, recDao, listRepo, clock),
            pauseContactUseCase = PauseContactUseCase(contactRepo, clock),
            undoStack = undoStack,
            savedStateHandle = savedState,
        )
        return Setup(vm, contactRepo, listRepo)
    }

    private data class Setup(
        val vm: BrowseViewModel,
        val contactRepo: FakeContactRepository,
        val listRepo: FakeListRepository,
    )

    // ============================================================================
    // Carryover tests — 2 of these have known failures (allowlisted). Their
    // fixture passes the new 6 ctor params via makeVm.
    // ============================================================================

    @Test
    fun `empty repo and empty query emits Empty`() = runTest {
        val (vm, _, _) = makeVm()
        vm.uiState.test(timeout = 2.seconds) {
            assertEquals(BrowseUiState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `seeded contacts emits Ready with all three and empty query`() = runTest {
        val (vm, contactRepo, listRepo) = makeVm()
        contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "Alex"),
                contactFixture(id = 2L, displayName = "Bailey"),
                contactFixture(id = 3L, displayName = "Cam"),
            ),
        )
        // BrowseViewModel filters by membership join, so the
        // test must seed memberships for the same listId the SavedStateHandle
        // carries (default "1" → 1L). Without this the combine pipeline stays
        // at Empty.
        listRepo.seedMemberships(
            listOf(
                app.orbit.domain.membershipFixture(contactId = 1L, listId = 1L),
                app.orbit.domain.membershipFixture(contactId = 2L, listId = 1L),
                app.orbit.domain.membershipFixture(contactId = 3L, listId = 1L),
            ),
        )
        vm.uiState.test(timeout = 2.seconds) {
            val next = awaitReady(this)
            assertEquals(3, next.contacts.size)
            assertEquals("", next.searchQuery)
            assertEquals(listOf("Alex", "Bailey", "Cam"), next.contacts.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Queue-order contract.
     *
     * Wires a non-null [ruleTemplateRepo] (id=1) + a list with [ruleTemplateId]=1 so
     * [SurfaceQueueUseCase] produces a non-empty queue. Cold-start contacts (no call
     * events, null nextDueAt) sort by `contact.id ASC` (the deterministic tiebreak in
     * the three-key comparator: nextDueAt ASC → lastCalledAt ASC NULLS LAST → id ASC).
     * Expected queue order: Alex (id=1) → Bailey (id=2) → Cam (id=3).
     *
     * Asserts:
     *   1. [BrowseUiState.Ready.queuePositions] is keyed by UI-domain `"c-{entityId}"`.
     *   2. Positions are 1-based and span exactly {1, 2, 3}.
     *   3. [BrowseUiState.Ready.contacts] is ordered by queue position (head first).
     */
    @Test
    fun `Ready emits queuePositions keyed by ui-domain contact id`() = runTest {
        val templateRepo = FakeRuleTemplateRepository()
        templateRepo.seed(listOf(ruleTemplateFixture(id = 1L)))
        val (vm, contactRepo, listRepo) = makeVm(ruleTemplateRepo = templateRepo)
        // Seed the list entity with ruleTemplateId=1 so SurfaceQueueUseCase proceeds past
        // the `list.ruleTemplateId ?: return@combine emptyList()` guard.
        listRepo.seed(listOf(listFixture(id = 1L, ruleTemplateId = 1L)))
        contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "Alex"),
                contactFixture(id = 2L, displayName = "Bailey"),
                contactFixture(id = 3L, displayName = "Cam"),
            ),
        )
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 2L, listId = 1L),
                membershipFixture(contactId = 3L, listId = 1L),
            ),
        )
        vm.uiState.test(timeout = 2.seconds) {
            // Drain until we get a Ready with all 3 positions populated.
            val next = awaitReadyWhere(this) { it.queuePositions.size == 3 }
            // Map keys must be UI-domain Contact.id strings ("c-{entityId}"), not raw Longs.
            assertTrue(next.queuePositions.containsKey("c-1"), "expected key c-1")
            assertTrue(next.queuePositions.containsKey("c-2"), "expected key c-2")
            assertTrue(next.queuePositions.containsKey("c-3"), "expected key c-3")
            // Positions are 1-based and unique.
            assertEquals(setOf(1, 2, 3), next.queuePositions.values.toSet())
            // contacts list is ordered by queue position (head first).
            val sortedByPosition = next.contacts.sortedBy { next.queuePositions["${it.id}"] }
            assertEquals(next.contacts.map { it.name }, sortedByPosition.map { it.name })
            // Head contact carries position 1.
            assertEquals(1, next.queuePositions[next.contacts.first().id])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onSearchChanged filters contacts via SavedStateHandle`() = runTest {
        val (vm, contactRepo, listRepo) = makeVm()
        contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "Alex"),
                contactFixture(id = 2L, displayName = "Bailey"),
                contactFixture(id = 3L, displayName = "Cam"),
            ),
        )
        listRepo.seedMemberships(
            listOf(
                app.orbit.domain.membershipFixture(contactId = 1L, listId = 1L),
                app.orbit.domain.membershipFixture(contactId = 2L, listId = 1L),
                app.orbit.domain.membershipFixture(contactId = 3L, listId = 1L),
            ),
        )
        // Search needs a substring unique to one name — "A" alone matches
        // all three (Alex, b**a**iley, c**a**m) under the case-insensitive
        // contains() filter in buildState. The original allowlisted test
        // used "A" and was always going to fail once the search filter
        // actually wired through; "Alex" is the precise substring the
        // assertion claims to test.
        vm.onSearchChanged("Alex")
        vm.uiState.test(timeout = 2.seconds) {
            val filtered = awaitReadyWhere(this) { it.searchQuery == "Alex" }
            assertEquals(1, filtered.contacts.size)
            assertEquals("Alex", filtered.contacts[0].name)
            assertEquals("Alex", filtered.searchQuery)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Multi-select transition tests — load-bearing state machine
    // ============================================================================

    @Test
    fun onEnterMultiSelect_sets_isMultiSelect_true_and_seeds_selection() = runTest {
        val (vm, _, _) = makeVm()
        vm.onEnterMultiSelect(initialId = 42L)
        // Read terminal state directly from the StateFlow.
        // The combine pipeline applies multi-select state to BrowseUiState.Ready
        // when it exists; if the upstream is still Empty (no contacts seeded),
        // we still verify the underlying state flow contract via the eventual
        // Ready state. For this test we only check the multi-select StateFlow
        // observable surface is reachable — `vm.uiState.value` returns either
        // Loading/Empty (no Ready overlay) or Ready (with overlay). We assert
        // the observable contract via `vm.uiState.value` after seeding minimal
        // contact data.
        // Pure transition assertion: read via uiState filter for Ready.
        val state = vm.uiState.value
        // Either Loading/Empty (no contacts yet — multi-select state still
        // tracked via _isMultiSelect/_selectedIds internally) or Ready (overlay
        // applied). We verify by triggering the combine pipeline through a
        // contacts seed.
        @Suppress("UNUSED_VARIABLE") val _state = state
        // The deterministic assertion: after seeding contacts, Ready must
        // reflect the selection. We do that here.
        val s2 = makeVmWithContacts()
        s2.vm.onEnterMultiSelect(initialId = 42L)
        s2.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(true, ready.isMultiSelect)
            assertEquals(setOf(42L), ready.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onToggleSelect_adds_then_removes_id() = runTest {
        val s = makeVmWithContacts()
        s.vm.onEnterMultiSelect(initialId = 1L)
        s.vm.onToggleSelect(2L)
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(setOf(1L, 2L), ready.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
        s.vm.onToggleSelect(1L)
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(setOf(2L), ready.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onToggleSelect_to_empty_auto_exits_multiSelect() = runTest {
        val s = makeVmWithContacts()
        s.vm.onEnterMultiSelect(initialId = 1L)
        s.vm.onToggleSelect(1L)  // toggle off the only selected
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(false, ready.isMultiSelect)
            assertEquals(emptySet<Long>(), ready.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onExitMultiSelect_clears_selection_and_flips_off() = runTest {
        val s = makeVmWithContacts()
        s.vm.onEnterMultiSelect(1L)
        s.vm.onToggleSelect(2L)
        s.vm.onExitMultiSelect()
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(false, ready.isMultiSelect)
            assertEquals(emptySet<Long>(), ready.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onSelectAllMatching_unions_into_selection() = runTest {
        val s = makeVmWithContacts()
        s.vm.onEnterMultiSelect(1L)
        s.vm.onSelectAllMatching(setOf(2L, 3L, 4L))
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(setOf(1L, 2L, 3L, 4L), ready.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onBulkRemove_emits_snackbar_event_and_exits_multiSelect() = runTest {
        val s = makeVmWithContactsAndMembership()
        s.vm.onEnterMultiSelect(1L)
        s.vm.onToggleSelect(2L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkRemove()
            val event = awaitItem()
            assertTrue(
                event.message.startsWith("Removed 2 from"),
                "expected 'Removed 2 from <list>', got ${event.message}",
            )
            assertEquals("Undo", event.actionLabel)
            cancel()
        }
        // After commit, multi-select auto-exits (MOVE-06).
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(false, ready.isMultiSelect)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Orientation + honest states + the shared ContactSearch matcher.
    // ============================================================================

    @Test
    fun `listName emits the real list name`() = runTest {
        val s = makeVmWithContactsAndMembership()   // seeds list id=1 "Inner orbit"
        s.vm.listName.test(timeout = 2.seconds) {
            // Drain the initial "" (stateIn initialValue) if it surfaces first.
            while (true) {
                if (awaitItem() == "Inner orbit") break
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `due paused and ignored orientation surfaces on Ready`() = runTest {
        // TestClock default now — makeVm constructs TestClock() with this instant.
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val (vm, contactRepo, listRepo) = makeVm()
        contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "Alex"),
                contactFixture(id = 2L, displayName = "Bailey"),
                contactFixture(id = 3L, displayName = "Cam", pausedUntil = now.plusSeconds(86_400)),
                contactFixture(id = 4L, displayName = "Dana", isIgnored = true),
            ),
        )
        listRepo.seedMemberships(
            listOf(
                // Past nextDueAt → due; future → not due. Paused/ignored rows
                // carry a past nextDueAt too but must NOT read as due.
                app.orbit.domain.membershipFixture(contactId = 1L, listId = 1L, nextDueAt = now.minusSeconds(3_600)),
                app.orbit.domain.membershipFixture(contactId = 2L, listId = 1L, nextDueAt = now.plusSeconds(3_600)),
                app.orbit.domain.membershipFixture(contactId = 3L, listId = 1L, nextDueAt = now.minusSeconds(3_600)),
                app.orbit.domain.membershipFixture(contactId = 4L, listId = 1L, nextDueAt = now.minusSeconds(3_600)),
            ),
        )
        vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.contacts.size == 4 }
            assertEquals(setOf("c-1"), ready.dueIds)
            assertEquals(BrowseRowStatus.Paused, ready.rowStatus["c-3"])
            assertEquals(BrowseRowStatus.Ignored, ready.rowStatus["c-4"])
            assertNull(ready.rowStatus["c-1"], "active row carries no status word")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `call log denied with active filter emits CallLogDenied`() = runTest {
        val s = makeVmWithContacts()
        s.vm.onCallLogPermissionChanged(denied = true)
        s.vm.onToggleFilter(BrowseFilter.NotCalledYet)
        s.vm.uiState.test(timeout = 2.seconds) {
            while (true) {
                if (awaitItem() == BrowseUiState.CallLogDenied) break
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `call log denied without filters keeps Ready with the honest flag`() = runTest {
        val s = makeVmWithContacts()
        s.vm.onCallLogPermissionChanged(denied = true)
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.callLogPermissionDenied }
            assertEquals(4, ready.contacts.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `filters excluding everyone emit FilteredEmpty and clearing restores Ready`() = runTest {
        val s = makeVmWithContacts()   // zero call events → nobody "called recently"
        s.vm.onToggleFilter(BrowseFilter.CalledRecently)
        s.vm.uiState.test(timeout = 2.seconds) {
            while (true) {
                if (awaitItem() == BrowseUiState.FilteredEmpty) break
            }
            cancelAndIgnoreRemainingEvents()
        }
        s.vm.onClearFilters()
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.contacts.size == 4 }
            assertTrue(ready.activeFilters.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search folds diacritics via the shared matcher`() = runTest {
        val (vm, contactRepo, listRepo) = makeVm()
        contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "José"),
                contactFixture(id = 2L, displayName = "Bailey"),
            ),
        )
        listRepo.seedMemberships(
            listOf(
                app.orbit.domain.membershipFixture(contactId = 1L, listId = 1L),
                app.orbit.domain.membershipFixture(contactId = 2L, listId = 1L),
            ),
        )
        vm.onSearchChanged("jose")
        vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.searchQuery == "jose" }
            assertEquals(listOf("José"), ready.contacts.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search matches phone digits via the shared matcher`() = runTest {
        val (vm, contactRepo, listRepo) = makeVm()
        contactRepo.seed(
            listOf(
                // contactFixture default phone: +1555555<id padded to 4>.
                contactFixture(id = 1L, displayName = "Alex"),
                contactFixture(id = 2L, displayName = "Bailey"),
            ),
        )
        listRepo.seedMemberships(
            listOf(
                app.orbit.domain.membershipFixture(contactId = 1L, listId = 1L),
                app.orbit.domain.membershipFixture(contactId = 2L, listId = 1L),
            ),
        )
        vm.onSearchChanged("0002")
        vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.searchQuery == "0002" }
            assertEquals(listOf("Bailey"), ready.contacts.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Helpers — `makeVmWithContacts` / `makeVmWithContactsAndMembership` seed
    // upstream so the combine pipeline emits Ready (rather than Empty), letting
    // multi-select state flow through to the observable surface.
    // ============================================================================

    private fun makeVmWithContacts(): Setup {
        val s = makeVm(savedStateListId = "1")
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "Alex"),
                contactFixture(id = 2L, displayName = "Bailey"),
                contactFixture(id = 3L, displayName = "Cam"),
                contactFixture(id = 4L, displayName = "Dana"),
            ),
        )
        s.listRepo.seedMemberships(
            listOf(
                app.orbit.domain.membershipFixture(contactId = 1L, listId = 1L),
                app.orbit.domain.membershipFixture(contactId = 2L, listId = 1L),
                app.orbit.domain.membershipFixture(contactId = 3L, listId = 1L),
                app.orbit.domain.membershipFixture(contactId = 4L, listId = 1L),
            ),
        )
        return s
    }

    private fun makeVmWithContactsAndMembership(): Setup {
        val s = makeVmWithContacts()
        s.listRepo.seed(
            listOf(
                app.orbit.domain.listFixture(id = 1L, name = "Inner orbit"),
            ),
        )
        return s
    }

    /** Skips Loading and returns the first Ready emission. */
    private suspend fun awaitReady(
        flow: app.cash.turbine.ReceiveTurbine<BrowseUiState>,
    ): BrowseUiState.Ready {
        while (true) {
            val item = flow.awaitItem()
            if (item is BrowseUiState.Ready) return item
        }
    }

    /** Drains intermediate Ready emissions until [predicate] holds. */
    private suspend fun awaitReadyWhere(
        flow: app.cash.turbine.ReceiveTurbine<BrowseUiState>,
        predicate: (BrowseUiState.Ready) -> Boolean,
    ): BrowseUiState.Ready {
        while (true) {
            val item = flow.awaitItem()
            if (item is BrowseUiState.Ready && predicate(item)) return item
        }
    }
}
