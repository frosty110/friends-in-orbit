package app.orbit.domain.usecase

import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.serialization.PreIgnoreMembershipsSnapshot
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Test

/**
 * Exercises [IgnoreContactUseCase]. Pure JVM (no Robolectric / no Room) —
 * uses the [RecordingListMembershipDao] subclass for membership state and
 * the [FakeContactRepository] for the four-column write capture.
 *
 * Pitfall 1 mitigation is asserted directly: no DAO delete-method records a
 * call after invoke().
 */
class IgnoreContactUseCaseTest {

    private val T0: Instant = Instant.parse("2026-04-25T12:00:00Z")

    /** Pass-through TransactionRunner — runs the block directly on the calling coroutine. */
    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    /**
     * Subclass that seeds [observeByContactId] from a backing list so the use case can
     * read the current memberships. Records the [delete] / [deleteByPair] / [removeAll]
     * paths so the Pitfall 1 assertion (no delete) can fail loudly if a regression
     * routes through any of them.
     */
    private class SeededListMembershipDao(
        private val memberships: List<ListMembershipEntity>,
    ) : RecordingListMembershipDao() {
        val deleteCalls: MutableList<ListMembershipEntity> = mutableListOf()
        val deleteByPairCalls: MutableList<Pair<Long, Long>> = mutableListOf()

        override fun observeByContactId(contactId: Long): Flow<List<ListMembershipEntity>> =
            flowOf(memberships.filter { it.contactId == contactId })

        override suspend fun getMembershipsForContact(contactId: Long): List<ListMembershipEntity> =
            memberships.filter { it.contactId == contactId }

        override suspend fun delete(membership: ListMembershipEntity): Int {
            deleteCalls += membership
            return 1
        }

        override suspend fun deleteByPair(contactId: Long, listId: Long): Int {
            deleteByPairCalls += contactId to listId
            return 1
        }
    }

    @Test
    fun `invoke writes isIgnored true ignoredAt and snapshot atomically`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 42L)))
        val membershipDao = SeededListMembershipDao(
            memberships = listOf(
                ListMembershipEntity(contactId = 42L, listId = 10L, addedAt = T0),
                ListMembershipEntity(contactId = 42L, listId = 20L, addedAt = T0),
            ),
        )
        val useCase = IgnoreContactUseCase(passThruTx, contactRepo, membershipDao, FakeListRepository(), TestClock(T0))

        useCase(contactId = 42L, contactName = "Alex Chen")

        val args = contactRepo.markIgnoredCalls.single()
        assertEquals(42L, args.contactId)
        assertEquals(true, args.isIgnored)
        assertEquals(T0, args.ignoredAt)
        // The snapshot JSON decodes back to the membership listIds [10, 20].
        val snapshotJson = args.preIgnoreListMembershipsJson
        assertTrue(snapshotJson != null, "snapshot JSON must be written")
        val decoded = JsonProvider.json.decodeFromString<PreIgnoreMembershipsSnapshot>(snapshotJson)
        assertEquals(listOf(10L, 20L), decoded.listIds)
    }

    @Test
    fun `invoke does NOT delete list memberships (Pitfall 1)`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 42L)))
        val membershipDao = SeededListMembershipDao(
            memberships = listOf(
                ListMembershipEntity(contactId = 42L, listId = 10L, addedAt = T0),
                ListMembershipEntity(contactId = 42L, listId = 20L, addedAt = T0),
            ),
        )
        val useCase = IgnoreContactUseCase(passThruTx, contactRepo, membershipDao, FakeListRepository(), TestClock(T0))

        useCase(contactId = 42L, contactName = "Alex Chen")

        // Pitfall 1: ignoring MUST NOT delete any membership rows.
        assertEquals(0, membershipDao.deleteCalls.size, "Pitfall 1: no delete(membership) calls")
        assertEquals(0, membershipDao.deleteByPairCalls.size, "Pitfall 1: no deleteByPair calls")
        assertEquals(0, membershipDao.removeCalls.size, "Pitfall 1: no removeAll calls")
    }

    @Test
    fun `Result label uses contact name in sentence case`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 42L)))
        val membershipDao = SeededListMembershipDao(memberships = emptyList())
        val useCase = IgnoreContactUseCase(passThruTx, contactRepo, membershipDao, FakeListRepository(), TestClock(T0))

        val result = useCase(contactId = 42L, contactName = "Alex Chen")

        assertEquals("Ignored Alex Chen", result.label)
    }

    @Test
    fun `Result inverse flips four columns back to false null null`() = runTest {
        val contactRepo = FakeContactRepository(listOf(contactFixture(id = 42L)))
        val membershipDao = SeededListMembershipDao(memberships = emptyList())
        val useCase = IgnoreContactUseCase(passThruTx, contactRepo, membershipDao, FakeListRepository(), TestClock(T0))

        val result = useCase(contactId = 42L, contactName = "Alex Chen")
        contactRepo.markIgnoredCalls.clear()  // discard the forward write; assert inverse only
        result.inverse()

        val args = contactRepo.markIgnoredCalls.single()
        assertEquals(42L, args.contactId)
        assertEquals(false, args.isIgnored)
        assertNull(args.ignoredAt)
        assertNull(args.preIgnoreListMembershipsJson)
    }
}
