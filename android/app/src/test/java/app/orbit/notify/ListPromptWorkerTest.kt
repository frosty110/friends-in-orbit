package app.orbit.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.orbit.data.AppPrefs
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.repository.ListRepository
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.usecase.SurfaceNextUseCase
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 * - The nudge's person comes from a real [SurfaceNextUseCase] over the same
 *   fakes, so "the person the nudge names" is "the person Card view shows
 *   first" by construction, and [AppPrefs] is the real DataStore (NOTIF-15).
 *
 * NOTIF-13 / NOTIF-14 / NOTIF-15 are pinned at the bottom of the class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ListPromptWorkerTest {

    private lateinit var context: Application
    private lateinit var fakeLists: FakeListRepository
    private lateinit var fakeContacts: FakeContactRepository
    private lateinit var surfaceNext: SurfaceNextUseCase
    private lateinit var appPrefs: AppPrefs
    private lateinit var recordingScheduler: RecordingNudgeScheduler

    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        fakeLists = FakeListRepository()
        fakeContacts = FakeContactRepository()
        surfaceNext = SurfaceNextUseCase(
            contactRepo = fakeContacts,
            listRepo = fakeLists,
            callEventRepo = FakeCallEventRepository(),
            ruleTemplateRepo = FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = 1L))),
            clock = TestClock(t0),
            json = JsonProvider.json,
        )
        appPrefs = AppPrefs(context)
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
                        surfaceNext, appPrefs,
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

    // ─── NOTIF-13: lock-screen version ─────────────────────────────────────────

    /**
     * Every nudge is private with a public version, and the public version is
     * built from constants: no list name, no person, no face, no actions.
     * Checked on the name-free nudge here and on the named one below.
     */
    @Test
    fun everyNudge_isPrivate_withANameFreePublicVersion() = runBlocking {
        val listId = 10L
        fakeLists.seedList(
            ListEntity(
                id = listId, name = "Recovery support", sortOrder = 0,
                notificationsEnabled = true,
                nudgeScheduleJson = NudgeSchedule.DEFAULT_JSON,
            )
        )
        fakeLists.stubbedDueCount = 1

        buildWorker(listId).doWork()

        val posted = postedNotification()
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals(Notification.CATEGORY_REMINDER, posted.category)
        assertLockScreenSafe(posted, forbidden = listOf("Recovery support"))
    }

    // ─── NOTIF-14: the nudge names the list's next person ──────────────────────

    @Test
    fun nudge_namesTheListsNextPerson_withFaceAndCallAction() = runBlocking {
        registerDialer()
        val listId = 11L
        seedListWithMembers(listId, "Family", contactFixture(id = 7L, displayName = "Kai Nakamura"))

        buildWorker(listId).doWork()

        val posted = postedNotification()
        assertEquals("Family", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(NotificationCopy.nudgeNamedBody("Kai"), bodyOf(posted))
        assertNotNull(posted.getLargeIcon(), "the nudge carries the person's face")
        val action = posted.actions?.singleOrNull()
        assertNotNull(action, "exactly one action: Call")
        assertEquals("Call Kai", action.title.toString())
        assertLockScreenSafe(posted, forbidden = listOf("Family", "Kai", "Nakamura"))
        assertEquals(7L, appPrefs.nudgeLastNamedContactId(listId), "who was named is remembered (NOTIF-15)")
    }

    @Test
    fun nudge_offersNoCallAction_whenThereIsNoDialer() = runBlocking {
        val listId = 12L
        seedListWithMembers(listId, "Family", contactFixture(id = 7L, displayName = "Kai Nakamura"))

        buildWorker(listId).doWork()

        val posted = postedNotification()
        assertEquals(NotificationCopy.nudgeNamedBody("Kai"), bodyOf(posted))
        assertTrue(posted.actions.isNullOrEmpty(), "no button that does nothing on a dialer-less device")
    }

    // ─── NOTIF-15: a name is said once ────────────────────────────────────────

    @Test
    fun nextNudge_goesOutWithoutAName_whenTheSamePersonIsStillNext() = runBlocking {
        registerDialer()
        val listId = 13L
        seedListWithMembers(listId, "Family", contactFixture(id = 7L, displayName = "Kai Nakamura"))

        buildWorker(listId).doWork()
        buildWorker(listId).doWork()

        val posted = postedNotification()
        assertEquals(NotificationCopy.nudgeBody("Family", 1), bodyOf(posted))
        assertNull(posted.getLargeIcon(), "no face when the name is held back")
        assertTrue(posted.actions.isNullOrEmpty(), "no Call action when the name is held back")
        assertLockScreenSafe(posted, forbidden = listOf("Family", "Kai"))
        assertEquals(7L, appPrefs.nudgeLastNamedContactId(listId), "the record still names the last person named")
    }

    @Test
    fun nextNudge_namesSomeoneNew_onceTheDeckHasMovedOn() = runBlocking {
        registerDialer()
        val listId = 14L
        val kai = contactFixture(id = 7L, displayName = "Kai Nakamura")
        val priya = contactFixture(id = 8L, displayName = "Priya Shah")
        seedListWithMembers(listId, "Family", kai, priya)

        buildWorker(listId).doWork()
        // Kai was called: the deck now leads with Priya.
        fakeLists.updateMemberships { rows ->
            rows.map {
                if (it.contactId == kai.id) it.copy(nextDueAt = t0.plusSeconds(86_400L * 14)) else it
            }
        }
        buildWorker(listId).doWork()

        val posted = postedNotification()
        assertEquals(NotificationCopy.nudgeNamedBody("Priya"), bodyOf(posted))
        assertEquals("Call Priya", posted.actions?.single()?.title.toString())
        assertEquals(8L, appPrefs.nudgeLastNamedContactId(listId))
    }

    // ─── Helpers for the nudge-content tests ──────────────────────────────────

    private fun seedListWithMembers(listId: Long, name: String, vararg members: ContactEntity) {
        fakeLists.seedList(
            ListEntity(
                id = listId, name = name, sortOrder = 0,
                notificationsEnabled = true,
                ruleTemplateId = 1L,
                nudgeScheduleJson = NudgeSchedule.DEFAULT_JSON,
            )
        )
        // Due in member order: the first is the deck's head.
        fakeLists.seedMemberships(
            members.mapIndexed { i, contact ->
                membershipFixture(
                    contactId = contact.id,
                    listId = listId,
                    nextDueAt = t0.minusSeconds(3_600L * (members.size - i)),
                )
            }
        )
        fakeContacts.seed(members.toList())
        fakeLists.stubbedDueCount = members.size
    }

    private fun bodyOf(posted: Notification): String =
        posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun postedNotification(): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = Shadows.shadowOf(nm).getActiveNotifications()
        assertEquals(1, active.size, "one nudge per list")
        return active.single().notification
    }

    /**
     * NOTIF-13: the lock-screen version exists, carries the fixed copy, and
     * nothing that names a person or a list, shows a face, or acts.
     */
    private fun assertLockScreenSafe(posted: Notification, forbidden: List<String>) {
        val public = assertNotNull(posted.publicVersion, "a public version for the lock screen")
        val title = public.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = public.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertEquals(NotificationCopy.PUBLIC_TITLE, title)
        assertEquals(NotificationCopy.PUBLIC_BODY, text)
        forbidden.forEach { word ->
            assertFalse(title.contains(word) || text.contains(word), "lock screen must not show '$word'")
        }
        assertNull(public.getLargeIcon(), "no face on the lock screen")
        assertTrue(public.actions.isNullOrEmpty(), "no actions on the lock screen")
    }

    /** Robolectric has no dialer; register one so ACTION_DIAL resolves. */
    private fun registerDialer() {
        val pm = Shadows.shadowOf(context.packageManager)
        val dialer = ComponentName("com.example.dialer", "com.example.dialer.DialActivity")
        pm.addActivityIfNotPresent(dialer)
        pm.addIntentFilterForActivity(
            dialer,
            IntentFilter(Intent.ACTION_DIAL).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("tel")
            },
        )
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
    surfaceNext: SurfaceNextUseCase,
    appPrefs: AppPrefs,
    private val fixedNow: LocalTime?,
    private val stubbedDndBlocking: Boolean = false,
) : ListPromptWorker(appContext, params, nudgeScheduler, listRepo, surfaceNext, appPrefs) {
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
