package app.orbit.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.AppPrefs
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.CallEventRepositoryImpl
import app.orbit.data.repository.WaitingCalls
import app.orbit.domain.clock.TestClock
import app.orbit.nav.AppLinks
import app.orbit.nav.Routes
import app.orbit.testutil.newPrefs
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
 * NOTIF-16, over the real NOTE-05 query (in-memory Room), a real DataStore
 * for dismissals and Robolectric's notification manager: when the
 * notification after a call posts, when it does not, what it says on and
 * off the lock screen, where its tap leads, and that it goes away once the
 * call stops waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PostCallNotifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService()!!
    private val now: Instant = Instant.parse("2026-10-07T18:00:00Z")
    private val clock = TestClock(now)
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val watchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: AppPrefs by lazy { tmp.newPrefs(storeScope) }
    private val foreground = AppForeground()

    private lateinit var db: OrbitDatabase
    private lateinit var repo: CallEventRepository
    private lateinit var waiting: WaitingCalls
    private lateinit var notifier: TestNotifier
    private var listId = 0L

    private class TestNotifier(
        context: Context,
        waiting: WaitingCalls,
        repo: CallEventRepository,
        foreground: AppForeground,
        prefs: AppPrefs,
        clock: TestClock,
    ) : PostCallNotifier(context, waiting, repo, foreground, prefs, clock) {
        var dnd = false
        override fun dndBlocking(): Boolean = dnd
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java).allowMainThreadQueries().build()
        repo = CallEventRepositoryImpl(db, db.callEventDao(), db.contactDao(), db.listMembershipDao(), db.listDao())
        waiting = WaitingCalls(repo, prefs, clock)
        notifier = TestNotifier(context, waiting, repo, foreground, prefs, clock)
        OrbitNotifications.ensureChannels(context)
        runBlocking { listId = db.listDao().insert(ListEntity(name = "Inner orbit", sortOrder = 0)) }
    }

    @After
    fun tearDown() {
        watchScope.cancel()
        db.close()
        storeScope.cancel()
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private suspend fun person(id: Long, name: String, onList: Boolean = true, ignored: Boolean = false): Long {
        db.contactDao().insert(
            ContactEntity(
                id = id,
                phoneNumber = "+1555000$id",
                normalizedPhone = "+1555000$id",
                displayName = name,
                firstSeenByAppAt = now.minus(Duration.ofDays(90)),
                isIgnored = ignored,
            ),
        )
        if (onList) {
            db.listMembershipDao().insert(ListMembershipEntity(contactId = id, listId = listId, addedAt = now.minus(Duration.ofDays(30))))
        }
        return id
    }

    /** A call that started [startedAgo] and lasted [seconds], as a sync would write it. */
    private suspend fun call(contactId: Long, startedAgo: Duration, seconds: Int = 14 * 60): Long =
        db.callEventDao().insert(
            CallEventEntity(
                contactId = contactId,
                occurredAt = now.minus(startedAgo),
                direction = CallDirection.OUTGOING,
                durationSeconds = seconds,
                source = CallSource.CALL_LOG,
            ),
        )

    private fun active(): List<Notification> =
        Shadows.shadowOf(manager).activeNotifications
            .filter { it.notification.channelId == OrbitNotifications.CHANNEL_AFTER_CALL }
            .map { it.notification }

    private fun minutes(m: Long) = Duration.ofMinutes(m)

    /** One sync pass: the marker before, the calls it writes, the hook after. */
    private suspend fun syncWriting(bulkPass: Boolean = false, write: suspend () -> Unit) {
        val marker = notifier.markBeforeSync()
        write()
        notifier.onSyncFinished(insertedAfterId = marker, bulkPass = bulkPass)
    }

    // ── when it posts ─────────────────────────────────────────────────────────

    @Test
    fun `a fresh call worth a note, synced while Orbit is closed, posts by first name`() = runBlocking {
        val kai = person(1L, "Kai Mensah")
        var callId = 0L

        syncWriting { callId = call(kai, startedAgo = minutes(20)) }

        val posted = active().single()
        assertEquals("How was your call with Kai?", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Add a note while it's fresh.", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // The tap opens the page for that call, through the nudge's route extra.
        val tap = Shadows.shadowOf(posted.contentIntent).savedIntent
        assertEquals(Routes.postCallNote("1", callId), tap.getStringExtra(AppLinks.EXTRA_NAVIGATE_TO))
    }

    @Test
    fun `the lock screen version names no one`() = runBlocking {
        val kai = person(1L, "Kai Mensah")
        syncWriting { call(kai, startedAgo = minutes(20)) }

        val posted = active().single()
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        val public = assertNotNull(posted.publicVersion, "a public version for the lock screen")
        assertEquals("How was your call?", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Add a note while it's fresh.", public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `it times out when the call stops waiting, a day after the call started`() = runBlocking {
        val kai = person(1L, "Kai Mensah")
        syncWriting { call(kai, startedAgo = minutes(20)) }

        // Nothing in the database changes when the window closes, so the
        // shade reconciliation would never hear of it; Android removes it.
        assertEquals(Duration.ofHours(24).minus(minutes(20)).toMillis(), active().single().timeoutAfter)
    }

    @Test
    fun `one notification per person, however many of their calls a pass writes`() = runBlocking {
        val kai = person(1L, "Kai Mensah")

        syncWriting {
            call(kai, startedAgo = minutes(90))
            call(kai, startedAgo = minutes(20))
        }

        assertEquals(1, active().size)
    }

    // ── when it does not ──────────────────────────────────────────────────────

    @Test
    fun `nothing posts while Orbit is on screen`() = runBlocking {
        foreground.onStarted()
        val kai = person(1L, "Kai Mensah")

        syncWriting { call(kai, startedAgo = minutes(20)) }

        assertTrue(active().isEmpty())
    }

    @Test
    fun `nothing posts for a call that ended more than 2 hours ago`() = runBlocking {
        val kai = person(1L, "Kai Mensah")

        // Started 2h 15m ago and lasted 14 minutes: ended 2h 1m ago.
        syncWriting { call(kai, startedAgo = minutes(135), seconds = 14 * 60) }

        assertTrue(active().isEmpty())
    }

    @Test
    fun `a call that ended exactly 2 hours ago still posts`() = runBlocking {
        val kai = person(1L, "Kai Mensah")

        syncWriting { call(kai, startedAgo = minutes(134), seconds = 14 * 60) }

        assertEquals(1, active().size)
    }

    @Test
    fun `nothing posts for a call under a minute`() = runBlocking {
        val kai = person(1L, "Kai Mensah")

        syncWriting { call(kai, startedAgo = minutes(5), seconds = 59) }

        assertTrue(active().isEmpty())
    }

    @Test
    fun `nothing posts for an ignored person or someone on no list`() = runBlocking {
        val ignored = person(1L, "Kai Mensah", ignored = true)
        val listless = person(2L, "Mara Ellis", onList = false)

        syncWriting {
            call(ignored, startedAgo = minutes(20))
            call(listless, startedAgo = minutes(20))
        }

        assertTrue(active().isEmpty())
    }

    @Test
    fun `nothing posts for a first import or a full resync`() = runBlocking {
        val kai = person(1L, "Kai Mensah")

        syncWriting(bulkPass = true) { call(kai, startedAgo = minutes(20)) }

        assertTrue(active().isEmpty())
    }

    @Test
    fun `nothing posts for a call that was already there before the pass`() = runBlocking {
        val kai = person(1L, "Kai Mensah")
        call(kai, startedAgo = minutes(20))

        // A pass that writes nothing new about Kai.
        syncWriting { call(person(2L, "Mara Ellis", onList = false), startedAgo = minutes(10)) }

        assertTrue(active().isEmpty())
    }

    @Test
    fun `nothing posts with the channel off, notifications off, or Do Not Disturb on`() = runBlocking {
        val kai = person(1L, "Kai Mensah")

        // The user switching the channel off in Android's settings.
        manager.deleteNotificationChannel(OrbitNotifications.CHANNEL_AFTER_CALL)
        manager.createNotificationChannel(
            NotificationChannel(OrbitNotifications.CHANNEL_AFTER_CALL, "After a call", NotificationManager.IMPORTANCE_NONE),
        )
        syncWriting { call(kai, startedAgo = minutes(30)) }
        assertTrue(active().isEmpty(), "channel off")

        manager.deleteNotificationChannel(OrbitNotifications.CHANNEL_AFTER_CALL)
        OrbitNotifications.ensureChannels(context)
        Shadows.shadowOf(manager).setNotificationsEnabled(false)
        syncWriting { call(kai, startedAgo = minutes(20)) }
        assertTrue(active().isEmpty(), "notifications off")

        Shadows.shadowOf(manager).setNotificationsEnabled(true)
        notifier.dnd = true
        syncWriting { call(kai, startedAgo = minutes(10)) }
        assertTrue(active().isEmpty(), "Do Not Disturb")
    }

    @Test
    fun `nothing posts for a call already dismissed on Home`() = runBlocking {
        val kai = person(1L, "Kai Mensah")

        syncWriting {
            val id = call(kai, startedAgo = minutes(20))
            waiting.dismiss(listOf(id))
        }

        assertTrue(active().isEmpty())
    }

    // ── when it goes away ─────────────────────────────────────────────────────

    @Test
    fun `saving a note for that person cancels it`() = runBlocking {
        val kai = person(1L, "Kai Mensah")
        val mara = person(2L, "Mara Ellis")
        syncWriting {
            call(kai, startedAgo = minutes(20))
            call(mara, startedAgo = minutes(30))
        }
        assertEquals(2, active().size)

        db.noteDao().insert(NoteEntity(contactId = kai, createdAt = now, body = "Went well"))
        notifier.reconcileShade()

        assertEquals(listOf("How was your call with Mara?"), active().map { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() })
    }

    @Test
    fun `dismissing the call on Home cancels it`() = runBlocking {
        val kai = person(1L, "Kai Mensah")
        var id = 0L
        syncWriting { id = call(kai, startedAgo = minutes(20)) }
        assertEquals(1, active().size)

        waiting.dismiss(listOf(id))
        notifier.reconcileShade()

        assertTrue(active().isEmpty())
    }

    @Test
    fun `the shade follows the waiting calls by itself once started`() = runBlocking {
        val kai = person(1L, "Kai Mensah")
        syncWriting { call(kai, startedAgo = minutes(20)) }
        notifier.startShadeReconciliation(watchScope)

        // A note written on Contact detail, which knows nothing about notifications.
        db.noteDao().insert(NoteEntity(contactId = kai, createdAt = now, body = "Went well"))

        withTimeout(30_000L) { while (active().isNotEmpty()) delay(25) }
    }
}
