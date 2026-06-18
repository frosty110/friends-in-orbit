package app.orbit.ui.screens.picker

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.AppPrefs
import app.orbit.data.android.ContactsReader
import app.orbit.data.android.PhoneContact
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.dao.TestListDaoStub
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.CopyContactsUseCase
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.MoveContactsUseCase
import app.orbit.domain.usecase.UnignoreContactUseCase
import app.orbit.testutil.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Behavioral tests for the picker's ignore/unignore flows.
 *
 * Robolectric supplies the `@ApplicationContext Context` the VM reads
 * READ_CONTACTS from (granted via shadow so init lands in Ready). Fakes from
 * `FakeRepositories.kt` + a pass-through [TransactionRunner] make the use-case
 * writes synchronous on the rule's Unconfined dispatcher.
 *
 * Deliberately does NOT collect [ContactPickerViewModel.uiState] here: the
 * pipeline hops through real `Dispatchers.Default`/`IO` (flowOn), which races
 * runTest's virtual clock. List-removal semantics (ignored rows leave
 * `filteredContacts`) are covered as pure state in [ContactPickerUiStateTest];
 * these tests pin the write, the bus event, the undo entry, and the
 * selection-drop — the VM's side of the contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ContactPickerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Pass-through TransactionRunner — runs the block directly on the calling coroutine. */
    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    /** Device address book stub — the ignore flows never touch the provider. */
    private class FakeContactsReader(context: Context) : ContactsReader(context) {
        override suspend fun readAll(): List<PhoneContact> = emptyList()
    }

    private data class Setup(
        val vm: ContactPickerViewModel,
        val contactRepo: FakeContactRepository,
        val membershipDao: RecordingListMembershipDao,
        val undoStack: UndoStack,
        val commitBus: PickerCommitBus,
        val savedState: SavedStateHandle,
    )

    private fun fixture(
        membershipDao: RecordingListMembershipDao = RecordingListMembershipDao(),
        mode: String = "add",
        sourceListId: String? = null,
    ): Setup {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)

        val contactRepo = FakeContactRepository()
        val listRepo = FakeListRepository()
        val lists = listOf(
            listFixture(id = 1L, name = "Inner orbit"),
            listFixture(id = 2L, name = "Late night"),
        )
        listRepo.seed(lists)
        val listDao = TestListDaoStub(lists = lists)
        val clock = TestClock()
        val undoStack = UndoStack()
        val commitBus = PickerCommitBus()
        val savedStateArgs = buildMap<String, Any?> {
            put("targetListId", "1")
            put("mode", mode)
            if (sourceListId != null) put("sourceListId", sourceListId)
        }
        val savedState = SavedStateHandle(savedStateArgs)

        val vm = ContactPickerViewModel(
            appContext = app,
            contactRepo = contactRepo,
            listRepo = listRepo,
            listMembershipDao = membershipDao,
            callEventRepo = FakeCallEventRepository(),
            appPrefs = AppPrefs(app),
            moveUseCase = MoveContactsUseCase(passThruTx, membershipDao, listDao, listRepo, clock),
            copyUseCase = CopyContactsUseCase(passThruTx, membershipDao, listDao, listRepo, clock),
            ignoreUseCase = IgnoreContactUseCase(passThruTx, contactRepo, membershipDao, listRepo, clock),
            unignoreUseCase = UnignoreContactUseCase(
                passThruTx, contactRepo, listDao, membershipDao, listRepo, clock,
            ),
            undoStack = undoStack,
            contactsReader = FakeContactsReader(app),
            clock = clock,
            commitBus = commitBus,
            appScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher),
            savedStateHandle = savedState,
        )
        return Setup(vm, contactRepo, membershipDao, undoStack, commitBus, savedState)
    }

    private fun selectedIdsIn(savedState: SavedStateHandle): Set<Long> =
        savedState.get<LongArray>("selectedIds")?.toSet().orEmpty()

    // ─── Ignore flow ─────────────────────────────────────────

    @Test
    fun `onIgnore writes the flip, publishes Ignored with Undo, drops selection`() = runTest {
        val s = fixture()
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        s.vm.onToggleSelect(12L)
        assertTrue(12L in selectedIdsIn(s.savedState))

        s.commitBus.events.test {
            s.vm.onIgnore(12L, "Sarah")
            val event = awaitItem()
            assertEquals("Ignored Sarah", event.message)
            assertEquals("Undo", event.actionLabel)
        }

        assertEquals(
            "the four-column flip must land via ContactRepository.markIgnored",
            true,
            s.contactRepo.getById(12L)?.isIgnored,
        )
        assertFalse(
            "an ignored contact must not ride along into a later commit",
            12L in selectedIdsIn(s.savedState),
        )
    }

    @Test
    fun `onIgnore undo inverse restores the contact`() = runTest {
        val s = fixture()
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))

        s.vm.onIgnore(12L, "Sarah")
        assertEquals(true, s.contactRepo.getById(12L)?.isIgnored)

        val pending = s.undoStack.take()
        assertNotNull("ignore must record a depth-1 undo", pending)
        assertEquals("Ignored Sarah", pending?.label)
        pending?.inverse?.invoke()
        assertEquals(false, s.contactRepo.getById(12L)?.isIgnored)
    }

    @Test
    fun `onIgnore failure publishes couldn't save and records no undo`() = runTest {
        val throwingDao = object : RecordingListMembershipDao() {
            override suspend fun getMembershipsForContact(contactId: Long): List<ListMembershipEntity> =
                throw IllegalStateException("simulated cipher read failure")
        }
        val s = fixture(membershipDao = throwingDao)
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))

        s.commitBus.events.test {
            s.vm.onIgnore(12L, "Sarah")
            val event = awaitItem()
            assertEquals("Couldn't save that", event.message)
            assertNull("failure toast carries no action", event.actionLabel)
        }
        assertNull("failed ignore must not record an undo", s.undoStack.peek())
    }

    // ─── Move commit (was a silent no-op) ────────────────────

    @Test
    fun `onCommit in Move mode dispatches MoveContactsUseCase with undo and snackbar`() = runTest {
        val s = fixture(mode = "move", sourceListId = "2")
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 12L, displayName = "Sarah"),
                contactFixture(id = 13L, displayName = "Marcus"),
            ),
        )
        // Source-side membership rows so the use case's inverse can snapshot them.
        s.membershipDao.seed(
            ListMembershipEntity(contactId = 12L, listId = 2L, addedAt = Instant.parse("2026-01-01T00:00:00Z")),
            ListMembershipEntity(contactId = 13L, listId = 2L, addedAt = Instant.parse("2026-01-01T00:00:00Z")),
        )
        s.vm.onToggleSelect(12L)
        s.vm.onToggleSelect(13L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals("Moved 2 to Inner orbit", event.message)
            assertEquals("Undo", event.actionLabel)
        }

        val move = s.membershipDao.moveCalls.single()
        assertEquals(2L, move.fromListId)
        assertEquals(1L, move.toListId)
        assertEquals(setOf(12L, 13L), move.ids.toSet())
        assertTrue("commit clears the selection", selectedIdsIn(s.savedState).isEmpty())

        // Undo restores the source rows and removes the freshly-moved target rows.
        val pending = s.undoStack.take()
        assertNotNull("move must record a depth-1 undo", pending)
        assertEquals("Moved 2 to Inner orbit", pending?.label)
        pending?.inverse?.invoke()
        val removed = s.membershipDao.removeCalls.single()
        assertEquals(1L, removed.fromListId)
        assertEquals(setOf(12L, 13L), removed.ids.toSet())
        val restored = s.membershipDao.insertCalls.single().memberships
        assertEquals(
            setOf(12L to 2L, 13L to 2L),
            restored.map { it.contactId to it.listId }.toSet(),
        )
    }

    @Test
    fun `Move commit without a sourceListId surfaces a failure instead of a silent no-op`() = runTest {
        // The init guard routes this VM to NotFound, so the commit bar never
        // renders — but a direct onCommit must still fail loudly, not drop the
        // selection on the floor.
        val s = fixture(mode = "move", sourceListId = null)
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        s.vm.onToggleSelect(12L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals("Couldn't save that", event.message)
            assertNull("failure toast carries no action", event.actionLabel)
        }
        assertTrue("no move dispatch without a source", s.membershipDao.moveCalls.isEmpty())
        assertNull("no undo entry for a failed move", s.undoStack.peek())
    }

    // ─── EmptyDevice phase resolution ────────────────────────
    //
    // Pure-predicate tests over the top-level resolvePickerPhase — the uiState
    // pipeline hops real dispatchers (see class KDoc), so the phase logic is
    // pinned here without collecting the flow.

    @Test
    fun `resolvePickerPhase flips Ready to EmptyDevice only when device read confirmed empty`() {
        // Confirmed-empty device + empty store → EmptyDevice.
        assertEquals(
            ContactPickerUiState.Phase.EmptyDevice,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.Ready,
                isCommitting = false,
                deviceEmpty = true,
                hasAnyContacts = false,
            ),
        )
        // Read not finished yet (null) → stay Ready, no skeleton lie.
        assertEquals(
            ContactPickerUiState.Phase.Ready,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.Ready,
                isCommitting = false,
                deviceEmpty = null,
                hasAnyContacts = false,
            ),
        )
        // Device empty but store still projects pickable contacts
        // (call-log-only rows) → stay Ready.
        assertEquals(
            ContactPickerUiState.Phase.Ready,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.Ready,
                isCommitting = false,
                deviceEmpty = true,
                hasAnyContacts = true,
            ),
        )
        // Permission surfaces are never overridden.
        assertEquals(
            ContactPickerUiState.Phase.PermissionRationale,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.PermissionRationale,
                isCommitting = false,
                deviceEmpty = true,
                hasAnyContacts = false,
            ),
        )
        // Committing wins over everything.
        assertEquals(
            ContactPickerUiState.Phase.Committing,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.Ready,
                isCommitting = true,
                deviceEmpty = true,
                hasAnyContacts = false,
            ),
        )
    }

    // ─── Starred filter persistence ──────────────────────────

    @Test
    fun `toggling the Starred filter round-trips through SavedStateHandle`() = runTest {
        val s = fixture()
        s.vm.onToggleFilter(PickerFilter.Starred)
        assertEquals(
            listOf("Starred"),
            s.savedState.get<Array<String>>("activeFilters")?.toList(),
        )
        s.vm.onToggleFilter(PickerFilter.Starred)
        assertEquals(
            emptyList<String>(),
            s.savedState.get<Array<String>>("activeFilters")?.toList(),
        )
    }

    // ─── Add commit (the add-contacts-to-list flow) ──────────

    @Test
    fun `onCommit in Add mode inserts memberships, publishes Added with Undo, clears selection`() =
        runTest {
            val s = fixture(mode = "add")
            s.contactRepo.seed(
                listOf(
                    contactFixture(id = 12L, displayName = "Sarah"),
                    contactFixture(id = 13L, displayName = "Marcus"),
                ),
            )
            s.vm.onToggleSelect(12L)
            s.vm.onToggleSelect(13L)

            s.commitBus.events.test {
                s.vm.onCommit()
                val event = awaitItem()
                assertEquals("Added 2 to Inner orbit", event.message)
                assertEquals("Undo", event.actionLabel)
            }

            val insert = s.membershipDao.insertCalls.single()
            assertEquals(
                "Add commit inserts one membership row per selected contact onto the target list",
                setOf(12L to 1L, 13L to 1L),
                insert.memberships.map { it.contactId to it.listId }.toSet(),
            )
            assertTrue("commit clears the selection", selectedIdsIn(s.savedState).isEmpty())
        }

    @Test
    fun `onCommit Add undo inverse removes the freshly-added memberships`() = runTest {
        val s = fixture(mode = "add")
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        s.vm.onToggleSelect(12L)
        s.vm.onCommit()
        assertEquals(1, s.membershipDao.insertCalls.size)

        val pending = s.undoStack.take()
        assertNotNull("add must record a depth-1 undo", pending)
        assertEquals("Added 1 to Inner orbit", pending?.label)
        pending?.inverse?.invoke()
        val removed = s.membershipDao.removeCalls.single()
        assertEquals(1L, removed.fromListId)
        assertEquals(listOf(12L), removed.ids)
    }

    @Test
    fun `onCommit with an empty selection is a no-op`() = runTest {
        val s = fixture(mode = "add")
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))

        s.commitBus.events.test {
            s.vm.onCommit()
            expectNoEvents()
        }
        assertTrue("no insert without a selection", s.membershipDao.insertCalls.isEmpty())
        assertNull("no undo for an empty commit", s.undoStack.peek())
    }

    @Test
    fun `onCommit Add failure surfaces couldn't save and records no undo`() = runTest {
        val throwingDao = object : RecordingListMembershipDao() {
            override suspend fun insertAll(memberships: List<ListMembershipEntity>) =
                throw IllegalStateException("simulated cipher write failure")
        }
        val s = fixture(membershipDao = throwingDao, mode = "add")
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        s.vm.onToggleSelect(12L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals("Couldn't save that", event.message)
            assertNull("failure toast carries no action", event.actionLabel)
        }
        assertNull("failed add must not record an undo", s.undoStack.peek())
    }

    // ─── Copy commit ─────────────────────────────────────────

    @Test
    fun `onCommit in Copy mode dispatches CopyContactsUseCase with undo and snackbar`() = runTest {
        val s = fixture(mode = "copy")
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 12L, displayName = "Sarah"),
                contactFixture(id = 13L, displayName = "Marcus"),
            ),
        )
        s.vm.onToggleSelect(12L)
        s.vm.onToggleSelect(13L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals("Copied 2 to Inner orbit", event.message)
            assertEquals("Undo", event.actionLabel)
        }

        val insert = s.membershipDao.insertCalls.single()
        assertEquals(
            setOf(12L to 1L, 13L to 1L),
            insert.memberships.map { it.contactId to it.listId }.toSet(),
        )
        assertTrue("commit clears the selection", selectedIdsIn(s.savedState).isEmpty())
        assertNotNull("copy must record a depth-1 undo", s.undoStack.peek())
    }

    // ─── Selection state ─────────────────────────────────────

    @Test
    fun `onToggleSelect adds then removes an id through SavedStateHandle`() = runTest {
        val s = fixture()
        s.vm.onToggleSelect(12L)
        assertEquals(setOf(12L), selectedIdsIn(s.savedState))
        s.vm.onToggleSelect(13L)
        assertEquals(setOf(12L, 13L), selectedIdsIn(s.savedState))
        s.vm.onToggleSelect(12L)
        assertEquals(setOf(13L), selectedIdsIn(s.savedState))
    }

    @Test
    fun `onSelectAllMatching unions the filtered ids into the existing selection`() = runTest {
        val s = fixture()
        s.vm.onToggleSelect(12L)
        s.vm.onSelectAllMatching(setOf(13L, 14L, 12L))
        assertEquals(
            "select-all unions, never replaces",
            setOf(12L, 13L, 14L),
            selectedIdsIn(s.savedState),
        )
    }

    @Test
    fun `onClearSelection empties the selection`() = runTest {
        val s = fixture()
        s.vm.onToggleSelect(12L)
        s.vm.onToggleSelect(13L)
        s.vm.onClearSelection()
        assertTrue(selectedIdsIn(s.savedState).isEmpty())
    }

    // ─── Search + sort persistence ───────────────────────────

    @Test
    fun `onSearchChanged round-trips through SavedStateHandle`() = runTest {
        val s = fixture()
        s.vm.onSearchChanged("sarah")
        assertEquals("sarah", s.savedState.get<String>("searchQuery"))
    }

    @Test
    fun `setSortBy persists the chosen sort token`() = runTest {
        val s = fixture()
        s.vm.setSortBy(PickerSort.ByRecency)
        assertEquals("ByRecency", s.savedState.get<String>("sortBy"))
        s.vm.setSortBy(PickerSort.ByMostCalled)
        assertEquals("ByMostCalled", s.savedState.get<String>("sortBy"))
        s.vm.setSortBy(PickerSort.ByName)
        assertEquals("ByName", s.savedState.get<String>("sortBy"))
    }

    // ─── Filter toggling (call-frequency single-select group) ─

    @Test
    fun `call-frequency filters are mutually exclusive in SavedStateHandle`() = runTest {
        val s = fixture()
        s.vm.onToggleFilter(PickerFilter.CommonlyCalled)
        assertEquals(
            listOf("CommonlyCalled"),
            s.savedState.get<Array<String>>("activeFilters")?.toList(),
        )
        // Activating Rarely clears Commonly — single-select group.
        s.vm.onToggleFilter(PickerFilter.RarelyCalled)
        assertEquals(
            listOf("RarelyCalled"),
            s.savedState.get<Array<String>>("activeFilters")?.toList(),
        )
    }

    @Test
    fun `non-frequency filters coexist with a frequency filter`() = runTest {
        val s = fixture()
        s.vm.onToggleFilter(PickerFilter.CommonlyCalled)
        s.vm.onToggleFilter(PickerFilter.RecentlyAdded)
        assertEquals(
            "RecentlyAdded does not clear the frequency filter",
            setOf("CommonlyCalled", "RecentlyAdded"),
            s.savedState.get<Array<String>>("activeFilters")?.toSet(),
        )
    }

    // ─── Unignore flow ───────────────────────────────────────

    @Test
    fun `onUnignore publishes Restored and the undo re-ignores`() = runTest {
        val s = fixture()
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 12L, displayName = "Sarah", isIgnored = true)
                    .copy(ignoredAt = Instant.parse("2026-06-01T00:00:00Z")),
            ),
        )

        s.commitBus.events.test {
            s.vm.onUnignore(12L, "Sarah")
            val event = awaitItem()
            assertEquals("Restored Sarah", event.message)
            assertEquals("Undo", event.actionLabel)
        }
        assertEquals(false, s.contactRepo.getById(12L)?.isIgnored)

        // Undo intent is to RE-ignore (SettingsIgnoredViewModel shape).
        val pending = s.undoStack.take()
        assertNotNull(pending)
        pending?.inverse?.invoke()
        assertEquals(true, s.contactRepo.getById(12L)?.isIgnored)
    }
}
