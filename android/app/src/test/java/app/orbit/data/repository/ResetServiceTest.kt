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
import app.orbit.widget.WidgetUpdateScheduler
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ResetService] fulfills the full settings-spec contract: cancel scheduled
 * WorkManager jobs, stop the content observers, THEN wipe Room + DataStore.
 * These tests pin each leg.
 *
 * Work requests are enqueued with long initial delays so the synchronous
 * test executor leaves them ENQUEUED (a zero-delay request would execute —
 * and fail worker instantiation without a Hilt factory — before reset runs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ResetServiceTest {

    private val context: android.content.Context get() =
        ApplicationProvider.getApplicationContext()

    private lateinit var db: OrbitDatabase

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
        val prefsDir = java.io.File(context.filesDir.parentFile, "datastore")
        if (prefsDir.exists()) prefsDir.deleteRecursively()
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
    fun `resetAll cancels unique works, stops observer, wipes db and prefs`() = runBlocking {
        val wm = WorkManager.getInstance(context)
        enqueueAllUniqueWorks(wm)

        // Sanity — all three unique works are live before reset.
        assertTrue(statesFor(wm, ContentObserverController.UNIQUE_NAME_SYNC).isNotEmpty())
        assertTrue(statesFor(wm, ContactsIngestWorker.UNIQUE_NAME).isNotEmpty())
        assertTrue(statesFor(wm, digestUniqueName).isNotEmpty())

        // Seed the widget works the scheduler owns (debounced one-time +
        // hourly periodic sweep) so the test proves resetAll cancels both.
        // The 30s/1h delays keep them ENQUEUED under the synchronous executor.
        WidgetUpdateScheduler.scheduleImmediate(context)
        WidgetUpdateScheduler.schedulePeriodic(context)
        assertTrue(statesFor(wm, WidgetUpdateScheduler.UNIQUE_WORK).isNotEmpty())
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
        val prefs = AppPrefs(context)
        prefs.setOnboardingComplete(true)
        assertEquals(true, prefs.isOnboardingComplete.first())

        val controller = CountingController(context)
        val service = ResetService(
            context = context,
            database = db,
            appPrefs = prefs,
            contentObserverController = controller,
        )

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
        // ...and ONE final refresh is re-enqueued AFTER the wipe so placed
        // widgets re-render "No one due" instead of the wiped contact's name.
        assertTrue(
            statesFor(wm, WidgetUpdateScheduler.UNIQUE_WORK)
                .any { it == WorkInfo.State.ENQUEUED },
            "a final widget refresh must be enqueued post-wipe",
        )

        // 2. Observers stopped (same cleanup path as permission revocation).
        assertTrue(controller.stopCount >= 1, "ContentObserverController.stop() must run")

        // 3. Room wiped.
        assertTrue(db.contactDao().getAllOnce().isEmpty(), "contacts must be wiped")
        assertEquals(null, db.ruleTemplateDao().get(1L))

        // 4. Prefs wiped — onboarding flag back to false, so the task
        //    restart lands on the welcome screen.
        assertEquals(false, prefs.isOnboardingComplete.first())
    }
}
