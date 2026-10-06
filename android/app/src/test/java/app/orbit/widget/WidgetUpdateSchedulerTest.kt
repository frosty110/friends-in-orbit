package app.orbit.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WidgetUpdateScheduler] enqueue / cancel assertions — WIDGET-05, WIDGET-06.
 *
 * Test pattern mirrors [app.orbit.notify.ListPromptWorkerTest]: Robolectric +
 * WorkManagerTestInitHelper initialises an isolated WorkManager so enqueue/cancel
 * state is inspectable without actually running workers.
 *
 * @Config(sdk = [33]) targets Android 13, the minimum WorkManager-periodic SDK
 * where REQUIRE_DEVICE_IDLE is available.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WidgetUpdateSchedulerTest {

    private lateinit var context: Application
    private lateinit var wm: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        wm = WorkManager.getInstance(context)
    }

    // ─── WidgetUpdateScheduler enqueue / cancel ─────────────────────────────

    /** Enqueuing an immediate update work request uses KEEP policy. */
    @Test
    fun scheduleImmediate_enqueuesUniqueWorkWithKeepPolicy() {
        WidgetUpdateScheduler.scheduleImmediate(context)

        val infos = wm.getWorkInfosForUniqueWork(WidgetUpdateScheduler.UNIQUE_WORK).get()
        assertNotNull(infos, "work infos must not be null")
        assertEquals(1, infos.size, "exactly one work item must be enqueued")
        assertEquals(
            WorkInfo.State.ENQUEUED,
            infos.single().state,
            "work state must be ENQUEUED",
        )
    }

    /** Rapid successive calls coalesce into a single pending work item (KEEP policy). */
    @Test
    fun scheduleImmediate_rapidCallsCoalesceToOneWorker() {
        repeat(10) { WidgetUpdateScheduler.scheduleImmediate(context) }

        val infos = wm.getWorkInfosForUniqueWork(WidgetUpdateScheduler.UNIQUE_WORK).get()
        assertNotNull(infos, "work infos must not be null")
        assertEquals(
            1,
            infos.size,
            "KEEP policy: 10 rapid calls must coalesce to exactly 1 enqueued item",
        )
    }

    /** Scheduling a periodic update enqueues a unique periodic work item. */
    @Test
    fun schedulePeriodic_enqueuesUniquePeriodicWork() {
        WidgetUpdateScheduler.schedulePeriodic(context)

        val infos = wm.getWorkInfosForUniqueWork(WidgetUpdateScheduler.PERIODIC_WORK).get()
        assertNotNull(infos, "work infos must not be null")
        assertEquals(1, infos.size, "exactly one periodic work item must be enqueued")
        // The WorkManager test initializer may run the periodic work immediately,
        // so accept ENQUEUED or RUNNING — both confirm the periodic work was registered.
        val state = infos.single().state
        val registered = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
        assertEquals(true, registered, "periodic work state must be ENQUEUED or RUNNING (got $state)")
    }

    /**
     * refreshNow() (WIDGET-06, the reset path) replaces a pending debounced
     * refresh with an undelayed one on the same unique name, so the wiped
     * name leaves the home screen at once instead of in 30 seconds. The new
     * request has no delay, so the test executor may already have started
     * it: it is a different request, and it is never CANCELLED.
     */
    @Test
    fun refreshNow_replacesThePendingDebouncedRefreshWithAnUndelayedOne() {
        WidgetUpdateScheduler.scheduleImmediate(context)
        val debounced = wm.getWorkInfosForUniqueWork(WidgetUpdateScheduler.UNIQUE_WORK).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, debounced.state, "the debounced refresh waits on its delay")
        assertEquals(30_000L, debounced.initialDelayMillis, "WIDGET-05: the debounce is 30 seconds")

        WidgetUpdateScheduler.refreshNow(context)

        val infos = wm.getWorkInfosForUniqueWork(WidgetUpdateScheduler.UNIQUE_WORK).get()
        assertEquals(1, infos.size, "REPLACE leaves exactly one record on the unique name")
        assertNotEquals(debounced.id, infos.single().id, "the pending debounced refresh must be replaced")
        assertNotEquals(WorkInfo.State.CANCELLED, infos.single().state, "the undelayed refresh must be live")
        assertEquals(0L, infos.single().initialDelayMillis, "refreshNow must not wait out the debounce")
    }

    /** cancelAll() cancels both the one-time and periodic work names. */
    @Test
    fun cancelAll_cancelsBothWorkNames() {
        WidgetUpdateScheduler.scheduleImmediate(context)
        WidgetUpdateScheduler.schedulePeriodic(context)

        WidgetUpdateScheduler.cancelAll(context)

        val immediateInfos = wm.getWorkInfosForUniqueWork(WidgetUpdateScheduler.UNIQUE_WORK).get()
        val periodicInfos = wm.getWorkInfosForUniqueWork(WidgetUpdateScheduler.PERIODIC_WORK).get()

        // WorkManager test harness may return empty list or CANCELLED state after cancelUniqueWork.
        val immediateAllCancelled = immediateInfos.isEmpty() ||
            immediateInfos.all { it.state == WorkInfo.State.CANCELLED }
        val periodicAllCancelled = periodicInfos.isEmpty() ||
            periodicInfos.all { it.state == WorkInfo.State.CANCELLED }

        assertEquals(true, immediateAllCancelled, "immediate work must be CANCELLED after cancelAll")
        assertEquals(true, periodicAllCancelled, "periodic work must be CANCELLED after cancelAll")
    }
}
