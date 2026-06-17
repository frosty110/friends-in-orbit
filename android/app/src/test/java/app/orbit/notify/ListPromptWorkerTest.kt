package app.orbit.notify

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.orbit.data.entity.ListEntity
import app.orbit.data.repository.ListRepository
import app.orbit.domain.FakeListRepository
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * [ListPromptWorker] fire-time gate assertions — NOTIF-01, NOTIF-03, NOTIF-06.
 *
 * Test pattern mirrors [app.orbit.calllog.CallLogSyncWorkerTest]:
 * [TestListenableWorkerBuilder] + custom [WorkerFactory] + Robolectric shadow for
 * notification posting assertions.
 *
 * Key decisions:
 * - [RecordingNudgeScheduler] subclasses (open) [NudgeScheduler] to capture
 *   [scheduleFromEntity] calls; WorkManager is never accessed in tests.
 * - [FakeListRepository] (from domain.FakeRepositories) provides controllable
 *   per-gate test fixtures.
 * - [FixedClockWorker] overrides [currentLocalTime] to inject deterministic
 *   LocalTime for the midnight-spanning active-hours gate tests.
 * - Robolectric grants POST_NOTIFICATIONS via [grantNotificationPermission] for
 *   the all-gates-pass branch; revoked by default for the NOTIF-01 gate test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ListPromptWorkerTest {

    private lateinit var context: Application
    private lateinit var fakeLists: FakeListRepository
    private lateinit var recordingScheduler: RecordingNudgeScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        fakeLists = FakeListRepository()
        recordingScheduler = RecordingNudgeScheduler(context, fakeLists)
    }

    // ─── Helper: build a worker at a fixed LocalTime ──────────────────────────

    private fun buildWorker(
        listId: Long,
        fixedNow: LocalTime? = null,
        dndBlocking: Boolean = false,
    ): ListPromptWorker =
        TestListenableWorkerBuilder<ListPromptWorker>(context)
            .setInputData(workDataOf(ListPromptWorker.KEY_LIST_ID to listId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker {
                    return ControlledWorker(
                        appContext, workerParameters, recordingScheduler, fakeLists,
                        fixedNow = fixedNow,
                        stubbedDndBlocking = dndBlocking,
                    )
                }
            })
            .build()

    private fun activeNotificationCount(): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return Shadows.shadowOf(nm).getActiveNotifications().size
    }

    // ─── Gate 2: notifications disabled (NOTIF-01 residual fire-time gate) ────

    @Test
    fun worker_returnsSuccess_whenNotificationsDisabled_andReEnqueues() = runBlocking {
        // Robolectric's ShadowNotificationManager initializes mAreNotificationsEnabled=true
        // by default; explicitly disable to simulate POST_NOTIFICATIONS denied / user muted.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(nm).setNotificationsEnabled(false)

        val listId = 1L
        fakeLists.seedList(
            ListEntity(
                id = listId, name = "Friends", sortOrder = 0,
                notificationsEnabled = true,
                nudgeScheduleJson = NudgeSchedule.DEFAULT_JSON,
            )
        )
        fakeLists.stubbedDueCount = 1

        val result = buildWorker(listId).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, activeNotificationCount(), "no notification posted when notifications disabled")
        assertEquals(1, recordingScheduler.scheduleFromEntityCalls.size, "re-enqueue must fire even when notifications disabled")

        // Reset for subsequent tests.
        Shadows.shadowOf(nm).setNotificationsEnabled(true)
    }

    // ─── Gate 3: DND blocking (NOTIF-06) ──────────────────────────────────────

    @Test
    fun worker_returnsSuccess_whenDndBlocking_andReEnqueues() = runBlocking {

        val listId = 2L
        fakeLists.seedList(
            ListEntity(
                id = listId, name = "Work", sortOrder = 0,
                notificationsEnabled = true,
                nudgeScheduleJson = NudgeSchedule.DEFAULT_JSON,
            )
        )
        fakeLists.stubbedDueCount = 1

        // Inject dndBlocking = true via the ControlledWorker override.
        // (Robolectric's ShadowNotificationManager.setInterruptionFilter is protected —
        // not accessible from outside the shadow package; override the hook instead.)
        val result = buildWorker(listId, dndBlocking = true).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, activeNotificationCount(), "no notification posted when DND blocks")
        assertEquals(1, recordingScheduler.scheduleFromEntityCalls.size, "re-enqueue must fire even when DND blocks")
    }

    // ─── Gate 5: dueCount = 0 ─────────────────────────────────────────────────

    @Test
    fun worker_returnsSuccess_whenDueCountIsZero_andReEnqueues() = runBlocking {

        val listId = 3L
        fakeLists.seedList(
            ListEntity(
                id = listId, name = "Inner orbit", sortOrder = 0,
                notificationsEnabled = true,
                nudgeScheduleJson = NudgeSchedule.DEFAULT_JSON,
            )
        )
        fakeLists.stubbedDueCount = 0 // due count = 0 → gate fails

        val result = buildWorker(listId).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, activeNotificationCount(), "no notification posted when due count is zero")
        assertEquals(1, recordingScheduler.scheduleFromEntityCalls.size, "re-enqueue must fire even when due count zero")
    }

    // ─── All gates pass: notification posted ──────────────────────────────────

    @Test
    fun worker_postsAndReEnqueues_whenAllGatesPass() = runBlocking {

        val listId = 4L
        val listName = "Late night"
        fakeLists.seedList(
            ListEntity(
                id = listId, name = listName, sortOrder = 0,
                notificationsEnabled = true,
                nudgeScheduleJson = NudgeSchedule.DEFAULT_JSON,
            )
        )
        fakeLists.stubbedDueCount = 3

        val result = buildWorker(listId).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, activeNotificationCount(), "exactly one notification posted when all gates pass")
        val postedNotif = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .let { Shadows.shadowOf(it).getActiveNotifications() }
            .first()
        assertEquals(
            NotificationCopy.nudgeTitle(listName),
            postedNotif.notification.extras.getString("android.title"),
            "notification title must be list name",
        )
        assertEquals(
            NotificationCopy.nudgeBody(listName, 3),
            postedNotif.notification.extras.getString("android.text"),
            "notification body must follow D-18 format",
        )
        assertEquals(1, recordingScheduler.scheduleFromEntityCalls.size, "re-enqueue always fires")
    }

    // ─── Gate 1: list-level notificationsEnabled = false ─────────────────────

    @Test
    fun worker_returnsSuccess_whenListMuted_andReEnqueues() = runBlocking {

        val listId = 5L
        fakeLists.seedList(
            ListEntity(
                id = listId, name = "Paused list", sortOrder = 0,
                notificationsEnabled = false, // muted at list level
                nudgeScheduleJson = NudgeSchedule.DEFAULT_JSON,
            )
        )
        fakeLists.stubbedDueCount = 2

        val result = buildWorker(listId).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, activeNotificationCount(), "no notification when list is muted")
        assertEquals(1, recordingScheduler.scheduleFromEntityCalls.size, "re-enqueue fires even when list is muted")
    }

    // ─── Gate 4: midnight-spanning active-hours window (23:30 = inside) ───────

    /**
     * Midnight-spanning window 22:00–02:00: currentTime=23:30 is INSIDE the window.
     * With due >= 1 and all other gates passing, the notification MUST be posted.
     * (D-09 / NOTIF-03: spansMidnight case, start > end)
     */
    @Test
    fun worker_posts_whenInsideMidnightSpanningActiveHoursWindow() = runBlocking {

        val listId = 6L
        fakeLists.seedList(
            ListEntity(
                id = listId, name = "Night owls", sortOrder = 0,
                notificationsEnabled = true,
                activeHoursStart = LocalTime.of(22, 0),
                activeHoursEnd = LocalTime.of(2, 0),
                nudgeScheduleJson = NudgeSchedule.DEFAULT_JSON,
            )
        )
        fakeLists.stubbedDueCount = 1

        // 23:30 is inside 22:00–02:00 (midnight-spanning window)
        val result = buildWorker(listId, fixedNow = LocalTime.of(23, 30)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, activeNotificationCount(), "23:30 is inside 22:00–02:00 window: notification must post")
        assertEquals(1, recordingScheduler.scheduleFromEntityCalls.size, "re-enqueue fires")
    }

    // ─── Gate 4: midnight-spanning active-hours window (10:00 = outside) ──────

    /**
     * Midnight-spanning window 22:00–02:00: currentTime=10:00 is OUTSIDE the window.
     * No notification must post, but the next slot MUST still be re-enqueued.
     * (D-09 / NOTIF-03: midnight-spanning "skip-and-re-enqueue" case)
     */
    @Test
    fun worker_skipsButReEnqueues_whenOutsideMidnightSpanningActiveHoursWindow() = runBlocking {

        val listId = 7L
        fakeLists.seedList(
            ListEntity(
                id = listId, name = "Night owls", sortOrder = 0,
                notificationsEnabled = true,
                activeHoursStart = LocalTime.of(22, 0),
                activeHoursEnd = LocalTime.of(2, 0),
                nudgeScheduleJson = NudgeSchedule.DEFAULT_JSON,
            )
        )
        fakeLists.stubbedDueCount = 1

        // 10:00 is outside 22:00–02:00 (midnight-spanning window)
        val result = buildWorker(listId, fixedNow = LocalTime.of(10, 0)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, activeNotificationCount(), "10:00 is outside 22:00–02:00 window: no notification")
        assertEquals(
            1, recordingScheduler.scheduleFromEntityCalls.size,
            "re-enqueue MUST still fire when outside active-hours window (finally block invariant)",
        )
    }

    // ─── Gate: list not found (deleted between schedule and fire) ─────────────

    @Test
    fun worker_returnsSuccess_whenListGone() = runBlocking {

        val listId = 99L
        // No list seeded — repo returns null for getById(99)

        val result = buildWorker(listId).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, activeNotificationCount(), "no notification when list is gone")
        // re-enqueue does NOT fire (list gone = no schedule to re-enqueue)
        assertEquals(0, recordingScheduler.scheduleFromEntityCalls.size)
    }
}

// ─── Test doubles ─────────────────────────────────────────────────────────────

/**
 * Subclass of [NudgeScheduler] that records [scheduleFromEntity] calls and
 * DOES NOT invoke WorkManager (avoiding the real [WorkManager.getInstance] call).
 */
private class RecordingNudgeScheduler(context: Context, listRepo: ListRepository) :
    NudgeScheduler(context, listRepo) {

    val scheduleFromEntityCalls: MutableList<ListEntity> = mutableListOf()

    override suspend fun scheduleFromEntity(list: ListEntity) {
        scheduleFromEntityCalls += list
        // Do NOT call super — WorkManager is not initialized in Robolectric unit tests.
    }
}

/**
 * Subclass of [ListPromptWorker] that overrides test-injectable hooks:
 * - [currentLocalTime] → returns [fixedNow] when non-null (active-hours gate tests).
 * - [dndBlocking] → returns [stubbedDndBlocking] (DND gate tests — Robolectric's
 *   ShadowNotificationManager.setInterruptionFilter is protected, not accessible
 *   from outside the shadow package).
 */
private class ControlledWorker(
    appContext: Context,
    params: WorkerParameters,
    nudgeScheduler: NudgeScheduler,
    listRepo: ListRepository,
    private val fixedNow: LocalTime?,
    private val stubbedDndBlocking: Boolean = false,
) : ListPromptWorker(appContext, params, nudgeScheduler, listRepo) {
    override fun currentLocalTime(): LocalTime = fixedNow ?: LocalTime.now()
    override fun dndBlocking(): Boolean = stubbedDndBlocking
}

// ─── FakeListRepository extension helper ──────────────────────────────────────

/**
 * Seeds a single [ListEntity] preserving its declared [ListEntity.id].
 * [FakeListRepository.seed] replaces the entire list state, so each test
 * creates a fresh [FakeListRepository] (via setUp) and then calls this once.
 */
private fun FakeListRepository.seedList(list: ListEntity) {
    seed(listOf(list))
}
