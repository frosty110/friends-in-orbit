package app.orbit.domain.usecase

import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.dao.TestListDaoStub
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.serialization.PreIgnoreMembershipsSnapshot
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Test

/**
 * Exercises [UnignoreContactUseCase] against its contracts. Pure JVM
 * (no Robolectric / no Room).
 *
 * Verifies the drift-detection invariant: snapshot memberships are restored
 * iff the list still exists AND is not archived AND the membership isn't
 * already current.
 */
class UnignoreContactUseCaseTest {

    private val T0: Instant = Instant.parse("2026-04-25T12:00:00Z")
    private val T1: Instant = Instant.parse("2026-04-30T12:00:00Z")

    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    /**
     * Subclass that seeds [observeByContactId] from a backing list and records
     * each [insert] call so the drift-restore assertion can verify which listIds
     * were re-membershipped.
     */
    private class SeededListMembershipDao(
        private val memberships: List<ListMembershipEntity>,
    ) : RecordingListMembershipDao() {
        val capturedInserts: MutableList<ListMembershipEntity> = mutableListOf()

        override fun observeByContactId(contactId: Long): Flow<List<ListMembershipEntity>> =
            flowOf(memberships.filter { it.contactId == contactId })

        override suspend fun getMembershipsForContact(contactId: Long): List<ListMembershipEntity> =
            memberships.filter { it.contactId == contactId }

        override suspend fun insert(membership: ListMembershipEntity): Long {
            capturedInserts += membership
            return 1L
        }
    }

    @Test
    fun `invoke restores memberships that drifted away during ignore window`() = runTest {
        // Snapshot at ignore time: contact was on lists [1, 2, 3].
        val snapshotJson = JsonProvider.json.encodeToString(
            PreIgnoreMembershipsSnapshot(listIds = listOf(1L, 2L, 3L)),
        )
        val ignoredContact = contactFixture(id = 42L, isIgnored = true).copy(
            ignoredAt = T0,
            preIgnoreListMembershipsJson = snapshotJson,
        )
        val contactRepo = FakeContactRepository(listOf(ignoredContact))
        // Drift: only list 2 still has a membership for contact 42.
        val currentMemberships = listOf(
            ListMembershipEntity(contactId = 42L, listId = 2L, addedAt = T0),
        )
        val membershipDao = SeededListMembershipDao(currentMemberships)
        // All three lists are still active (none archived).
        val listDao = TestListDaoStub(
            lists = listOf(listFixture(id = 1L), listFixture(id = 2L), listFixture(id = 3L)),
        )
        val useCase = UnignoreContactUseCase(
            passThruTx, contactRepo, listDao, membershipDao, FakeListRepository(), TestClock(T1),
        )

        useCase(contactId = 42L)

        // Drift restore: lists 1 and 3 are re-membershipped (NOT 2 — already current).
        val insertedListIds = membershipDao.capturedInserts.map { it.listId }.toSet()
        assertEquals(setOf(1L, 3L), insertedListIds, "drift restore must skip current memberships")
        // Restored memberships use clock.now() as addedAt.
        membershipDao.capturedInserts.forEach { row ->
            assertEquals(T1, row.addedAt, "restored membership.addedAt = clock.now()")
            assertEquals(42L, row.contactId)
        }
        // The four ignore columns are cleared via markIgnored.
        val markIgnoredArgs = contactRepo.markIgnoredCalls.single()
        assertEquals(42L, markIgnoredArgs.contactId)
        assertEquals(false, markIgnoredArgs.isIgnored)
        assertNull(markIgnoredArgs.ignoredAt)
        assertNull(markIgnoredArgs.preIgnoreListMembershipsJson)
    }

    @Test
    fun `invoke skips memberships whose list was archived during ignore window`() = runTest {
        // Snapshot: contact was on lists [1, 2, 3].
        val snapshotJson = JsonProvider.json.encodeToString(
            PreIgnoreMembershipsSnapshot(listIds = listOf(1L, 2L, 3L)),
        )
        val ignoredContact = contactFixture(id = 42L, isIgnored = true).copy(
            ignoredAt = T0,
            preIgnoreListMembershipsJson = snapshotJson,
        )
        val contactRepo = FakeContactRepository(listOf(ignoredContact))
        // No current memberships — all three drifted away.
        val membershipDao = SeededListMembershipDao(memberships = emptyList())
        // List 2 was archived during the ignore window; lists 1 and 3 are still active.
        val listDao = TestListDaoStub(
            lists = listOf(
                listFixture(id = 1L),
                listFixture(id = 2L, isArchived = true),
                listFixture(id = 3L),
            ),
        )
        val useCase = UnignoreContactUseCase(
            passThruTx, contactRepo, listDao, membershipDao, FakeListRepository(), TestClock(T1),
        )

        useCase(contactId = 42L)

        // Only lists 1 and 3 are restored — list 2 is silently skipped (archived).
        val insertedListIds = membershipDao.capturedInserts.map { it.listId }.toSet()
        assertEquals(setOf(1L, 3L), insertedListIds, "archived list must be skipped, not re-targeted")
    }

    @Test
    fun `invoke handles null snapshot (legacy contact) without crashing`() = runTest {
        // Legacy contact ignored before the snapshot column existed — not populated.
        val ignoredContact = contactFixture(id = 42L, isIgnored = true).copy(
            ignoredAt = T0,
            preIgnoreListMembershipsJson = null,
        )
        val contactRepo = FakeContactRepository(listOf(ignoredContact))
        val membershipDao = SeededListMembershipDao(memberships = emptyList())
        val listDao = TestListDaoStub(lists = listOf(listFixture(id = 1L)))
        val useCase = UnignoreContactUseCase(
            passThruTx, contactRepo, listDao, membershipDao, FakeListRepository(), TestClock(T1),
        )

        useCase(contactId = 42L)

        // No memberships restored (snapshot was null).
        assertTrue(membershipDao.capturedInserts.isEmpty(), "null snapshot → no restores")
        // Four ignore columns still cleared.
        val args = contactRepo.markIgnoredCalls.single()
        assertEquals(false, args.isIgnored)
        assertNull(args.ignoredAt)
        assertNull(args.preIgnoreListMembershipsJson)
    }
}
