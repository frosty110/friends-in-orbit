package app.orbit.calllog

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * NOTIF-16's call-log trigger: [ContentObserverController.start] arms a
 * WorkManager content URI trigger on `CallLog.Calls.CONTENT_URI` only with
 * READ_CALL_LOG, [ContentObserverController.stop] disarms it, and
 * [CallLogTriggerWorker] re-arms itself and starts the ordinary sync each
 * time it runs. Whether Android really wakes a dead process for it is the
 * platform's part and is not run here (no device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CallLogTriggerWorkerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork()
    }

    private fun grantCallLog() =
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALL_LOG)

    private fun trigger(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(CallLogTriggerWorker.UNIQUE_NAME).get()
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }

    // ── arming ────────────────────────────────────────────────────────────────

    @Test
    fun start_withCallLogAccess_armsATriggerOnTheCallLog() {
        grantCallLog()

        ContentObserverController(context).start()

        val armed = trigger().single()
        val uriTrigger = armed.constraints.contentUriTriggers.single()
        assertEquals(CallLog.Calls.CONTENT_URI, uriTrigger.uri)
        assertTrue(uriTrigger.isTriggeredForDescendants)
    }

    @Test
    fun start_withoutCallLogAccess_armsNothing() {
        ContentObserverController(context).start()

        assertTrue(trigger().isEmpty(), "no permission, no wake-ups for a log Orbit cannot read")
    }

    @Test
    fun startingAgain_keepsTheOneArmedTrigger() {
        grantCallLog()
        val controller = ContentObserverController(context)

        controller.start()
        val first = trigger().single().id
        controller.start()

        assertEquals(first, trigger().single().id, "KEEP: app start and a grant arm the same trigger")
    }

    @Test
    fun stop_disarmsTheTrigger() {
        grantCallLog()
        val controller = ContentObserverController(context)
        controller.start()

        controller.stop()

        assertTrue(trigger().isEmpty())
    }

    // ── the worker ────────────────────────────────────────────────────────────

    /** Records what the worker asks of the controller; posts nothing to WorkManager. */
    private class RecordingController(ctx: Context) : ContentObserverController(ctx) {
        var rearmed = 0
        var syncs = 0
        override fun rearmCallLogTrigger() {
            rearmed++
        }
        override fun enqueueObservedSync() {
            syncs++
        }
    }

    private fun worker(controller: ContentObserverController): CallLogTriggerWorker =
        TestListenableWorkerBuilder<CallLogTriggerWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = CallLogTriggerWorker(appContext, workerParameters, controller)
            })
            .build()

    @Test
    fun aRun_reArmsTheTrigger_andStartsTheOrdinarySync() = runTest {
        grantCallLog()
        val controller = RecordingController(context)

        val result = worker(controller).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, controller.rearmed, "a content URI trigger fires once per enqueue")
        assertEquals(1, controller.syncs, "the same sync the observer starts: one ingest path")
    }

    @Test
    fun aRunWithoutCallLogAccess_letsTheChainEnd() = runTest {
        val controller = RecordingController(context)

        val result = worker(controller).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, controller.rearmed)
        assertEquals(0, controller.syncs)
    }

    @Test
    fun theTriggersSync_isTheObserversUniqueSync() {
        grantCallLog()

        ContentObserverController(context).enqueueObservedSync()

        val sync = workManager.getWorkInfosForUniqueWork(ContentObserverController.UNIQUE_NAME_SYNC).get()
        assertEquals(1, sync.size)
    }
}
