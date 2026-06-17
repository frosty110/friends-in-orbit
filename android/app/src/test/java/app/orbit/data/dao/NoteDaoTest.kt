package app.orbit.data.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.NoteEntity
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [NoteDao.recentForContact] +
 * the existing [NoteDao.observeByContactId] ordering against an in-memory
 * Room database. Robolectric supplies an Application context so
 * Room.inMemoryDatabaseBuilder can construct the DB without a connected
 * device.
 *
 * Pattern matches the testOptions { unitTests { isIncludeAndroidResources
 * = true } } wiring in app/build.gradle.kts (already enabled for the
 * SettingsViewModelTest fixture).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class NoteDaoTest {

    private lateinit var db: OrbitDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var contactDao: ContactDao

    private val NOW: Instant = Instant.parse("2026-04-26T12:00:00Z")

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, OrbitDatabase::class.java)
            .allowMainThreadQueries() // tests run on the test dispatcher; allow for runBlocking simplicity
            .build()
        noteDao = db.noteDao()
        contactDao = db.contactDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedContact(id: Long): Long =
        contactDao.insert(
            ContactEntity(
                id = id,
                phoneNumber = "+1555000${id.toString().padStart(4, '0')}",
                normalizedPhone = "+1555000${id.toString().padStart(4, '0')}",
                displayName = "Contact $id",
                firstSeenByAppAt = NOW,
            ),
        )

    @Test
    fun `recentForContact returns up to limit notes within since window newest-first`() = runTest {
        val contactId = seedContact(42L)
        // Insert 4 notes spanning >30 days.
        // n1..n3 within the 30-day window, n0 outside.
        val n0 = NoteEntity(contactId = contactId, createdAt = NOW.minusSeconds(60L * 60 * 24 * 60), body = "old-60d")
        val n1 = NoteEntity(contactId = contactId, createdAt = NOW.minusSeconds(60L * 60 * 24 * 5), body = "5d")
        val n2 = NoteEntity(contactId = contactId, createdAt = NOW.minusSeconds(60L * 60 * 24 * 2), body = "2d")
        val n3 = NoteEntity(contactId = contactId, createdAt = NOW.minusSeconds(60L * 60 * 24 * 1), body = "1d")
        listOf(n0, n1, n2, n3).forEach { noteDao.insert(it) }

        val since = NOW.minusSeconds(60L * 60 * 24 * 30) // 30 days ago
        noteDao.recentForContact(contactId = contactId, since = since, limit = 2).test {
            val rows = awaitItem()
            assertEquals(2, rows.size, "expected 2 (limit)")
            assertEquals("1d", rows[0].body, "newest first")
            assertEquals("2d", rows[1].body, "second-newest second")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recentForContact excludes notes older than since window`() = runTest {
        val contactId = seedContact(7L)
        val ancient = NoteEntity(contactId = contactId, createdAt = NOW.minusSeconds(60L * 60 * 24 * 90), body = "ancient")
        val recent = NoteEntity(contactId = contactId, createdAt = NOW.minusSeconds(60L * 60 * 24 * 5), body = "recent")
        listOf(ancient, recent).forEach { noteDao.insert(it) }

        val since = NOW.minusSeconds(60L * 60 * 24 * 30)
        noteDao.recentForContact(contactId = contactId, since = since, limit = 10).test {
            val rows = awaitItem()
            assertEquals(1, rows.size)
            assertEquals("recent", rows.single().body)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeByContactId emits notes ordered by createdAt DESC`() = runTest {
        val contactId = seedContact(11L)
        val a = NoteEntity(contactId = contactId, createdAt = NOW.minusSeconds(300), body = "oldest")
        val b = NoteEntity(contactId = contactId, createdAt = NOW.minusSeconds(120), body = "middle")
        val c = NoteEntity(contactId = contactId, createdAt = NOW, body = "newest")
        listOf(a, b, c).forEach { noteDao.insert(it) }

        noteDao.observeByContactId(contactId).test {
            val rows = awaitItem()
            assertEquals(listOf("newest", "middle", "oldest"), rows.map { it.body })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
