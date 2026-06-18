package app.orbit.ui.screens.onboarding

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.domain.FakeCallEventRepository
import app.orbit.testutil.MainDispatcherRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [OnboardingSyncViewModel] (ONB-16/17/18).
 *
 * Under Robolectric, READ_CALL_LOG is NOT granted by default. That makes the
 * `SyncState.Skipped` branch the deterministic, host-independent path: `init`
 * must NOT start the observer or enqueue any sync work, and the gate must
 * resolve to [SyncState.Skipped] so the advertised "Continue without it" path
 * never dead-ends. The WorkManager-state-dependent branches (InProgress /
 * Succeeded / Empty / Failed) require live WorkInfo transitions and are pinned
 * by on-device/instrumented coverage; the JVM tests here lock in the
 * permission-denied gate and the `onRetry` no-op guard.
 *
 * Fixture pattern mirrors SettingsViewModelTest:
 *   - Robolectric + real DataStore via `ApplicationProvider.getApplicationContext()`.
 *   - `@Config(application = Application::class)` bypasses `OrbitApp.onCreate`.
 *   - Test WorkManager on a `SynchronousExecutor` — the VM reads
 *     `getWorkInfosForUniqueWorkFlow` in a field initializer, so the WorkManager
 *     singleton must exist before construction.
 *   - [CountingController] subclasses the `open` [ContentObserverController] to
 *     count `start()` without registering a real ContentObserver.
 *   - Hand-rolled [FakeCallEventRepository]; no mockk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OnboardingSyncViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: android.content.Context get() =
        ApplicationProvider.getApplicationContext()

    /**
     * Counts `start()` without touching the real ContentResolver.
     * [ContentObserverController] is an `open class` with `open fun start/stop`
     * for exactly this seam (see SettingsViewModelTest.CountingController).
     */
    private class CountingController(ctx: android.content.Context) :
        ContentObserverController(ctx) {
        var startCount: Int = 0
        var stopCount: Int = 0
        override fun start() { startCount++ }
        override fun stop() { stopCount++ }
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
    fun clearDataStore() {
        runBlocking {
            val prefs = AppPrefs(context)
            prefs.setLastCallLogSyncAt(0L)
        }
        val prefsDir = java.io.File(context.filesDir.parentFile, "datastore")
        if (prefsDir.exists()) prefsDir.deleteRecursively()
    }

    private fun buildVm(
        controller: ContentObserverController,
        callEventRepo: FakeCallEventRepository = FakeCallEventRepository(),
    ): OnboardingSyncViewModel =
        OnboardingSyncViewModel(
            context = context,
            appPrefs = AppPrefs(context),
            controller = controller,
            callEventRepo = callEventRepo,
        )

    // ============================================================================
    // Test 1 — READ_CALL_LOG not granted (Robolectric default): init must not
    // start the observer (no permission to observe yet).
    // ============================================================================

    @Test
    fun `init does not start observer when call-log permission is denied`() {
        val controller = CountingController(context)
        buildVm(controller)
        assertEquals(0, controller.startCount, "init must not start the observer without permission")
    }

    // ============================================================================
    // Test 2 — the gate resolves to Skipped when permission is denied, so
    // Continue stays enabled and the "Continue without it" path never
    // dead-ends.
    // ============================================================================

    @Test
    fun `gate is Skipped when call-log permission is denied`() = runBlocking {
        val controller = CountingController(context)
        val vm = buildVm(controller)

        val ready = withTimeout(3_000L) {
            (vm.uiState as Flow<OnboardingSyncUiState>)
                .filterIsInstance<OnboardingSyncUiState.Ready>()
                .first()
        }
        assertEquals(SyncState.Skipped, ready.syncState)
    }

    // ============================================================================
    // Test 3 — onRetry() is a no-op when permission is denied: it must neither
    // enqueue sync work nor touch the observer. (The retry CTA is reachable
    // only from the Failed state, which the granted path drives — but the guard
    // must hold regardless.)
    // ============================================================================

    @Test
    fun `onRetry no-ops when call-log permission is denied`() = runBlocking {
        val controller = CountingController(context)
        val vm = buildVm(controller)
        val wm = WorkManager.getInstance(context)

        vm.onRetry()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val works = wm
            .getWorkInfosForUniqueWork(ContentObserverController.UNIQUE_NAME_SYNC)
            .get()
        assertTrue(
            works.isEmpty(),
            "onRetry must not enqueue sync work without permission; got=${works.map { it.state }}",
        )
        assertEquals(0, controller.startCount, "onRetry must not start the observer")
    }

    // ============================================================================
    // Test 4 — the Ready snapshot folds the (empty) aggregate to zero counts
    // when no events are seeded; the Skipped gate still carries call/contact
    // counts of 0.
    // ============================================================================

    @Test
    fun `ready carries zero counts with an empty aggregate`() = runBlocking {
        val controller = CountingController(context)
        val vm = buildVm(controller, FakeCallEventRepository())

        val ready = withTimeout(3_000L) {
            (vm.uiState as Flow<OnboardingSyncUiState>)
                .filterIsInstance<OnboardingSyncUiState.Ready>()
                .first()
        }
        assertEquals(0, ready.callCount)
        assertEquals(0, ready.contactCount)
    }
}
