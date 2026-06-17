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
import app.orbit.data.entity.ContactEntity
import app.orbit.domain.FakeContactRepository
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * [IncomingFollowUpWorker] gate assertions — NOTIF-04 / D-13 / D-14.
 *
 * Test pattern mirrors [app.orbit.calllog.CallLogSyncWorkerTest]:
 * [TestListenableWorkerBuilder] + custom [WorkerFactory] + Robolectric shadow for
 * notification posting assertions.
 *
 * Key decisions:
 * - [FakeFollowUpDedupStore] provides controllable dedup timestamps.
 * - [FakeContactRepository] (from domain.FakeRepositories) seeds contacts.
 * - Robolectric grants POST_NOTIFICATIONS via [grantNotificationPermission] for
 *   the all-gates-pass branch.
 *
 * @Config(application = Application::class) bypasses OrbitApp.onCreate so we
 * don't need a Hilt graph for the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class IncomingFollowUpWorkerTest {

    private lateinit var context: Application
    private lateinit var fakeContacts: FakeContactRepository
    private lateinit var fakeDedupStore: FakeFollowUpDedupStore

    private val contactId = 42L
    private val trackedContact = ContactEntity(
        id = contactId,
        phoneNumber = "+14155551234",
        normalizedPhone = "+14155551234",
        displayName = "Alice",
        firstSeenByAppAt = Instant.EPOCH,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        fakeContacts = FakeContactRepository(listOf(trackedContact))
        fakeDedupStore = FakeFollowUpDedupStore()
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private fun buildWorker(cId: Long = contactId): IncomingFollowUpWorker =
        TestListenableWorkerBuilder<IncomingFollowUpWorker>(context)
            .setInputData(workDataOf(IncomingFollowUpWorker.KEY_CONTACT_ID to cId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = IncomingFollowUpWorker(
                    appContext, workerParameters, fakeDedupStore, fakeContacts,
                )
            })
            .build()

    private fun grantNotificationPermission() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(nm).setNotificationsEnabled(true)
    }

    private fun activeNotificationCount(): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return Shadows.shadowOf(nm).activeNotifications.size
    }

    // ─── Dedup window: within 30 min → skip ───────────────────────────────────

    @Test
    fun worker_skipsNotification_whenFollowUpWithinDedupWindow() = runBlocking {
        grantNotificationPermission()
        // Record a follow-up 20 minutes ago — still within the 30-minute window.
        fakeDedupStore.seed(contactId, System.currentTimeMillis() - 20 * 60_000L)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, activeNotificationCount(), "no notification posted when within dedup window")
        assertEquals(0, fakeDedupStore.recordCalls.size, "record() must NOT be called on dedup skip")
    }

    // ─── Outside dedup window: post + record ──────────────────────────────────

    @Test
    fun worker_postsNotification_whenFollowUpOutsideDedupWindow() = runBlocking {
        grantNotificationPermission()
        // Record a follow-up 31 minutes ago — outside the 30-minute window.
        fakeDedupStore.seed(contactId, System.currentTimeMillis() - 31 * 60_000L)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, activeNotificationCount(), "notification must be posted outside dedup window")
        assertEquals(1, fakeDedupStore.recordCalls.size, "record() must be called after posting")
        assertEquals(contactId, fakeDedupStore.recordCalls.first().first, "record() must use the correct contactId")
    }

    // ─── No prior record → post ───────────────────────────────────────────────

    @Test
    fun worker_postsNotification_whenNoPriorFollowUpRecorded() = runBlocking {
        grantNotificationPermission()
        // No prior record seeded — lastFollowUpAtMs returns null → dedup window false.

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, activeNotificationCount(), "notification must be posted when no prior record")
        assertEquals(1, fakeDedupStore.recordCalls.size, "record() must be called after first post")
    }

    // ─── Gate: notifications disabled → skip ─────────────────────────────────

    @Test
    fun worker_skipsNotification_whenNotificationsDisabled() = runBlocking {
        // Robolectric default may be enabled; explicitly disable.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(nm).setNotificationsEnabled(false)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, activeNotificationCount(), "no notification when system notifications disabled")

        // Re-enable for subsequent tests.
        Shadows.shadowOf(nm).setNotificationsEnabled(true)
    }

    // ─── Gate: contact gone → skip ────────────────────────────────────────────

    @Test
    fun worker_skipsNotification_whenContactNotFound() = runBlocking {
        grantNotificationPermission()
        // Use a contactId that doesn't exist in fakeContacts.

        val result = buildWorker(cId = 999L).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, activeNotificationCount(), "no notification when contact not found")
    }

    // ─── Gate: contact ignored → skip ────────────────────────────────────────

    @Test
    fun worker_skipsNotification_whenContactIsIgnored() = runBlocking {
        grantNotificationPermission()
        val ignoredContacts = FakeContactRepository(
            listOf(trackedContact.copy(isIgnored = true))
        )
        val worker = TestListenableWorkerBuilder<IncomingFollowUpWorker>(context)
            .setInputData(workDataOf(IncomingFollowUpWorker.KEY_CONTACT_ID to contactId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = IncomingFollowUpWorker(
                    appContext, workerParameters, fakeDedupStore, ignoredContacts,
                )
            })
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, activeNotificationCount(), "no notification when contact is ignored")
    }

    // ─── Missing contactId → failure ─────────────────────────────────────────

    @Test
    fun worker_returnsFailure_whenContactIdMissing() = runBlocking {
        grantNotificationPermission()
        val worker = TestListenableWorkerBuilder<IncomingFollowUpWorker>(context)
            .setInputData(workDataOf()) // no KEY_CONTACT_ID
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = IncomingFollowUpWorker(
                    appContext, workerParameters, fakeDedupStore, fakeContacts,
                )
            })
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }
}

// ─── Test doubles ─────────────────────────────────────────────────────────────

/**
 * Controllable [FollowUpDedupStore] for unit tests.
 *
 * Seeds a per-contact timestamp via [seed] before each test. Records every
 * [record] call in [recordCalls] for assertion.
 *
 * Passes [ApplicationProvider.getApplicationContext] to the superclass constructor
 * so Kotlin constructs the object cleanly; the context field is never accessed
 * because all data-accessing methods are overridden.
 */
private class FakeFollowUpDedupStore :
    FollowUpDedupStore(ApplicationProvider.getApplicationContext()) {

    // contactId → lastFollowUpAtMs seed
    private val seeds = mutableMapOf<Long, Long>()

    /** Captured args for each record() call: contactId → atMs. */
    val recordCalls: MutableList<Pair<Long, Long>> = mutableListOf()

    fun seed(contactId: Long, atMs: Long) {
        seeds[contactId] = atMs
    }

    override suspend fun lastFollowUpAtMs(contactId: Long): Long? = seeds[contactId]

    override suspend fun record(contactId: Long, atMs: Long) {
        recordCalls += contactId to atMs
    }
}
