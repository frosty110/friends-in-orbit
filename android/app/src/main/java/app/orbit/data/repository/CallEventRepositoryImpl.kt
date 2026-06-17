package app.orbit.data.repository

import androidx.room.withTransaction
import app.orbit.data.dao.CallEventDao
import app.orbit.data.dao.ContactDao
import app.orbit.data.dao.ListDao
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.CallEventEntity
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of CallEventRepository.
 *
 * `markCalledAtomic` wraps the multi-table write in `db.withTransaction { ... }` — this
 * extends the existing @Transaction boundary on `ContactDao.insertCallEventAndTouchContact`
 * across the per-membership update loop, so observers see all writes (call event + contact
 * touch + N membership updates) atomically.
 *
 * The legacy `observeAll()` sentinel was deleted along with the
 * `OBSERVE_ALL_LIMIT = Int.MAX_VALUE` companion constant. Two scoped overrides
 * replace it: [observeForContact] (single-contact, explicit limit) for the
 * ContactDetail call-history feed, and [observeAggregatesForContacts] (per-contact
 * COUNT + lastAt) for picker / search / smart-list decoration.
 */
internal class CallEventRepositoryImpl @Inject constructor(
    private val db: OrbitDatabase,
    private val callEventDao: CallEventDao,
    private val contactDao: ContactDao,
    private val listMembershipDao: ListMembershipDao,
    private val listDao: ListDao,
) : CallEventRepository {

    override fun observeForContact(contactId: Long, limit: Int): Flow<List<CallEventEntity>> =
        callEventDao.observeForContact(contactId, limit)

    override fun observeAggregatesForContacts(ids: List<Long>): Flow<Map<Long, CallAgg>> =
        callEventDao.observeAggregatesForContacts(ids).map { rows ->
            rows.associate { it.contactId to CallAgg(it.cnt, it.lastAt) }
        }

    override fun observeRecentForListContacts(listId: Long): Flow<List<CallEventEntity>> =
        callEventDao.observeForListContacts(listId)

    override fun observeLatestPerContactInList(listId: Long): Flow<Map<Long, CallEventEntity>> =
        callEventDao.observeLatestPerContactInList(listId).map { rows ->
            // WR-01 fix — the DAO @Query INNER-JOINs on (contactId, MAX(occurredAt));
            // when two events for the same contact share an exact occurredAt the
            // join returns both rows. `associateBy` would silently last-row-wins
            // with no determinism on which row survives. Group + deterministic
            // tiebreak by primary key (highest id wins — matches "last write wins"
            // for events recorded in insertion order).
            rows.groupBy { it.contactId }
                .mapValues { (_, evs) -> evs.maxBy { it.id } }
        }

    override suspend fun insert(event: CallEventEntity): Long = callEventDao.insert(event)

    override suspend fun markCalledAtomic(
        contactId: Long,
        event: CallEventEntity,
        nextDueByListId: Map<Long, Instant?>,
    ) {
        require(event.contactId == contactId) {
            "CallEventEntity.contactId=${event.contactId} does not match contactId=$contactId"
        }
        db.withTransaction {
            val contact = contactDao.get(contactId)
                ?: error("markCalledAtomic: contactId=$contactId not present in contacts")
            // Reuse the DAO's @Transaction primitive; its boundary nests inside withTransaction.
            contactDao.insertCallEventAndTouchContact(event, contact)
            // `nowMs` snapshot used for every per-listId
            // `recomputeDueCount` predicate inside this transaction. The
            // call's `occurredAt` is the canonical "now" for the dueCount
            // snapshot: any membership whose freshly-written `nextDueAt`
            // is > occurredAt is excluded; <= occurredAt (or NULL) is
            // included. This keeps `lists.dueCount` aligned with the
            // moment of the call, even when the call is recorded
            // retroactively (delayed CallLogSyncWorker run, T-16-08).
            val nowMs = event.occurredAt.toEpochMilli()
            for ((listId, nextDueAt) in nextDueByListId) {
                val membership = listMembershipDao.get(contactId, listId) ?: continue
                // DOM-06 invariant: recording a call resets skipCount so the next surfacing
                // cycle doesn't re-apply stale skip penalty on top of the fresh cooldown
                // (MarkCalledUseCase already passes skipCount=0 into RuleContext; the DB
                // must match or skipCount accumulates monotonically).
                listMembershipDao.update(membership.copy(nextDueAt = nextDueAt, skipCount = 0))
                // Keep `lists.dueCount` fresh atomically with
                // the nextDueAt write. SQL-only (DAO @Query) so the recompute
                // stays inside the same `withTransaction` boundary; no
                // dispatcher switch inside the transaction (Pitfall 3 — a
                // context switch here would release the transaction's thread
                // confinement and risk deadlock).
                listDao.recomputeDueCount(listId, nowMs)
            }
        }
    }

    override fun observeForLog(limit: Int): Flow<List<CallEventEntity>> =
        callEventDao.observeForLog(limit)

    override suspend fun latestUnnotedOutgoing(since: Instant): CallEventEntity? =
        callEventDao.latestUnnotedOutgoing(since)

    override suspend fun byId(id: Long): CallEventEntity? = callEventDao.getById(id)

    override suspend fun snapshotAll(): List<CallEventEntity> = callEventDao.snapshotAll()

    override fun observeAggregatesAll(): Flow<Map<Long, CallAgg>> =
        callEventDao.observeAggregatesAll().map { rows ->
            rows.associate { it.contactId to CallAgg(it.cnt, it.lastAt) }
        }
}
