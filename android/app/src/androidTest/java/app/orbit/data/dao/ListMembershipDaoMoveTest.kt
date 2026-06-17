package app.orbit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Atomicity test for [ListMembershipDao.moveAll] — covers MOVE-03's `@Transaction`
 * contract end-to-end at the DAO layer. Three tests:
 *   1. Happy path — N rows move from source to target in one transaction.
 *   2. Empty-list no-op — `IN ()` SQL syntax pitfall avoided by Room's empty-list handling.
 *   3. `addedAt` write-through — target rows record the `nowMs` parameter as their
 *      `addedAt` Instant (DAO converts via [Instant.ofEpochMilli]).
 *
 * Negative paths (transaction rollback on synthetic exception) are deferred — Room's
 * `@Transaction` rollback is heavily exercised upstream and a synthetic-failure test
 * is low-yield. If a regression appears, add a targeted test then.
 *
 * Uses in-memory [OrbitDatabase] (no SQLCipher) per the sibling migration-test
 * pattern. `connectedDebugAndroidTest` execution runs on a connected device, the
 * same precedent as Migration1To2Test.
 */
@RunWith(AndroidJUnit4::class)
class ListMembershipDaoMoveTest {

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
    fun moveAll_removes_from_source_and_inserts_into_target() = runBlocking {
        val seedTime = Instant.parse("2026-04-25T12:00:00Z")
        val sourceListId = listDao.insert(ListEntity(name = "Source", sortOrder = 0))
        val targetListId = listDao.insert(ListEntity(name = "Target", sortOrder = 1))
        val c1 = contactDao.insert(
            ContactEntity(
                phoneNumber = "+15551110001",
                normalizedPhone = "+15551110001",
                displayName = "Alex",
                firstSeenByAppAt = seedTime,
            ),
        )
        val c2 = contactDao.insert(
            ContactEntity(
                phoneNumber = "+15551110002",
                normalizedPhone = "+15551110002",
                displayName = "Sam",
                firstSeenByAppAt = seedTime,
            ),
        )
        val c3 = contactDao.insert(
            ContactEntity(
                phoneNumber = "+15551110003",
                normalizedPhone = "+15551110003",
                displayName = "Jordan",
                firstSeenByAppAt = seedTime,
            ),
        )
        membershipDao.insert(
            ListMembershipEntity(listId = sourceListId, contactId = c1, addedAt = seedTime),
        )
        membershipDao.insert(
            ListMembershipEntity(listId = sourceListId, contactId = c2, addedAt = seedTime),
        )
        membershipDao.insert(
            ListMembershipEntity(listId = sourceListId, contactId = c3, addedAt = seedTime),
        )

        // Move c1 + c2 to target; c3 stays in source.
        membershipDao.moveAll(sourceListId, targetListId, listOf(c1, c2), nowMs = 100L)

        val sourceAfter = membershipDao.observeByListId(sourceListId).first()
        val targetAfter = membershipDao.observeByListId(targetListId).first()

        assertEquals(1, sourceAfter.size, "source must keep the un-moved row")
        assertEquals(c3, sourceAfter[0].contactId)
        assertEquals(2, targetAfter.size, "target must receive both moved rows")
        assertEquals(setOf(c1, c2), targetAfter.map { it.contactId }.toSet())
    }

    @Test
    fun moveAll_with_empty_id_list_is_no_op() = runBlocking {
        val seedTime = Instant.parse("2026-04-25T12:00:00Z")
        val sourceListId = listDao.insert(ListEntity(name = "Source", sortOrder = 0))
        val targetListId = listDao.insert(ListEntity(name = "Target", sortOrder = 1))
        val c1 = contactDao.insert(
            ContactEntity(
                phoneNumber = "+15551110011",
                normalizedPhone = "+15551110011",
                displayName = "Alex",
                firstSeenByAppAt = seedTime,
            ),
        )
        membershipDao.insert(
            ListMembershipEntity(listId = sourceListId, contactId = c1, addedAt = seedTime),
        )

        membershipDao.moveAll(sourceListId, targetListId, emptyList(), nowMs = 100L)

        assertEquals(1, membershipDao.observeByListId(sourceListId).first().size)
        assertEquals(0, membershipDao.observeByListId(targetListId).first().size)
    }

    @Test
    fun moveAll_writes_addedAt_for_target_rows() = runBlocking {
        val seedTime = Instant.parse("2026-04-25T12:00:00Z")
        val sourceListId = listDao.insert(ListEntity(name = "S", sortOrder = 0))
        val targetListId = listDao.insert(ListEntity(name = "T", sortOrder = 1))
        val c1 = contactDao.insert(
            ContactEntity(
                phoneNumber = "+15551110021",
                normalizedPhone = "+15551110021",
                displayName = "Alex",
                firstSeenByAppAt = seedTime,
            ),
        )
        membershipDao.insert(
            ListMembershipEntity(listId = sourceListId, contactId = c1, addedAt = seedTime),
        )

        membershipDao.moveAll(sourceListId, targetListId, listOf(c1), nowMs = 9999L)

        val targetRow = membershipDao.get(c1, targetListId)
        assertNotNull(targetRow)
        assertEquals(
            Instant.ofEpochMilli(9999L),
            targetRow.addedAt,
            "moveAll converts nowMs (Long epoch-millis) to Instant for target row addedAt",
        )
    }
}
