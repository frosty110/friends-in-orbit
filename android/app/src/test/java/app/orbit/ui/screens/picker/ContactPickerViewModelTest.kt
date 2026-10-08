package app.orbit.ui.screens.picker

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.test
import app.orbit.calllog.ContactsIngestWorker
import app.orbit.calllog.ContentObserverController
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
 *
 * The test WorkManager (SettingsViewModelTest's fixture) backs the VM's
 * ingest-in-flight flow and receives the real ingest request a grant made on
 * this screen enqueues; [CountingController] keeps `start()` off the
 * ContentResolver.
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

    @Before
    fun initWorkManager() {
        // The VM observes getWorkInfosForUniqueWorkFlow and the controller
        // enqueues a real WorkRequest on a grant. A synchronous executor runs
        // each request to its terminal state before enqueue returns.
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext<Context>(),
            config
        )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        db.close()
    }

    /**
     * Counts `start()` without registering against the ContentResolver; the
     * ingest request still goes to the (test) WorkManager, so a test can read
     * it back under ContactsIngestWorker.UNIQUE_NAME.
     */
    private class CountingController(ctx: Context) : ContentObserverController(ctx) {
        var startCount: Int = 0
        override fun start() { startCount++ }
        override fun stop() {}
    }

    /** A stand-in for the ingest worker that ends SUCCEEDED. Public: built by reflection. */
    class Succeeds(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result = Result.success()
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
        val savedState: SavedStateHandle,
        val controller: CountingController
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
        listRepoOverride: ((FakeListRepository) -> app.orbit.data.repository.ListRepository)? =
            null,
        // False builds the VM with READ_CONTACTS denied (the Robolectric
        // default), so a test can grant it afterwards and drive the edge.
        grantContacts: Boolean = true,
        // LIST-28: Collect's route argument, the chosen ids comma-separated.
        selected: String? = null,
        // What a process death left in the handle, merged over the route.
        restoredState: Map<String, Any?> = emptyMap(),
        // Lists beyond the target (1, "Inner orbit") and "Late night" (2).
        extraLists: List<app.orbit.data.entity.ListEntity> = emptyList()
    ): Setup {
        val app = ApplicationProvider.getApplicationContext<Application>()
        if (grantContacts) Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        val controller = CountingController(app)

        val contactRepo = FakeContactRepository()
        val listRepo = FakeListRepository()
        val lists = listOf(
            listFixture(id = 1L, name = "Inner orbit", type = targetType),
            listFixture(id = 2L, name = "Late night")
        ) + extraLists
        listRepo.seed(lists)
        val listDao = TestListDaoStub(lists = lists)
        val clock = TestClock()
        val undoStack = UndoStack()
        val commitBus = PickerCommitBus()
        val savedStateArgs = buildMap<String, Any?> {
            // A Relink route carries the orphan, not a list (Routes.relinkContact),
            // and a Collect route nothing at all (Routes.collectPeople).
            if (mode != "relink" && mode != "collect") put("targetListId", targetListId)
            put("mode", mode)
            if (sourceListId != null) put("sourceListId", sourceListId)
            if (relinkContactId != null) put("relinkContactId", relinkContactId)
            if (selected != null) put("selected", selected)
            putAll(restoredState)
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
            contentObserverController = controller,
            workManager = WorkManager.getInstance(app),
            savedStateHandle = savedState,
            // Both flowOn hops on the test dispatcher, so uiState can be
            // collected without racing real IO/Default threads.
            ioDispatcher = mainDispatcherRule.testDispatcher,
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )
        return Setup(vm, contactRepo, membershipDao, undoStack, commitBus, savedState, controller)
    }

    private fun ingestWorkCount(): Int =
        WorkManager.getInstance(ApplicationProvider.getApplicationContext<Context>())
            .getWorkInfosForUniqueWork(ContactsIngestWorker.UNIQUE_NAME)
            .get()
            .size

    private fun grantContacts() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.READ_CONTACTS)
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
                hasAnyContacts = false,
                ingesting = false
            )
        )
        // Read not finished yet (null) → stay Ready, no skeleton lie.
        assertEquals(
            ContactPickerUiState.Phase.Ready,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.Ready,
                isCommitting = false,
                deviceEmpty = null,
                hasAnyContacts = false,
                ingesting = false
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
                hasAnyContacts = true,
                ingesting = false
            )
        )
        // Permission surfaces are never overridden.
        assertEquals(
            ContactPickerUiState.Phase.PermissionRationale,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.PermissionRationale,
                isCommitting = false,
                deviceEmpty = true,
                hasAnyContacts = false,
                ingesting = false
            )
        )
        // Committing wins over everything.
        assertEquals(
            ContactPickerUiState.Phase.Committing,
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.Ready,
                isCommitting = true,
                deviceEmpty = true,
                hasAnyContacts = false,
                ingesting = false
            )
        )
    }

    @Test
    fun `resolvePickerPhase holds the skeleton while the first ingest is in flight`() {
        fun resolve(deviceEmpty: Boolean?, hasAnyContacts: Boolean, ingesting: Boolean) =
            resolvePickerPhase(
                basePhase = ContactPickerUiState.Phase.Ready,
                isCommitting = false,
                deviceEmpty = deviceEmpty,
                hasAnyContacts = hasAnyContacts,
                ingesting = ingesting
            )
        // Phone read non-empty, Room still empty, ingest running: the skeleton,
        // not an empty Ready that reads "everyone is already on the list".
        assertEquals(
            ContactPickerUiState.Phase.LoadingPermission,
            resolve(deviceEmpty = false, hasAnyContacts = false, ingesting = true)
        )
        // Nothing in flight: Ready, and the empty state is honest.
        assertEquals(
            ContactPickerUiState.Phase.Ready,
            resolve(deviceEmpty = false, hasAnyContacts = false, ingesting = false)
        )
        // Rows already there: the list shows while the ingest refreshes it.
        assertEquals(
            ContactPickerUiState.Phase.Ready,
            resolve(deviceEmpty = false, hasAnyContacts = true, ingesting = true)
        )
        // A truly empty address book has nothing to wait for.
        assertEquals(
            ContactPickerUiState.Phase.EmptyDevice,
            resolve(deviceEmpty = true, hasAnyContacts = false, ingesting = true)
        )
        // The read not finished yet, ingest in flight: still the skeleton.
        assertEquals(
            ContactPickerUiState.Phase.LoadingPermission,
            resolve(deviceEmpty = null, hasAnyContacts = false, ingesting = true)
        )
    }

    // ─── A grant made on this screen runs the ingest (CMP-1) ──

    @Test
    fun `granting contacts from the rationale starts the observer and runs one ingest`() = runTest {
        val s = fixture(mode = "add", grantContacts = false)
        assertEquals(0, s.controller.startCount)
        assertEquals("nothing enqueued while denied", 0, ingestWorkCount())

        // The user tapped Grant access and allowed it.
        grantContacts()
        s.vm.onPermissionResult(granted = true)

        assertEquals("the grant registers the contacts observer", 1, s.controller.startCount)
        assertEquals("one ingest under the unique name", 1, ingestWorkCount())
    }

    @Test
    fun `a grant noticed on resume runs the ingest once, a later resume does not`() = runTest {
        val s = fixture(mode = "add", grantContacts = false)

        // Still denied on the first resume: nothing.
        s.vm.refreshPermission()
        assertEquals(0, s.controller.startCount)
        assertEquals(0, ingestWorkCount())

        // Allowed in the phone's settings, then back to the picker.
        grantContacts()
        s.vm.refreshPermission()
        assertEquals(1, s.controller.startCount)
        assertEquals(1, ingestWorkCount())

        // Already Ready: a second resume is not a grant.
        s.vm.refreshPermission()
        assertEquals(1, s.controller.startCount)
        assertEquals(1, ingestWorkCount())
    }

    @Test
    fun `opening the picker with contacts already granted enqueues no ingest`() = runTest {
        // Init's LoadingPermission -> Ready is not a grant made here; the
        // ingest for that grant ran in onboarding or Settings.
        val s = fixture(mode = "add")
        s.vm.refreshPermission()

        assertEquals(0, s.controller.startCount)
        assertEquals(0, ingestWorkCount())
    }

    // runBlocking (real time): the WorkInfo flow emits on WorkManager's own
    // executor, and the test driver's setInitialDelayMet runs the stand-in
    // worker there too, the OnboardingSyncViewModelTest idiom.
    @Test
    fun `while the ingest runs, an empty store is the skeleton, then honest Ready`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val wm = WorkManager.getInstance(app)
        // An ingest that has not run yet: ENQUEUED. The hour's delay stands in
        // for an address book still being read.
        val ingest = OneTimeWorkRequestBuilder<Succeeds>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniqueWork(ContactsIngestWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, ingest)
            .result.get()
        // The phone has a contact; Room has none of them yet.
        val s = fixture(mode = "add", contactsReader = FlakyContactsReader(app))

        // The first pipeline emission carries the target's name; the stateIn
        // initial value does not, which tells the two LoadingPermissions apart.
        val waiting = withTimeout(30_000L) { s.vm.uiState.first { it.targetListName.isNotEmpty() } }
        assertEquals(ContactPickerUiState.Phase.LoadingPermission, waiting.phase)
        assertTrue(waiting.allContacts.isEmpty())

        // The ingest finishes (and, in this fixture, finds nobody to insert).
        WorkManagerTestInitHelper.getTestDriver(app)!!.setInitialDelayMet(ingest.id)

        val settled = withTimeout(30_000L) {
            s.vm.uiState.first { it.phase != ContactPickerUiState.Phase.LoadingPermission }
        }
        assertEquals(ContactPickerUiState.Phase.Ready, settled.phase)
        assertEquals(ContactPickerUiState.EmptyReason.EveryoneOnList, settled.emptyReason)
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

    // ─── What a row says: LIST-24 and the Recently added chip ─

    @Test
    fun `rows leave archived lists out, and Recently added reads the device date`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // TestClock() is 2026-01-01T12:00Z; Recently added is the last 30 days.
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val dao = object : RecordingListMembershipDao() {
            override fun observeAll(): Flow<List<ListMembershipEntity>> = flowOf(
                listOf(
                    ListMembershipEntity(contactId = 12L, listId = 2L, addedAt = now),
                    ListMembershipEntity(contactId = 12L, listId = 3L, addedAt = now),
                    ListMembershipEntity(contactId = 13L, listId = 3L, addedAt = now)
                )
            )
        }
        val s = fixture(
            membershipDao = dao,
            contactsReader = FlakyContactsReader(app),
            extraLists = listOf(listFixture(id = 3L, name = "Old friends", isArchived = true))
        )
        s.contactRepo.seed(
            listOf(
                // In the first sync's batch: one shared first sight, an old device date.
                contactFixture(
                    id = 12L,
                    firstSeenByAppAt = now.minus(Duration.ofDays(2)),
                    deviceUpdatedAt = now.minus(Duration.ofDays(400))
                ),
                // Saved since then.
                contactFixture(
                    id = 13L,
                    firstSeenByAppAt = now.minus(Duration.ofDays(1)),
                    deviceUpdatedAt = now.minus(Duration.ofDays(1))
                )
            )
        )

        s.vm.uiState.test {
            var item = awaitItem()
            while (item.phase != ContactPickerUiState.Phase.Ready || item.allContacts.size < 2) {
                item = awaitItem()
            }
            val byId = item.allContacts.associateBy { it.contactId }
            assertEquals(
                "the archived list is not named",
                listOf("Late night"),
                byId.getValue(12L).listNames
            )
            assertTrue(
                "on an archived list only is Not on a list",
                PickerFilter.Unsorted.matches(byId.getValue(13L))
            )
            assertFalse("the first sync's batch", byId.getValue(12L).isRecentlyAdded)
            assertTrue("saved since the sync", byId.getValue(13L).isRecentlyAdded)
            cancelAndIgnoreRemainingEvents()
        }
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

    // ─── LIST-28: Collect, New list's People step ────────────

    @Test
    fun `Collect needs no list, opens with the chosen ticked, and offers everyone`() = runTest {
        // Sarah is already on Inner orbit: Add mode for that list would hide
        // her, but a list that does not exist yet has no members to hide.
        val sarahOnInnerOrbit = ListMembershipEntity(contactId = 12L, listId = 1L, addedAt = Instant.EPOCH)
        val membershipDao = object : RecordingListMembershipDao() {
            override fun observeAll(): Flow<List<ListMembershipEntity>> = kotlinx.coroutines.flow.flowOf(listOf(sarahOnInnerOrbit))
        }
        val s = fixture(membershipDao = membershipDao, mode = "collect", selected = "12,13")
        s.contactRepo.seed(
            listOf(
                contactFixture(id = 12L, displayName = "Sarah"),
                contactFixture(id = 13L, displayName = "Marcus"),
                contactFixture(id = 14L, displayName = "Priya")
            )
        )

        s.vm.uiState.test {
            var item = awaitItem()
            while (item.phase != ContactPickerUiState.Phase.Ready) item = awaitItem()
            assertEquals(PickerMode.Collect, item.mode)
            assertEquals(setOf(12L, 13L), item.selectedIds)
            assertEquals(setOf(12L, 13L, 14L), item.allContacts.map { it.contactId }.toSet())
            // "On a list" can offer every list: there is no target to leave out.
            assertEquals(setOf(1L, 2L), item.availableLists.map { it.id }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Collect keeps what the user changed over the route's selection`() = runTest {
        // A process death restores the handle: the route's "12,13" must not
        // overwrite the selection the user had narrowed to 13.
        val s = fixture(
            mode = "collect",
            selected = "12,13",
            restoredState = mapOf("selectedIds" to longArrayOf(13L))
        )

        assertEquals(setOf(13L), selectedIdsIn(s.savedState))
    }

    @Test
    fun `Collect never commits, so nothing reaches a list`() = runTest {
        val s = fixture(mode = "collect", selected = "12")
        s.contactRepo.seed(listOf(contactFixture(id = 12L, displayName = "Sarah")))

        // The screen hands the selection back instead; a commit here would
        // be to no list, so it fails loudly (rules.md Code 3).
        kotlin.test.assertFailsWith<IllegalStateException> { s.vm.onCommit() }
        assertTrue(s.membershipDao.insertCalls.isEmpty())
        assertEquals(setOf(12L), selectedIdsIn(s.savedState))
    }

    @Test
    fun `the selected argument reads as ids and drops what is not one`() {
        assertEquals(listOf(3L, 7L, 12L), parseSelected("3,7,12").toList())
        assertEquals(listOf(3L, 12L), parseSelected("3, x,,12,3").toList())
        assertTrue(parseSelected(null).isEmpty())
        assertTrue(parseSelected("").isEmpty())
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
