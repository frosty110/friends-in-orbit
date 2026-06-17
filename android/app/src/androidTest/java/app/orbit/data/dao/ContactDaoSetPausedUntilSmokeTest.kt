package app.orbit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ContactEntity
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for [ContactDao.setPausedUntil] — the single-column atomic
 * `@Query("UPDATE contacts SET pausedUntil = :until WHERE id = :id")` introduced
 * to fix the read-then-write race in `ContactRepositoryImpl.setPausedUntil`
 * (review finding H-03).
 *
 * The contract is "this update touches `pausedUntil` and nothing else." Three
 * assertions:
 *   1. Round-trips a non-null Instant on a fresh row.
 *   2. Clears the column back to null on a previously-paused row.
 *   3. Does NOT clobber other columns (`displayName`, `phoneNumber`,
 *      `firstSeenByAppAt`, `isIgnored`) — proves the single-column claim and
 *      protects against a future regression that swaps to a full-row `@Update`.
 *
 * In-memory Room (no SQLCipher) — encryption is verified separately by
 * `scripts/verify-phase1.sh`.
 */
@RunWith(AndroidJUnit4::class)
class ContactDaoSetPausedUntilSmokeTest {

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
    fun setPausedUntil_writesNonNullInstant() = runTest {
        val id = dao.insert(
            ContactEntity(
                phoneNumber = "+15551110001",
                normalizedPhone = "+15551110001",
                displayName = "Alice",
                firstSeenByAppAt = Instant.parse("2026-04-23T12:00:00Z"),
            ),
        )
        val until = Instant.parse("2026-05-01T00:00:00Z")

        val rowsAffected = dao.setPausedUntil(id, until)

        assertEquals(1, rowsAffected)
        val fetched = dao.get(id)
        assertNotNull(fetched)
        assertEquals(until, fetched.pausedUntil)
    }

    @Test
    fun setPausedUntil_clearsToNull() = runTest {
        val until = Instant.parse("2026-05-01T00:00:00Z")
        val id = dao.insert(
            ContactEntity(
                phoneNumber = "+15551110002",
                normalizedPhone = "+15551110002",
                displayName = "Bob",
                firstSeenByAppAt = Instant.parse("2026-04-23T12:00:00Z"),
                pausedUntil = until,
            ),
        )

        dao.setPausedUntil(id, null)

        val fetched = dao.get(id)
        assertNotNull(fetched)
        assertEquals(null, fetched.pausedUntil)
    }

    @Test
    fun setPausedUntil_doesNotClobberOtherColumns() = runTest {
        val firstSeen = Instant.parse("2026-04-23T12:00:00Z")
        val id = dao.insert(
            ContactEntity(
                phoneNumber = "+15551110003",
                normalizedPhone = "+15551110003",
                displayName = "Carol",
                firstSeenByAppAt = firstSeen,
                isIgnored = true,
                photoUri = "content://photos/42",
            ),
        )

        dao.setPausedUntil(id, Instant.parse("2026-06-01T00:00:00Z"))

        val fetched = dao.get(id)
        assertNotNull(fetched)
        // Only pausedUntil should change.
        assertEquals("Carol", fetched.displayName)
        assertEquals("+15551110003", fetched.phoneNumber)
        assertEquals(firstSeen, fetched.firstSeenByAppAt)
        assertEquals(true, fetched.isIgnored)
        assertEquals("content://photos/42", fetched.photoUri)
    }
}
