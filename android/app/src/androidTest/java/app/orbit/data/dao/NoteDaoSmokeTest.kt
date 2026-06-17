package app.orbit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.NoteEntity
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
 * Smoke test for [NoteDao] — proves insert + read round-trip plus DESC ordering on
 * `createdAt` when observing notes by contact id.
 *
 * Uses `Room.inMemoryDatabaseBuilder(...)` intentionally — NOT the production
 * `DatabaseFactory.create()` — so SQLCipher is bypassed. See sibling test
 * [ContactDaoSmokeTest] for the locked harness rationale.
 */
@RunWith(AndroidJUnit4::class)
class NoteDaoSmokeTest {

    private lateinit var db: OrbitDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var noteDao: NoteDao

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contactDao = db.contactDao()
        noteDao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGet_roundTrips() = runTest {
        val contactId = contactDao.insert(
            ContactEntity(
                phoneNumber = "+15555550000",
                normalizedPhone = "+15555550000",
                displayName = "Eve",
                firstSeenByAppAt = Instant.parse("2026-04-23T12:00:00Z"),
            ),
        )
        val now = Instant.parse("2026-04-23T14:00:00Z")
        val note = NoteEntity(
            contactId = contactId,
            createdAt = now,
            body = "Discussed the project roadmap.",
        )

        val id = noteDao.insert(note)
        val fetched = noteDao.get(id)

        assertNotNull(fetched)
        assertEquals(contactId, fetched.contactId)
        assertEquals(now, fetched.createdAt)
        assertEquals("Discussed the project roadmap.", fetched.body)
    }

    @Test
    fun observeByContactId_returnsDescByCreatedAt() = runTest {
        val contactId = contactDao.insert(
            ContactEntity(
                phoneNumber = "+15556660000",
                normalizedPhone = "+15556660000",
                displayName = "Frank",
                firstSeenByAppAt = Instant.parse("2026-04-23T12:00:00Z"),
            ),
        )
        val earliest = Instant.parse("2026-04-20T09:00:00Z")
        val middle = Instant.parse("2026-04-21T09:00:00Z")
        val latest = Instant.parse("2026-04-22T09:00:00Z")

        // Insert out of order.
        noteDao.insert(NoteEntity(contactId = contactId, createdAt = earliest, body = "first"))
        noteDao.insert(NoteEntity(contactId = contactId, createdAt = latest, body = "third"))
        noteDao.insert(NoteEntity(contactId = contactId, createdAt = middle, body = "second"))

        val notes = noteDao.observeByContactId(contactId).first()

        assertEquals(3, notes.size)
        assertEquals("third", notes[0].body)
        assertEquals("second", notes[1].body)
        assertEquals("first", notes[2].body)
    }
}
