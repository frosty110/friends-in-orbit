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
 * Unit tests for [CopyContactsUseCase] — MOVE-04.
 *
 * Aligned with the post-review ctor (`TransactionRunner` + `ListMembershipDao` +
 * `ListDao` + `Clock`). Behavior contract:
 *
 *   forward — `insertAll(notYetMembers)` ONLY (skips IDs already on target).
 *   inverse — `removeAll(toListId, notYetMembers)` ONLY.
 *
 * The use case short-circuits to `Result(inverse = {}, label = "")` when
 *   - `contactIds` is empty, or
 *   - the destination list is missing or archived.
 */
class CopyContactsUseCaseTest {

    private val fixedNow: Instant = Instant.parse("2026-04-25T12:00:00Z")
    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = fixedNow
    }

    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    private fun listDaoWithTarget(): TestListDaoStub = TestListDaoStub(
        listOf(ListEntity(id = 20L, name = "Target", sortOrder = 0)),
    )

    @Test
    fun invoke_inserts_only_notYetMembers_not_existing_ones() = runTest {
        val dao = RecordingListMembershipDao()
        // contactId=2 is already on the target — it must NOT be re-inserted.
        dao.seed(
            ListMembershipEntity(contactId = 2L, listId = 20L, addedAt = fixedNow, nextDueAt = null),
        )
        val useCase = CopyContactsUseCase(passThruTx, dao, listDaoWithTarget(), FakeListRepository(), fixedClock)

        useCase(toListId = 20L, contactIds = listOf(1L, 2L, 3L), targetListName = "Target")

        // Only contactIds 1 and 3 should be inserted (2 was pre-existing).
        val insertCall = dao.insertCalls.single()
        assertEquals(setOf(1L, 3L), insertCall.memberships.map { it.contactId }.toSet())
        assertTrue(insertCall.memberships.all { it.listId == 20L })
        assertTrue(insertCall.memberships.all { it.addedAt == fixedNow })
    }

    @Test
    fun invoke_with_no_pre_existing_inserts_all_ids() = runTest {
        val dao = RecordingListMembershipDao()
        val useCase = CopyContactsUseCase(passThruTx, dao, listDaoWithTarget(), FakeListRepository(), fixedClock)

        useCase(20L, listOf(1L, 2L, 3L), "Target")

        val insertCall = dao.insertCalls.single()
        assertEquals(setOf(1L, 2L, 3L), insertCall.memberships.map { it.contactId }.toSet())
    }

    @Test
    fun invoke_with_all_pre_existing_skips_insert_entirely() = runTest {
        val dao = RecordingListMembershipDao()
        dao.seed(
            ListMembershipEntity(contactId = 1L, listId = 20L, addedAt = fixedNow),
            ListMembershipEntity(contactId = 2L, listId = 20L, addedAt = fixedNow),
        )
        val useCase = CopyContactsUseCase(passThruTx, dao, listDaoWithTarget(), FakeListRepository(), fixedClock)

        useCase(20L, listOf(1L, 2L), "Target")

        // Every id is already a member — no insert dispatched.
        assertTrue(dao.insertCalls.isEmpty())
    }

    @Test
    fun result_label_uses_target_name_and_full_count() = runTest {
        val dao = RecordingListMembershipDao()
        val useCase = CopyContactsUseCase(passThruTx, dao, listDaoWithTarget(), FakeListRepository(), fixedClock)

        val result = useCase(20L, listOf(1L, 2L), "Inner orbit")

        // Label uses the full input count, NOT just the notYetMembers count
        // (per the use case contract — see CopyContactsUseCase docstring).
        assertEquals("Copied 2 to Inner orbit", result.label)
    }

    @Test
    fun result_inverse_removes_only_notYetMembers() = runTest {
        val dao = RecordingListMembershipDao()
        // contactId=2 is pre-existing on the target.
        dao.seed(
            ListMembershipEntity(contactId = 2L, listId = 20L, addedAt = fixedNow),
        )
        val useCase = CopyContactsUseCase(passThruTx, dao, listDaoWithTarget(), FakeListRepository(), fixedClock)

        val result = useCase(20L, listOf(1L, 2L, 3L), "Target")
        dao.clearCalls()
        result.inverse.invoke()

        // Inverse must remove only the rows the forward call inserted —
        // pre-existing memberships (contactId=2) must NOT be deleted.
        val removeCall = dao.removeCalls.single()
        assertEquals(20L, removeCall.fromListId)
        assertEquals(setOf(1L, 3L), removeCall.ids.toSet())
    }

    @Test
    fun result_inverse_with_no_inserts_dispatches_nothing() = runTest {
        val dao = RecordingListMembershipDao()
        // All ids pre-existing → nothing inserted → nothing to remove on undo.
        dao.seed(
            ListMembershipEntity(contactId = 1L, listId = 20L, addedAt = fixedNow),
            ListMembershipEntity(contactId = 2L, listId = 20L, addedAt = fixedNow),
        )
        val useCase = CopyContactsUseCase(passThruTx, dao, listDaoWithTarget(), FakeListRepository(), fixedClock)

        val result = useCase(20L, listOf(1L, 2L), "Target")
        dao.clearCalls()
        result.inverse.invoke()

        assertTrue(dao.removeCalls.isEmpty())
    }

    @Test
    fun empty_id_list_short_circuits_no_dao_call() = runTest {
        val dao = RecordingListMembershipDao()
        val useCase = CopyContactsUseCase(passThruTx, dao, listDaoWithTarget(), FakeListRepository(), fixedClock)

        val result = useCase(20L, emptyList(), "Target")

        assertTrue(dao.insertCalls.isEmpty())
        assertTrue(dao.removeCalls.isEmpty())
        assertEquals("", result.label)
    }

    @Test
    fun archived_destination_short_circuits_no_dao_call() = runTest {
        val dao = RecordingListMembershipDao()
        val listDaoArchived = TestListDaoStub(
            listOf(ListEntity(id = 20L, name = "Archived", sortOrder = 0, isArchived = true)),
        )
        val useCase = CopyContactsUseCase(passThruTx, dao, listDaoArchived, FakeListRepository(), fixedClock)

        val result = useCase(20L, listOf(1L), "Archived")

        assertTrue(dao.insertCalls.isEmpty())
        assertEquals("", result.label)
    }

    @Test
    fun missing_destination_short_circuits_no_dao_call() = runTest {
        val dao = RecordingListMembershipDao()
        val listDaoEmpty = TestListDaoStub(emptyList())
        val useCase = CopyContactsUseCase(passThruTx, dao, listDaoEmpty, FakeListRepository(), fixedClock)

        val result = useCase(20L, listOf(1L), "Missing")

        assertTrue(dao.insertCalls.isEmpty())
        assertEquals("", result.label)
    }
}
