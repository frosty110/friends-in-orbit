package app.orbit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {

    @Query("SELECT * FROM lists WHERE isArchived = 0 ORDER BY sortOrder ASC")
    fun observeActive(): Flow<List<ListEntity>>

    /**
     * Suspend snapshot variant of [observeActive]. Used by [reorder] inside
     * a `withTransaction` block — collecting the Flow via `.first()` worked
     * in practice but relied on Room's internal query-executor scheduling.
     * Documented idiom is a suspend snapshot method; this is its declaration.
     */
    @Query("SELECT * FROM lists WHERE isArchived = 0 ORDER BY sortOrder ASC")
    suspend fun getActive(): List<ListEntity>

    @Query("SELECT * FROM lists ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE id = :id")
    fun observeById(id: Long): Flow<ListEntity?>

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun get(id: Long): ListEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(list: ListEntity): Long

    @Update
    suspend fun update(list: ListEntity): Int

    @Delete
    suspend fun delete(list: ListEntity): Int

    /**
     * Multi-table transactional delete. CASCADE on ListMembershipEntity and (indirectly)
     * on NoteEntity via the contact chain is enforced by ON DELETE CASCADE at the FK level,
     * but @Transaction wraps the cascade so Flow observers see the whole deletion atomically.
     */
    @Transaction
    @Query("DELETE FROM lists WHERE id = :listId")
    suspend fun deleteList(listId: Long): Int

    // ─── Narrow column updates for ListRepository write surface ──────────────

    /** LIST-02 reorder — writes only sortOrder for one row inside the repo's withTransaction. */
    @Query("UPDATE lists SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    /** LIST-02 archive — flips isArchived without touching memberships. */
    @Query("UPDATE lists SET isArchived = :archived WHERE id = :id")
    suspend fun updateArchived(id: Long, archived: Boolean)

    /** SMART-06 smart-rule edit — null clears, non-null overwrites. */
    @Query("UPDATE lists SET smartRuleJson = :json WHERE id = :id")
    suspend fun updateSmartRuleJson(id: Long, json: String?)

    /** LIST-04 per-list rule-param override — null clears (use template default). */
    @Query("UPDATE lists SET ruleParamsOverrideJson = :json WHERE id = :id")
    suspend fun updateRuleParamsOverrideJson(id: Long, json: String?)

    /** LIST-08 atomic-step inside convertSmartToStatic: flip type + clear smart rule. */
    @Query("UPDATE lists SET type = :type, smartRuleJson = :smartRuleJson WHERE id = :id")
    suspend fun updateTypeAndSmartRuleJson(id: Long, type: ListType, smartRuleJson: String?)

    // ─── H3 fix — single-column setters bypass the read-modify-write race in
    //              ListConfigViewModel. Each setter writes only the column it
    //              owns so two overlapping VM dispatches no longer clobber.

    @Query("UPDATE lists SET ruleTemplateId = :templateId WHERE id = :id")
    suspend fun updateRuleTemplate(id: Long, templateId: Long)

    @Query("UPDATE lists SET activeHoursStart = :start, activeHoursEnd = :end WHERE id = :id")
    suspend fun updateActiveHours(id: Long, start: java.time.LocalTime?, end: java.time.LocalTime?)

    @Query("UPDATE lists SET notificationsEnabled = :enabled WHERE id = :id")
    suspend fun updateNotificationsEnabled(id: Long, enabled: Boolean)

    /** ONB-11 / ONB-24 — atomic single-column write of `name` (matches the H3-fix family). */
    @Query("UPDATE lists SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    // ─── NOTIF-10/11 — nudge schedule persistence ──────────────────────

    /** NOTIF-10/11 — atomic single-column write of nudge schedule JSON. Null clears. */
    @Query("UPDATE lists SET nudgeScheduleJson = :json WHERE id = :id")
    suspend fun updateNudgeScheduleJson(id: Long, json: String?)

    /**
     * NOTIF-12 fire-time gate — O(1) read of the denormalized dueCount column
     * (populated by MIGRATION_8_9 backfill and kept fresh by the seven mutator
     * use cases via [recomputeDueCount]). Workers use this to skip posting when
     * no members are due, keeping silent days truly silent.
     *
     * Returns null when the list row does not exist (deleted between schedule and
     * fire); callers should treat null as dueCount = 0.
     */
    @Query("SELECT dueCount FROM lists WHERE id = :id")
    suspend fun dueCountForList(id: Long): Int?

    // ─── R2.B — denormalized dueCount keep-fresh helper ────────────────
    //
    // ADR 0006 Rule 2: hot-path derived values are denormalized into the schema
    // and updated by the use cases that mutate the underlying data. The seven
    // mutator use cases (Move, Copy, BulkRemove, Ignore, Unignore + SurfaceSooner,
    // MarkCalled) all call this method inside their existing withTransaction
    // blocks. The `nowMs` arg is epoch-millis to match `nextDueAt`'s storage
    // affinity (OrbitTypeConverters.Instant ↔ Long).

    /** R2.B — atomic dueCount recompute for a single list. */
    @Query(
        "UPDATE lists SET dueCount = (" +
            "SELECT COUNT(*) FROM list_memberships " +
            "WHERE list_memberships.listId = :listId " +
            "AND (list_memberships.nextDueAt IS NULL " +
            "OR list_memberships.nextDueAt <= :nowMs)) " +
            "WHERE id = :listId",
    )
    suspend fun recomputeDueCount(listId: Long, nowMs: Long)

    /**
     * WR-02 — atomic dueCount recompute across every active (non-archived)
     * list as a single SQL statement. Used by [HomeFeed.refreshDueCountsIfStale]
     * so the periodic 5-minute foreground refresh is one transaction (SQLite
     * row-level locking keeps the table consistent), not N independent UPDATEs
     * with a partial-success window between them. Faster than the per-list
     * loop too — no application-side iteration.
     */
    @Query(
        "UPDATE lists SET dueCount = (" +
            "SELECT COUNT(*) FROM list_memberships " +
            "WHERE list_memberships.listId = lists.id " +
            "AND (list_memberships.nextDueAt IS NULL " +
            "OR list_memberships.nextDueAt <= :nowMs)) " +
            "WHERE isArchived = 0",
    )
    suspend fun recomputeDueCountForActive(nowMs: Long)
}
