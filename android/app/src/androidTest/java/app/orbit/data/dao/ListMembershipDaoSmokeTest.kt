package app.orbit.data.dao

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for [ListMembershipDao] — proves the composite PK (contactId, listId)
 * is enforced and that the FK onDelete = CASCADE declared in [ListMembershipEntity]
 * fires end-to-end when the parent [ContactEntity] row is deleted.
 *
 * Uses the in-memory [OrbitDatabase] builder (no SQLCipher); encryption correctness
 * is verified separately by an adb bytes-check.
 */
@RunWith(AndroidJUnit4::class)
class ListMembershipDaoSmokeTest {

    private lateinit var db: OrbitDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var listDao: ListDao
    private lateinit var membershipDao: ListMembershipDao

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contactDao = db.contactDao()
        listDao = db.listDao()
        membershipDao = db.listMembershipDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun compositePrimaryKey_rejectsDuplicateInsert() = runTest {
        val contactId = contactDao.insert(
            ContactEntity(
                phoneNumber = "+15551110000",
                normalizedPhone = "+15551110000",
                displayName = "Alice",
                firstSeenByAppAt = Instant.parse("2026-04-23T12:00:00Z"),
            ),
        )
        val listId = listDao.insert(ListEntity(name = "Inner orbit", sortOrder = 0))
        val membership = ListMembershipEntity(
            contactId = contactId,
            listId = listId,
            addedAt = Instant.parse("2026-04-23T12:00:00Z"),
        )

        membershipDao.insert(membership)

        // Second insert with the same (contactId, listId) composite PK must fail
        // per the @Insert(onConflict = ABORT) contract on ListMembershipDao.
        assertFailsWith<SQLiteConstraintException> {
            membershipDao.insert(membership)
        }

        // Sanity: observeByListId still sees exactly one row.
        val rows = membershipDao.observeByListId(listId).first()
        assertEquals(1, rows.size)
    }

    @Test
    fun deletingContact_cascadesMembershipDeletion() = runTest {
        val contact = ContactEntity(
            phoneNumber = "+15552220000",
            normalizedPhone = "+15552220000",
            displayName = "Bob",
            firstSeenByAppAt = Instant.parse("2026-04-23T12:00:00Z"),
        )
        val contactId = contactDao.insert(contact)
        val listId = listDao.insert(ListEntity(name = "Late night", sortOrder = 0))
        membershipDao.insert(
            ListMembershipEntity(
                contactId = contactId,
                listId = listId,
                addedAt = Instant.parse("2026-04-23T12:00:00Z"),
            ),
        )

        // Pre-condition: membership exists.
        assertNotNull(membershipDao.get(contactId, listId))

        // Delete the parent contact; FK onDelete = CASCADE must remove the membership.
        val insertedContact = contactDao.get(contactId)
        assertNotNull(insertedContact)
        contactDao.delete(insertedContact)

        // Post-condition: cascade fired; membership row is gone.
        assertNull(membershipDao.get(contactId, listId))
    }
}
