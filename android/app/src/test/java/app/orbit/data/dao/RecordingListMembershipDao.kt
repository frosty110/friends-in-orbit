package app.orbit.data.dao

import app.orbit.data.entity.ListMembershipEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test-only recording fake for [ListMembershipDao]. Captures calls to
 * `moveAll` / `removeAll` / `insertAll` for assertion. Used by
 * [app.orbit.domain.usecase.MoveContactsUseCaseTest] and
 * [app.orbit.domain.usecase.CopyContactsUseCaseTest] to verify the use cases
 * dispatch the right DAO method exactly once with the right arguments.
 *
 * The `moveAll` override deliberately does NOT call the default body
 * (`removeAll + insertAll`) — boundary-level tests assert at the `moveAll`
 * seam, not at downstream removeAll/insertAll dispatches. DAO-layer
 * atomicity is verified separately by an androidTest.
 */
open class RecordingListMembershipDao : ListMembershipDao {

    data class MoveCall(
        val fromListId: Long,
        val toListId: Long,
        val ids: List<Long>,
        val nowMs: Long,
    )
    data class RemoveCall(val fromListId: Long, val ids: List<Long>)
    data class InsertCall(val memberships: List<ListMembershipEntity>)
    data class UpdateNextDueAtCall(
        val contactId: Long,
        val listId: Long,
        val nextDueAt: Instant?,
    )

    val moveCalls: MutableList<MoveCall> = mutableListOf()
    val removeCalls: MutableList<RemoveCall> = mutableListOf()
    val insertCalls: MutableList<InsertCall> = mutableListOf()
    val updateNextDueAtCalls: MutableList<UpdateNextDueAtCall> = mutableListOf()

    /**
     * In-memory backing for `get(contactId, listId)` and `observeByContactId`.
     * Subclasses may override either accessor; tests that need to seed source-side
     * snapshots (e.g. MoveContactsUseCaseTest verifying inverse restoration) call
     * [seed] to populate this map and then read it back via the default `get()`
     * implementation below.
     */
    private val seeded: MutableMap<Pair<Long, Long>, ListMembershipEntity> = mutableMapOf()

    /** Seed in-memory rows so [get] can return them (used by Move/Copy snapshot tests). */
    fun seed(vararg rows: ListMembershipEntity) {
        rows.forEach { seeded[it.contactId to it.listId] = it }
    }

    /** Discard recorded forward calls so an inverse-only assertion can isolate undo dispatches. */
    fun clearCalls() {
        moveCalls.clear()
        removeCalls.clear()
        insertCalls.clear()
        updateNextDueAtCalls.clear()
    }

    override fun observeAll(): Flow<List<ListMembershipEntity>> =
        flowOf(emptyList())

    override fun observeByListId(listId: Long): Flow<List<ListMembershipEntity>> =
        flowOf(emptyList())

    override fun observeByContactId(contactId: Long): Flow<List<ListMembershipEntity>> =
        flowOf(emptyList())

    override suspend fun get(contactId: Long, listId: Long): ListMembershipEntity? =
        seeded[contactId to listId]

    // Default no-op snapshot variants. IgnoreContactUseCaseTest /
    // UnignoreContactUseCaseTest override these in inline subclasses to seed
    // reads — same shape as the existing `observeByContactId`/`observeByListId`
    // override pattern used by those tests.
    override suspend fun getMembershipsForContact(contactId: Long): List<ListMembershipEntity> =
        emptyList()

    override suspend fun getMembersOfList(listId: Long): List<ListMembershipEntity> =
        emptyList()

    // The four methods below are `open` so use-case tests
    // (IgnoreContactUseCaseTest, UnignoreContactUseCaseTest) can override
    // them in inline subclasses to seed reads or capture writes — Pitfall 1
    // delete-path assertion + drift-restore insert capture.
    override suspend fun insert(membership: ListMembershipEntity): Long = 1L

    /**
     * ONB-19 — IGNORE-on-conflict variant. Default is the same trivial
     * `1L` return as [insert]; tests that need to observe the addMember path
     * subclass and override (matching the existing `insert` override pattern
     * in [app.orbit.domain.usecase.UnignoreContactUseCaseTest]).
     */
    override suspend fun insertOrIgnore(membership: ListMembershipEntity): Long = 1L

    override suspend fun update(membership: ListMembershipEntity): Int = 1

    override suspend fun delete(membership: ListMembershipEntity): Int = 1

    override suspend fun deleteByPair(contactId: Long, listId: Long): Int = 1

    override suspend fun updateNextDueAt(
        contactId: Long,
        listId: Long,
        nextDueAt: Instant?,
    ): Int {
        updateNextDueAtCalls += UpdateNextDueAtCall(contactId, listId, nextDueAt)
        return 1
    }

    override suspend fun removeAll(fromListId: Long, ids: List<Long>) {
        removeCalls += RemoveCall(fromListId, ids.toList())
    }

    override suspend fun insertAll(memberships: List<ListMembershipEntity>) {
        insertCalls += InsertCall(memberships.toList())
    }

    override suspend fun moveAll(
        fromListId: Long,
        toListId: Long,
        ids: List<Long>,
        nowMs: Long,
    ) {
        moveCalls += MoveCall(fromListId, toListId, ids.toList(), nowMs)
    }

    // Bulk paths don't exercise this; default to empty map.
    override fun observeMemberCountsByListId(): Flow<Map<Long, Int>> =
        flowOf(emptyMap())
}
