package app.orbit.data.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.NoteEntity
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [CallEventDao.observeForLog], [CallEventDao.latestUnnotedOutgoing],
 * and [CallEventDao.getById] against an in-memory Room database.
 *
 * NOTE on the `observeForLog filters contactId IS NOT NULL` test contract:
 * [CallEventEntity.contactId] is non-nullable on the current schema, so the
 * SQL `IS NOT NULL` filter is forward-compat / defensive — it cannot be
 * exercised through the entity. The assertion verifies that all seeded rows
 * (which all have non-null contactId) flow through; the null-filter case
 * is impossible to reach via the typed path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CallEventDaoLogTest {

    private lateinit var db: OrbitDatabase
    private lateinit var callEventDao: CallEventDao
    private lateinit var contactDao: ContactDao
    private lateinit var noteDao: NoteDao

    private val NOW: Instant = Instant.parse("2026-04-26T12:00:00Z")
    private val FIRST_SEEN: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        callEventDao = db.callEventDao()
        contactDao = db.contactDao()
        noteDao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedContact(id: Long): Long = contactDao.insert(
        ContactEntity(
            id = id,
            phoneNumber = "+1555000${id.toString().padStart(4, '0')}",
            normalizedPhone = "+1555000${id.toString().padStart(4, '0')}",
            displayName = "Contact $id",
            firstSeenByAppAt = FIRST_SEEN,
        ),
    )

    private fun event(
        contactId: Long,
        occurredAt: Instant,
        direction: CallDirection = CallDirection.OUTGOING,
        durationSeconds: Int = 300,
    ): CallEventEntity = CallEventEntity(
        contactId = contactId,
        occurredAt = occurredAt,
        direction = direction,
        durationSeconds = durationSeconds,
        source = CallSource.CALL_LOG,
    )

    @Test
    fun `observeForLog returns rows with contactId set ordered by occurredAt DESC`() = runTest {
        val cid = seedContact(1L)
        callEventDao.insert(event(cid, NOW.minusSeconds(300)))
        callEventDao.insert(event(cid, NOW.minusSeconds(60)))
        callEventDao.insert(event(cid, NOW.minusSeconds(180)))

        // observeForLog is bounded by `limit`; pass Int.MAX_VALUE to
        // exercise the full feed shape used when the user taps "Show 200 more".
        callEventDao.observeForLog(limit = Int.MAX_VALUE).test {
            val rows = awaitItem()
            assertEquals(3, rows.size)
            // Ordered DESC: -60s first, -180s second, -300s third.
            assertEquals(
                listOf(NOW.minusSeconds(60), NOW.minusSeconds(180), NOW.minusSeconds(300)),
                rows.map { it.occurredAt },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `latestUnnotedOutgoing returns null when matching note exists`() = runTest {
        val cid = seedContact(1L)
        val callAt = NOW.minusSeconds(60)
        callEventDao.insert(event(cid, callAt, direction = CallDirection.OUTGOING))
        // Note created AFTER the call → call is "noted".
        noteDao.insert(NoteEntity(contactId = cid, createdAt = callAt.plusSeconds(5), body = "post-call"))

        val result = callEventDao.latestUnnotedOutgoing(since = NOW.minusSeconds(60 * 60))
        assertNull(result, "covering note should suppress the banner")
    }

    @Test
    fun `latestUnnotedOutgoing returns event when no covering note exists`() = runTest {
        val cid = seedContact(1L)
        val callAt = NOW.minusSeconds(120)
        callEventDao.insert(event(cid, callAt, direction = CallDirection.OUTGOING))
        // Note created BEFORE the call → does NOT cover (Pitfall 5).
        noteDao.insert(NoteEntity(contactId = cid, createdAt = callAt.minusSeconds(60), body = "older note"))

        val result = callEventDao.latestUnnotedOutgoing(since = NOW.minusSeconds(60 * 60))
        assertNotNull(result)
        assertEquals(callAt, result.occurredAt)
        assertEquals(CallDirection.OUTGOING, result.direction)
    }

    @Test
    fun `latestUnnotedOutgoing ignores INCOMING calls`() = runTest {
        val cid = seedContact(1L)
        val callAt = NOW.minusSeconds(60)
        callEventDao.insert(event(cid, callAt, direction = CallDirection.INCOMING))

        val result = callEventDao.latestUnnotedOutgoing(since = NOW.minusSeconds(60 * 60))
        assertNull(result, "incoming calls must not trigger the post-call banner")
    }

    @Test
    fun `latestUnnotedOutgoing ignores events before since window`() = runTest {
        val cid = seedContact(1L)
        // Call 2 hours ago; window starts 1 hour ago.
        callEventDao.insert(event(cid, NOW.minusSeconds(60 * 60 * 2), direction = CallDirection.OUTGOING))

        val result = callEventDao.latestUnnotedOutgoing(since = NOW.minusSeconds(60 * 60))
        assertNull(result)
    }

    @Test
    fun `getById returns single CallEventEntity for valid id and null for unknown`() = runTest {
        val cid = seedContact(1L)
        val rowId = callEventDao.insert(event(cid, NOW.minusSeconds(30)))

        val found = callEventDao.getById(rowId)
        assertNotNull(found)
        assertEquals(rowId, found.id)
        assertEquals(cid, found.contactId)

        val missing = callEventDao.getById(rowId + 9999)
        assertNull(missing)
    }
}
