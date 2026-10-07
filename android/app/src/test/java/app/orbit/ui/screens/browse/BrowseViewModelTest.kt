package app.orbit.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.orbit.R
import app.orbit.data.dao.IgnoredSnapshot
import app.orbit.data.dao.PausedUntilSnapshot
import app.orbit.data.dao.RecordingContactDao
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.dao.TestListDaoStub
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.data.feed.BrowseFeed
import app.orbit.data.feed.BrowseFeedSnapshot
import app.orbit.data.repository.ListRepository
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.callEventFixture
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.model.PauseDuration
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.BulkIgnoreUseCase
import app.orbit.domain.usecase.BulkPauseUseCase
import app.orbit.domain.usecase.BulkRemoveFromListUseCase
import app.orbit.domain.usecase.CopyContactsUseCase
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.MoveContactsUseCase
import app.orbit.domain.usecase.PauseContactUseCase
import app.orbit.domain.usecase.SurfaceQueueUseCase
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.util.UiText
import app.orbit.ui.util.pausedPeopleSnackbar
import app.orbit.ui.util.pausedSnackbar
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Behavioral tests for [BrowseViewModel]: the state contract, the queue
 * order, search and filters, and the multi-select state machine with every
 * bulk action's snackbar and inverse.
 *
 * Pattern mirrors [app.orbit.ui.screens.card.CardViewViewModelTest] and
 * [app.orbit.ui.screens.home.HomeViewModelTest]. Asserts terminal state per
 * ARCH-02 observable contract; UnconfinedTestDispatcher + stateIn initialValue
 * collapse is the documented test-side behavior.
 *
 * The bulk use cases run over the recording DAOs
 * ([RecordingListMembershipDao], [RecordingContactDao]) so a test can read
 * what a forward write and its Undo dispatched; the single-row ones run over
 * [FakeContactRepository], whose state the inverse round-trips. A write that
 * fails is a use case whose [TransactionRunner] throws ([makeVm]'s
 * `useCaseTx`), the way Room would fail inside its transaction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Pass-through transaction runner for JVM unit tests (Pitfall 3 — no dispatcher switch). */
    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    /** A transaction that fails before its block runs: the use case throws, as Room would. */
    private val failingTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T =
            throw IllegalStateException("disk full")
    }

    /**
     * Builds the VM over the fakes.
     *
     * [scriptedForList], when set, replaces the real feed's per-list stream so
     * a test can hold the feed before its first emission or make it fail.
     * [targetLists] is what the Move and Copy use cases' `ListDao` resolves a
     * destination to. [ignoredSnapshots] and [pausedSnapshots] seed what the
     * bulk Ignore and Pause use cases read as each person's prior state.
     * [getByIdGate], when set, parks the VM's `listRepo.getById` until it
     * completes, so a test can overlap two dispatches (browse-2). [useCaseTx]
     * is the transaction runner the bulk use cases and the single-row Ignore
     * run in; [failingTx] makes each of them throw.
     */
    private fun makeVm(
        savedStateListId: String? = "1",
        ruleTemplateRepo: FakeRuleTemplateRepository = FakeRuleTemplateRepository(),
        scriptedForList: ((Long) -> StateFlow<BrowseFeedSnapshot>)? = null,
        targetLists: List<ListEntity> = emptyList(),
        ignoredSnapshots: List<IgnoredSnapshot> = emptyList(),
        pausedSnapshots: List<PausedUntilSnapshot> = emptyList(),
        getByIdGate: CompletableDeferred<Unit>? = null,
        useCaseTx: TransactionRunner = passThruTx
    ): Setup {
        val contactRepo = FakeContactRepository()
        val listRepo = FakeListRepository()
        val callEventRepo = FakeCallEventRepository()
        val clock = app.orbit.domain.clock.TestClock()
        val recDao = RecordingListMembershipDao()
        val recContactDao = RecordingContactDao(ignoredSnapshots, pausedSnapshots)
        val undoStack = UndoStack()
        val savedState = SavedStateHandle(mapOf("listId" to savedStateListId))
        val refreshes = intArrayOf(0)
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
            json = JsonProvider.json
        )
        val browseFeed = if (scriptedForList == null) {
            BrowseFeed(
                listRepo = listRepo,
                contactRepo = contactRepo,
                callEventRepo = callEventRepo,
                surfaceQueueUseCase = surfaceQueueUseCase,
                scope = CoroutineScope(UnconfinedTestDispatcher())
            )
        } else {
            object : BrowseFeed(
                listRepo = listRepo,
                contactRepo = contactRepo,
                callEventRepo = callEventRepo,
                surfaceQueueUseCase = surfaceQueueUseCase,
                scope = CoroutineScope(UnconfinedTestDispatcher())
            ) {
                override fun forList(listId: Long): StateFlow<BrowseFeedSnapshot> =
                    scriptedForList(listId)
            }
        }
        // The VM's own repository handle, parked on the gate when a test asks
        // for it; the feed and the use cases keep the plain fake.
        val vmListRepo: ListRepository = if (getByIdGate == null) {
            listRepo
        } else {
            object : ListRepository by listRepo {
                override suspend fun getById(id: Long): ListEntity? {
                    getByIdGate.await()
                    return listRepo.getById(id)
                }
            }
        }
        // The Move/Copy destination lookup. A stub, not the fake repository: the
        // use cases take a ListDao.
        val listDao = TestListDaoStub(targetLists)
        val vm = BrowseViewModel(
            contactRepo = contactRepo,
            listRepo = vmListRepo,
            browseFeed = browseFeed,
            clock = clock,
            moveUseCase = MoveContactsUseCase(useCaseTx, recDao, listDao, listRepo, clock),
            copyUseCase = CopyContactsUseCase(useCaseTx, recDao, listDao, listRepo, clock),
            bulkRemoveFromListUseCase = BulkRemoveFromListUseCase(
                useCaseTx,
                recDao,
                listRepo,
                clock
            ),
            bulkIgnoreUseCase = BulkIgnoreUseCase(useCaseTx, recContactDao),
            bulkPauseUseCase = BulkPauseUseCase(useCaseTx, recContactDao, clock),
            // Single-row Ignore + Pause use cases. IgnoreContactUseCase
            // takes the same passThruTx + recDao so the inverse closure round-trips
            // through the FakeContactRepository state without a real Room transaction.
            ignoreContactUseCase = IgnoreContactUseCase(
                useCaseTx,
                contactRepo,
                recDao,
                listRepo,
                clock
            ),
            pauseContactUseCase = PauseContactUseCase(contactRepo, clock),
            undoStack = undoStack,
            widgetRefreshTrigger = WidgetRefreshTrigger { refreshes[0] += 1 },
            savedStateHandle = savedState
        )
        return Setup(vm, contactRepo, listRepo, callEventRepo, undoStack, recDao, recContactDao, refreshes)
    }

    private class Setup(
        val vm: BrowseViewModel,
        val contactRepo: FakeContactRepository,
        val listRepo: FakeListRepository,
        val callEventRepo: FakeCallEventRepository,
        val undoStack: UndoStack,
        val recDao: RecordingListMembershipDao,
        val recContactDao: RecordingContactDao,
        private val refreshes: IntArray
    ) {
        /** How many times the VM asked for a widget refresh (WIDGET-06). */
        val widgetRefreshes: Int get() = refreshes[0]

        operator fun component1() = vm
        operator fun component2() = contactRepo
        operator fun component3() = listRepo
    }

    @Test
    fun `onSingleRowUnpause clears the pause and Undo restores it`() = runTest {
        // Regression: Browse offered Pause on a paused row and nothing else, so
        // an indefinite pause could never be undone once its snackbar was gone.
        val sentinel = PauseContactUseCase.INDEFINITE_PAUSE_SENTINEL
        val s = makeVm()
        s.contactRepo.seed(
            listOf(contactFixture(id = 7L, displayName = "Kai", pausedUntil = sentinel))
        )

        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onSingleRowUnpause(7L, "Kai")
            // "Unpaused Kai" (SnackbarCopyTest pins the English).
            assertEquals(UiText.res(R.string.components_snackbar_unpaused, "Kai"), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(null, s.contactRepo.getById(7L)?.pausedUntil)
        // WIDGET-06 (wnl-6): the direct write asks the widget to follow; until
        // 2026-10-06 an unpaused person stayed off the widget until the hourly sweep.
        assertEquals(1, s.widgetRefreshes, "the unpause schedules a widget refresh")
        val undo = s.undoStack.take()
        undo!!.inverse()
        assertEquals(
            sentinel,
            s.contactRepo.getById(7L)?.pausedUntil,
            "Undo restores the exact prior pause"
        )
        assertEquals(2, s.widgetRefreshes, "and so does its Undo")
    }

    // ============================================================================
    // State contract
    // ============================================================================

    @Test
    fun `empty repo and empty query emits Empty`() = runTest {
        val (vm, _, _) = makeVm()
        vm.uiState.test(timeout = 2.seconds) {
            var item = awaitItem()
            while (item == BrowseUiState.Loading) item = awaitItem()
            assertEquals(BrowseUiState.Empty, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an unparseable list id is Error that cannot retry, never an empty list`() = runTest {
        // Regression (browse-19): a malformed route used to render "No one here
        // yet" with an "Add people" that opened the picker for a list that does
        // not exist. A path that cannot happen gets a loud guard (rules.md Code 3).
        // That Error has no feed behind it, so it says so (canRetry = false) and
        // Retry leaves it exactly as it was; until 2026-10-06 the screen offered
        // Retry as its accent and the tap changed nothing.
        val (vm, _, _) = makeVm(savedStateListId = "inner")
        vm.uiState.test(timeout = 2.seconds) {
            assertEquals(BrowseUiState.Error(canRetry = false), awaitItem())
            vm.onRetry()
            expectNoEvents()
            assertEquals(BrowseUiState.Error(canRetry = false), vm.uiState.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `listType emits the browsed list's type`() = runTest {
        val s = makeVmWithContacts()
        s.listRepo.seed(listOf(listFixture(id = 1L, name = "Late night", type = ListType.SMART)))
        s.vm.listType.test(timeout = 2.seconds) {
            while (true) {
                if (awaitItem() == ListType.SMART) break
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // BROWSE-06: honest loading and error states
    // ============================================================================

    @Test
    fun `before the feed's first snapshot the state is Loading, never Empty`() = runTest {
        // Regression: the VM started at Empty and the feed's placeholder had no
        // members, so a full list said "No one here yet" until data arrived.
        val feed = MutableStateFlow(BrowseFeedSnapshot.NotLoaded)
        val (vm, _, _) = makeVm(scriptedForList = { feed })
        vm.uiState.test(timeout = 2.seconds) {
            assertEquals(BrowseUiState.Loading, awaitItem())
            expectNoEvents()
            feed.value = BrowseFeedSnapshot(emptyList(), emptyList(), emptyList(), emptyList())
            assertEquals(BrowseUiState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed feed is Error, and Retry subscribes again`() = runTest {
        var calls = 0
        val (vm, _, _) = makeVm(
            scriptedForList = {
                calls += 1
                MutableStateFlow(
                    if (calls == 1) {
                        BrowseFeedSnapshot.Failed
                    } else {
                        BrowseFeedSnapshot(
                            memberships = listOf(membershipFixture(contactId = 1L, listId = 1L)),
                            allContacts = listOf(contactFixture(id = 1L, displayName = "Alex")),
                            callEvents = emptyList(),
                            queueOrder = emptyList()
                        )
                    }
                )
            }
        )
        vm.uiState.test(timeout = 2.seconds) {
            var item = awaitItem()
            while (item == BrowseUiState.Loading) item = awaitItem()
            assertEquals(BrowseUiState.Error(), item)
            vm.onRetry()
            assertEquals(listOf("Alex"), awaitReady(this).contacts.map { it.name })
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
                contactFixture(id = 3L, displayName = "Cam")
            )
        )
        // BrowseViewModel filters by membership join, so the
        // test must seed memberships for the same listId the SavedStateHandle
        // carries (default "1" → 1L). Without this the combine pipeline stays
        // at Empty.
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 2L, listId = 1L),
                membershipFixture(contactId = 3L, listId = 1L)
            )
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
                contactFixture(id = 3L, displayName = "Cam")
            )
        )
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 2L, listId = 1L),
                membershipFixture(contactId = 3L, listId = 1L)
            )
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
                contactFixture(id = 3L, displayName = "Cam")
            )
        )
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 2L, listId = 1L),
                membershipFixture(contactId = 3L, listId = 1L)
            )
        )
        // "Alex" is the one substring that matches one name; "A" alone matched
        // all three (Alex, b**a**iley, c**a**m).
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
    // BROWSE-02: chip × chip is a union, chip × search an intersection
    // ============================================================================

    @Test
    fun `both chips active yields the union of recently called and never called`() = runTest {
        val s = makeVmWithCallHistory()
        s.vm.onToggleFilter(BrowseFilter.CalledRecently)
        s.vm.onToggleFilter(BrowseFilter.NotCalledYet)
        s.vm.uiState.test(timeout = 2.seconds) {
            // Alex (5 days ago) and the two never called; Bailey (60 days ago)
            // matches neither chip.
            val ready = awaitReadyWhere(this) { it.activeFilters.size == 2 }
            assertEquals(listOf("Alex", "Cam", "Dana"), ready.contacts.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a chip and a query intersect`() = runTest {
        val s = makeVmWithCallHistory()
        s.vm.onToggleFilter(BrowseFilter.NotCalledYet)
        s.vm.onSearchChanged("Dana")
        s.vm.uiState.test(timeout = 2.seconds) {
            // Never called: Cam and Dana; the query keeps Dana.
            val ready = awaitReadyWhere(this) { it.searchQuery == "Dana" && it.activeFilters.isNotEmpty() }
            assertEquals(listOf("Dana"), ready.contacts.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
        // Alex matches the query but has been called, so the chip excludes him:
        // nothing matches, and the state says so about the search.
        s.vm.onSearchChanged("Alex")
        s.vm.uiState.test(timeout = 2.seconds) {
            while (true) {
                val item = awaitItem()
                if (item is BrowseUiState.NoMatches) {
                    assertEquals("Alex", item.query)
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Multi-select transition tests — load-bearing state machine
    // ============================================================================

    @Test
    fun onEnterMultiSelect_sets_isMultiSelect_true_and_seeds_selection() = runTest {
        val s = makeVmWithContacts()
        s.vm.onEnterMultiSelect(initialId = 42L)
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(true, ready.isMultiSelect)
            assertEquals(setOf(42L), ready.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the app bar's Select enters multi-select with nothing selected and stays`() = runTest {
        // Vision BROWSE-2 (browse-4): a visible way in. MOVE-06's auto-exit
        // lives in onToggleSelect only, so the empty entry does not bounce.
        val s = makeVmWithContacts()
        s.vm.onEnterMultiSelect()
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(true, ready.isMultiSelect)
            assertEquals(emptySet<Long>(), ready.selectedIds)
            expectNoEvents()
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
        s.vm.onToggleSelect(1L) // toggle off the only selected
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
    fun `select all after a search covers only the matching rows`() = runTest {
        // MOVE-05 (browse-3): the screen passes the ids of Ready.contacts, which
        // is the searched and filtered set, so a search for "a" selects Alex,
        // Bailey, Cam and Dana but a search for "Cam" selects Cam alone.
        val s = makeVmWithContacts()
        s.vm.onSearchChanged("Cam")
        s.vm.onEnterMultiSelect()
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReadyWhere(this) { it.searchQuery == "Cam" && it.isMultiSelect }
            val matching = ready.contacts.mapNotNull { it.id.removePrefix("c-").toLongOrNull() }.toSet()
            s.vm.onSelectAllMatching(matching)
            val selected = awaitReadyWhere(this) { it.selectedIds.isNotEmpty() }
            assertEquals(setOf(3L), selected.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ============================================================================
    // Bulk actions: each snackbar, each inverse (MOVE-03, MOVE-04, MOVE-07)
    // ============================================================================

    @Test
    fun onBulkRemove_emits_snackbar_event_and_exits_multiSelect() = runTest {
        val s = makeVmWithContactsAndMembership()
        s.recDao.seed(membershipFixture(contactId = 1L, listId = 1L), membershipFixture(contactId = 2L, listId = 1L))
        s.vm.onEnterMultiSelect(1L)
        s.vm.onToggleSelect(2L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkRemove()
            val event = awaitItem()
            // "Removed 2 people from Inner orbit" (strings_browse.xml).
            assertEquals(
                UiText.plural(R.plurals.browse_snackbar_removed, 2, 2, "Inner orbit"),
                event.message
            )
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancel()
        }
        // After commit, multi-select auto-exits (MOVE-06).
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(false, ready.isMultiSelect)
            cancelAndIgnoreRemainingEvents()
        }
        // The Undo snackbar's action runs the inverse, which puts both rows back.
        assertEquals(listOf(1L, 2L), s.recDao.removeCalls.single().ids)
        s.vm.onUndo()
        assertEquals(setOf(1L, 2L), s.recDao.insertCalls.single().memberships.map { it.contactId }.toSet())
        assertNull(s.undoStack.peek(), "onUndo consumed the pending entry")
    }

    @Test
    fun `a second Remove while the first is in flight does nothing`() = runTest {
        // Regression (browse-2): two quick taps on Remove launched two
        // coroutines before onExitMultiSelect cleared the selection. The second
        // found no memberships left, re-inserted nothing on undo, yet reported
        // the full count: a second snackbar, and UndoStack.put (depth 1)
        // replaced the real inverse with a no-op. The guard is a compareAndSet
        // on isCommitting before the selection is read.
        val gate = CompletableDeferred<Unit>()
        val s = makeVmWithContactsAndMembership(getByIdGate = gate)
        s.recDao.seed(membershipFixture(contactId = 1L, listId = 1L), membershipFixture(contactId = 2L, listId = 1L))
        s.vm.onEnterMultiSelect(1L)
        s.vm.onToggleSelect(2L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkRemove() // suspends on getById, with isCommitting taken
            s.vm.onBulkRemove() // returns at the guard
            gate.complete(Unit)
            assertEquals(
                UiText.plural(R.plurals.browse_snackbar_removed, 2, 2, "Inner orbit"),
                awaitItem().message
            )
            expectNoEvents()
            cancel()
        }
        assertEquals(1, s.recDao.removeCalls.size, "one write, not two")
        s.undoStack.take()!!.inverse()
        assertEquals(
            setOf(1L, 2L),
            s.recDao.insertCalls.single().memberships.map { it.contactId }.toSet(),
            "the one Undo restores both"
        )
    }

    @Test
    fun `onBulkMove to a regular list reports the move with Undo, and the inverse restores it`() = runTest {
        val s = makeVmWithContactsAndMembership(
            targetLists = listOf(
                listFixture(id = 1L, name = "Inner orbit"),
                listFixture(id = 2L, name = "Family")
            )
        )
        s.recDao.seed(membershipFixture(contactId = 1L, listId = 1L), membershipFixture(contactId = 3L, listId = 1L))
        s.vm.onEnterMultiSelect(1L)
        s.vm.onToggleSelect(3L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkMove(targetListId = 2L, targetListName = "Family")
            val event = awaitItem()
            // "Moved 2 people to Family" (strings_components.xml).
            assertEquals(UiText.plural(R.plurals.components_snackbar_moved, 2, 2, "Family"), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancel()
        }
        val move = s.recDao.moveCalls.single()
        assertEquals(1L, move.fromListId)
        assertEquals(2L, move.toListId)
        assertEquals(listOf(1L, 3L), move.ids)

        s.undoStack.take()!!.inverse()
        val removed = s.recDao.removeCalls.single()
        assertEquals(2L, removed.fromListId, "undo takes them off the target")
        assertEquals(listOf(1L, 3L), removed.ids)
        val restored = s.recDao.insertCalls.single().memberships
        assertEquals(setOf(1L, 3L), restored.map { it.contactId }.toSet(), "and puts the source rows back")
        assertTrue(restored.all { it.listId == 1L })
    }

    @Test
    fun `onBulkMove to a smart list is a failed save with no Undo`() = runTest {
        // Regression (browse-1): the sync owns a smart list's rows; a move into
        // one lost people from both lists after the Undo window.
        val s = makeVmWithContactsAndMembership(
            targetLists = listOf(
                listFixture(id = 1L, name = "Inner orbit"),
                listFixture(id = 2L, name = "Late night", type = ListType.SMART)
            )
        )
        s.recDao.seed(membershipFixture(contactId = 1L, listId = 1L))
        s.vm.onEnterMultiSelect(1L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkMove(targetListId = 2L, targetListName = "Late night")
            val event = awaitItem()
            assertEquals(UiText.res(R.string.components_snackbar_save_failed), event.message)
            assertNull(event.actionLabel, "no Undo for a write that did not happen")
            cancel()
        }
        assertTrue(s.recDao.moveCalls.isEmpty(), "nothing moved")
        assertNull(s.undoStack.peek(), "nothing to undo")
        // A failed save keeps the selection for another target, as a write
        // that throws does (until 2026-10-07 this path left multi-select).
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertTrue(ready.isMultiSelect, "the selection stays for another try")
            assertEquals(setOf(1L), ready.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onBulkMove to an archived list is a failed save with no Undo`() = runTest {
        val s = makeVmWithContactsAndMembership(
            targetLists = listOf(
                listFixture(id = 1L, name = "Inner orbit"),
                listFixture(id = 2L, name = "Old crowd", isArchived = true)
            )
        )
        s.vm.onEnterMultiSelect(1L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkMove(targetListId = 2L, targetListName = "Old crowd")
            val event = awaitItem()
            assertEquals(UiText.res(R.string.components_snackbar_save_failed), event.message)
            assertNull(event.actionLabel)
            cancel()
        }
        assertNull(s.undoStack.peek())
    }

    @Test
    fun `onBulkCopy to a regular list reports the copy with Undo, and the inverse removes only what it added`() = runTest {
        val s = makeVmWithContactsAndMembership(
            targetLists = listOf(
                listFixture(id = 1L, name = "Inner orbit"),
                listFixture(id = 2L, name = "Family")
            )
        )
        // 2 is already on Family; the copy adds 1 only, and Undo removes 1 only.
        s.recDao.seed(membershipFixture(contactId = 2L, listId = 2L))
        s.vm.onEnterMultiSelect(1L)
        s.vm.onToggleSelect(2L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkCopy(targetListId = 2L, targetListName = "Family")
            val event = awaitItem()
            // "Copied 2 people to Family": the full count, as the use case reports.
            assertEquals(UiText.plural(R.plurals.components_snackbar_copied, 2, 2, "Family"), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancel()
        }
        val inserted = s.recDao.insertCalls.single().memberships
        assertEquals(listOf(1L), inserted.map { it.contactId })
        assertTrue(inserted.all { it.listId == 2L })

        s.undoStack.take()!!.inverse()
        val removed = s.recDao.removeCalls.single()
        assertEquals(2L, removed.fromListId)
        assertEquals(listOf(1L), removed.ids, "the pre-existing membership stays")
    }

    @Test
    fun `onBulkCopy to a smart list is a failed save with no Undo`() = runTest {
        val s = makeVmWithContactsAndMembership(
            targetLists = listOf(
                listFixture(id = 1L, name = "Inner orbit"),
                listFixture(id = 2L, name = "Late night", type = ListType.SMART)
            )
        )
        s.vm.onEnterMultiSelect(1L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkCopy(targetListId = 2L, targetListName = "Late night")
            val event = awaitItem()
            assertEquals(UiText.res(R.string.components_snackbar_save_failed), event.message)
            assertNull(event.actionLabel)
            cancel()
        }
        assertTrue(s.recDao.insertCalls.isEmpty(), "nothing copied")
        assertNull(s.undoStack.peek())
    }

    @Test
    fun `onBulkIgnore reports the count and the inverse restores each prior flag`() = runTest {
        // 2 was already ignored before the batch; Undo must leave it ignored.
        val s = makeVmWithContactsAndMembership(
            ignoredSnapshots = listOf(IgnoredSnapshot(1L, false), IgnoredSnapshot(2L, true))
        )
        s.vm.onEnterMultiSelect(1L)
        s.vm.onToggleSelect(2L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkIgnore()
            val event = awaitItem()
            // "Ignored 2 people" (strings_browse.xml).
            assertEquals(UiText.plural(R.plurals.browse_snackbar_ignored, 2, 2), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancel()
        }
        val forward = s.recContactDao.setIgnoredCalls.single()
        assertEquals(setOf(1L, 2L), forward.ids.toSet())
        assertEquals(true, forward.ignored)

        s.recContactDao.setIgnoredCalls.clear()
        s.undoStack.take()!!.inverse()
        val restored = s.recContactDao.setIgnoredCalls.associate { it.ignored to it.ids.toSet() }
        assertEquals(mapOf(false to setOf(1L), true to setOf(2L)), restored)
    }

    @Test
    fun `onBulkPause words the duration and the inverse restores each prior pause`() = runTest {
        val priorOfTwo = Instant.parse("2026-03-01T00:00:00Z")
        val s = makeVmWithContactsAndMembership(
            pausedSnapshots = listOf(PausedUntilSnapshot(1L, null), PausedUntilSnapshot(2L, priorOfTwo))
        )
        s.vm.onEnterMultiSelect(1L)
        s.vm.onToggleSelect(2L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkPause(PauseDuration.OneMonth)
            val event = awaitItem()
            // "Paused 2 people for 1 month" (strings_components.xml, PauseTextTest).
            assertEquals(pausedPeopleSnackbar(2, PauseDuration.OneMonth), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancel()
        }
        val forward = s.recContactDao.setPausedUntilCalls.single()
        assertEquals(setOf(1L, 2L), forward.ids.toSet())

        s.recContactDao.setPausedUntilCalls.clear()
        s.undoStack.take()!!.inverse()
        val restored = s.recContactDao.setPausedUntilCalls.associate { it.until to it.ids.toSet() }
        assertEquals(mapOf(null to setOf(1L), priorOfTwo to setOf(2L)), restored)
    }

    // ============================================================================
    // A write that throws (rules.md Code 3). Until 2026-10-06 the bulk handlers
    // were try/finally with no catch and the single-row ones bare launches; with
    // no CoroutineExceptionHandler in the app the exception took the process
    // down, and either way nothing told the user. Now the failure is a
    // "Couldn't save your change" snackbar with no Undo, the bar is freed, and
    // the selection stays for another try.
    // ============================================================================

    @Test
    fun `a bulk write that throws says Couldn't save, has no Undo and frees the bar`() = runTest {
        val s = makeVmWithContactsAndMembership(useCaseTx = failingTx)
        s.vm.onEnterMultiSelect(1L)
        s.vm.onToggleSelect(2L)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onBulkIgnore()
            val event = awaitItem()
            assertEquals(UiText.res(R.string.components_snackbar_save_failed), event.message)
            assertNull(event.actionLabel, "no Undo for a write that did not happen")
            expectNoEvents()
            cancel()
        }
        assertNull(s.undoStack.peek(), "nothing to undo")
        assertTrue(s.recContactDao.setIgnoredCalls.isEmpty(), "nothing written")
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertFalse(ready.isCommitting, "the bar is enabled again")
            assertTrue(ready.isMultiSelect, "the selection stays for another try")
            assertEquals(setOf(1L, 2L), ready.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a single-row write that throws says Couldn't save and offers no Undo`() = runTest {
        val s = makeVmWithContacts(useCaseTx = failingTx)
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onSingleRowIgnore(1L, "Alex")
            val event = awaitItem()
            assertEquals(UiText.res(R.string.components_snackbar_save_failed), event.message)
            assertNull(event.actionLabel, "no Undo for a write that did not happen")
            expectNoEvents()
            cancel()
        }
        assertNull(s.undoStack.peek(), "nothing to undo")
        assertEquals(false, s.contactRepo.getById(1L)?.isIgnored, "Alex is not ignored")
    }

    // ============================================================================
    // Single-row quick actions (BROWSE-04)
    // ============================================================================

    @Test
    fun `onSingleRowIgnore says who, and Undo brings them back`() = runTest {
        val s = makeVmWithContacts()
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onSingleRowIgnore(1L, "Alex")
            val event = awaitItem()
            // "Ignored Alex" (SnackbarCopyTest pins the English).
            assertEquals(UiText.res(R.string.components_snackbar_ignored, "Alex"), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancel()
        }
        assertEquals(true, s.contactRepo.getById(1L)?.isIgnored)
        s.vm.onUndo()
        assertEquals(false, s.contactRepo.getById(1L)?.isIgnored)
    }

    @Test
    fun `onSingleRowPause words the duration, and Undo restores the prior pause`() = runTest {
        val s = makeVmWithContacts()
        val now = Instant.parse("2026-01-01T12:00:00Z") // TestClock's default
        s.vm.snackbarEvents.test(timeout = 2.seconds) {
            s.vm.onSingleRowPause(1L, "Alex", PauseDuration.OneWeek)
            val event = awaitItem()
            // "Paused Alex for 1 week" (strings_components.xml, PauseTextTest).
            assertEquals(pausedSnackbar("Alex", PauseDuration.OneWeek), event.message)
            assertEquals(UiText.res(R.string.components_action_undo), event.actionLabel)
            cancel()
        }
        assertEquals(now.plus(Duration.ofDays(7)), s.contactRepo.getById(1L)?.pausedUntil)
        // The use case fires the widget refresh for the forward write (its own
        // test); the VM fires it for the Undo, which is a direct write (wnl-6).
        assertEquals(0, s.widgetRefreshes)
        s.vm.onUndo()
        assertNull(s.contactRepo.getById(1L)?.pausedUntil, "Undo clears the pause that was not there before")
        assertEquals(1, s.widgetRefreshes, "the Undo's direct write schedules a widget refresh")
    }

    // ============================================================================
    // Orientation + honest states + the shared ContactSearch matcher.
    // ============================================================================

    @Test
    fun `listName emits the real list name`() = runTest {
        val s = makeVmWithContactsAndMembership() // seeds list id=1 "Inner orbit"
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
                contactFixture(id = 4L, displayName = "Dana", isIgnored = true)
            )
        )
        listRepo.seedMemberships(
            listOf(
                // Past nextDueAt → due; future → not due. Paused/ignored rows
                // carry a past nextDueAt too but must NOT read as due.
                membershipFixture(
                    contactId = 1L,
                    listId = 1L,
                    nextDueAt = now.minusSeconds(3_600)
                ),
                membershipFixture(
                    contactId = 2L,
                    listId = 1L,
                    nextDueAt = now.plusSeconds(3_600)
                ),
                membershipFixture(
                    contactId = 3L,
                    listId = 1L,
                    nextDueAt = now.minusSeconds(3_600)
                ),
                membershipFixture(
                    contactId = 4L,
                    listId = 1L,
                    nextDueAt = now.minusSeconds(3_600)
                )
            )
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
        val s = makeVmWithContacts() // zero call events → nobody "called recently"
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
                contactFixture(id = 2L, displayName = "Bailey")
            )
        )
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 2L, listId = 1L)
            )
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
                contactFixture(id = 2L, displayName = "Bailey")
            )
        )
        listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 2L, listId = 1L)
            )
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

    private fun makeVmWithContacts(
        targetLists: List<ListEntity> = emptyList(),
        ignoredSnapshots: List<IgnoredSnapshot> = emptyList(),
        pausedSnapshots: List<PausedUntilSnapshot> = emptyList(),
        getByIdGate: CompletableDeferred<Unit>? = null,
        useCaseTx: TransactionRunner = passThruTx
    ): Setup {
        val s = makeVm(
            savedStateListId = "1",
            targetLists = targetLists,
            ignoredSnapshots = ignoredSnapshots,
            pausedSnapshots = pausedSnapshots,
            getByIdGate = getByIdGate,
            useCaseTx = useCaseTx
        )
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 1L, displayName = "Alex"),
                contactFixture(id = 2L, displayName = "Bailey"),
                contactFixture(id = 3L, displayName = "Cam"),
                contactFixture(id = 4L, displayName = "Dana")
            )
        )
        s.listRepo.seedMemberships(
            listOf(
                membershipFixture(contactId = 1L, listId = 1L),
                membershipFixture(contactId = 2L, listId = 1L),
                membershipFixture(contactId = 3L, listId = 1L),
                membershipFixture(contactId = 4L, listId = 1L)
            )
        )
        return s
    }

    private fun makeVmWithContactsAndMembership(
        targetLists: List<ListEntity> = emptyList(),
        ignoredSnapshots: List<IgnoredSnapshot> = emptyList(),
        pausedSnapshots: List<PausedUntilSnapshot> = emptyList(),
        getByIdGate: CompletableDeferred<Unit>? = null,
        useCaseTx: TransactionRunner = passThruTx
    ): Setup {
        val s = makeVmWithContacts(
            targetLists, ignoredSnapshots, pausedSnapshots, getByIdGate, useCaseTx
        )
        s.listRepo.seed(
            listOf(
                listFixture(id = 1L, name = "Inner orbit")
            )
        )
        return s
    }

    /**
     * The four people with a call history for the chip tests: Alex called 5
     * days ago (recent), Bailey 60 days ago (neither chip), Cam and Dana never.
     */
    private fun makeVmWithCallHistory(): Setup {
        val s = makeVmWithContacts()
        val now = Instant.parse("2026-01-01T12:00:00Z") // TestClock's default
        s.callEventRepo.seed(
            listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = now.minus(Duration.ofDays(5))),
                callEventFixture(id = 2L, contactId = 2L, occurredAt = now.minus(Duration.ofDays(60)))
            )
        )
        return s
    }

    /** Skips Loading and returns the first Ready emission. */
    private suspend fun awaitReady(
        flow: app.cash.turbine.ReceiveTurbine<BrowseUiState>
    ): BrowseUiState.Ready {
        while (true) {
            val item = flow.awaitItem()
            if (item is BrowseUiState.Ready) return item
        }
    }

    /** Drains intermediate Ready emissions until [predicate] holds. */
    private suspend fun awaitReadyWhere(
        flow: app.cash.turbine.ReceiveTurbine<BrowseUiState>,
        predicate: (BrowseUiState.Ready) -> Boolean
    ): BrowseUiState.Ready {
        while (true) {
            val item = flow.awaitItem()
            if (item is BrowseUiState.Ready && predicate(item)) return item
        }
    }
}
