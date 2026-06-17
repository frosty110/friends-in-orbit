package app.orbit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for [CallEventDao] — proves insert + read round-trip plus the composite
 * `(contactId, occurredAt)` index backing the `observeByContactId` query's DESC ordering.
 *
 * Uses `Room.inMemoryDatabaseBuilder(...)` intentionally — NOT the production
 * `DatabaseFactory.create()` — so SQLCipher is bypassed. See sibling test
 * [ContactDaoSmokeTest] for the locked harness rationale.
 */
@RunWith(AndroidJUnit4::class)
class CallEventDaoSmokeTest {

    private lateinit var db: OrbitDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var callEventDao: CallEventDao

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contactDao = db.contactDao()
        callEventDao = db.callEventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGet_roundTrips() = runTest {
        val contactId = contactDao.insert(
            ContactEntity(
                phoneNumber = "+15553330000",
                normalizedPhone = "+15553330000",
                displayName = "Carol",
                firstSeenByAppAt = Instant.parse("2026-04-23T12:00:00Z"),
            ),
        )
        val event = CallEventEntity(
            contactId = contactId,
            occurredAt = Instant.parse("2026-04-23T13:00:00Z"),
            direction = CallDirection.OUTGOING,
            durationSeconds = 120,
            source = CallSource.CALL_LOG,
        )

        val id = callEventDao.insert(event)
        val fetched = callEventDao.get(id)

        assertNotNull(fetched)
        assertEquals(contactId, fetched.contactId)
        assertEquals(CallDirection.OUTGOING, fetched.direction)
        assertEquals(CallSource.CALL_LOG, fetched.source)
        assertEquals(120, fetched.durationSeconds)
    }

    @Test
    fun observeByContactId_returnsDescByOccurredAt() = runTest {
        val contactId = contactDao.insert(
            ContactEntity(
                phoneNumber = "+15554440000",
                normalizedPhone = "+15554440000",
                displayName = "Dave",
                firstSeenByAppAt = Instant.parse("2026-04-23T12:00:00Z"),
            ),
        )
        // Insert out of order to prove the composite index orders DESC on read.
        val earliest = Instant.parse("2026-04-20T12:00:00Z")
        val middle = Instant.parse("2026-04-21T12:00:00Z")
        val latest = Instant.parse("2026-04-22T12:00:00Z")
        callEventDao.insert(
            CallEventEntity(
                contactId = contactId,
                occurredAt = earliest,
                direction = CallDirection.OUTGOING,
                durationSeconds = 30,
                source = CallSource.MANUAL,
            ),
        )
        callEventDao.insert(
            CallEventEntity(
                contactId = contactId,
                occurredAt = latest,
                direction = CallDirection.INCOMING,
                durationSeconds = 45,
                source = CallSource.CALL_LOG,
            ),
        )
        callEventDao.insert(
            CallEventEntity(
                contactId = contactId,
                occurredAt = middle,
                direction = CallDirection.OUTGOING,
                durationSeconds = 60,
                source = CallSource.CALL_LOG,
            ),
        )

        val events = callEventDao.observeByContactId(contactId).first()

        assertEquals(3, events.size)
        // DESC ordering by occurredAt (composite index on (contactId, occurredAt)).
        assertEquals(latest, events[0].occurredAt)
        assertEquals(middle, events[1].occurredAt)
        assertEquals(earliest, events[2].occurredAt)
    }
}
