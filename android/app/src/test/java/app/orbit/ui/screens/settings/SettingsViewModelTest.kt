package app.orbit.ui.screens.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.orbit.calllog.CallLogPermissionState
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.dao.PreIgnoreSnapshot
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ResetService
import app.orbit.testutil.MainDispatcherRule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [SettingsViewModel].
 *
 * Fixture pattern:
 *   - Robolectric's [ApplicationProvider.getApplicationContext] supplies a real
 *     [android.content.Context] so the production [AppPrefs] constructor runs
 *     unchanged.
 *   - DataStore auto-creates its backing preferences file under the
 *     Robolectric-managed files dir; the `@After` hook wipes it so tests
 *     don't leak persisted state across @Test methods.
 *   - [MainDispatcherRule] swaps `Dispatchers.Main` for `UnconfinedTestDispatcher`
 *     so `viewModelScope` + `stateIn` run on the test dispatcher.
 *
 * Pattern divergence from CardViewViewModelTest: DataStore's
 * internal IO dispatcher is NOT swapped by `MainDispatcherRule`, so the first
 * Turbine emission is Loading (the stateIn initialValue), followed by the
 * terminal Ready when DataStore delivers its first read. Tests 1–3 use
 * `uiState.filterIsInstance<Ready>().first()` to skip the Loading gate
 * deterministically; Test 4 exercises the Loading invariant in isolation via
 * StandardTestDispatcher + direct StateFlow.value snapshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Stock Application class — avoids OrbitApp.onCreate (which schedules
// WorkManager + the Hilt graph that isn't set up for JVM tests). We only
// need the Context to back AppPrefs' DataStore.
@Config(sdk = [33], application = Application::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: android.content.Context get() =
        ApplicationProvider.getApplicationContext()

    private fun buildAppPrefs(): AppPrefs = AppPrefs(context)

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

    private fun buildController(): ContentObserverController =
        ContentObserverController(context)

    /**
     * Test double for [ContentObserverController] that counts start/stop
     * invocations without registering against the real ContentResolver.
     * ContentObserverController is an `open class` + `open fun start/stop` to
     * enable this subclass.
     */
    private class CountingController(ctx: android.content.Context) :
        ContentObserverController(ctx) {
        var startCount: Int = 0
        var stopCount: Int = 0
        override fun start() { startCount++ }
        override fun stop() { stopCount++ }
    }

    /**
     * Fixture support — minimal [ContactRepository] stub that emits an empty
     * ignored list. The Settings combine folds
     * `contactRepo.observeIgnored().map { it.size }` to drive the "{N} ignored"
     * subtitle. Settings tests never assert on the count, so the empty-flow
     * stub is sufficient — the live VM still combines the value, the
     * assertion-relevant fields (digest / call-log) propagate unchanged.
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

    /**
     * Minimal [ResetService] stub. Most Settings tests do
     * not exercise the destructive-reset path; this anonymous subclass
     * overrides [resetAll] to a counting no-op so we do not need to stand up a
     * real [OrbitDatabase] fixture (the full reset behavior is pinned by
     * [app.orbit.data.repository.ResetServiceTest]). The constructor still
     * requires real dependency instances per the [ResetService] @Inject
     * signature — it takes the application Context and the
     * [ContentObserverController].
     */
    private class RecordingResetService(
        ctx: android.content.Context,
        db: OrbitDatabase,
        prefs: AppPrefs,
        controller: ContentObserverController,
    ) : ResetService(
        context = ctx,
        database = db,
        appPrefs = prefs,
        contentObserverController = controller,
    ) {
        var resetCount: Int = 0
        override suspend fun resetAll() { resetCount++ }
    }

    private fun buildResetService(
        prefs: AppPrefs = buildAppPrefs(),
    ): RecordingResetService {
        val db = androidx.room.Room
            .inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return RecordingResetService(context, db, prefs, buildController())
    }

    private fun buildVm(
        prefs: AppPrefs = buildAppPrefs(),
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
        )

    @After
    fun clearDataStore() {
        // DataStore persists to the Robolectric file system AND caches a
        // process-wide singleton per Context+name (see AppViewModelTest for
        // the same pattern). Explicitly reset every flag this class writes
        // before wiping the on-disk file so neighbouring test classes see
        // fresh defaults regardless of runner ordering. The daily-digest-hour
        // key was dropped — only call-log keys remain.
        runBlocking {
            val prefs = buildAppPrefs()
            prefs.setCallLogImportDays(90)
            prefs.setCallLogSyncEnabled(false)
        }
        val prefsDir = java.io.File(context.filesDir.parentFile, "datastore")
        if (prefsDir.exists()) prefsDir.deleteRecursively()
    }

    private suspend fun StateFlow<SettingsUiState>.awaitReady(): SettingsUiState.Ready =
        (this as Flow<SettingsUiState>).filterIsInstance<SettingsUiState.Ready>().first()

    // ============================================================================
    // Test 1 — fresh AppPrefs emits Ready with AppPrefs defaults
    // (call-log import 90 days; permissions not granted under Robolectric).
    // The daily-digest-hour assertion (Test 1's digest variant + Test 3
    // entirely) was retired when the field was dropped.
    // ============================================================================

    @Test
    fun `fresh prefs emit Ready with defaults`() = runTest {
        val vm = buildVm()
        val ready = vm.uiState.awaitReady()
        assertEquals(90, ready.callLogImportDays)
    }

    // ============================================================================
    // Test 4 — Loading is the structural initialValue before the scheduler
    // drains the upstream pipeline (idiom: StandardTestDispatcher +
    // synchronous StateFlow.value read).
    // ============================================================================

    @Test
    fun `initial StateFlow value is Loading before scheduler drains`() {
        mainDispatcherRule.withMainDispatcher(StandardTestDispatcher()) {
            val vm = buildVm()
            assertEquals(
                SettingsUiState.Loading,
                vm.uiState.value,
                "stateIn(initialValue = Loading) contract — first observable value",
            )
        }
    }

    // ============================================================================
    // Call-log Settings UI tests (CALL-01/02/04/06).
    // ============================================================================

    // Test 5 — default state reflects 90-day window + Denied permission +
    // sync-not-in-flight. Robolectric grants no permission by default; with
    // shouldShowRequestPermissionRationale=false (no Activity bound), the VM
    // computes PermanentlyDenied. We assert "not Granted" + numeric defaults.
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

    // Test 6 — onPermissionResult(Granted) writes isCallLogSyncEnabled=true
    // AND enqueues full-resync work via the unique-work name.
    //
    // Uses `runBlocking` (real time) — DataStore writes hop to a real IO
    // dispatcher that does NOT cooperate with `runTest`'s virtual scheduler
    // (known lesson: "Turbine's test(timeout) uses real time, not runTest
    // virtual clock; withTimeout collapses under virtual clock").
    //
    // We wait for BOTH a non-empty WorkInfo emission (proves the enqueue
    // happened) and the prefs flag flip (proves the side-effect chain ran)
    // — observing each side independently avoids racing on the fact that
    // setCallLogSyncEnabled + enqueueImmediateSync run sequentially in the
    // same launch but resume on different dispatchers.
    @Test
    fun `onPermissionResult Granted enables sync and enqueues work`() = runBlocking {
        val prefs = buildAppPrefs()
        val controller = buildController()
        val vm = buildVm(prefs, controller)
        val wm = WorkManager.getInstance(context)

        vm.onPermissionResult(CallLogPermissionState.Granted)

        // Wait for the WorkManager flow to surface at least one WorkInfo.
        kotlinx.coroutines.withTimeout(3_000L) {
            wm.getWorkInfosForUniqueWorkFlow(ContentObserverController.UNIQUE_NAME_SYNC)
                .filter { it.isNotEmpty() }
                .first()
        }

        // Wait for the prefs flow to settle on `true`.
        kotlinx.coroutines.withTimeout(3_000L) {
            prefs.isCallLogSyncEnabled.filter { it }.first()
        }

        // Sanity: idle the main looper so any straggler work transitions
        // settle before we read the synchronous WorkInfo list.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val works = wm
            .getWorkInfosForUniqueWork(ContentObserverController.UNIQUE_NAME_SYNC)
            .get()
        assertTrue(works.isNotEmpty(), "expected at least one WorkInfo for unique work")
    }

    // Test 7 — onImportDaysChanged writes through to AppPrefs and the value
    // is observable via the prefs flow. See Test 6 for the runBlocking
    // rationale (DataStore + virtual time don't mix).
    @Test
    fun `onImportDaysChanged writes through to prefs`() = runBlocking {
        val prefs = buildAppPrefs()
        val vm = buildVm(prefs)

        vm.onImportDaysChanged(30)
        delay(100)
        assertEquals(30, prefs.callLogImportDays.first())

        vm.onImportDaysChanged(365)
        delay(100)
        assertEquals(365, prefs.callLogImportDays.first())
    }

    // Test 8 — onManualResync no-ops when permission state is not Granted,
    // then enqueues work after onPermissionResult(Granted) flips it. See
    // Test 6 for the runBlocking + flow-wait rationale.
    @Test
    fun `onManualResync requires granted permission`() = runBlocking {
        val prefs = buildAppPrefs()
        val controller = buildController()
        val vm = buildVm(prefs, controller)
        val wm = WorkManager.getInstance(context)

        // Default permission state is not-Granted — resync should no-op.
        vm.onManualResync()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val worksBefore = wm
            .getWorkInfosForUniqueWork(ContentObserverController.UNIQUE_NAME_SYNC)
            .get()
        assertTrue(
            worksBefore.isEmpty(),
            "resync should not enqueue when permission not Granted; got=${worksBefore.map { it.state }}",
        )

        // Flip to Granted via the VM path (this also enqueues full-resync).
        vm.onPermissionResult(CallLogPermissionState.Granted)
        kotlinx.coroutines.withTimeout(3_000L) {
            wm.getWorkInfosForUniqueWorkFlow(ContentObserverController.UNIQUE_NAME_SYNC)
                .filter { it.isNotEmpty() }
                .first()
        }

        // Trigger another resync — REPLACE policy keeps a single unique work.
        vm.onManualResync()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
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

    // ============================================================================
    // Permission revocation cleanup. The grant path was already covered
    // by Test 6; these tests lock in the inverse contract: any Denied or
    // PermanentlyDenied result clears the persisted `isCallLogSyncEnabled` flag
    // AND stops the observer. Plus the OS-mediated revocation (user toggling via
    // system Settings while backgrounded) detected by `refreshPermissionState`.
    // ============================================================================

    @Test
    fun `onPermissionResult Denied clears pref and stops observer`() = runBlocking {
        val prefs = buildAppPrefs()
        val controller = CountingController(context)
        val vm = buildVm(prefs, controller)

        // Seed the "previously enabled" state so we can observe the flip-to-false.
        prefs.setCallLogSyncEnabled(true)

        vm.onPermissionResult(CallLogPermissionState.Denied)

        kotlinx.coroutines.withTimeout(3_000L) {
            prefs.isCallLogSyncEnabled.filter { !it }.first()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(false, prefs.isCallLogSyncEnabled.first())
        assertTrue(controller.stopCount >= 1, "stop() must be called on Denied result")
    }

    @Test
    fun `onPermissionResult PermanentlyDenied clears pref and stops observer`() = runBlocking {
        val prefs = buildAppPrefs()
        val controller = CountingController(context)
        val vm = buildVm(prefs, controller)
        // Seed the "previously enabled" state and wait for the DataStore
        // commit to land before triggering the VM. Without this await the
        // VM's clear-pref write can race the seed under suite contention —
        // if the clear lands first the seed eventually emits `true` and the
        // `.filter { !it }.first()` below times out (a documented flake from
        // an earlier seeding pattern).
        prefs.setCallLogSyncEnabled(true)
        kotlinx.coroutines.withTimeout(3_000L) {
            prefs.isCallLogSyncEnabled.filter { it }.first()
        }

        vm.onPermissionResult(CallLogPermissionState.PermanentlyDenied)

        kotlinx.coroutines.withTimeout(3_000L) {
            prefs.isCallLogSyncEnabled.filter { !it }.first()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(false, prefs.isCallLogSyncEnabled.first())
        assertTrue(controller.stopCount >= 1, "stop() must be called on PermanentlyDenied result")
    }

    @Test
    fun `refreshPermissionState Granted to Denied clears pref and stops observer`() = runBlocking {
        val prefs = buildAppPrefs()
        val controller = CountingController(context)
        val vm = buildVm(prefs, controller)

        // Drive prior state to Granted via the public path; this also seeds prefs=true.
        vm.onPermissionResult(CallLogPermissionState.Granted)
        kotlinx.coroutines.withTimeout(3_000L) {
            prefs.isCallLogSyncEnabled.filter { it }.first()
        }
        val stopBefore = controller.stopCount

        // Robolectric default: READ_CALL_LOG is not granted. With
        // rationalePending=true the VM resolves Denied — i.e. a Granted→Denied
        // transition that must trigger cleanup.
        vm.refreshPermissionState(rationalePending = true)

        kotlinx.coroutines.withTimeout(3_000L) {
            prefs.isCallLogSyncEnabled.filter { !it }.first()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(false, prefs.isCallLogSyncEnabled.first())
        assertTrue(
            controller.stopCount > stopBefore,
            "stop() must be called on Granted→Denied transition",
        )
    }

    // ============================================================================
    // Reset completion event. After ResetService.resetAll()
    // returns, the VM emits on resetCompleteEvents so the screen can restart
    // the task into onboarding (the user must not be stranded in a ghost app).
    // ============================================================================

    @Test
    fun `onResetConfirmed runs reset and emits completion event`() = runBlocking {
        val resetService = buildResetService()
        val vm = buildVm(resetService = resetService)

        // Subscribe BEFORE triggering — resetCompleteEvents has no replay.
        val received = async {
            kotlinx.coroutines.withTimeout(3_000L) { vm.resetCompleteEvents.first() }
        }
        // Let the collector attach before the emit races it.
        delay(50)

        vm.onResetConfirmed()

        received.await()
        assertEquals(1, resetService.resetCount, "resetAll must run exactly once")
    }
}
