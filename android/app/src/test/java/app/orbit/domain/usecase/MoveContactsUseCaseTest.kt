package app.orbit.domain.usecase

import app.orbit.data.dao.RecordingListMembershipDao
import app.orbit.data.dao.TestListDaoStub
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.domain.FakeListRepository
import app.orbit.domain.clock.Clock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [MoveContactsUseCase] — MOVE-03.
 *
 * Aligned with the post-review ctor (`TransactionRunner` + `ListMembershipDao` +
 * `ListDao` + `Clock`) and the new "snapshot-and-restore" inverse contract:
 *
 *   forward — `moveAll(fromListId, toListId, ids, nowMs)`
 *   inverse — `removeAll(toListId, newlyAdded)` + `insertAll(sourceSnapshot)`
 *
 * The use case short-circuits to `Result(inverse = {}, label = "")` when
 *   - `contactIds` is empty, or
 *   - `fromListId == toListId`, or
 *   - the destination list is missing or archived.
 */
class MoveContactsUseCaseTest {

    private val fixedNow: Instant = Instant.parse("2026-04-25T12:00:00Z")
    private val pre: Instant = Instant.parse("2026-04-01T08:00:00Z")
    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = fixedNow
    }

    /** Pass-through transaction runner — runs the block directly on the calling coroutine. */
    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    /** Destination list always present + non-archived for the happy path. */
    private fun listDaoWithBoth(): TestListDaoStub = TestListDaoStub(
        listOf(
            ListEntity(id = 10L, name = "Source", sortOrder = 0),
            ListEntity(id = 20L, name = "Target", sortOrder = 1),
        ),
    )

    @Test
    fun invoke_dispatches_moveAll_with_args_in_order() = runTest {
        val dao = RecordingListMembershipDao()
        val useCase = MoveContactsUseCase(passThruTx, dao, listDaoWithBoth(), FakeListRepository(), fixedClock)

        useCase(fromListId = 10L, toListId = 20L, contactIds = listOf(1L, 2L, 3L), targetListName = "Target")

        val call = dao.moveCalls.single()
        assertEquals(10L, call.fromListId)
        assertEquals(20L, call.toListId)
        assertEquals(listOf(1L, 2L, 3L), call.ids)
        assertEquals(fixedNow.toEpochMilli(), call.nowMs)
    }

    @Test
    fun result_label_uses_target_name_and_count() = runTest {
        val dao = RecordingListMembershipDao()
        val useCase = MoveContactsUseCase(passThruTx, dao, listDaoWithBoth(), FakeListRepository(), fixedClock)

        val result = useCase(10L, 20L, listOf(1L, 2L), "Inner orbit")

        assertEquals("Moved 2 to Inner orbit", result.label)
    }

    @Test
    fun result_inverse_restores_source_via_removeAll_and_insertAll() = runTest {
        val dao = RecordingListMembershipDao()
        // Seed source-side rows so the use case can snapshot them and the inverse
        // can replay the snapshotted addedAt verbatim (NOT clock.now()).
        dao.seed(
            ListMembershipEntity(contactId = 1L, listId = 10L, addedAt = pre, nextDueAt = null),
            ListMembershipEntity(contactId = 2L, listId = 10L, addedAt = pre, nextDueAt = null),
        )
        val useCase = MoveContactsUseCase(passThruTx, dao, listDaoWithBoth(), FakeListRepository(), fixedClock)

        val result = useCase(10L, 20L, listOf(1L, 2L), "Target")
        dao.clearCalls() // discard the forward dispatch — assert only on the inverse
        result.inverse.invoke()

        // Inverse uses removeAll(toListId, newlyAdded) + insertAll(sourceSnapshot).
        val removeCall = dao.removeCalls.single()
        assertEquals(20L, removeCall.fromListId)
        assertEquals(listOf(1L, 2L), removeCall.ids)

        val insertCall = dao.insertCalls.single()
        assertEquals(2, insertCall.memberships.size)
        // addedAt was restored verbatim from the pre-move snapshot.
        assertTrue(insertCall.memberships.all { it.addedAt == pre }, "inverse must replay snapshotted addedAt")
        assertEquals(setOf(1L, 2L), insertCall.memberships.map { it.contactId }.toSet())
        // Restored rows live on the source list.
        assertTrue(insertCall.memberships.all { it.listId == 10L })
    }

    @Test
    fun empty_id_list_short_circuits_no_dao_call() = runTest {
        val dao = RecordingListMembershipDao()
        val useCase = MoveContactsUseCase(passThruTx, dao, listDaoWithBoth(), FakeListRepository(), fixedClock)

        val result = useCase(10L, 20L, emptyList(), "Target")

        assertTrue(dao.moveCalls.isEmpty())
        assertTrue(dao.removeCalls.isEmpty())
        assertTrue(dao.insertCalls.isEmpty())
        assertEquals("", result.label)
    }

    @Test
    fun same_list_short_circuits_no_dao_call() = runTest {
        val dao = RecordingListMembershipDao()
        val useCase = MoveContactsUseCase(passThruTx, dao, listDaoWithBoth(), FakeListRepository(), fixedClock)

        val result = useCase(10L, 10L, listOf(1L), "Same")

        assertTrue(dao.moveCalls.isEmpty())
        assertEquals("", result.label)
    }

    @Test
    fun archived_destination_short_circuits_no_dao_call() = runTest {
        val dao = RecordingListMembershipDao()
        val listDaoArchived = TestListDaoStub(
            listOf(
                ListEntity(id = 10L, name = "Source", sortOrder = 0),
                ListEntity(id = 20L, name = "Archived target", sortOrder = 1, isArchived = true),
            ),
        )
        val useCase = MoveContactsUseCase(passThruTx, dao, listDaoArchived, FakeListRepository(), fixedClock)

        val result = useCase(10L, 20L, listOf(1L), "Archived target")

        assertTrue(dao.moveCalls.isEmpty())
        assertEquals("", result.label)
    }

    @Test
    fun missing_destination_short_circuits_no_dao_call() = runTest {
        val dao = RecordingListMembershipDao()
        // Only the source list exists — destination is absent.
        val listDaoSourceOnly = TestListDaoStub(
            listOf(ListEntity(id = 10L, name = "Source", sortOrder = 0)),
        )
        val useCase = MoveContactsUseCase(passThruTx, dao, listDaoSourceOnly, FakeListRepository(), fixedClock)

        val result = useCase(10L, 20L, listOf(1L), "Missing")

        assertTrue(dao.moveCalls.isEmpty())
        assertEquals("", result.label)
    }
}
