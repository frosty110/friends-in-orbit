package app.orbit.data.repository

import androidx.room.withTransaction
import app.orbit.data.dao.ListDao
import app.orbit.data.dao.ListMembershipDao
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.domain.JsonProvider
import app.orbit.domain.smart.SmartListEngine
import app.orbit.domain.smart.SmartListRule
import app.orbit.domain.usecase.MutationResult
import java.time.Instant
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Room-backed implementation of ListRepository. Wraps both ListDao (the `lists` table) and
 * ListMembershipDao (the `list_memberships` join table) in one impl.
 *
 * `incrementSkipCount` composes a read + update on the membership row and wraps the pair in
 * `db.withTransaction { }` (mirroring `CallEventRepositoryImpl.markCalledAtomic`) to close
 * the lost-update window between two concurrent writers to the same row.
 *
 * Write surface:
 *  - 8 write methods (LIST-01/02/04/06/08, SMART-06)
 *  - `reorder` rewrites only the affected sortOrder range inside `db.withTransaction` —
 *    range-only renumber mitigation at the data layer.
 *  - `convertSmartToStatic` snapshots smart membership via [SmartListEngine.snapshotOnce],
 *    inserts memberships, and flips type/clears smartRuleJson — all inside one transaction
 *    (same shape as `CallEventRepositoryImpl.markCalledAtomic`).
 */
internal class ListRepositoryImpl @Inject constructor(
    private val db: OrbitDatabase,
    private val listDao: ListDao,
    private val listMembershipDao: ListMembershipDao,
    private val smartListEngine: SmartListEngine,
) : ListRepository {

    private val json = JsonProvider.json

    override fun observeAll(): Flow<List<ListEntity>> = listDao.observeAll()

    override fun observeActive(): Flow<List<ListEntity>> = listDao.observeActive()

    override suspend fun getById(id: Long): ListEntity? = listDao.get(id)

    override fun observeMembersOfList(listId: Long): Flow<List<ListMembershipEntity>> =
        listMembershipDao.observeByListId(listId)

    override fun observeMembershipsForContact(contactId: Long): Flow<List<ListMembershipEntity>> =
        listMembershipDao.observeByContactId(contactId)

    override suspend fun incrementSkipCount(
        contactId: Long,
        listId: Long,
        newNextDueAt: Instant,
    ): MutationResult = db.withTransaction {
        val current = listMembershipDao.get(contactId, listId)
            ?: return@withTransaction MutationResult.MembershipMissing
        listMembershipDao.update(
            current.copy(
                skipCount = current.skipCount + 1,
                nextDueAt = newNextDueAt,
            ),
        )
        MutationResult.Success
    }

    override suspend fun updateNextDueAt(
        contactId: Long,
        listId: Long,
        nextDueAt: Instant,
    ): MutationResult {
        val rows = listMembershipDao.updateNextDueAt(contactId, listId, nextDueAt)
        return if (rows == 0) MutationResult.MembershipMissing else MutationResult.Success
    }

    // Card-loop undo (2026-06-09) — read + full-row update inside one transaction,
    // mirroring `incrementSkipCount` above, so a concurrent writer cannot interleave
    // between the snapshot read and the restore write.
    override suspend fun restoreMembershipSchedule(
        contactId: Long,
        listId: Long,
        nextDueAt: Instant?,
        skipCount: Int,
    ): MutationResult = db.withTransaction {
        val current = listMembershipDao.get(contactId, listId)
            ?: return@withTransaction MutationResult.MembershipMissing
        listMembershipDao.update(
            current.copy(
                nextDueAt = nextDueAt,
                skipCount = skipCount,
            ),
        )
        MutationResult.Success
    }

    // ─── Write surface ─────────────────────────────────────────────────────────────────

    override suspend fun create(list: ListEntity): Long = listDao.insert(list)

    override suspend fun update(list: ListEntity) {
        listDao.update(list)
    }

    override suspend fun setArchived(listId: Long, archived: Boolean) {
        listDao.updateArchived(listId, archived)
    }

    override suspend fun reorder(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        db.withTransaction {
            // Read current active set ordered by sortOrder ASC — source of truth, NOT VM
            // optimistic state. The read happens INSIDE the transaction so a racing emit
            // cannot interleave between read and write.
            val active: List<ListEntity> = listDao.getActive()
            if (fromIndex !in active.indices || toIndex !in active.indices) {
                return@withTransaction
            }
            val moved = active.toMutableList().apply {
                val item = removeAt(fromIndex)
                add(toIndex, item)
            }
            // Range-only renumber: only rows whose new index ≠ old index get a write.
            val lo = minOf(fromIndex, toIndex)
            val hi = maxOf(fromIndex, toIndex)
            for (idx in lo..hi) {
                val row = moved[idx]
                val newSortOrder = idx
                if (row.sortOrder != newSortOrder) {
                    listDao.updateSortOrder(row.id, newSortOrder)
                }
            }
        }
    }

    override suspend fun setSmartRuleJson(listId: Long, json: String?) {
        listDao.updateSmartRuleJson(listId, json)
    }

    override suspend fun setRuleParamsOverrideJson(listId: Long, json: String?) {
        listDao.updateRuleParamsOverrideJson(listId, json)
    }

    override suspend fun convertSmartToStatic(listId: Long) {
        db.withTransaction {
            val list = listDao.get(listId) ?: return@withTransaction
            val ruleJson = list.smartRuleJson ?: return@withTransaction  // already static — no-op
            val rule = json.decodeFromString(SmartListRule.serializer(), ruleJson)
            val members = smartListEngine.snapshotOnce(rule)
            // Defensive — convert is one-way, but a double-call would otherwise raise an
            // OnConflictStrategy.ABORT failure on the membership insert. Skip pre-existing.
            val existing = listMembershipDao.getMembersOfList(listId)
                .map { it.contactId }.toSet()
            val now = Instant.now()  // snapshot time — within the transaction so deterministic
            // H8 fix — single batched insertAll instead of N individual inserts. The
            // moveAll @Transaction in ListMembershipDao already uses this shape; matching
            // it here lets Room emit one statement-batch instead of one statement per row.
            val toInsert = members
                .filterNot { it.id in existing }
                .map { contact ->
                    ListMembershipEntity(
                        contactId = contact.id,
                        listId = listId,
                        addedAt = now,
                        nextDueAt = now,
                    )
                }
            if (toInsert.isNotEmpty()) {
                listMembershipDao.insertAll(toInsert)
            }
            listDao.updateTypeAndSmartRuleJson(listId, ListType.STATIC, null)
        }
    }

    // ─── H3 fix — atomic single-column setters (race-safe) ─────────────────────────────

    override suspend fun updateRuleTemplate(listId: Long, templateId: Long) {
        listDao.updateRuleTemplate(listId, templateId)
    }

    override suspend fun updateActiveHours(listId: Long, start: LocalTime?, end: LocalTime?) {
        listDao.updateActiveHours(listId, start, end)
    }

    override suspend fun updateNotificationsEnabled(listId: Long, enabled: Boolean) {
        listDao.updateNotificationsEnabled(listId, enabled)
    }

    override suspend fun updateName(listId: Long, name: String) {
        listDao.updateName(listId, name)
    }

    override suspend fun addMember(
        listId: Long,
        contactId: Long,
        addedAt: Instant,
    ): Boolean {
        val row = ListMembershipEntity(
            contactId = contactId,
            listId = listId,
            addedAt = addedAt,
            nextDueAt = null,
        )
        // @Insert(onConflict = IGNORE) returns -1 when the row already exists.
        val rowId = listMembershipDao.insertOrIgnore(row)
        return rowId != -1L
    }

    // ─── NOTIF-10/11 — nudge schedule persistence ──────────────────────────────

    override suspend fun setNudgeScheduleJson(listId: Long, json: String?) {
        listDao.updateNudgeScheduleJson(listId, json)
    }

    override suspend fun dueCountForList(listId: Long): Int =
        listDao.dueCountForList(listId) ?: 0

    // ─── dueCount keep-fresh wrapper ─────────────────────────────────────────
    override suspend fun recomputeDueCountForList(listId: Long, now: Instant) {
        listDao.recomputeDueCount(listId, now.toEpochMilli())
    }

    // ─── WR-02 — atomic bulk recompute across all active lists ───────────────
    override suspend fun recomputeDueCountForActive(now: Instant) {
        listDao.recomputeDueCountForActive(now.toEpochMilli())
    }

    override fun observeById(id: Long): Flow<ListEntity?> = listDao.observeById(id)

    override fun observeMemberCountsByListId(): Flow<Map<Long, Int>> =
        listMembershipDao.observeMemberCountsByListId()

    // ─── D-25 — hard-delete an archived list ───────────────────────────────────────────
    //
    // Memberships cascade via FK `ON DELETE CASCADE` on ListMembershipEntity. The DAO
    // query is already `@Transaction`-annotated, so the row delete + cascade run inside
    // a single transaction without an explicit `db.withTransaction` wrap here.
    override suspend fun delete(listId: Long) {
        listDao.deleteList(listId)
    }
}
