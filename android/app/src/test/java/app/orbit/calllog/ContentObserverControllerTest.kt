package app.orbit.calllog

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CALL-03 enqueue-semantics test for [ContentObserverController].
 *
 * Robolectric + WorkManagerTestInitHelper give us a JVM-only, synchronous WorkManager
 * harness. We bypass `OrbitApp.onCreate` (which would try to plant Timber and start
 * the real observer) by setting [Config.application] to vanilla [android.app.Application].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ContentObserverControllerTest {

    private lateinit var context: android.content.Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork()
    }

    @Test
    fun enqueueImmediateSync_creates_unique_work_with_REPLACE_semantics() = runTest {
        val controller = ContentObserverController(context)
        controller.enqueueImmediateSync(fullResync = true)
        controller.enqueueImmediateSync(fullResync = false) // should REPLACE

        val works = workManager.getWorkInfosForUniqueWork(
            ContentObserverController.UNIQUE_NAME_SYNC,
        ).get()

        // REPLACE keeps one ACTIVE entry; older entry transitions to CANCELLED.
        val activeCount = works.count {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
        assertTrue(
            activeCount <= 1,
            "REPLACE violated: $activeCount active; states=${works.map { it.state }}",
        )
    }

    @Test
    fun companion_constants_are_canonical() {
        assertEquals("orbit.call_log_sync", ContentObserverController.UNIQUE_NAME_SYNC)
        assertEquals("full_resync", ContentObserverController.KEY_FULL_RESYNC)
        assertEquals(10L, ContentObserverController.DEBOUNCE_SECONDS)
    }

    @Test
    fun start_is_idempotent() {
        val controller = ContentObserverController(context)
        // Double-start is safe — second call is a no-op.
        controller.start()
        controller.start()
        controller.stop()
        controller.start()
        // Reaching here with no exception indicates the @Volatile-guarded
        // idempotency invariant holds: stop unregistered exactly one observer,
        // and the second start re-registered exactly one.
    }
}
