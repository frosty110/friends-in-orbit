package app.orbit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ContactEntity
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for [ContactDao] — drives the DAO through an in-memory [OrbitDatabase]
 * instance and asserts insert/read round-trips plus the entity-level invariants
 * declared in the data model (DATA-08 firstSeenByAppAt, IGNORE-01 isIgnored default false).
 *
 * Uses `Room.inMemoryDatabaseBuilder(...)` intentionally — NOT the production
 * `DatabaseFactory.create()` — so SQLCipher is bypassed. SQLCipher encryption
 * correctness is verified separately by an adb bytes-check.
 */
@RunWith(AndroidJUnit4::class)
class ContactDaoSmokeTest {

    private lateinit var db: OrbitDatabase
    private lateinit var dao: ContactDao

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.contactDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGet_roundTrips() = runTest {
        val now = Instant.parse("2026-04-23T12:00:00Z")
        val contact = ContactEntity(
            phoneNumber = "+15551234567",
            normalizedPhone = "+15551234567",
            displayName = "Alice",
            firstSeenByAppAt = now,
        )

        val id = dao.insert(contact)
        val fetched = dao.get(id)

        assertNotNull(fetched)
        assertEquals("Alice", fetched.displayName)
        assertEquals("+15551234567", fetched.phoneNumber)
        assertEquals(now, fetched.firstSeenByAppAt)
    }

    @Test
    fun insertedContact_defaultsIsIgnoredFalseAndPausedUntilNull() = runTest {
        val id = dao.insert(
            ContactEntity(
                phoneNumber = "+15557654321",
                normalizedPhone = "+15557654321",
                displayName = "Bob",
                firstSeenByAppAt = Instant.parse("2026-04-23T12:00:00Z"),
            ),
        )

        val fetched = dao.get(id)

        assertNotNull(fetched)
        // IGNORE-01: isIgnored default is false (entity default must survive the round-trip).
        assertEquals(false, fetched.isIgnored)
        // pausedUntil round-trips as null when not set at insert time.
        assertEquals(null, fetched.pausedUntil)
    }

    @Test
    fun observeAll_emitsInsertedContact() = runTest {
        dao.insert(
            ContactEntity(
                phoneNumber = "+15550000001",
                normalizedPhone = "+15550000001",
                displayName = "Carol",
                firstSeenByAppAt = Instant.parse("2026-04-23T12:00:00Z"),
            ),
        )

        val emitted = dao.observeAll().first()

        assertEquals(1, emitted.size)
        assertEquals("Carol", emitted[0].displayName)
        assertTrue(emitted[0].id > 0L)
    }
}
