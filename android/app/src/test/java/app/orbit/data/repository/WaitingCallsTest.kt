package app.orbit.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.AppPrefs
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.domain.clock.TestClock
import app.orbit.testutil.newPrefs
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NOTE-05, end to end: [WaitingCalls] over the real Room query
 * (`CallEventDao.observeWaitingForNote`, in memory) and a real DataStore for
 * the dismissals, one test per rule and one per edge. The clock is fixed, so
 * "the last 24 hours" and "a minute or more" are tested at their exact
 * boundaries.
 *
 * Replaces the four `latestUnnotedOutgoing` cases that lived in
 * `CallEventDaoLogTest` (a ten-minute, outgoing-only window), retired with
 * that query on 2026-10-07.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WaitingCallsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now: Instant = Instant.parse("2026-10-07T18:00:00Z")
    private val clock = TestClock(now)
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: AppPrefs by lazy { tmp.newPrefs(storeScope) }

    private lateinit var db: OrbitDatabase
    private lateinit var waiting: WaitingCalls

    private var activeListId = 0L
    private var archivedListId = 0L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        waiting = WaitingCalls(repository(), prefs, clock)
        runBlocking {
            activeListId = db.listDao().insert(ListEntity(name = "Inner orbit", sortOrder = 0))
            archivedListId = db.listDao().insert(ListEntity(name = "Old friends", sortOrder = 1, isArchived = true))
        }
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun repository(): CallEventRepository = CallEventRepositoryImpl(
        db,
        db.callEventDao(),
        db.contactDao(),
        db.listMembershipDao(),
        db.listDao(),
    )

    private suspend fun person(
        id: Long,
        name: String = "Contact $id",
        lists: List<Long> = listOf(activeListId),
        ignored: Boolean = false,
        archived: Boolean = false,
    ): Long {
        db.contactDao().insert(
            ContactEntity(
                id = id,
                phoneNumber = "+1555000$id",
                normalizedPhone = "+1555000$id",
                displayName = name,
                firstSeenByAppAt = now.minus(Duration.ofDays(100)),
                isIgnored = ignored,
                isArchived = archived,
            ),
        )
        lists.forEach { db.listMembershipDao().insert(ListMembershipEntity(contactId = id, listId = it, addedAt = now.minus(Duration.ofDays(50)))) }
        return id
    }

    private suspend fun call(
        contactId: Long,
        ago: Duration,
        seconds: Int = 14 * 60,
        direction: CallDirection = CallDirection.OUTGOING,
        source: CallSource = CallSource.CALL_LOG,
    ): Long = db.callEventDao().insert(
        CallEventEntity(
            contactId = contactId,
            occurredAt = now.minus(ago),
            direction = direction,
            durationSeconds = seconds,
            source = source,
        ),
    )

    private suspend fun note(contactId: Long, at: Instant) {
        db.noteDao().insert(NoteEntity(contactId = contactId, createdAt = at, body = "Went well"))
    }

    private suspend fun waitingIds(): List<Long> = waiting.current().map { it.callEventId }

    private fun hours(h: Long) = Duration.ofHours(h)

    // ── what waits ────────────────────────────────────────────────────────────

    @Test
    fun `a connected call of a minute or more with someone on a list waits, with their name`() = runBlocking {
        val kai = person(1L, name = "Kai Mensah")
        val id = call(kai, ago = hours(2))

        val rows = waiting.current()

        assertEquals(listOf(id), rows.map { it.callEventId })
        assertEquals("Kai Mensah", rows.single().displayName)
        assertEquals(CallDirection.OUTGOING, rows.single().direction)
    }

    @Test
    fun `a call they made waits too`() = runBlocking {
        val id = call(person(1L), ago = hours(1), direction = CallDirection.INCOMING)

        assertEquals(listOf(id), waitingIds())
    }

    @Test
    fun `59 seconds is not worth a note, 60 is`() = runBlocking {
        call(person(1L), ago = hours(1), seconds = 59)
        val minute = call(person(2L), ago = hours(1), seconds = 60)

        assertEquals(listOf(minute), waitingIds())
    }

    @Test
    fun `a call that started exactly 24 hours ago still waits, a second earlier it does not`() = runBlocking {
        val edge = call(person(1L), ago = hours(24))
        call(person(2L), ago = hours(24).plusSeconds(1))

        assertEquals(listOf(edge), waitingIds())
    }

    @Test
    fun `a connection logged by hand and an attempt never wait`() = runBlocking {
        call(person(1L), ago = hours(1), source = CallSource.MANUAL)
        call(person(2L), ago = hours(1), source = CallSource.ATTEMPT)

        assertEquals(emptyList(), waitingIds())
    }

    @Test
    fun `someone on no list, or only on an archived list, does not wait`() = runBlocking {
        call(person(1L, lists = emptyList()), ago = hours(1))
        call(person(2L, lists = listOf(archivedListId)), ago = hours(1))
        val both = call(person(3L, lists = listOf(archivedListId, activeListId)), ago = hours(1))

        assertEquals(listOf(both), waitingIds())
    }

    @Test
    fun `an ignored or archived person does not wait`() = runBlocking {
        call(person(1L, ignored = true), ago = hours(1))
        call(person(2L, archived = true), ago = hours(1))

        assertEquals(emptyList(), waitingIds())
    }

    // ── notes ─────────────────────────────────────────────────────────────────

    @Test
    fun `a note written since the call started ends the wait, one from before does not`() = runBlocking {
        val kai = person(1L)
        call(kai, ago = hours(3))
        note(kai, at = now.minus(hours(1)))
        val sam = person(2L)
        val samsCall = call(sam, ago = hours(3))
        note(sam, at = now.minus(hours(5)))

        assertEquals(listOf(samsCall), waitingIds())
    }

    @Test
    fun `a note dated to the moment the call started covers it`() = runBlocking {
        val kai = person(1L)
        call(kai, ago = hours(3))
        // A retroactive note from Call history is back-dated to the call.
        note(kai, at = now.minus(hours(3)))

        assertEquals(emptyList(), waitingIds())
    }

    @Test
    fun `saving a note elsewhere takes the call off the list live`() = runBlocking {
        val kai = person(1L)
        val id = call(kai, ago = hours(1))

        waiting.observe(now).test {
            assertEquals(listOf(id), awaitItem().map { it.callEventId })
            note(kai, at = now)
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── one per person, newest first ──────────────────────────────────────────

    @Test
    fun `two calls with one person are one entry, the latest`() = runBlocking {
        val kai = person(1L)
        call(kai, ago = hours(6))
        val latest = call(kai, ago = hours(2))

        assertEquals(listOf(latest), waitingIds())
    }

    @Test
    fun `a short latest call does not hide an earlier call worth a note`() = runBlocking {
        val kai = person(1L)
        val long = call(kai, ago = hours(6))
        call(kai, ago = hours(2), seconds = 20)

        assertEquals(listOf(long), waitingIds())
    }

    @Test
    fun `people are listed newest call first`() = runBlocking {
        val older = call(person(1L), ago = hours(9))
        val newest = call(person(2L), ago = hours(1))
        val middle = call(person(3L), ago = hours(4))

        assertEquals(listOf(newest, middle, older), waitingIds())
    }

    // ── dismissals ────────────────────────────────────────────────────────────

    @Test
    fun `a dismissed call no longer waits, and undo brings it back`() = runBlocking {
        val id = call(person(1L), ago = hours(1))

        waiting.dismiss(listOf(id))
        assertEquals(emptyList(), waitingIds())

        waiting.undoDismiss(listOf(id))
        assertEquals(listOf(id), waitingIds())
    }

    @Test
    fun `dismissing the latest call dismisses the person, the earlier call does not come back`() = runBlocking {
        val kai = person(1L)
        call(kai, ago = hours(6))
        val latest = call(kai, ago = hours(2))

        waiting.dismiss(listOf(latest))

        assertEquals(emptyList(), waitingIds())
    }

    @Test
    fun `a newer call waits even when the person's earlier one was dismissed`() = runBlocking {
        val kai = person(1L)
        val earlier = call(kai, ago = hours(6))
        waiting.dismiss(listOf(earlier))

        val newer = call(kai, ago = hours(1))

        assertEquals(listOf(newer), waitingIds())
    }

    @Test
    fun `a dismissal is kept in the store, so a new process still honours it`() = runBlocking {
        val id = call(person(1L), ago = hours(1))
        waiting.dismiss(listOf(id))

        // A fresh WaitingCalls over a fresh AppPrefs: nothing in memory carries
        // the dismissal, only the DataStore (one store per file, so the test
        // shares it, as a restarted process shares the file).
        val afterRestart = WaitingCalls(repository(), prefs, clock)

        assertEquals(emptyList(), afterRestart.current())
    }

    @Test
    fun `dismissals older than 48 hours are forgotten on the next write, younger ones kept`() = runBlocking {
        clock.set(now.minus(hours(49)))
        waiting.dismiss(listOf(1L))
        clock.set(now.minus(hours(47)))
        waiting.dismiss(listOf(2L))
        clock.set(now)

        waiting.dismiss(listOf(3L))

        assertEquals(setOf(2L, 3L), prefs.dismissedPostCallIds.first())
    }

    @Test
    fun `dismissing an id twice keeps its first time`() = runBlocking {
        clock.set(now.minus(hours(47)))
        waiting.dismiss(listOf(1L))
        clock.set(now)
        waiting.dismiss(listOf(1L))

        // Had the second write restamped it, it would survive this prune.
        clock.set(now.plus(hours(2)))
        waiting.dismiss(listOf(9L))

        assertTrue(1L !in prefs.dismissedPostCallIds.first())
    }
}
