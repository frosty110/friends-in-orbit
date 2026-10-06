package app.orbit.ui.screens.picker

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.android.ContactsReader
import app.orbit.data.android.PhoneContact
import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.dao.TestListDaoStub
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.db.RoomTransactionRunner
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
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
import app.orbit.domain.usecase.RelinkContactUseCase
import app.orbit.domain.usecase.UnignoreContactUseCase
import app.orbit.testutil.MainDispatcherRule
import app.orbit.testutil.newPrefs
import app.orbit.ui.util.UiText
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Behavioral tests for the contact picker's ViewModel: the ignore/unignore
 * flows, the commits by mode and their guards, and the state pipeline's
 * terminal phases (NotFound for a missing row, Error with Retry, PICK-09).
 *
 * Robolectric supplies the `@ApplicationContext Context` the VM reads
 * READ_CONTACTS from (granted via shadow so init lands in Ready). Fakes from
 * `FakeRepositories.kt` + a pass-through [TransactionRunner] make the use-case
 * writes synchronous on the rule's Unconfined dispatcher.
 *
 * [ContactPickerViewModel.uiState] is collected where a test needs a phase:
 * the fixture passes the rule's test dispatcher for both of the pipeline's
 * flowOn hops (the address-book read and the candidate reduction), so nothing
 * races runTest. Until 2026-10-06 those hops were hard-wired to real
 * `Dispatchers.IO` / `Default` and this class could not collect the state at
 * all, which left the read-failure path, the retry generation tagging and
 * "the selection survives the error" unverified. Filter and sort semantics
 * stay in [ContactPickerUiStateTest] as pure state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ContactPickerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // One DataStore per test method, on a scope this test owns and cancels
    // (testutil/TestDataStore.kt): the process-wide `AppPrefs(context)` store
    // was shared by every method of a class and stranded writes across them.
    @get:Rule
    val tmp = TemporaryFolder()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { tmp.newPrefs(storeScope) }

    /** Pass-through TransactionRunner — runs the block directly on the calling coroutine. */
    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    /**
     * Real Room for the Relink merge (CONTACT-07): its foreign keys and unique
     * number index are what the merge has to get right, so it is not faked.
     */
    private val db: OrbitDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            OrbitDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

    /** Snackbar copy is UiText (strings_picker.xml and shared); resolved against real resources. */
    private fun UiText?.text(): String? = this?.asString(ApplicationProvider.getApplicationContext<Context>())

    @After
    fun tearDown() {
        storeScope.cancel()
        db.close()
    }

    /** Device address book stub — the ignore flows never touch the provider. */
    private class FakeContactsReader(context: Context) : ContactsReader(context) {
        override suspend fun readAll(): List<PhoneContact> = emptyList()
    }

    /** Address-book stub whose read can be made to fail, then recover (PICK-09). */
    private class FlakyContactsReader(context: Context) : ContactsReader(context) {
        var failing = false
        override suspend fun readAll(): List<PhoneContact> {
            if (failing) throw java.io.IOException("simulated provider failure")
            return listOf(
                PhoneContact(contactId = 100L, displayName = "Sarah", phone = "+15555550001", normalizedPhone = "+15555550001")
            )
        }
    }

    private data class Setup(
        val vm: ContactPickerViewModel,
        val contactRepo: FakeContactRepository,
        val membershipDao: RecordingListMembershipDao,
        val undoStack: UndoStack,
        val commitBus: PickerCommitBus,
        val savedState: SavedStateHandle
    )

    private fun fixture(
        membershipDao: RecordingListMembershipDao = RecordingListMembershipDao(),
        mode: String = "add",
        sourceListId: String? = null,
        relinkContactId: String? = null,
        targetListId: String = "1",
        targetType: ListType = ListType.STATIC,
        contactsReader: ContactsReader? = null,
        // Wraps the seeded fake (interface delegation) so one test can make a
        // Room source fail.
        listRepoOverride: ((FakeListRepository) -> app.orbit.data.repository.ListRepository)? = null
    ): Setup {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)

        val contactRepo = FakeContactRepository()
        val listRepo = FakeListRepository()
        val lists = listOf(
            listFixture(id = 1L, name = "Inner orbit", type = targetType),
            listFixture(id = 2L, name = "Late night")
        )
        listRepo.seed(lists)
        val listDao = TestListDaoStub(lists = lists)
        val clock = TestClock()
        val undoStack = UndoStack()
        val commitBus = PickerCommitBus()
        val savedStateArgs = buildMap<String, Any?> {
            // A Relink route carries the orphan, not a list (Routes.relinkContact).
            if (mode != "relink") put("targetListId", targetListId)
            put("mode", mode)
            if (sourceListId != null) put("sourceListId", sourceListId)
            if (relinkContactId != null) put("relinkContactId", relinkContactId)
        }
        val savedState = SavedStateHandle(savedStateArgs)

        val vm = ContactPickerViewModel(
            appContext = app,
            contactRepo = contactRepo,
            listRepo = listRepoOverride?.invoke(listRepo) ?: listRepo,
            listMembershipDao = membershipDao,
            callEventRepo = FakeCallEventRepository(),
            appPrefs = prefs,
            moveUseCase = MoveContactsUseCase(passThruTx, membershipDao, listDao, listRepo, clock),
            copyUseCase = CopyContactsUseCase(passThruTx, membershipDao, listDao, listRepo, clock),
            relinkUseCase = RelinkContactUseCase(
                RoomTransactionRunner(db),
                db.contactDao(),
                db.listMembershipDao(),
                listRepo,
                clock
            ),
            ignoreUseCase = IgnoreContactUseCase(
                passThruTx,
                contactRepo,
                membershipDao,
                listRepo,
                clock
            ),
            unignoreUseCase = UnignoreContactUseCase(
                passThruTx,
                contactRepo,
                listDao,
                membershipDao,
                listRepo,
                clock
            ),
            undoStack = undoStack,
            contactsReader = contactsReader ?: FakeContactsReader(app),
            clock = clock,
            commitBus = commitBus,
            appScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher),
            savedStateHandle = savedState,
            // Both flowOn hops on the test dispatcher, so uiState can be
            // collected without racing real IO/Default threads.
            ioDispatcher = mainDispatcherRule.testDispatcher,
            defaultDispatcher = mainDispatcherRule.testDispatcher
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
            assertEquals("Ignored Sarah", event.message.text())
            assertEquals("Undo", event.actionLabel.text())
        }

        assertEquals(
            "the four-column flip must land via ContactRepository.markIgnored",
            true,
            s.contactRepo.getById(12L)?.isIgnored
        )
        assertFalse(
            "an ignored contact must not ride along into a later commit",
            12L in selectedIdsIn(s.savedState)
        )
    }

    @Test
    fun `onIgnore undo inverse restores the contact`() = runTest {
        val s = fixture()
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))

        s.commitBus.events.test {
            s.vm.onIgnore(12L, "Sarah")
            // The words travel on the event; UndoStack holds only the inverse.
            assertEquals("Ignored Sarah", awaitItem().message.text())
        }
        assertEquals(true, s.contactRepo.getById(12L)?.isIgnored)

        val pending = s.undoStack.take()
        assertNotNull("ignore must record a depth-1 undo", pending)
        pending?.inverse?.invoke()
        assertEquals(false, s.contactRepo.getById(12L)?.isIgnored)
    }

    @Test
    fun `onIgnore failure publishes couldn't save and records no undo`() = runTest {
        val throwingDao = object : RecordingListMembershipDao() {
            override suspend fun getMembershipsForContact(
                contactId: Long
            ): List<ListMembershipEntity> =
                throw IllegalStateException("simulated cipher read failure")
        }
        val s = fixture(membershipDao = throwingDao)
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))

        s.commitBus.events.test {
            s.vm.onIgnore(12L, "Sarah")
            val event = awaitItem()
            assertEquals("Couldn't save that", event.message.text())
            assertNull("failure toast carries no action", event.actionLabel.text())
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
                contactFixture(id = 13L, displayName = "Marcus")
            )
        )
        // Source-side membership rows so the use case's inverse can snapshot them.
        s.membershipDao.seed(
            ListMembershipEntity(
                contactId = 12L,
                listId = 2L,
                addedAt = Instant.parse("2026-01-01T00:00:00Z")
            ),
            ListMembershipEntity(
                contactId = 13L,
                listId = 2L,
                addedAt = Instant.parse("2026-01-01T00:00:00Z")
            )
        )
        s.vm.onToggleSelect(12L)
        s.vm.onToggleSelect(13L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals("Moved 2 people to Inner orbit", event.message.text())
            assertEquals("Undo", event.actionLabel.text())
        }

        val move = s.membershipDao.moveCalls.single()
        assertEquals(2L, move.fromListId)
        assertEquals(1L, move.toListId)
        assertEquals(setOf(12L, 13L), move.ids.toSet())
        assertTrue("commit clears the selection", selectedIdsIn(s.savedState).isEmpty())

        // Undo restores the source rows and removes the freshly-moved target rows.
        val pending = s.undoStack.take()
        assertNotNull("move must record a depth-1 undo", pending)
        // The words are asserted on the event above; UndoStack holds only the inverse.
        assertNull("depth-1: taking the undo leaves nothing behind", s.undoStack.peek())
        pending?.inverse?.invoke()
        val removed = s.membershipDao.removeCalls.single()
        assertEquals(1L, removed.fromListId)
        assertEquals(setOf(12L, 13L), removed.ids.toSet())
        val restored = s.membershipDao.insertCalls.single().memberships
        assertEquals(
            setOf(12L to 2L, 13L to 2L),
            restored.map { it.contactId to it.listId }.toSet()
        )
    }

    @Test
    fun `Move commit without a sourceListId surfaces a failure instead of a silent no-op`() =
        runTest {
            // The init guard routes this VM to NotFound, so the commit bar never
            // renders — but a direct onCommit must still fail loudly, not drop the
            // selection on the floor.
            val s = fixture(mode = "move", sourceListId = null)
            s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
            s.vm.onToggleSelect(12L)

            s.commitBus.events.test {
                s.vm.onCommit()
                val event = awaitItem()
                assertEquals("Couldn't save that", event.message.text())
                assertNull("failure toast carries no action", event.actionLabel.text())
            }
            assertTrue("no move dispatch without a source", s.membershipDao.moveCalls.isEmpty())
            assertNull("no undo entry for a failed move", s.undoStack.peek())
        }

    // ─── Relink mode (CONTACT-07) ────────────────────────────

    @Test
    fun `Relink selection is single - a new pick replaces the old one`() = runTest {
        val s = fixture(mode = "relink", relinkContactId = "10")

        s.vm.onToggleSelect(20L)
        s.vm.onToggleSelect(21L)
        assertEquals(setOf(21L), selectedIdsIn(s.savedState))

        s.vm.onToggleSelect(21L)
        assertTrue("tapping the pick again clears it", selectedIdsIn(s.savedState).isEmpty())
    }

    @Test
    fun `Relink refuses select-all so a stale tap cannot stage a multi-contact merge`() = runTest {
        val s = fixture(mode = "relink", relinkContactId = "10")

        s.vm.onSelectAllMatching(setOf(20L, 21L, 22L))

        assertTrue(selectedIdsIn(s.savedState).isEmpty())
    }

    @Test
    fun `Relink commit merges, publishes Re-linked with Undo, and never touches a list`() =
        runTest {
            val s = fixture(mode = "relink", relinkContactId = "10")
            val first = Instant.parse("2026-01-01T00:00:00Z")
            db.contactDao().insert(
                contactFixture(
                    id = 10L,
                    displayName = "Mum (old)",
                    phoneContactId = 100L,
                    isOrphaned = true,
                    firstSeenByAppAt = first
                )
            )
            db.contactDao().insert(
                contactFixture(
                    id = 20L,
                    displayName = "Mum",
                    phoneContactId = 200L,
                    firstSeenByAppAt = first
                )
            )
            s.vm.onToggleSelect(20L)

            s.commitBus.events.test {
                s.vm.onCommit()
                val event = awaitItem()
                assertEquals("Re-linked to Mum", event.message.text())
                assertEquals("Undo", event.actionLabel.text())
            }

            assertEquals("Mum", db.contactDao().get(10L)?.displayName)
            assertFalse(db.contactDao().get(10L)!!.isOrphaned)
            assertNull("the picked row was merged away", db.contactDao().get(20L))
            // The words are asserted on the event above; UndoStack holds only the inverse.
            assertNotNull("re-link must record a depth-1 undo", s.undoStack.peek())
            assertTrue(
                "Regression: Re-link used to insert memberships into a list " +
                    "sharing the contact's id",
                s.membershipDao.insertCalls.isEmpty()
            )
        }

    @Test
    fun `Relink commit the merge refuses surfaces a failure and records no undo`() = runTest {
        val s = fixture(mode = "relink", relinkContactId = "10")
        // Not orphaned (a sync restored it), so there is nothing to re-link.
        db.contactDao().insert(contactFixture(id = 10L, phoneContactId = 100L))
        db.contactDao().insert(contactFixture(id = 20L, phoneContactId = 200L))
        s.vm.onToggleSelect(20L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals("Couldn't save that", event.message.text())
            assertNull(event.actionLabel.text())
        }
        assertNull(s.undoStack.peek())
        assertEquals(2, db.contactDao().getAllOnce().size)
    }

    @Test
    fun `a Relink route without the orphan id commits nothing`() = runTest {
        val s = fixture(mode = "relink", relinkContactId = null)
        s.vm.onToggleSelect(20L)

        s.vm.onCommit()

        assertNull(s.undoStack.peek())
        assertTrue(s.membershipDao.insertCalls.isEmpty())
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
                hasAnyContacts = false
            )
        )
        // Read not finished yet (null) → stay Ready, no skeleton lie.
        assertEquals(
            ContactPickerUiState.Phase.Ready,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.Ready,
                isCommitting = false,
                deviceEmpty = null,
                hasAnyContacts = false
            )
        )
        // Device empty but store still projects pickable contacts
        // (call-log-only rows) → stay Ready.
        assertEquals(
            ContactPickerUiState.Phase.Ready,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.Ready,
                isCommitting = false,
                deviceEmpty = true,
                hasAnyContacts = true
            )
        )
        // Permission surfaces are never overridden.
        assertEquals(
            ContactPickerUiState.Phase.PermissionRationale,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.PermissionRationale,
                isCommitting = false,
                deviceEmpty = true,
                hasAnyContacts = false
            )
        )
        // Committing wins over everything.
        assertEquals(
            ContactPickerUiState.Phase.Committing,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.Ready,
                isCommitting = true,
                deviceEmpty = true,
                hasAnyContacts = false
            )
        )
    }

    // ─── Starred filter persistence ──────────────────────────

    @Test
    fun `toggling the Starred filter round-trips through SavedStateHandle`() = runTest {
        val s = fixture()
        s.vm.onToggleFilter(PickerFilter.Starred)
        assertEquals(
            listOf("Starred"),
            s.savedState.get<Array<String>>("activeFilters")?.toList()
        )
        s.vm.onToggleFilter(PickerFilter.Starred)
        assertEquals(
            emptyList<String>(),
            s.savedState.get<Array<String>>("activeFilters")?.toList()
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
                    contactFixture(id = 13L, displayName = "Marcus")
                )
            )
            s.vm.onToggleSelect(12L)
            s.vm.onToggleSelect(13L)

            s.commitBus.events.test {
                s.vm.onCommit()
                val event = awaitItem()
                assertEquals("Added 2 people to Inner orbit", event.message.text())
                assertEquals("Undo", event.actionLabel.text())
            }

            val insert = s.membershipDao.insertCalls.single()
            assertEquals(
                "Add commit inserts one membership row per selected contact onto the target list",
                setOf(12L to 1L, 13L to 1L),
                insert.memberships.map { it.contactId to it.listId }.toSet()
            )
            assertTrue("commit clears the selection", selectedIdsIn(s.savedState).isEmpty())
        }

    @Test
    fun `onCommit Add undo inverse removes the freshly-added memberships`() = runTest {
        val s = fixture(mode = "add")
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        s.vm.onToggleSelect(12L)
        s.commitBus.events.test {
            s.vm.onCommit()
            // The words travel on the event; UndoStack holds only the inverse.
            assertEquals("Added 1 person to Inner orbit", awaitItem().message.text())
        }
        assertEquals(1, s.membershipDao.insertCalls.size)

        val pending = s.undoStack.take()
        assertNotNull("add must record a depth-1 undo", pending)
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
            assertEquals("Couldn't save that", event.message.text())
            assertNull("failure toast carries no action", event.actionLabel.text())
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
                contactFixture(id = 13L, displayName = "Marcus")
            )
        )
        s.vm.onToggleSelect(12L)
        s.vm.onToggleSelect(13L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals("Copied 2 people to Inner orbit", event.message.text())
            assertEquals("Undo", event.actionLabel.text())
        }

        val insert = s.membershipDao.insertCalls.single()
        assertEquals(
            setOf(12L to 1L, 13L to 1L),
            insert.memberships.map { it.contactId to it.listId }.toSet()
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
            selectedIdsIn(s.savedState)
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
            s.savedState.get<Array<String>>("activeFilters")?.toList()
        )
        // Activating Rarely clears Commonly — single-select group.
        s.vm.onToggleFilter(PickerFilter.RarelyCalled)
        assertEquals(
            listOf("RarelyCalled"),
            s.savedState.get<Array<String>>("activeFilters")?.toList()
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
            s.savedState.get<Array<String>>("activeFilters")?.toSet()
        )
    }

    // ─── Smart lists are not user-curated ────────────────────
    //
    // SmartListMembershipSync rewrites a smart list's rows from its rule, so a
    // membership added here would vanish on the next reconcile with no
    // message. The surfaces hide "Add people" on smart lists; the commit
    // refuses one loudly in case a route still reaches it (rules.md Code 3).

    @Test
    fun `onCommit into a smart list refuses loudly and inserts nothing`() = runTest {
        val s = fixture(mode = "add", targetType = ListType.SMART)
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        s.vm.onToggleSelect(12L)

        s.commitBus.events.test {
            s.vm.onCommit()
            val event = awaitItem()
            assertEquals("Couldn't save that", event.message.text())
            assertNull(event.actionLabel.text())
        }
        assertTrue("no membership row for a smart list", s.membershipDao.insertCalls.isEmpty())
        assertNull(s.undoStack.peek())
    }

    @Test
    fun `Copy into a smart list is refused the same way`() = runTest {
        val s = fixture(mode = "copy", targetType = ListType.SMART)
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))
        s.vm.onToggleSelect(12L)

        s.commitBus.events.test {
            s.vm.onCommit()
            assertEquals("Couldn't save that", awaitItem().message.text())
        }
        assertTrue(s.membershipDao.insertCalls.isEmpty())
    }

    // ─── A well-formed id whose row is gone ──────────────────

    @Test
    fun `a valid route whose list no longer exists lands on NotFound`() = runTest {
        // List 7 is not seeded: Room emits null for a missing row, which used
        // to leave a working-looking picker with a blank target name.
        val s = fixture(mode = "add", targetListId = "7")
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))

        s.vm.uiState.test {
            var item = awaitItem()
            while (item.phase == ContactPickerUiState.Phase.LoadingPermission) item = awaitItem()
            assertEquals(ContactPickerUiState.Phase.NotFound, item.phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── PICK-09: a failed read is an error, not a crash ─────

    @Test
    fun `a failed address-book read is Error with the selection kept, and Retry recovers`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val reader = FlakyContactsReader(app).apply { failing = true }
        val s = fixture(mode = "add", contactsReader = reader)
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah", phoneContactId = 100L)))
        s.vm.onToggleSelect(12L)

        s.vm.uiState.test {
            var item = awaitItem()
            while (item.phase == ContactPickerUiState.Phase.LoadingPermission) item = awaitItem()
            assertEquals(ContactPickerUiState.Phase.Error, item.phase)
            assertEquals("the selection survives the error", setOf(12L), item.selectedIds)

            reader.failing = false
            s.vm.onRetry()
            var next = awaitItem()
            while (next.phase != ContactPickerUiState.Phase.Ready) next = awaitItem()
            assertEquals(listOf(12L), next.allContacts.map { it.contactId })
            assertEquals("Retry keeps the selection too", setOf(12L), next.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failing Room source is Error, and Retry re-subscribes it`() = runTest {
        var failing = true
        val s = fixture(
            mode = "add",
            listRepoOverride = { fake ->
                object : app.orbit.data.repository.ListRepository by fake {
                    override fun observeAll(): Flow<List<app.orbit.data.entity.ListEntity>> = flow {
                        if (failing) throw java.io.IOException("simulated read failure")
                        emitAll(fake.observeAll())
                    }
                }
            }
        )
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))

        s.vm.uiState.test {
            var item = awaitItem()
            while (item.phase == ContactPickerUiState.Phase.LoadingPermission) item = awaitItem()
            assertEquals(ContactPickerUiState.Phase.Error, item.phase)

            failing = false
            s.vm.onRetry()
            var next = awaitItem()
            while (next.phase == ContactPickerUiState.Phase.Error) next = awaitItem()
            // The device read is empty in this fixture but the store still has
            // a pickable row, so recovery is Ready (resolvePickerPhase keeps
            // call-log-only rows honest), with the lists back for the filter.
            assertEquals(ContactPickerUiState.Phase.Ready, next.phase)
            assertEquals(listOf("Late night"), next.availableLists.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Unignore flow ───────────────────────────────────────

    @Test
    fun `onUnignore publishes Unignored and the undo re-ignores`() = runTest {
        val s = fixture()
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 12L, displayName = "Sarah", isIgnored = true)
                    .copy(ignoredAt = Instant.parse("2026-06-01T00:00:00Z"))
            )
        )

        s.commitBus.events.test {
            s.vm.onUnignore(12L, "Sarah")
            val event = awaitItem()
            // The glossary's one word for the inverse of Ignore (voice.md).
            assertEquals("Unignored Sarah", event.message.text())
            assertEquals("Undo", event.actionLabel.text())
        }
        assertEquals(false, s.contactRepo.getById(12L)?.isIgnored)

        // Undo intent is to RE-ignore (SettingsIgnoredViewModel shape).
        val pending = s.undoStack.take()
        assertNotNull(pending)
        pending?.inverse?.invoke()
        assertEquals(true, s.contactRepo.getById(12L)?.isIgnored)
    }
}
