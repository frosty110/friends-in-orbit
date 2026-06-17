package app.orbit.data.repository

import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.domain.usecase.MutationResult
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow

/**
 * Bridges DAO calls for the `lists` and `list_memberships` tables.
 *
 * This was originally split across `ListRepository` + `ListMembershipRepository`;
 * the pair was later collapsed to one impl wrapping both DAOs.
 */
interface ListRepository {

    /** Observes every list row, ordered by sortOrder ASC (DAO-enforced). */
    fun observeAll(): Flow<List<ListEntity>>

    /**
     * Non-archived list rows for Home / primary-nav surfaces.
     * Backed by `ListDao.observeActive()` (`WHERE isArchived = 0 ORDER BY
     * sortOrder ASC`). HomeFeed reads this; archived lists are reachable only
     * via Lists Manager (which keeps using `observeAll()`).
     */
    fun observeActive(): Flow<List<ListEntity>>

    /** One-shot read of a single list by primary key; returns null if absent. */
    suspend fun getById(id: Long): ListEntity?

    /** Observes all memberships of a specific list, for surfacing-membership rendering. */
    fun observeMembersOfList(listId: Long): Flow<List<ListMembershipEntity>>

    /** Observes every list a contact belongs to, for cross-list propagation reads. */
    fun observeMembershipsForContact(contactId: Long): Flow<List<ListMembershipEntity>>

    /**
     * Increments skipCount and updates nextDueAt for a single (contact, list)
     * membership. Returns [MutationResult.MembershipMissing] when the row has
     * vanished between dispatch and write (race-with-delete) so callers can
     * distinguish that from a successful no-op. H7 fix.
     */
    suspend fun incrementSkipCount(contactId: Long, listId: Long, newNextDueAt: Instant): MutationResult

    /**
     * H6 — set `nextDueAt` directly without touching `skipCount`. [SurfaceSoonerUseCase]
     * uses this so that "sooner" (a negative skip) does not pollute downstream
     * consumers (badges, dampening, analytics) that read `skipCount` semantically.
     * Returns [MutationResult.MembershipMissing] when the row has vanished between
     * dispatch and write.
     */
    suspend fun updateNextDueAt(contactId: Long, listId: Long, nextDueAt: Instant): MutationResult

    /**
     * Card-loop undo (2026-06-09) — restore a membership's persisted schedule
     * to a previously captured snapshot: both `nextDueAt` (nullable — a
     * cold-start membership legitimately has none) and `skipCount` in one
     * atomic write. The inverse closure of a swipe-commit snackbar Undo uses
     * this to put the row back exactly as it was before the Skip / Sooner
     * mutation. Returns [MutationResult.MembershipMissing] when the row has
     * vanished between dispatch and write.
     */
    suspend fun restoreMembershipSchedule(
        contactId: Long,
        listId: Long,
        nextDueAt: Instant?,
        skipCount: Int,
    ): MutationResult

    // ─── List write surface ─────────────────────────────────────────────────────────

    /** Insert a new list. Returns the generated rowid. (LIST-01) */
    suspend fun create(list: ListEntity): Long

    /** Update an existing list (rename, rule template change, active hours, notifications). */
    suspend fun update(list: ListEntity)

    /** Flip the isArchived flag without touching memberships. (LIST-02) */
    suspend fun setArchived(listId: Long, archived: Boolean)

    /**
     * Reorder a list within the active set: move the row at fromIndex (0-based, ASC sortOrder)
     * to toIndex. Implemented as a range-only sortOrder rewrite inside db.withTransaction
     * (race-safe against concurrent VM dispatches). Operates on isArchived=false rows only.
     */
    suspend fun reorder(fromIndex: Int, toIndex: Int)

    /** Write the smart-rule JSON for a list. Null clears. (SMART-06) */
    suspend fun setSmartRuleJson(listId: Long, json: String?)

    /** Write the per-list rule-params override JSON. Null clears (use template's shared params). (LIST-04) */
    suspend fun setRuleParamsOverrideJson(listId: Long, json: String?)

    /**
     * Atomic smart→static conversion (LIST-08): inside one db.withTransaction —
     *  (a) snapshot current smart membership via SmartListEngine.snapshotOnce(rule),
     *  (b) insert one ListMembershipEntity row per snapshotted contact,
     *  (c) update list to set type=STATIC and smartRuleJson=null.
     * All three steps run inside a single transaction.
     */
    suspend fun convertSmartToStatic(listId: Long)

    /**
     * D-25 — hard-delete a list by primary key. Idempotent: returns silently
     * if the row no longer exists. Memberships cascade via Room's FK
     * `ON DELETE CASCADE` on `ListMembershipEntity` (no manual cleanup).
     *
     * Scope: scheduled list prompts (WorkManager) do not yet exist — the
     * inverse hook lands when prompt scheduling does. Per the PRD,
     * Delete is reachable only after the user has soft-archived the list, so
     * the destructive call sits behind an explicit two-step gesture.
     */
    suspend fun delete(listId: Long)

    // ─── H3 fix — atomic single-column setters for ListConfigViewModel ────────────────
    //
    // The earlier setters used `getById → copy → update`, which is a read-modify-write
    // round trip. Two overlapping setter taps clobber each other (e.g. quickly toggling
    // notifications while changing the rule template can lose one of the writes). The
    // three methods below let the VM hand the DAO exactly the column(s) it intends to
    // change; concurrent setters on different columns no longer collide.

    /** LIST-06 — atomic write of `ruleTemplateId`. */
    suspend fun updateRuleTemplate(listId: Long, templateId: Long)

    /** LIST-05 — atomic write of `activeHoursStart` + `activeHoursEnd` (both nulls = always active). */
    suspend fun updateActiveHours(listId: Long, start: LocalTime?, end: LocalTime?)

    /** LIST-05 — atomic flip of `notificationsEnabled`. */
    suspend fun updateNotificationsEnabled(listId: Long, enabled: Boolean)

    /**
     * ONB-11 / ONB-24 — atomic single-column write of `name`. Mirrors the
     * H3-fix family ([updateRuleTemplate], [updateActiveHours],
     * [updateNotificationsEnabled]). Used by [ListConfigViewModel.setName] so the
     * onboarding first-list flow can write the user's typed name without a
     * read-modify-write round trip.
     */
    suspend fun updateName(listId: Long, name: String)

    /**
     * ONB-19 — single-row membership insert for the onboarding nav graph.
     *
     * Production list-creation flows hand the picker → BatchAddContactsUseCase
     * → [ListRepositoryImpl] write path. Onboarding's "Make this my first list"
     * preview path forEach-iterates the candidate ids on the nav graph,
     * and the "Start blank" path inserts zero — neither uses the
     * picker. This thin add-one wrapper exists so the nav graph isn't a use-case
     * consumer (which would widen the trust boundary unnecessarily).
     *
     * Insert semantics: `OnConflictStrategy.IGNORE` — re-adding an existing
     * membership is a no-op (matches BatchAddContactsUseCase idempotency).
     *
     * @return true if a new row was inserted, false if the row already existed.
     */
    suspend fun addMember(listId: Long, contactId: Long, addedAt: Instant): Boolean

    /** Reactive single-list observer — replaces the earlier .observeAll().map{firstOrNull{...}} workaround. */
    fun observeById(id: Long): Flow<ListEntity?>

    /**
     * Per-list membership counts for tile rendering. Returns a Flow<Map<Long, Int>>
     * keyed by listId; empty lists (no memberships) are absent from the map. UI
     * layer must default missing entries to 0.
     */
    fun observeMemberCountsByListId(): Flow<Map<Long, Int>>

    // ─── NOTIF-10/11 — nudge schedule persistence ───────────────────────────────

    /**
     * NOTIF-10/11 — atomic single-column write of the serialized nudge schedule.
     * Null clears. Mirrors the H3-fix family ([updateRuleTemplate], etc.) to
     * avoid read-modify-write races when the schedule is saved alongside other
     * list config fields.
     */
    suspend fun setNudgeScheduleJson(listId: Long, json: String?)

    /**
     * NOTIF-12 fire-time gate — O(1) read of the denormalized `lists.dueCount`
     * column (ADR 0006 Rule 2). Workers call this before posting a nudge to skip
     * silent days (gate: dueCount ≥ 1 required). Returns 0 if the list row does
     * not exist (deleted between schedule and fire).
     */
    suspend fun dueCountForList(listId: Long): Int

    /**
     * Keep `lists.dueCount` fresh. Called by every mutator
     * use case (Move/Copy/BulkRemove/Ignore/Unignore/SurfaceSooner/MarkCalled)
     * inside the same `withTransaction` block that performs the underlying
     * membership or `nextDueAt` write. Atomic with the trigger write.
     *
     * ADR 0006 Rule 2 — denormalized values are updated at the choke point,
     * not via SQL triggers (triggers are invisible to grep / typed queries).
     */
    suspend fun recomputeDueCountForList(listId: Long, now: Instant)

    /**
     * WR-02 — atomic bulk recompute across every active (non-archived) list.
     * Used by [HomeFeed.refreshDueCountsIfStale] so the
     * periodic 5-minute foreground refresh is one SQL statement rather than
     * a per-list loop with a partial-success window. SQLite row-level locking
     * keeps the table consistent across the single UPDATE.
     */
    suspend fun recomputeDueCountForActive(now: Instant)
}
