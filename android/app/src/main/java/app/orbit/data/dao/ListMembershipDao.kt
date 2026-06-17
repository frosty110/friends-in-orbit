package app.orbit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.orbit.data.entity.ListMembershipEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface ListMembershipDao {

    /**
     * Observes every list_memberships row. Used by [app.orbit.ui.screens.picker.ContactPickerViewModel]
     * to compute each [app.orbit.data.entity.ContactEntity]'s `listIds: Set<Long>` projection inside
     * one combine emission — avoids per-contact `observeByContactId` fan-out (would be O(N) flows).
     * Address books are bounded (low-thousands in practice), so the un-scoped
     * read is cheap.
     */
    @Query("SELECT * FROM list_memberships")
    fun observeAll(): Flow<List<ListMembershipEntity>>

    @Query("SELECT * FROM list_memberships WHERE listId = :listId")
    fun observeByListId(listId: Long): Flow<List<ListMembershipEntity>>

    @Query("SELECT * FROM list_memberships WHERE contactId = :contactId")
    fun observeByContactId(contactId: Long): Flow<List<ListMembershipEntity>>

    @Query("SELECT * FROM list_memberships WHERE contactId = :contactId AND listId = :listId")
    suspend fun get(contactId: Long, listId: Long): ListMembershipEntity?

    /**
     * Suspend snapshot variants of [observeByContactId] / [observeByListId].
     * Used by callers that need a one-shot read inside a `withTransaction`
     * block where collecting a Flow via `.first()` relies on Room's internal
     * query-executor scheduling. The documented Room idiom is a suspend
     * snapshot method; this is its declaration.
     */
    @Query("SELECT * FROM list_memberships WHERE contactId = :contactId")
    suspend fun getMembershipsForContact(contactId: Long): List<ListMembershipEntity>

    @Query("SELECT * FROM list_memberships WHERE listId = :listId")
    suspend fun getMembersOfList(listId: Long): List<ListMembershipEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(membership: ListMembershipEntity): Long

    /**
     * ONB-19 — idempotent single-row insert for [ListRepository.addMember].
     * Returns -1 when the row already exists (caller maps to `false`).
     * Mirrors [insertAll]'s IGNORE strategy for the single-row path used by the
     * onboarding "Make this my first list" preview tap.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(membership: ListMembershipEntity): Long

    @Update
    suspend fun update(membership: ListMembershipEntity): Int

    @Delete
    suspend fun delete(membership: ListMembershipEntity): Int

    @Query("DELETE FROM list_memberships WHERE contactId = :contactId AND listId = :listId")
    suspend fun deleteByPair(contactId: Long, listId: Long): Int

    /**
     * H6 — atomic single-column write for `nextDueAt`. Used by [SurfaceSoonerUseCase]
     * to advance a membership's surfacing time without polluting `skipCount`
     * (a "sooner" is a negative skip; reusing `incrementSkipCount` was semantically
     * wrong). Returns the row count so the repository layer can distinguish a
     * race-with-delete from a successful no-op.
     */
    @Query("UPDATE list_memberships SET nextDueAt = :nextDueAt WHERE contactId = :contactId AND listId = :listId")
    suspend fun updateNextDueAt(contactId: Long, listId: Long, nextDueAt: Instant?): Int

    /** Bulk delete memberships from a list. Used by Move (remove leg) and BulkRemove. */
    @Query("DELETE FROM list_memberships WHERE listId = :fromListId AND contactId IN (:ids)")
    suspend fun removeAll(fromListId: Long, ids: List<Long>)

    /** Bulk insert memberships. ON CONFLICT IGNORE so re-adds are idempotent (Move undo, Copy). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(memberships: List<ListMembershipEntity>)

    /**
     * Atomic move — MOVE-03 contract. `@Transaction` keeps both legs in one DB transaction.
     *
     * Pitfall 3: this body MUST NOT call withContext(Dispatchers.X) — Room confines
     * suspending DAO calls to its internal transaction executor.
     *
     * Note: `nowMs` is a wall-clock epoch-millis (Long) per the use-case contract;
     * it is converted to [Instant] inside this body because [ListMembershipEntity.addedAt]
     * is `Instant`. Conversion lives at the DAO seam to keep the use-case API stable.
     */
    @Transaction
    suspend fun moveAll(fromListId: Long, toListId: Long, ids: List<Long>, nowMs: Long) {
        removeAll(fromListId, ids)
        val now = Instant.ofEpochMilli(nowMs)
        insertAll(ids.map { ListMembershipEntity(listId = toListId, contactId = it, addedAt = now) })
    }

    /**
     * Per-list membership counts for Lists Manager tiles + Home tile badges.
     * Returns a Flow<Map<Long, Int>> where the key is listId.
     * Empty lists (no memberships) are absent from the map; UI must default to 0.
     *
     * Implemented via `@MapColumn` (Room 2.5+) — supersedes the deprecated
     * `@MapInfo`. Both parameter annotations are required: one tags the key
     * (listId), the other tags the value (memberCount).
     */
    @Query("SELECT listId, COUNT(*) AS memberCount FROM list_memberships GROUP BY listId")
    fun observeMemberCountsByListId(): Flow<Map<
        @MapColumn(columnName = "listId") Long,
        @MapColumn(columnName = "memberCount") Int,
        >>
}
