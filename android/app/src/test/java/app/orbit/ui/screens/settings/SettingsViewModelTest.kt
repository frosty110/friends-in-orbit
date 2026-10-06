package app.orbit.ui.screens.settings

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.orbit.R
import app.orbit.calllog.CallLogPermissionState
import app.orbit.calllog.ContactsIngestWorker
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.dao.PreIgnoreSnapshot
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ResetService
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.testutil.MainDispatcherRule
import app.orbit.testutil.awaitValue
import app.orbit.testutil.newPrefs
import app.orbit.ui.theme.OrbitDarkMode
import app.orbit.ui.theme.OrbitThemeId
import app.orbit.ui.util.UiText
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [SettingsViewModel].
 *
 * Fixture pattern:
 *   - Robolectric's [ApplicationProvider.getApplicationContext] supplies a real
 *     [Context] for the OS permission reads and WorkManager.
 *   - Each method gets its own DataStore file and scope
 *     (`testutil/TestDataStore.kt`), cancelled in `@After`, so no method can
 *     see another's writes or wait on a write another method stranded. The
 *     class used to share the process-wide `orbit_prefs` singleton across
 *     methods and reset it by hand, which is where the suite's 30 second
 *     timeouts came from.
 *   - [MainDispatcherRule] swaps `Dispatchers.Main` for `UnconfinedTestDispatcher`
 *     so `viewModelScope` + `stateIn` run on the test dispatcher.
 *
 * DataStore's internal IO dispatcher is NOT swapped by `MainDispatcherRule`,
 * so the first emission is Loading (the stateIn initialValue), followed by the
 * terminal Ready when DataStore delivers its first read. Tests use
 * `uiState.filterIsInstance<Ready>().first()` to skip the Loading gate
 * deterministically; the Loading test exercises the invariant in isolation via
 * StandardTestDispatcher + direct StateFlow.value snapshot.
 *
 * The permission side effects (`start()`, `stop()`, the enqueues) run on the
 * caller's thread before the VM method returns, since nothing is written to
 * DataStore first any more, so the tests assert them directly. The one path
 * that still follows a DataStore write ([SettingsViewModel.onImportDaysChanged])
 * is observed through [CountingController.syncRequests], a channel the
 * controller feeds, never through the write it follows.
 *
 * Waiting for a DataStore write is done by polling (`awaitValue`), never by a
 * collector started after the write: DataStore 1.1.1 can hand such a
 * collector the old value stamped with the new version and then drop the
 * update (see `testutil/TestDataStore.kt`). The `uiState` predicates below are
 * only ever awaited after a plain `awaitReady()` made the upstream combine
 * hot, so its DataStore collectors were subscribed before the write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Stock Application class: avoids OrbitApp.onCreate (which schedules
// WorkManager + the Hilt graph that isn't set up for JVM tests).
@Config(sdk = [33], application = Application::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: AppPrefs by lazy { tmp.newPrefs(storeScope) }
    private val clock = TestClock(Instant.parse("2026-10-06T10:00:00Z"))
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun initWorkManager() {
        // SettingsViewModel observes
        // WorkManager.getWorkInfosForUniqueWorkFlow + ContentObserverController
        // enqueues real WorkRequests. The test WorkManager runs every request
        // on a synchronous executor so enqueues complete before assertion.
        // Idempotent: calling initialize twice on the same context is safe in
        // the test helper.
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        appScope.cancel()
    }

    private fun buildController(): ContentObserverController =
        ContentObserverController(context)

    /**
     * Test double for [ContentObserverController] that counts start/stop
     * invocations without registering against the real ContentResolver, and
     * records every [enqueueImmediateSync] (its `fullResync` flag) on a channel
     * so a test can await the request instead of racing the DataStore write it
     * follows. Contacts ingests still go to the real WorkManager.
     * ContentObserverController is an `open class` + `open fun start/stop` to
     * enable this subclass.
     */
    private class CountingController(ctx: Context) : ContentObserverController(ctx) {
        @Volatile var startCount: Int = 0
        @Volatile var stopCount: Int = 0
        val syncRequests = Channel<Boolean>(Channel.UNLIMITED)
        override fun start() { startCount++ }
        override fun stop() { stopCount++ }
        override fun enqueueImmediateSync(fullResync: Boolean) { syncRequests.trySend(fullResync) }
    }

    /**
     * Fixture support: a minimal [ContactRepository] stub that emits an empty
     * ignored list. The Settings combine folds
     * `contactRepo.observeIgnored().map { it.size }` to drive the "{N} ignored"
     * subtitle. Most tests never assert on the count, so the empty-flow stub is
     * sufficient; the count itself is pinned against [FakeContactRepository].
     */
    private object EmptyIgnoredContactRepository : ContactRepository {
        override fun observeAll(): Flow<List<ContactEntity>> = flowOf(emptyList())
        // Settings tests do not exercise the list-scoped pipeline.
        override fun observeForListMembers(listId: Long): Flow<List<ContactEntity>> = flowOf(emptyList())
        override fun observeNeverCalled(): Flow<List<ContactEntity>> = flowOf(emptyList())
        override suspend fun snapshotNeverCalled(): List<ContactEntity> = emptyList()
        // Settings tests do not exercise the reconciler match index.
        override suspend fun snapshotAllPhones(): List<app.orbit.data.entity.ContactPhoneEntity> = emptyList()
        override fun observeById(id: Long): Flow<ContactEntity?> = flowOf(null)
        override suspend fun getById(id: Long): ContactEntity? = null
        override suspend fun setPausedUntil(id: Long, until: Instant?) {}
        override fun observeIgnored(): Flow<List<ContactEntity>> = flowOf(emptyList())
        override suspend fun markIgnored(
            id: Long,
            isIgnored: Boolean,
            ignoredAt: Instant?,
            preIgnoreListMembershipsJson: String?,
        ) {}
        override suspend fun getPreIgnoreSnapshot(id: Long): PreIgnoreSnapshot? = null
        override suspend fun setRuleOverrideJson(id: Long, json: String?) {}
        override suspend fun setArchived(id: Long, archived: Boolean) {}
    }

    /** SET-11 fixture: the ignored query fails on its first subscription, then passes. */
    private class FlakyIgnoredContactRepository : ContactRepository by EmptyIgnoredContactRepository {
        var failuresLeft: Int = 1
        override fun observeIgnored(): Flow<List<ContactEntity>> = flow {
            if (failuresLeft > 0) {
                failuresLeft--
                throw IllegalStateException("disk")
            }
            emit(emptyList())
        }
    }

    /**
     * Minimal [ResetService] stub. Most Settings tests do not exercise the
     * destructive-reset path; this subclass replaces [resetAll] with a
     * counting stand-in so we do not need to stand up a real wipe (the full
     * reset behavior is pinned by [app.orbit.data.repository.ResetServiceTest]).
     * [gate], when set, holds the reset open so a test can clear the ViewModel
     * mid-way; [failWith] makes it throw. The constructor still requires real
     * dependency instances per the [ResetService] @Inject signature.
     */
    private class RecordingResetService(
        ctx: Context,
        db: OrbitDatabase,
        prefs: AppPrefs,
        controller: ContentObserverController,
    ) : ResetService(
        context = ctx,
        database = db,
        appPrefs = prefs,
        contentObserverController = controller,
    ) {
        @Volatile var resetCount: Int = 0
        var gate: CompletableDeferred<Unit>? = null
        var failWith: Throwable? = null
        override suspend fun resetAll() {
            gate?.await()
            failWith?.let { throw it }
            resetCount++
            signalResetComplete()
        }
    }

    private fun buildResetService(
        prefs: AppPrefs = this.prefs,
    ): RecordingResetService {
        val db = androidx.room.Room
            .inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return RecordingResetService(context, db, prefs, buildController())
    }

    private fun buildVm(
        prefs: AppPrefs = this.prefs,
        controller: ContentObserverController = buildController(),
        contactRepo: ContactRepository = EmptyIgnoredContactRepository,
        resetService: ResetService = buildResetService(prefs),
    ): SettingsViewModel =
        SettingsViewModel(
            context = context,
            appPrefs = prefs,
            contentObserverController = controller,
            contactRepo = contactRepo,
            resetService = resetService,
            clock = clock,
            appScope = appScope,
        )

    private suspend fun StateFlow<SettingsUiState>.awaitReady(): SettingsUiState.Ready =
        (this as Flow<SettingsUiState>).filterIsInstance<SettingsUiState.Ready>().first()

    private suspend fun StateFlow<SettingsUiState>.awaitReady(
        matching: (SettingsUiState.Ready) -> Boolean,
    ): SettingsUiState.Ready = withTimeout(30_000L) {
        (this@awaitReady as Flow<SettingsUiState>).filterIsInstance<SettingsUiState.Ready>().filter(matching).first()
    }

    private fun grant(vararg permissions: String) {
        Shadows.shadowOf(context as Application).grantPermissions(*permissions)
    }

    private fun idleMain() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    // ============================================================================
    // Defaults and the Loading gate.
    // ============================================================================

    @Test
    fun `fresh prefs emit Ready with defaults`() = runTest {
        val vm = buildVm()
        val ready = vm.uiState.awaitReady()
        assertEquals(90, ready.callLogImportDays)
        assertEquals(clock.now(), ready.now, "the sync rows word their time against the state's clock")
    }

    // Loading is the structural initialValue before the scheduler drains the
    // upstream pipeline (idiom: StandardTestDispatcher + synchronous
    // StateFlow.value read).
    @Test
    fun `initial StateFlow value is Loading before scheduler drains`() {
        mainDispatcherRule.withMainDispatcher(StandardTestDispatcher()) {
            val vm = buildVm()
            assertEquals(
                SettingsUiState.Loading,
                vm.uiState.value,
                "stateIn(initialValue = Loading) contract: first observable value",
            )
        }
    }

    // Robolectric grants no permission by default; with
    // shouldShowRequestPermissionRationale=false (no Activity bound) the raw
    // reading is PermanentlyDenied, and never-asked resolves it to Denied
    // (SET-12). We assert "not Granted" + numeric defaults.
    @Test
    fun `default state has 90-day import and not-granted permission`() = runTest {
        val vm = buildVm()
        val ready = vm.uiState.awaitReady()
        assertEquals(90, ready.callLogImportDays)
        assertTrue(
            ready.callLogPermissionState !is CallLogPermissionState.Granted,
            "default permission must not be Granted (Robolectric default)",
        )
        assertEquals(false, ready.callLogSyncInFlight)
    }

    // ============================================================================
    // SET-11: a failing source shows Error, and Try again recovers.
    // ============================================================================

    @Test
    fun `a failing source shows Error, and Retry recovers`() = runBlocking {
        val vm = buildVm(contactRepo = FlakyIgnoredContactRepository())

        val error = withTimeout(30_000L) {
            vm.uiState.filterIsInstance<SettingsUiState.Error>().first()
        }
        assertEquals(SettingsUiState.Error, error)

        vm.onRetry()

        val ready = withTimeout(30_000L) { vm.uiState.awaitReady() }
        assertEquals(0, ready.ignoredContactCount)
    }

    // ============================================================================
    // Row subtitles and appearance write-throughs.
    // ============================================================================

    // "{N} ignored" equals the rows the Ignored screen lists: both read
    // observeIgnored(), which excludes archived people.
    @Test
    fun `ignored count matches the ignored query and skips archived people`() = runTest {
        val repo = FakeContactRepository(
            listOf(
                contactFixture(id = 1L, isIgnored = true),
                contactFixture(id = 2L, isIgnored = true),
                contactFixture(id = 3L, isIgnored = true, isArchived = true),
            ),
        )
        val vm = buildVm(contactRepo = repo)
        val ready = vm.uiState.awaitReady()
        assertEquals(2, ready.ignoredContactCount)
        assertEquals(repo.observeIgnored().first().size, ready.ignoredContactCount)
    }

    @Test
    fun `appearance choices persist and read back, and a negative hue reads as no override`() = runBlocking {
        val vm = buildVm()
        vm.uiState.awaitReady()

        vm.onSelectTheme(OrbitThemeId.PLUM)
        vm.onSelectDarkMode(OrbitDarkMode.DARK)
        vm.onAccentHue(200)
        val custom = vm.uiState.awaitReady { it.colorTheme == OrbitThemeId.PLUM && it.darkMode == OrbitDarkMode.DARK && it.accentHue == 200 }
        assertEquals(200, custom.accentHue)

        // null clears the override: AppPrefs stores -1, the VM maps it back to null.
        vm.onAccentHue(null)
        val cleared = vm.uiState.awaitReady { it.accentHue == null }
        assertEquals(OrbitThemeId.PLUM, cleared.colorTheme, "clearing the hue keeps the theme")
    }

    // ============================================================================
    // SET-12: "Off in your phone's settings" only after the OS was asked once.
    // ============================================================================

    @Test
    fun `a permission never asked for reads Denied, and PermanentlyDenied once asked`() = runBlocking {
        val vm = buildVm()
        // The screen's ON_RESUME refresh: not granted, no rationale (the OS
        // cannot tell never-asked from refused-for-good).
        vm.refreshAllPermissionStates(callLogRationale = false, contactsRationale = false, notifsRationale = false)

        val fresh = vm.uiState.awaitReady()
        assertEquals(PermissionStatus.Denied, fresh.contactsPermissionState, "never asked: the row offers Allow")
        assertEquals(CallLogPermissionState.Denied, fresh.callLogPermissionState)
        assertEquals(PermissionStatus.Denied, fresh.notificationsPermissionState)

        // The launcher fired once for Contacts; the OS still reports no rationale.
        vm.onLauncherFired(android.Manifest.permission.READ_CONTACTS)

        val asked = vm.uiState.awaitReady { it.contactsPermissionState == PermissionStatus.PermanentlyDenied }
        assertEquals(CallLogPermissionState.Denied, asked.callLogPermissionState, "the call log was never asked")
        assertEquals(PermissionStatus.Denied, asked.notificationsPermissionState, "notifications were never asked")
    }

    @Test
    fun `a refusal that still allows a rationale reads Denied whether or not it was asked`() = runBlocking {
        val vm = buildVm()
        vm.onLauncherFired(android.Manifest.permission.READ_CALL_LOG)
        awaitValue(true) { prefs.hasAskedCallLog.first() }

        vm.refreshAllPermissionStates(callLogRationale = true, contactsRationale = true, notifsRationale = true)

        val ready = vm.uiState.awaitReady { it.callLogPermissionState == CallLogPermissionState.Denied }
        assertEquals(PermissionStatus.Denied, ready.contactsPermissionState)
    }

    // ============================================================================
    // SET-14: Notifications are Granted only when Android will show them.
    // ============================================================================

    @Test
    @Config(sdk = [31])
    fun `on 31 with the app's notifications switched off, the row reads PermanentlyDenied`() = runTest {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(nm).setNotificationsEnabled(false)
        try {
            val vm = buildVm()
            val ready = vm.uiState.awaitReady()
            // No runtime permission below 33, so there is nothing to ask for:
            // the only way back on is the phone's settings.
            assertEquals(PermissionStatus.PermanentlyDenied, ready.notificationsPermissionState)
        } finally {
            Shadows.shadowOf(nm).setNotificationsEnabled(true)
        }
    }

    @Test
    @Config(sdk = [31])
    fun `on 31 with notifications enabled, the row reads Granted`() = runTest {
        val vm = buildVm()
        assertEquals(PermissionStatus.Granted, vm.uiState.awaitReady().notificationsPermissionState)
    }

    @Test
    fun `on 33 the permission held but notifications switched off reads PermanentlyDenied even if never asked`() = runTest {
        grant(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(nm).setNotificationsEnabled(false)
        try {
            val vm = buildVm()
            val ready = vm.uiState.awaitReady()
            assertEquals(PermissionStatus.PermanentlyDenied, ready.notificationsPermissionState)
        } finally {
            Shadows.shadowOf(nm).setNotificationsEnabled(true)
        }
    }

    @Test
    fun `on 33 the permission held and notifications enabled reads Granted`() = runTest {
        grant(android.Manifest.permission.POST_NOTIFICATIONS)
        val vm = buildVm()
        assertEquals(PermissionStatus.Granted, vm.uiState.awaitReady().notificationsPermissionState)
    }

    // ============================================================================
    // Call-log Settings UI tests (CALL-01/02/04/06).
    // ============================================================================

    // onPermissionResult(Granted) starts the observer and enqueues a full
    // resync, on the caller's thread, before it returns.
    @Test
    fun `onPermissionResult Granted starts the observer and enqueues a full resync`() = runBlocking {
        val controller = CountingController(context)
        val vm = buildVm(controller = controller)

        vm.onPermissionResult(CallLogPermissionState.Granted)

        assertEquals(1, controller.startCount, "start() must run on grant (Pitfall 5)")
        assertEquals(true, controller.syncRequests.tryReceive().getOrNull(), "the grant imports the window: fullResync = true")
    }

    // onImportDaysChanged writes through to AppPrefs and the value is
    // observable via the prefs flow. runBlocking (real time): DataStore writes
    // hop to a real IO dispatcher that does not cooperate with runTest's
    // virtual scheduler.
    @Test
    fun `onImportDaysChanged writes through to prefs`() = runBlocking {
        val vm = buildVm()

        vm.onImportDaysChanged(30)
        awaitValue(30) { prefs.callLogImportDays.first() }

        vm.onImportDaysChanged(365)
        awaitValue(365) { prefs.callLogImportDays.first() }
        assertEquals(365, prefs.callLogImportDays.first())
    }

    // SET-04: widening the window with the call log readable runs a full
    // resync (the incremental worker would never reach back); narrowing does
    // not. The request is observed on the controller's channel, which is fed
    // AFTER the write, so the assertion cannot run ahead of the side effect.
    @Test
    fun `widening the import window with the call log readable runs a full resync, narrowing does not`() = runBlocking {
        grant(android.Manifest.permission.READ_CALL_LOG)
        val controller = CountingController(context)
        val vm = buildVm(controller = controller)
        assertTrue(vm.permissionState.value is CallLogPermissionState.Granted)

        // 90 → 30: narrowing. The write lands, nothing is enqueued.
        vm.onImportDaysChanged(30)
        awaitValue(30) { prefs.callLogImportDays.first() }

        // 30 → 365: widening.
        vm.onImportDaysChanged(365)
        val request = withTimeout(30_000L) { controller.syncRequests.receive() }
        assertEquals(true, request, "widening must run a FULL resync")
        assertTrue(controller.syncRequests.tryReceive().isFailure, "narrowing must not have enqueued anything")
    }

    @Test
    fun `widening the import window without the call log does not resync`() = runBlocking {
        val controller = CountingController(context)
        val vm = buildVm(controller = controller)

        vm.onImportDaysChanged(365)
        awaitValue(365) { prefs.callLogImportDays.first() }
        // Give any (wrong) enqueue a moment to land on the channel.
        delay(100)

        assertTrue(controller.syncRequests.tryReceive().isFailure, "no permission, no sync")
    }

    // onManualResync no-ops when permission state is not Granted, then
    // enqueues work after onPermissionResult(Granted) flips it. Uses the real
    // controller so the WorkManager record itself is asserted.
    @Test
    fun `onManualResync requires granted permission`() = runBlocking {
        val controller = buildController()
        val vm = buildVm(controller = controller)
        val wm = WorkManager.getInstance(context)

        // Default permission state is not-Granted, so resync should no-op.
        vm.onManualResync()
        idleMain()
        val worksBefore = wm
            .getWorkInfosForUniqueWork(ContentObserverController.UNIQUE_NAME_SYNC)
            .get()
        assertTrue(
            worksBefore.isEmpty(),
            "resync should not enqueue when permission not Granted; got=${worksBefore.map { it.state }}",
        )

        // Flip to Granted via the VM path (this also enqueues full-resync).
        vm.onPermissionResult(CallLogPermissionState.Granted)
        withTimeout(30_000L) {
            wm.getWorkInfosForUniqueWorkFlow(ContentObserverController.UNIQUE_NAME_SYNC)
                .filter { it.isNotEmpty() }
                .first()
        }

        // Trigger another resync: REPLACE policy keeps a single unique work.
        vm.onManualResync()
        idleMain()
        val worksAfter = wm
            .getWorkInfosForUniqueWork(ContentObserverController.UNIQUE_NAME_SYNC)
            .get()
        assertTrue(worksAfter.isNotEmpty(), "expected resync to enqueue when Granted")
        // Sanity check that every WorkInfo carries one of the known terminal
        // or in-flight states. Robolectric + SynchronousExecutor will usually
        // settle to SUCCEEDED before this read.
        val knownStates = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.CANCELLED,
        )
        worksAfter.forEach { info ->
            assertTrue(info.state in knownStates, "unexpected WorkInfo.state=${info.state}")
        }
    }

    // onManualContactsResync no-ops without READ_CONTACTS, and enqueues forced
    // contacts-ingest work once the permission is held. The VM reads
    // contacts-permission state from the OS at construction, so the grant is
    // applied before buildVm.
    @Test
    fun `onManualContactsResync requires granted contacts permission`() = runBlocking {
        val wm = WorkManager.getInstance(context)

        // Default (denied): a VM built without the grant should no-op.
        val deniedVm = buildVm()
        deniedVm.onManualContactsResync()
        idleMain()
        val before = wm
            .getWorkInfosForUniqueWork(ContactsIngestWorker.UNIQUE_NAME)
            .get()
        assertTrue(
            before.isEmpty(),
            "contacts resync must not enqueue without permission; got=${before.map { it.state }}",
        )

        // Grant READ_CONTACTS, then a freshly-built VM resolves Granted and enqueues.
        grant(android.Manifest.permission.READ_CONTACTS)
        val grantedVm = buildVm()
        grantedVm.onManualContactsResync()
        withTimeout(30_000L) {
            wm.getWorkInfosForUniqueWorkFlow(ContactsIngestWorker.UNIQUE_NAME)
                .filter { it.isNotEmpty() }
                .first()
        }
        val after = wm
            .getWorkInfosForUniqueWork(ContactsIngestWorker.UNIQUE_NAME)
            .get()
        assertTrue(after.isNotEmpty(), "expected contacts ingest to enqueue when Granted")
    }

    // ============================================================================
    // Permission transitions noticed on refresh (SET-07). A flip made in the
    // phone's settings while Orbit was backgrounded behaves like one made from
    // the row: a revocation stops the observer, a grant starts it and imports.
    // ============================================================================

    @Test
    fun `onPermissionResult Denied stops the observer`() {
        val controller = CountingController(context)
        val vm = buildVm(controller = controller)

        vm.onPermissionResult(CallLogPermissionState.Denied)

        assertEquals(1, controller.stopCount, "stop() must be called on Denied result")
    }

    @Test
    fun `onPermissionResult PermanentlyDenied stops the observer`() {
        val controller = CountingController(context)
        val vm = buildVm(controller = controller)

        vm.onPermissionResult(CallLogPermissionState.PermanentlyDenied)

        assertEquals(1, controller.stopCount, "stop() must be called on PermanentlyDenied result")
    }

    @Test
    fun `refreshPermissionState Granted to Denied stops the observer`() {
        val controller = CountingController(context)
        val vm = buildVm(controller = controller)

        // Drive prior state to Granted via the public path.
        vm.onPermissionResult(CallLogPermissionState.Granted)
        val stopBefore = controller.stopCount

        // Robolectric default: READ_CALL_LOG is not granted. With
        // rationalePending=true the VM resolves Denied, that is a Granted→Denied
        // transition that must trigger cleanup.
        vm.refreshPermissionState(rationalePending = true)

        assertEquals(stopBefore + 1, controller.stopCount, "stop() must be called on Granted→Denied transition")
    }

    @Test
    fun `a call-log grant noticed on refresh starts the observer and runs a full resync`() {
        val controller = CountingController(context)
        val vm = buildVm(controller = controller)
        vm.refreshAllPermissionStates(callLogRationale = false, contactsRationale = false, notifsRationale = false)
        assertEquals(0, controller.startCount)

        // The user allowed the call log in the phone's settings and came back.
        grant(android.Manifest.permission.READ_CALL_LOG)
        vm.refreshAllPermissionStates(callLogRationale = false, contactsRationale = false, notifsRationale = false)

        assertEquals(1, controller.startCount, "a grant noticed on resume registers the observer")
        assertEquals(true, controller.syncRequests.tryReceive().getOrNull(), "and imports the window")

        // Already Granted: a second refresh is not a second import.
        vm.refreshAllPermissionStates(callLogRationale = false, contactsRationale = false, notifsRationale = false)
        assertEquals(1, controller.startCount)
        assertTrue(controller.syncRequests.tryReceive().isFailure)
    }

    @Test
    fun `a contacts grant noticed on refresh starts the observer and runs one ingest`() = runBlocking {
        val controller = CountingController(context)
        val vm = buildVm(controller = controller)
        val wm = WorkManager.getInstance(context)
        vm.refreshAllPermissionStates(callLogRationale = false, contactsRationale = false, notifsRationale = false)
        assertEquals(0, controller.startCount)
        assertTrue(wm.getWorkInfosForUniqueWork(ContactsIngestWorker.UNIQUE_NAME).get().isEmpty())

        // The user allowed Contacts (from the row's launcher, or in the phone's
        // settings) and the screen refreshed.
        grant(android.Manifest.permission.READ_CONTACTS)
        vm.refreshAllPermissionStates(callLogRationale = false, contactsRationale = false, notifsRationale = false)

        assertEquals(1, controller.startCount, "a Contacts grant registers the contacts observer")
        withTimeout(30_000L) {
            wm.getWorkInfosForUniqueWorkFlow(ContactsIngestWorker.UNIQUE_NAME).filter { it.isNotEmpty() }.first()
        }

        // Already Granted: a second refresh does not re-register.
        vm.refreshAllPermissionStates(callLogRationale = false, contactsRationale = false, notifsRationale = false)
        assertEquals(1, controller.startCount)
    }

    // ============================================================================
    // Reset (SET-06). After ResetService.resetAll() returns, resetCompleteEvents
    // fires so the screen can restart the task into onboarding (the user must
    // not be stranded in a ghost app). The reset runs on the app scope, so it
    // finishes even when the ViewModel that started it is cleared mid-way; a
    // failure is told to the user.
    // ============================================================================

    @Test
    fun `onResetConfirmed runs reset and emits completion event`() = runBlocking {
        val resetService = buildResetService()
        val vm = buildVm(resetService = resetService)

        // Subscribe BEFORE triggering: resetCompleteEvents has no replay.
        val received = async {
            withTimeout(30_000L) { vm.resetCompleteEvents.first() }
        }
        // Let the collector attach before the emit races it.
        delay(50)

        vm.onResetConfirmed()

        received.await()
        assertEquals(1, resetService.resetCount, "resetAll must run exactly once")
    }

    @Test
    fun `a reset outlives the ViewModel that started it`() = runBlocking {
        val resetService = buildResetService().apply { gate = CompletableDeferred() }
        val vm = buildVm(resetService = resetService)
        val received = async {
            withTimeout(30_000L) { resetService.resetCompleteEvents.first() }
        }
        delay(50)

        vm.onResetConfirmed()
        // The user leaves Settings while the tables are being cleared:
        // ViewModelStore.clear() cancels viewModelScope.
        ViewModelStore().apply {
            put("settings", vm)
            clear()
        }
        delay(50)
        assertEquals(0, resetService.resetCount, "the reset is still held at the gate")

        resetService.gate!!.complete(Unit)

        received.await()
        assertEquals(1, resetService.resetCount, "the reset must finish after the ViewModel is gone")
    }

    @Test
    fun `a failing reset tells the user`() = runBlocking {
        val resetService = buildResetService().apply { failWith = IllegalStateException("db locked") }
        val vm = buildVm(resetService = resetService)
        val snackbar = async {
            withTimeout(30_000L) { vm.snackbarEvents.first() }
        }
        delay(50)

        vm.onResetConfirmed()

        assertEquals(UiText.res(R.string.settings_reset_failed), snackbar.await())
        assertEquals(0, resetService.resetCount)
    }
}
