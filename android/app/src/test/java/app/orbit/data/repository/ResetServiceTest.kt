package app.orbit.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.orbit.calllog.CallLogSyncWorker
import app.orbit.calllog.ContactsIngestWorker
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.testutil.newPrefs
import app.orbit.widget.WidgetUpdateScheduler
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ResetService] fulfills the full settings-spec contract: cancel scheduled
 * WorkManager jobs, stop the content observers, THEN wipe Room + DataStore,
 * and run one undebounced widget refresh. These tests pin each leg.
 *
 * Work requests are enqueued with long initial delays so the synchronous
 * test executor leaves them ENQUEUED (a zero-delay request would execute
 * before reset runs).
 *
 * Prefs come from a per-test DataStore (`testutil/TestDataStore.kt`) whose
 * scope is cancelled in `@After`, so nothing here can leak into, or wait on,
 * another method.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ResetServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: android.content.Context get() =
        ApplicationProvider.getApplicationContext()

    private lateinit var db: OrbitDatabase
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Legacy unique-work name for the periodic digest (since removed, NOTIF-08).
     * The literal "orbit.daily_digest" is pinned here so the test still proves
     * resetAll cancels the stale WorkManager record on existing installs.
     */
    private val digestUniqueName = "orbit.daily_digest"

    @Before
    fun setUp() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private class CountingController(ctx: android.content.Context) :
        ContentObserverController(ctx) {
        var stopCount: Int = 0
        override fun start() = Unit
        override fun stop() { stopCount++ }
    }

    private fun enqueueAllUniqueWorks(wm: WorkManager) {
        wm.enqueueUniqueWork(
            ContentObserverController.UNIQUE_NAME_SYNC,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CallLogSyncWorker>()
                .setInitialDelay(1, TimeUnit.HOURS)
                .build(),
        )
        wm.enqueueUniqueWork(
            ContactsIngestWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ContactsIngestWorker>()
                .setInitialDelay(1, TimeUnit.HOURS)
                .build(),
        )
        // NOTIF-08: the old periodic worker was removed. Enqueue a trivial
        // OneTimeWorkRequest by the legacy unique name "orbit.daily_digest" so the test
        // still proves resetAll cancels the stale record on existing installs.
        wm.enqueueUniqueWork(
            digestUniqueName,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CallLogSyncWorker>()
                .setInitialDelay(1, TimeUnit.HOURS)
                .build(),
        )
    }

    private fun statesFor(wm: WorkManager, uniqueName: String): List<WorkInfo.State> =
        wm.getWorkInfosForUniqueWork(uniqueName).get().map { it.state }

    @Test
    fun `resetAll cancels unique works, stops observer, wipes db and prefs, refreshes widgets now`() = runBlocking {
        val wm = WorkManager.getInstance(context)
        enqueueAllUniqueWorks(wm)

        // Sanity: all three unique works are live before reset.
        assertTrue(statesFor(wm, ContentObserverController.UNIQUE_NAME_SYNC).isNotEmpty())
        assertTrue(statesFor(wm, ContactsIngestWorker.UNIQUE_NAME).isNotEmpty())
        assertTrue(statesFor(wm, digestUniqueName).isNotEmpty())

        // Seed the widget works the scheduler owns (debounced one-time +
        // hourly periodic sweep) so the test proves resetAll cancels both.
        // The 30s/1h delays keep them ENQUEUED under the synchronous executor.
        WidgetUpdateScheduler.scheduleImmediate(context)
        WidgetUpdateScheduler.schedulePeriodic(context)
        val debouncedRefresh = wm.getWorkInfosForUniqueWork(WidgetUpdateScheduler.UNIQUE_WORK).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, debouncedRefresh.state)
        assertEquals(30_000L, debouncedRefresh.initialDelayMillis, "the pending refresh is the debounced one")
        assertTrue(statesFor(wm, WidgetUpdateScheduler.PERIODIC_WORK).isNotEmpty())

        // Seed Room + prefs with post-onboarding state.
        db.ruleTemplateDao().insert(
            RuleTemplateEntity(id = 1L, name = "Keep in touch", kind = RuleKind.KEEP_IN_TOUCH, paramsJson = "{}"),
        )
        db.contactDao().insert(
            ContactEntity(
                id = 1L,
                phoneNumber = "+15551234567",
                normalizedPhone = "+15551234567",
                displayName = "Sam",
                firstSeenByAppAt = Instant.ofEpochMilli(1_000L),
            ),
        )
        val prefs: AppPrefs = tmp.newPrefs(storeScope)
        prefs.setOnboardingComplete(true)
        assertEquals(true, prefs.isOnboardingComplete.first())

        val controller = CountingController(context)
        val service = ResetService(
            context = context,
            database = db,
            appPrefs = prefs,
            contentObserverController = controller,
        )

        // Subscribe BEFORE the reset: the completion event has no replay.
        val completed = async { withTimeout(30_000L) { service.resetCompleteEvents.first() } }
        delay(50)

        service.resetAll()

        // 1. Every unique work is cancelled.
        assertTrue(
            statesFor(wm, ContentObserverController.UNIQUE_NAME_SYNC)
                .all { it == WorkInfo.State.CANCELLED },
            "call-log sync work must be cancelled",
        )
        assertTrue(
            statesFor(wm, ContactsIngestWorker.UNIQUE_NAME)
                .all { it == WorkInfo.State.CANCELLED },
            "contacts ingest work must be cancelled",
        )
        assertTrue(
            statesFor(wm, digestUniqueName).all { it == WorkInfo.State.CANCELLED },
            "daily digest work must be cancelled",
        )
        // The hourly widget sweep is cancelled so no orphaned worker fires
        // against the wiped DB...
        assertTrue(
            statesFor(wm, WidgetUpdateScheduler.PERIODIC_WORK)
                .all { it == WorkInfo.State.CANCELLED },
            "widget periodic sweep must be cancelled",
        )
        // ...and ONE final refresh replaces the debounced one AFTER the wipe
        // (WIDGET-06, refreshNow) so placed widgets re-render the empty state
        // at once instead of showing the wiped person's name for 30 seconds.
        // It has no initial delay, so the synchronous executor may already
        // have started it: the record is live (never CANCELLED), and it is a
        // different request from the debounced one that was pending.
        val finalRefresh = wm.getWorkInfosForUniqueWork(WidgetUpdateScheduler.UNIQUE_WORK).get()
        assertEquals(1, finalRefresh.size, "exactly one widget refresh record after the reset")
        assertNotEquals(debouncedRefresh.id, finalRefresh.single().id, "the debounced refresh must be replaced")
        assertNotEquals(WorkInfo.State.CANCELLED, finalRefresh.single().state, "the final refresh must be live")
        assertEquals(0L, finalRefresh.single().initialDelayMillis, "the final refresh must not wait out the debounce")

        // 2. Observers stopped (same cleanup path as permission revocation).
        assertTrue(controller.stopCount >= 1, "ContentObserverController.stop() must run")

        // 3. Room wiped.
        assertTrue(db.contactDao().getAllOnce().isEmpty(), "contacts must be wiped")
        assertEquals(null, db.ruleTemplateDao().get(1L))

        // 4. Prefs wiped: the onboarding flag back to false, so the task
        //    restart lands on the welcome screen.
        assertEquals(false, prefs.isOnboardingComplete.first())

        // 5. The completion event the Settings screen restarts the task on.
        completed.await()
    }
}
