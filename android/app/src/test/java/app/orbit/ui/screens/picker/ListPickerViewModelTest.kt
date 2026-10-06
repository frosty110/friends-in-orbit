package app.orbit.ui.screens.picker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import app.orbit.R
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.contactFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.undo.UndoStack
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.util.UiText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Behavioral tests for [ListPickerViewModel] focused on the F-13 (contactId
 * "c-" prefix parsing) and F-14 (membership flag) hot-fixes, plus the
 * picker-commit lifecycle (commit survives the pop's viewModelScope
 * cancellation; outcome + Undo surface on [PickerCommitBus]).
 *
 * Mirrors [BrowseViewModelTest]: MainDispatcherRule + UnconfinedTestDispatcher,
 * fakes from `FakeRepositories.kt`, and `app.cash.turbine.test` to assert the
 * terminal observable state per ARCH-02.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListPickerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun fixture(
        contactIdArg: String? = "c-12",
        membershipDao: RecordingListMembershipDao = RecordingListMembershipDao(),
        appScope: CoroutineScope? = null,
    ): Setup {
        val contactRepo = FakeContactRepository()
        val listRepo = FakeListRepository()
        val undoStack = UndoStack()
        val commitBus = PickerCommitBus()
        val clock = TestClock()
        // Fixture repair — ctor widened with RuleTemplateRepository
        // for the inline-create path.
        val templateRepo = FakeRuleTemplateRepository()
        val savedState = SavedStateHandle(
            buildMap<String, Any?> {
                if (contactIdArg != null) put("contactId", contactIdArg)
            },
        )
        val vm = ListPickerViewModel(
            listRepo = listRepo,
            contactRepo = contactRepo,
            listMembershipDao = membershipDao,
            undoStack = undoStack,
            clock = clock,
            commitBus = commitBus,
            ruleTemplateRepo = templateRepo,
            // Commits run on the app scope, not viewModelScope.
            // Default: the rule's Unconfined dispatcher so commits complete
            // synchronously; the cancellation test passes a Standard-dispatched
            // scope to control timing.
            appScope = appScope
                ?: CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher),
            savedStateHandle = savedState,
        )
        return Setup(vm, contactRepo, listRepo, membershipDao, undoStack, commitBus, templateRepo)
    }

    private data class Setup(
        val vm: ListPickerViewModel,
        val contactRepo: FakeContactRepository,
        val listRepo: FakeListRepository,
        val membershipDao: RecordingListMembershipDao,
        val undoStack: UndoStack,
        val commitBus: PickerCommitBus,
        val templateRepo: FakeRuleTemplateRepository,
    )

    private fun seedReadyFor(setup: Setup, contactId: Long = 12L) {
        setup.contactRepo.seed(listOf(contactFixture(id = contactId, displayName = "Sarah")))
        setup.listRepo.seed(
            listOf(
                listFixture(id = 1L, name = "Inner orbit"),
                listFixture(id = 2L, name = "Late night"),
            ),
        )
    }

    private suspend fun awaitReady(
        flow: app.cash.turbine.ReceiveTurbine<ListPickerUiState>,
    ): ListPickerUiState {
        while (true) {
            val item = flow.awaitItem()
            if (item.phase == ListPickerUiState.Phase.Ready) return item
        }
    }

    // ─── F-13: contactId parsing ───────────────────────────────────────────

    @Test
    fun `contactId with c-prefix parses to long and exits NotFound`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals("Sarah", ready.contactName)
            assertEquals(2, ready.lists.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `contactId without prefix also parses defensively`() = runTest {
        val s = fixture(contactIdArg = "12")
        seedReadyFor(s, contactId = 12L)
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals("Sarah", ready.contactName)
            assertEquals(2, ready.lists.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `null contactId emits terminal NotFound with empty lists`() = runTest {
        val s = fixture(contactIdArg = null)
        // Even with seeded data, the NotFound branch never starts the combine.
        seedReadyFor(s, contactId = 12L)
        s.vm.uiState.test(timeout = 2.seconds) {
            val state = awaitItem()
            assertEquals(ListPickerUiState.Phase.NotFound, state.phase)
            assertEquals("", state.contactName)
            assertTrue(state.lists.isEmpty())
            assertTrue(state.selectedListIds.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `malformed contactId emits NotFound`() = runTest {
        val s = fixture(contactIdArg = "c-not-a-number")
        seedReadyFor(s, contactId = 12L)
        s.vm.uiState.test(timeout = 2.seconds) {
            val state = awaitItem()
            assertEquals(ListPickerUiState.Phase.NotFound, state.phase)
            assertTrue(state.lists.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Archived list filter ──────────────────────────────────────────────

    @Test
    fun `archived lists are filtered out`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        s.listRepo.seed(
            listOf(
                listFixture(id = 1L, name = "Inner orbit", isArchived = false),
                listFixture(id = 2L, name = "Old crew", isArchived = true),
            ),
        )
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(1, ready.lists.size)
            assertEquals(1L, ready.lists[0].listId)
            assertEquals("Inner orbit", ready.lists[0].name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── F-14: membership flag ─────────────────────────────────────────────

    @Test
    fun `isMember flag reflects existing memberships for the contact`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.listRepo.seedMemberships(
            listOf(membershipFixture(contactId = 12L, listId = 1L)),
        )
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            val byId = ready.lists.associateBy { it.listId }
            assertTrue("list 1 should be a member", byId.getValue(1L).isMember)
            assertFalse("list 2 should not be a member", byId.getValue(2L).isMember)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Selection toggle ──────────────────────────────────────────────────

    @Test
    fun `onToggleListSelect adds and removes ids and updates canCommit`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)

        s.vm.onToggleListSelect(1L)
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(setOf(1L), ready.selectedListIds)
            assertTrue("canCommit true with at least one selection", ready.canCommit)
            cancelAndIgnoreRemainingEvents()
        }

        s.vm.onToggleListSelect(2L)
        s.vm.uiState.test(timeout = 2.seconds) {
            while (true) {
                val ready = awaitReady(this)
                if (ready.selectedListIds == setOf(1L, 2L)) {
                    assertEquals(2, ready.selectionCount)
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }

        s.vm.onToggleListSelect(1L)
        s.vm.uiState.test(timeout = 2.seconds) {
            while (true) {
                val ready = awaitReady(this)
                if (ready.selectedListIds == setOf(2L)) break
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canCommit is false with empty selection and true with at least one`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)

        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertFalse("no selection -> canCommit false", ready.canCommit)
            cancelAndIgnoreRemainingEvents()
        }

        s.vm.onToggleListSelect(1L)
        s.vm.uiState.test(timeout = 2.seconds) {
            while (true) {
                val ready = awaitReady(this)
                if (ready.selectedListIds.isNotEmpty()) {
                    assertTrue("one selection -> canCommit true", ready.canCommit)
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onClearSelection empties the selection`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.vm.onToggleListSelect(1L)
        s.vm.onToggleListSelect(2L)
        s.vm.onClearSelection()

        s.vm.uiState.test(timeout = 2.seconds) {
            while (true) {
                val ready = awaitReady(this)
                if (ready.selectedListIds.isEmpty()) {
                    assertFalse("cleared selection -> canCommit false", ready.canCommit)
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selection persists into SavedStateHandle for process-death restore`() = runTest {
        val savedState = SavedStateHandle(mapOf("contactId" to "c-12"))
        val vm = ListPickerViewModel(
            listRepo = FakeListRepository(),
            contactRepo = FakeContactRepository(),
            listMembershipDao = RecordingListMembershipDao(),
            undoStack = UndoStack(),
            clock = TestClock(),
            commitBus = PickerCommitBus(),
            ruleTemplateRepo = FakeRuleTemplateRepository(),
            appScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher),
            savedStateHandle = savedState,
        )
        vm.onToggleListSelect(1L)
        vm.onToggleListSelect(2L)

        assertEquals(
            "the init collector mirrors the selection into the bundle",
            setOf(1L, 2L),
            savedState.get<LongArray>("selectedListIds")?.toSet().orEmpty(),
        )
    }

    // ─── Inline create (dead-end fix) ──────────────────────────────────────

    @Test
    fun `onCreateList creates a keep-in-touch list and selects it`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.templateRepo.seed(listOf(ruleTemplateFixture(id = 1L, kind = RuleKind.KEEP_IN_TOUCH)))

        s.vm.onCreateList("  Night owls  ")

        val created = s.listRepo.createCalls.single()
        assertEquals("Night owls", created.name)
        assertEquals(1L, created.ruleTemplateId)
        assertEquals("appends after the two seeded lists", 3, created.sortOrder)

        s.vm.uiState.test(timeout = 2.seconds) {
            while (true) {
                val ready = awaitReady(this)
                if (ready.lists.size == 3) {
                    val newRow = ready.lists.first { it.name == "Night owls" }
                    assertTrue(
                        "new list must come back selected so the next tap is the commit CTA",
                        newRow.listId in ready.selectedListIds,
                    )
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onCreateList with blank name is a no-op`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.vm.onCreateList("   ")
        assertTrue("blank name must not create a list", s.listRepo.createCalls.isEmpty())
    }

    @Test
    fun `onCreateList failure surfaces on the commit bus`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        // FakeListRepository is final — interface delegation gives us a
        // create-only failure hook without touching the shared fake.
        val throwingListRepo = object : app.orbit.data.repository.ListRepository by s.listRepo {
            override suspend fun create(list: app.orbit.data.entity.ListEntity): Long {
                throw IllegalStateException("simulated cipher write failure")
            }
        }
        val commitBus = PickerCommitBus()
        val vm = ListPickerViewModel(
            listRepo = throwingListRepo,
            contactRepo = s.contactRepo,
            listMembershipDao = s.membershipDao,
            undoStack = s.undoStack,
            clock = TestClock(),
            commitBus = commitBus,
            ruleTemplateRepo = s.templateRepo,
            appScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher),
            savedStateHandle = SavedStateHandle(mapOf("contactId" to "c-12")),
        )
        commitBus.events.test {
            vm.onCreateList("Night owls")
            assertEquals(UiText.res(R.string.picker_snackbar_create_list_failed), awaitItem().message)
        }
        assertTrue(s.listRepo.createCalls.isEmpty())
    }

    @Test
    fun `a failing source shows Error, and Retry recovers`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        var failing = true
        val flakyListRepo = object : app.orbit.data.repository.ListRepository by s.listRepo {
            override fun observeAll(): Flow<List<app.orbit.data.entity.ListEntity>> = flow {
                if (failing) throw java.io.IOException("simulated read failure")
                emitAll(s.listRepo.observeAll())
            }
        }
        val vm = ListPickerViewModel(
            listRepo = flakyListRepo,
            contactRepo = s.contactRepo,
            listMembershipDao = s.membershipDao,
            undoStack = s.undoStack,
            clock = TestClock(),
            commitBus = PickerCommitBus(),
            ruleTemplateRepo = s.templateRepo,
            appScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher),
            savedStateHandle = SavedStateHandle(mapOf("contactId" to "c-12")),
        )
        vm.uiState.test {
            var item = awaitItem()
            while (item.phase == ListPickerUiState.Phase.Loading) item = awaitItem()
            assertEquals(ListPickerUiState.Phase.Error, item.phase)
            failing = false
            vm.onRetry()
            var next = awaitItem()
            while (next.phase != ListPickerUiState.Phase.Ready) next = awaitItem()
            assertTrue(next.lists.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Picker-commit lifecycle ────────────────────────────────────────────

    /**
     * Gated DAO — `insertAll` parks at [gate] so the test can cancel the VM's
     * scope mid-write before releasing the insert (simulating the
     * `popBackStack()` that previously raced the commit).
     */
    private class GatedListMembershipDao : RecordingListMembershipDao() {
        val gate = CompletableDeferred<Unit>()
        override suspend fun insertAll(memberships: List<ListMembershipEntity>) {
            gate.await()
            super.insertAll(memberships)
        }
    }

    @Test
    fun `commit write completes even though viewModelScope is cancelled mid-write`() = runTest {
        val dao = GatedListMembershipDao()
        // Standard dispatcher on the shared scheduler so the commit coroutine
        // only advances when the test says so.
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val s = fixture(contactIdArg = "c-12", membershipDao = dao, appScope = appScope)
        seedReadyFor(s, contactId = 12L)
        s.vm.onToggleListSelect(1L)

        s.commitBus.events.test {
            s.vm.onCommit()
            testScheduler.runCurrent() // commit coroutine starts, parks at the gate
            assertTrue("write must not have landed yet", dao.insertCalls.isEmpty())

            // The pop: navigation clears the VM, cancelling viewModelScope.
            s.vm.viewModelScope.cancel()
            dao.gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            // Write landed and the result reached the app-level bus anyway.
            assertEquals(addedToLists(1), awaitItem().message)
        }
        assertEquals(1, dao.insertCalls.size)
        val inserted = dao.insertCalls[0].memberships
        assertEquals(listOf(1L), inserted.map { it.listId })
        assertEquals(listOf(12L), inserted.map { it.contactId })
    }

    @Test
    fun `commit publishes outcome with undo action on the app-level bus`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.vm.onToggleListSelect(1L)
        s.vm.onToggleListSelect(2L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals(addedToLists(2), event.message)
            assertEquals(UNDO, event.actionLabel)
        }
        assertEquals(1, s.membershipDao.insertCalls.size)
    }

    @Test
    fun `single-list commit uses the singular Added to 1 list copy`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.vm.onToggleListSelect(1L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals(addedToLists(1), event.message)
            assertEquals(UNDO, event.actionLabel)
        }
        val inserted = s.membershipDao.insertCalls.single().memberships
        assertEquals(listOf(1L), inserted.map { it.listId })
        assertEquals(listOf(12L), inserted.map { it.contactId })
    }

    @Test
    fun `empty-selection commit is a no-op`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)

        s.commitBus.events.test {
            s.vm.onCommit()
            expectNoEvents()
        }
        assertTrue("no insert without a selection", s.membershipDao.insertCalls.isEmpty())
        assertNull("no undo for an empty commit", s.undoStack.peek())
    }

    @Test
    fun `commit failure surfaces couldn't save instead of throwing`() = runTest {
        val dao = object : RecordingListMembershipDao() {
            override suspend fun insertAll(memberships: List<ListMembershipEntity>) {
                throw IllegalStateException("simulated cipher write failure")
            }
        }
        val s = fixture(contactIdArg = "c-12", membershipDao = dao)
        seedReadyFor(s, contactId = 12L)
        s.vm.onToggleListSelect(1L)

        // Unconfined app scope: an uncaught throw inside onCommit would fail
        // this test — the assertion below proves it surfaced as an event.
        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals(UiText.res(R.string.picker_snackbar_save_failed), event.message)
            assertNull("failure toast carries no action", event.actionLabel)
        }
        assertNull("failed commit must not record an undo", s.undoStack.peek())
    }

    @Test
    fun `undo removes the just-added memberships`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.vm.onToggleListSelect(1L)
        s.vm.onToggleListSelect(2L)
        s.vm.onCommit()
        assertEquals(1, s.membershipDao.insertCalls.size)

        // The app-level host takes the depth-1 inverse and replays it.
        val pending = s.undoStack.take()
        assertNotNull("commit must record a depth-1 undo", pending)
        pending?.inverse?.invoke()

        assertEquals(
            setOf(1L to listOf(12L), 2L to listOf(12L)),
            s.membershipDao.removeCalls.map { it.fromListId to it.ids }.toSet(),
        )
    }

    // ─── Lists the person is already on (G1: no lost work) ─────────────────
    //
    // Regression: a member list could be selected (its row had no `enabled`
    // gate, and a restored selection can carry one), the DAO's IGNORE made the
    // insert a no-op, the snackbar still said "Added to 2 lists", and Undo's
    // removeAll deleted the membership the person already had.

    @Test
    fun `a list the person is already on is not inserted and the count excludes it`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.listRepo.seedMemberships(listOf(membershipFixture(contactId = 12L, listId = 1L)))
        // Nothing collects uiState here, so the toggle guard cannot see the
        // rows: this is the SavedStateHandle-restore path, where the commit
        // itself has to drop the member list.
        s.vm.onToggleListSelect(1L)
        s.vm.onToggleListSelect(2L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals("the count is what was written, not what was ticked", addedToLists(1), event.message)
            assertEquals(UNDO, event.actionLabel)
        }
        val inserted = s.membershipDao.insertCalls.single().memberships
        assertEquals(listOf(2L), inserted.map { it.listId })
    }

    @Test
    fun `undo after a mixed selection never removes the pre-existing membership`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.listRepo.seedMemberships(listOf(membershipFixture(contactId = 12L, listId = 1L)))
        s.vm.onToggleListSelect(1L)
        s.vm.onToggleListSelect(2L)
        s.vm.onCommit()

        val pending = s.undoStack.take()
        assertNotNull("commit must record a depth-1 undo", pending)
        pending?.inverse?.invoke()

        assertEquals(
            "Undo removes only the row this commit inserted",
            listOf(2L to listOf(12L)),
            s.membershipDao.removeCalls.map { it.fromListId to it.ids },
        )
    }

    @Test
    fun `selecting only lists the person is already on writes nothing and says so`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.listRepo.seedMemberships(listOf(membershipFixture(contactId = 12L, listId = 1L)))
        s.vm.onToggleListSelect(1L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals(UiText.res(R.string.picker_snackbar_save_failed), event.message)
            assertNull("nothing to undo when nothing was written", event.actionLabel)
        }
        assertTrue("no insert for a member-only selection", s.membershipDao.insertCalls.isEmpty())
        assertNull(s.undoStack.peek())
    }

    @Test
    fun `a member list cannot be selected once the rows are known`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        seedReadyFor(s, contactId = 12L)
        s.listRepo.seedMemberships(listOf(membershipFixture(contactId = 12L, listId = 1L)))

        s.vm.uiState.test(timeout = 2.seconds) {
            awaitReady(this)
            s.vm.onToggleListSelect(1L)
            s.vm.onToggleListSelect(2L)
            while (true) {
                val ready = awaitReady(this)
                if (ready.selectedListIds.isNotEmpty()) {
                    assertEquals("the member list is refused, the other is taken", setOf(2L), ready.selectedListIds)
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a restored selection drops lists the person is already on`() = runTest {
        val savedState = SavedStateHandle(
            mapOf("contactId" to "c-12", "selectedListIds" to longArrayOf(1L, 2L)),
        )
        val listRepo = FakeListRepository()
        val contactRepo = FakeContactRepository()
        contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        listRepo.seed(listOf(listFixture(id = 1L, name = "Inner orbit"), listFixture(id = 2L, name = "Late night")))
        listRepo.seedMemberships(listOf(membershipFixture(contactId = 12L, listId = 1L)))
        val vm = ListPickerViewModel(
            listRepo = listRepo,
            contactRepo = contactRepo,
            listMembershipDao = RecordingListMembershipDao(),
            undoStack = UndoStack(),
            clock = TestClock(),
            commitBus = PickerCommitBus(),
            ruleTemplateRepo = FakeRuleTemplateRepository(),
            appScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher),
            savedStateHandle = savedState,
        )
        vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(setOf(2L), ready.selectedListIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Smart lists are not user-curated ─────────────────────────────────
    //
    // SmartListMembershipSync rewrites a smart list's rows from its rule, so
    // a membership added by hand is removed on the next reconcile with no
    // message. ListRow hides "+" for smart lists; this picker hides them from
    // its rows and refuses one at commit (rules.md Code 3: a loud guard).

    @Test
    fun `smart lists are not offered as rows`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        s.listRepo.seed(
            listOf(
                listFixture(id = 1L, name = "Inner orbit"),
                listFixture(id = 2L, name = "Recently added", type = ListType.SMART),
            ),
        )
        s.vm.uiState.test(timeout = 2.seconds) {
            val ready = awaitReady(this)
            assertEquals(listOf(1L), ready.lists.map { it.listId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a smart list in the selection is refused at commit`() = runTest {
        val s = fixture(contactIdArg = "c-12")
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        s.listRepo.seed(listOf(listFixture(id = 2L, name = "Recently added", type = ListType.SMART)))
        s.vm.onToggleListSelect(2L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals(UiText.res(R.string.picker_snackbar_save_failed), event.message)
            assertNull(event.actionLabel)
        }
        assertTrue("no insert into a smart list", s.membershipDao.insertCalls.isEmpty())
        assertNull(s.undoStack.peek())
    }

    // ─── A well-formed id whose row is gone ────────────────────────────────

    @Test
    fun `a well-formed id with no row is NotFound, not a nameless picker`() = runTest {
        val s = fixture(contactIdArg = "c-99")
        s.listRepo.seed(listOf(listFixture(id = 1L, name = "Inner orbit")))
        // Contact 99 is never seeded: Room emits null for a missing row.
        s.vm.uiState.test(timeout = 2.seconds) {
            var item = awaitItem()
            while (item.phase == ListPickerUiState.Phase.Loading) item = awaitItem()
            assertEquals(ListPickerUiState.Phase.NotFound, item.phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Snackbar copy is UiText; SnackbarCopyTest pins its English ("Added to 1
    // list", "Added to 2 lists"), so these compare which resource was meant.
    private fun addedToLists(n: Int): UiText = UiText.plural(R.plurals.picker_snackbar_added_to_lists, n, n)

    private companion object {
        val UNDO: UiText = UiText.res(R.string.components_action_undo)
    }
}
