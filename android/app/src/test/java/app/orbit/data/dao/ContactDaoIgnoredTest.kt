package app.orbit.data.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ContactEntity
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [ContactDao.observeIgnored], [ContactDao.markIgnored], and
 * [ContactDao.getPreIgnoreSnapshot] against an in-memory Room database.
 *
 * Covers IGNORE-06 (sort by `ignoredAt DESC`) + the round-trip of the
 * three ignore-related columns (`isIgnored`, `ignoredAt`,
 * `preIgnoreListMembershipsJson`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ContactDaoIgnoredTest {

    private lateinit var db: OrbitDatabase
    private lateinit var contactDao: ContactDao

    private val FIRST_SEEN: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contactDao = db.contactDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertContact(
        id: Long,
        isIgnored: Boolean = false,
        ignoredAt: Instant? = null,
        snapshot: String? = null,
    ): Long = contactDao.insert(
        ContactEntity(
            id = id,
            phoneNumber = "+1555000${id.toString().padStart(4, '0')}",
            normalizedPhone = "+1555000${id.toString().padStart(4, '0')}",
            displayName = "Contact $id",
            firstSeenByAppAt = FIRST_SEEN,
            isIgnored = isIgnored,
            ignoredAt = ignoredAt,
            preIgnoreListMembershipsJson = snapshot,
        ),
    )

    @Test
    fun `observeIgnored emits ignored contacts sorted by ignoredAt DESC`() = runTest {
        val tEarly = Instant.parse("2026-04-01T00:00:00Z")
        val tMid = Instant.parse("2026-04-15T00:00:00Z")
        val tLate = Instant.parse("2026-04-25T00:00:00Z")

        insertContact(1L, isIgnored = true, ignoredAt = tEarly)
        insertContact(2L, isIgnored = true, ignoredAt = tLate)
        insertContact(3L, isIgnored = true, ignoredAt = tMid)
        insertContact(4L, isIgnored = false) // not ignored — must be excluded

        contactDao.observeIgnored().test {
            val rows = awaitItem()
            assertEquals(3, rows.size, "non-ignored contact must be excluded")
            assertEquals(listOf(2L, 3L, 1L), rows.map { it.id }, "DESC by ignoredAt")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markIgnored round-trips ignoredAt and preIgnoreListMembershipsJson`() = runTest {
        insertContact(42L)
        val ignoredAt = Instant.parse("2026-04-26T15:30:00Z")
        val snapshot = """{"listIds":[1,2]}"""

        val updated = contactDao.markIgnored(
            id = 42L,
            isIgnored = true,
            ignoredAt = ignoredAt,
            preIgnoreListMembershipsJson = snapshot,
        )
        assertEquals(1, updated, "exactly one row must be updated")

        val readBack = contactDao.get(42L)
        assertNotNull(readBack)
        assertEquals(true, readBack.isIgnored)
        assertEquals(ignoredAt, readBack.ignoredAt)
        assertEquals(snapshot, readBack.preIgnoreListMembershipsJson)
    }

    @Test
    fun `getPreIgnoreSnapshot returns id and json projection only`() = runTest {
        val snapshot = """{"listIds":[7,9]}"""
        insertContact(99L, isIgnored = true, ignoredAt = FIRST_SEEN, snapshot = snapshot)

        val result = contactDao.getPreIgnoreSnapshot(99L)
        assertNotNull(result)
        assertEquals(99L, result.id)
        assertEquals(snapshot, result.preIgnoreListMembershipsJson)
    }

    @Test
    fun `getPreIgnoreSnapshot returns null for unknown id`() = runTest {
        val result = contactDao.getPreIgnoreSnapshot(404L)
        assertNull(result)
    }

    @Test
    fun `markIgnored clear path nulls all three columns and flips isIgnored false`() = runTest {
        insertContact(
            55L,
            isIgnored = true,
            ignoredAt = Instant.parse("2026-03-01T00:00:00Z"),
            snapshot = """{"listIds":[3]}""",
        )

        contactDao.markIgnored(55L, isIgnored = false, ignoredAt = null, preIgnoreListMembershipsJson = null)

        val readBack = contactDao.get(55L)
        assertNotNull(readBack)
        assertEquals(false, readBack.isIgnored)
        assertNull(readBack.ignoredAt)
        assertNull(readBack.preIgnoreListMembershipsJson)

        // observeIgnored should now exclude this row.
        val ignoredRows = contactDao.observeIgnored().first()
        assertTrue(ignoredRows.none { it.id == 55L })
    }
}
