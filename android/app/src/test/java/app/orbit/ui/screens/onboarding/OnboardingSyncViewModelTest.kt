package app.orbit.ui.screens.onboarding

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.orbit.calllog.ContactsIngestWorker
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.repository.CallAgg
import app.orbit.data.repository.CallEventRepository
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.callEventFixture
import app.orbit.testutil.MainDispatcherRule
import app.orbit.testutil.awaitValue
import app.orbit.testutil.newPrefs
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * Behavioral tests for [OnboardingSyncViewModel] (ONB-16/17/18): the state
 * machine that keeps onboarding from dead-ending on the sync gate.
 *
 * Restored from the test 841a645 removed (it covered the permission-denied
 * guards only and sent the WorkManager branches to a device run that never
 * happened), then widened to every [SyncState] and to the contacts-ingest
 * ordering (onb-2). This supersedes 841a645's "cover on-device instead".
 *
 * Fixture:
 *   - Robolectric; `@Config(application = Application::class)` bypasses
 *     `OrbitApp.onCreate`.
 *   - READ_CALL_LOG is denied by default and granted per test with
 *     `ShadowApplication.grantPermissions` BEFORE the VM is built, because the
 *     VM reads the real permission in `init` (ARCH-04: no permission seam).
 *   - Test WorkManager on a `SynchronousExecutor`, so an enqueued request
 *     runs to its terminal state before `enqueue` returns and
 *     `getWorkInfosForUniqueWorkFlow` reports SUCCEEDED or FAILED.
 *   - [SyncController] subclasses the `open` [ContentObserverController]:
 *     `start()`/`stop()` count instead of touching the ContentResolver, and
 *     `enqueueImmediateSync` enqueues a trivial [Succeeds] or [Fails] worker
 *     under `UNIQUE_NAME_SYNC`. The real `CallLogSyncWorker` is a @HiltWorker
 *     the test WorkManager cannot construct (no HiltWorkerFactory).
 *   - Each method gets its own DataStore (`testutil/TestDataStore.kt`),
 *     cancelled in `@After`.
 *   - [FakeCallEventRepository] for the aggregates; no mockk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OnboardingSyncViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: AppPrefs by lazy { tmp.newPrefs(storeScope) }

    /** A stand-in for the sync worker that ends SUCCEEDED. Public: WorkManager builds it by reflection. */
    class Succeeds(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result = Result.success()
    }

    /** A stand-in that ends FAILED. */
    class Fails(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result = Result.failure()
    }

    /**
     * Counts `start()` and records each `enqueueImmediateSync`, then enqueues
     * [outcome] under the real unique name so the VM's WorkInfo flow sees it.
     */
    private class SyncController(
        private val ctx: Context,
        private val outcome: Class<out Worker>,
    ) : ContentObserverController(ctx) {
        var startCount: Int = 0
        val syncRequests: MutableList<Boolean> = mutableListOf()
        override fun start() { startCount++ }
        override fun stop() {}
        override fun enqueueImmediateSync(fullResync: Boolean) {
            syncRequests += fullResync
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_NAME_SYNC,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(outcome).build(),
            )
        }
    }

    /** The aggregates read fails on its first subscription, then passes (a Room error, once). */
    private class FlakyAggregates(private val inner: CallEventRepository) : CallEventRepository by inner {
        var failuresLeft: Int = 1
        override fun observeAggregatesAll(): Flow<Map<Long, CallAgg>> = flow {
            if (failuresLeft > 0) {
                failuresLeft--
                throw IllegalStateException("disk")
            }
            emitAll(inner.observeAggregatesAll())
        }
    }

    @Before
    fun initWorkManager() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun grantCallLog() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALL_LOG)
    }

    private fun buildVm(
        controller: ContentObserverController,
        callEventRepo: CallEventRepository = FakeCallEventRepository(),
    ): OnboardingSyncViewModel =
        OnboardingSyncViewModel(
            context = context,
            appPrefs = prefs,
            controller = controller,
            callEventRepo = callEventRepo,
        )

    private suspend fun StateFlow<OnboardingSyncUiState>.awaitReady(
        matching: (OnboardingSyncUiState.Ready) -> Boolean = { true },
    ): OnboardingSyncUiState.Ready = withTimeout(30_000L) {
        (this@awaitReady as Flow<OnboardingSyncUiState>)
            .filterIsInstance<OnboardingSyncUiState.Ready>()
            .filter(matching)
            .first()
    }

    private fun syncWorkStates(): List<WorkInfo.State> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ContentObserverController.UNIQUE_NAME_SYNC)
            .get()
            .map { it.state }

    // ============================================================================
    // (1) No READ_CALL_LOG (the Robolectric default): Skipped, and nothing was
    // started or enqueued. Idle would otherwise read as InProgress and hold
    // Continue disabled for ever, the "Continue without it" dead end.
    // ============================================================================

    @Test
    fun `without call-log permission the gate is Skipped and nothing is enqueued`() = runBlocking {
        val controller = SyncController(context, Succeeds::class.java)
        val vm = buildVm(controller)

        val ready = vm.uiState.awaitReady()

        assertEquals(SyncState.Skipped, ready.syncState)
        assertEquals(0, controller.startCount, "init must not start the observer without permission")
        assertTrue(controller.syncRequests.isEmpty(), "init must not enqueue a sync without permission")
        assertTrue(syncWorkStates().isEmpty())
        assertEquals(0, ready.callCount)
        assertEquals(0, ready.contactCount)

        // onRetry enqueues nothing either: the retry CTA is unreachable from
        // Skipped, but the guard must hold regardless (it still re-subscribes
        // the pipeline, see 6c).
        vm.onRetry()
        assertTrue(controller.syncRequests.isEmpty())
    }

    // ============================================================================
    // (2) Granted, the worker SUCCEEDED, no calls, and a sync has completed
    // before (lastCallLogSyncAt > 0): Empty, not InProgress (ONB-17).
    // ============================================================================

    @Test
    fun `a successful sync with no calls after a completed sync is Empty`() = runBlocking {
        grantCallLog()
        prefs.setLastCallLogSyncAt(1_700_000_000_000L)
        awaitValue(1_700_000_000_000L) { prefs.lastCallLogSyncAt.first() }
        val controller = SyncController(context, Succeeds::class.java)

        val vm = buildVm(controller)
        val ready = vm.uiState.awaitReady { it.syncState != SyncState.InProgress }

        assertEquals(SyncState.Empty, ready.syncState)
        assertEquals(1, controller.startCount, "init registers the observer once permission is held")
        assertEquals(listOf(true), controller.syncRequests, "init runs one full resync")
        assertEquals(listOf(WorkInfo.State.SUCCEEDED), syncWorkStates())
    }

    // ============================================================================
    // (3) Granted, SUCCEEDED, with rows: Succeeded, and the counts fold the
    // aggregate (sum of counts, distinct people).
    // ============================================================================

    @Test
    fun `a successful sync with calls is Succeeded with the folded counts`() = runBlocking {
        grantCallLog()
        val events = FakeCallEventRepository(
            listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = Instant.parse("2026-10-01T10:00:00Z")),
                callEventFixture(id = 2L, contactId = 1L, occurredAt = Instant.parse("2026-10-02T10:00:00Z")),
                callEventFixture(id = 3L, contactId = 2L, occurredAt = Instant.parse("2026-10-03T10:00:00Z")),
            ),
        )
        val controller = SyncController(context, Succeeds::class.java)

        val vm = buildVm(controller, events)
        val ready = vm.uiState.awaitReady { it.syncState != SyncState.InProgress }

        assertEquals(SyncState.Succeeded, ready.syncState)
        assertEquals(3, ready.callCount)
        assertEquals(2, ready.contactCount)
        assertEquals(90, ready.importDays, "the default window until the user picks another")
    }

    // ============================================================================
    // (4) FAILED: Failed(0) with Try again; onRetry enqueues again and the
    // counter reaches 1, which flips the CTA to "Continue anyway" (ONB-18).
    // ============================================================================

    @Test
    fun `a failed sync is Failed and a retry counts and re-enqueues`() = runBlocking {
        grantCallLog()
        val controller = SyncController(context, Fails::class.java)

        val vm = buildVm(controller)
        val failed = vm.uiState.awaitReady { it.syncState is SyncState.Failed }
        assertEquals(SyncState.Failed(retryCount = 0), failed.syncState)
        assertEquals(listOf(true), controller.syncRequests)

        vm.onRetry()

        val failedAgain = vm.uiState.awaitReady { it.syncState == SyncState.Failed(retryCount = 1) }
        assertEquals(SyncState.Failed(retryCount = 1), failedAgain.syncState)
        assertEquals(listOf(true, true), controller.syncRequests, "the retry is a second full resync")
    }

    // ============================================================================
    // (5) Loading is the structural initial value before the scheduler drains
    // the pipeline (idiom: StandardTestDispatcher + a synchronous
    // StateFlow.value read, as SettingsViewModelTest does).
    // ============================================================================

    @Test
    fun `initial StateFlow value is Loading`() {
        mainDispatcherRule.withMainDispatcher(StandardTestDispatcher()) {
            val vm = buildVm(SyncController(context, Succeeds::class.java))
            assertEquals(OnboardingSyncUiState.Loading, vm.uiState.value)
        }
    }

    // ============================================================================
    // (6) A thrown read lands in the catch as Failed(retries) instead of an
    // uncaught exception, and Try again re-subscribes: the next read passes,
    // so the gate recovers to Succeeded. Fails without the
    // `_retryCount.flatMapLatest { ... .catch { } }` shape: a bare `.catch`
    // would terminate the pipeline and the retry could never recover.
    // ============================================================================

    @Test
    fun `a thrown read shows Failed and Try again recovers`() = runBlocking {
        grantCallLog()
        val controller = SyncController(context, Succeeds::class.java)
        val repo = FlakyAggregates(
            FakeCallEventRepository(
                listOf(callEventFixture(id = 1L, contactId = 1L, occurredAt = Instant.parse("2026-10-01T10:00:00Z"))),
            ),
        )

        val vm = buildVm(controller, repo)
        val failed = vm.uiState.awaitReady()
        assertEquals(SyncState.Failed(retryCount = 0), failed.syncState)
        assertEquals(0, failed.callCount)

        vm.onRetry()

        val recovered = vm.uiState.awaitReady { it.syncState !is SyncState.Failed && it.syncState != SyncState.InProgress }
        assertEquals(SyncState.Succeeded, recovered.syncState)
        assertEquals(1, recovered.callCount)
        assertEquals(0, repo.failuresLeft)
    }

    // ============================================================================
    // (6b) The same thrown read WITHOUT the permission is Skipped, not Failed:
    // the five flows are subscribed either way, and a Failed here would show a
    // Try again that enqueues nothing and a Continue that waits for a retry
    // count the guard never advanced (no back arrow on this step: a dead end,
    // ONB-18 and rules.md Code 3). Fails without the permission check in the
    // catch branch.
    // ============================================================================

    @Test
    fun `a thrown read without call-log permission is Skipped, not Failed`() = runBlocking {
        val controller = SyncController(context, Succeeds::class.java)
        val repo = FlakyAggregates(FakeCallEventRepository())

        val vm = buildVm(controller, repo)
        val ready = vm.uiState.awaitReady()

        assertEquals(SyncState.Skipped, ready.syncState)
        assertEquals(0, repo.failuresLeft, "the read did throw; the catch classified it")
        assertEquals(0, ready.callCount)
        assertTrue(controller.syncRequests.isEmpty(), "nothing to import without the permission")
    }

    // ============================================================================
    // (6c) Try again without the permission still re-subscribes: the counter
    // is the flatMapLatest key, so it must advance before the permission
    // guard; only the sync enqueue is gated. The recovered read carries one
    // call, so the second Ready differs from the catch's (a StateFlow drops an
    // equal value) and is the observable proof of a new subscription. Fails
    // with the bump below the guard: the catch emission is final and the
    // counted state never arrives.
    // ============================================================================

    @Test
    fun `Try again without call-log permission re-subscribes but enqueues nothing`() = runBlocking {
        val controller = SyncController(context, Succeeds::class.java)
        val repo = FlakyAggregates(
            FakeCallEventRepository(
                listOf(callEventFixture(id = 1L, contactId = 1L, occurredAt = Instant.parse("2026-10-01T10:00:00Z"))),
            ),
        )
        val vm = buildVm(controller, repo)
        val first = vm.uiState.awaitReady()
        assertEquals(SyncState.Skipped, first.syncState)
        assertEquals(0, first.callCount, "the catch has no counts to give")
        assertEquals(0, repo.failuresLeft)

        vm.onRetry()

        val recovered = vm.uiState.awaitReady { it.callCount == 1 }
        assertEquals(SyncState.Skipped, recovered.syncState)
        assertTrue(controller.syncRequests.isEmpty(), "the retry must not enqueue a sync without permission")
        assertTrue(syncWorkStates().isEmpty())
    }

    // ============================================================================
    // (7) Ordering (onb-2): while the contacts ingest is still on its first
    // run, a SUCCEEDED sync stays InProgress, because the ingest will ask for
    // a full resync once it has written people and the count is not final.
    // When the ingest finishes, the gate opens.
    // ============================================================================

    @Test
    fun `the gate waits for a pending contacts ingest, then opens`() = runBlocking {
        grantCallLog()
        val wm = WorkManager.getInstance(context)
        // An ingest that has not run yet: ENQUEUED, first attempt. The hour's
        // delay stands in for a large address book still being written.
        val ingest = OneTimeWorkRequestBuilder<Succeeds>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniqueWork(ContactsIngestWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, ingest).result.get()
        val controller = SyncController(context, Succeeds::class.java)

        val vm = buildVm(controller)
        val waiting = vm.uiState.awaitReady()
        assertEquals(listOf(WorkInfo.State.SUCCEEDED), syncWorkStates(), "the sync itself already finished")
        assertEquals(SyncState.InProgress, waiting.syncState, "but the gate holds while the ingest is pending")

        // The ingest runs to completion.
        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(ingest.id)

        val opened = vm.uiState.awaitReady { it.syncState != SyncState.InProgress }
        assertEquals(SyncState.Succeeded, opened.syncState)
    }

    // ============================================================================
    // (8) The wait is bounded: an ingest in retry backoff is ENQUEUED too, but
    // on a later attempt, and must not hold Continue disabled for as long as
    // the address book keeps failing. Pinned on the pure helper with
    // hand-built WorkInfos, since the test scheduler's backoff handling is
    // not what is under test.
    // ============================================================================

    @Test
    fun `an ingest in retry backoff does not hold the gate`() {
        fun info(state: WorkInfo.State, attempts: Int) =
            WorkInfo(UUID.randomUUID(), state, emptySet(), runAttemptCount = attempts)

        assertTrue(OnboardingSyncViewModel.ingestPending(listOf(info(WorkInfo.State.RUNNING, 0))))
        assertTrue(OnboardingSyncViewModel.ingestPending(listOf(info(WorkInfo.State.ENQUEUED, 0))))
        assertFalse(OnboardingSyncViewModel.ingestPending(listOf(info(WorkInfo.State.ENQUEUED, 1))), "backoff")
        assertFalse(OnboardingSyncViewModel.ingestPending(listOf(info(WorkInfo.State.SUCCEEDED, 1))))
        assertFalse(OnboardingSyncViewModel.ingestPending(listOf(info(WorkInfo.State.FAILED, 3))))
        assertFalse(OnboardingSyncViewModel.ingestPending(emptyList()), "no ingest was ever asked for")
    }
}
