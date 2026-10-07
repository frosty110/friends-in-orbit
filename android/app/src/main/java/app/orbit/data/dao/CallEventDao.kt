package app.orbit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Single-table DAO. The multi-table "insert call event + touch contact" method
 * lives on ContactDao (as `insertCallEventAndTouchContact`) because its second
 * write targets the `contacts` table — which is ContactDao's ownership.
 *
 * Plain interface: no multi-table default-impl methods live here.
 */
@Dao
interface CallEventDao {

    @Query("SELECT * FROM call_events WHERE contactId = :contactId ORDER BY occurredAt DESC")
    fun observeByContactId(contactId: Long): Flow<List<CallEventEntity>>

    /**
     * Contact-scoped recent events with explicit `LIMIT`. Used by
     * `ContactDetailViewModel` (at most 50 rows for the focused
     * contact, replacing the prior `observeAll().filter { contactId == ... }` shape).
     *
     * Distinct from [observeByContactId], which is unbounded; do not collapse the
     * two methods — other callers may need the full per-contact history.
     */
    @Query(
        "SELECT * FROM call_events " +
            "WHERE contactId = :contactId " +
            "ORDER BY occurredAt DESC " +
            "LIMIT :limit",
    )
    fun observeForContact(contactId: Long, limit: Int): Flow<List<CallEventEntity>>

    /**
     * Multi-contact aggregate observer. Returns one row per
     * contactId in `ids` carrying `cnt = COUNT(*)` and `lastAt = MAX(occurredAt)`.
     * Consumers join the result by contactId; rows for ids with zero events are
     * absent from the output (callers must default-coalesce at the call site).
     *
     * Push-down replacement for the deleted `CallEventRepository.observeAll()`
     * sentinel — the picker, global search, and the four non-NeverCalled smart-list
     * rule kinds derive their per-contact COUNT + lastAt from this query instead
     * of pulling the whole `call_events` table on every emission.
     */
    @Query(
        "SELECT contactId, COUNT(*) AS cnt, MAX(occurredAt) AS lastAt " +
            "FROM call_events " +
            "WHERE contactId IN (:ids) " +
            "GROUP BY contactId",
    )
    fun observeAggregatesForContacts(ids: List<Long>): Flow<List<CallAggRow>>

    @Query("SELECT * FROM call_events ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CallEventEntity>>

    /**
     * Call events for contacts on the given list. Joins through `list_memberships`.
     * Consumer: `SurfaceNextUseCase` — needs each candidate contact's last-call
     * timestamp for the `(nextDueAt ASC, lastCalledAt ASC)` tiebreak.
     */
    @Query(
        "SELECT * FROM call_events WHERE contactId IN " +
            "(SELECT contactId FROM list_memberships WHERE listId = :listId) " +
            "ORDER BY occurredAt DESC",
    )
    fun observeForListContacts(listId: Long): Flow<List<CallEventEntity>>

    /**
     * List-scoped per-contact "latest call" row. Replaces the full-pull
     * `observeForListContacts` + in-memory `events.groupBy { contactId }.maxByOrNull`
     * pattern in [SurfaceNextUseCase] when only the latest call per member is needed
     * for the `(nextDueAt ASC, lastCalledAt ASC)` tiebreak AND for the rule engines'
     * short-call / incoming-call cooldown adjustments.
     *
     * Returns a full [CallEventEntity] per contact (NOT just `MAX(occurredAt)`) — the
     * rule engines (Energize, LateNight, KeepInTouch) read `durationSeconds`,
     * `direction`, and `source` from the last-call event to decide cooldown
     * adjustments. Returning only the timestamp would silently regress three engines.
     *
     * Idiom: self-join on (contactId, MAX(occurredAt)) so SQLite picks the full row
     * matching the per-contact maximum. Leverages the schema-v8 index on
     * `call_events.occurredAt` for the MAX().
     *
     * Once the contact pipeline is list-scoped, the right shape
     * for "latest call per member" is a SQL aggregate, not a full pull.
     *
     * The repository layer flattens rows into `Map<Long, CallEventEntity>` keyed
     * by contactId for O(1) lookup. Members with zero events are absent from the
     * map; callers default-coalesce (`map[id] == null` means "never called").
     */
    @Query(
        "SELECT ce.* FROM call_events ce " +
            "INNER JOIN ( " +
            "    SELECT contactId, MAX(occurredAt) AS maxAt FROM call_events " +
            "    WHERE contactId IN (SELECT contactId FROM list_memberships WHERE listId = :listId) " +
            "    GROUP BY contactId " +
            ") latest ON ce.contactId = latest.contactId AND ce.occurredAt = latest.maxAt",
    )
    fun observeLatestPerContactInList(listId: Long): Flow<List<CallEventEntity>>

    @Query("SELECT * FROM call_events WHERE id = :id")
    suspend fun get(id: Long): CallEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: CallEventEntity): Long

    @Update
    suspend fun update(event: CallEventEntity): Int

    @Delete
    suspend fun delete(event: CallEventEntity): Int

    /**
     * Dedup check (CallLogReconciler) — returns the count of `call_events`
     * rows that already match `(contactId, occurredAt)` exactly. The reconciler
     * pre-checks this before each candidate insert to skip duplicates on repeat
     * sync passes.
     *
     * This is the application-side workaround for the non-`unique = true` index on
     * [CallEventEntity]; promoting the index to a unique constraint is a deferred
     * retroactive patch, currently blocked by the strict-migrations
     * policy while schema v=1 is live.
     */
    @Query("SELECT COUNT(*) FROM call_events WHERE contactId = :contactId AND occurredAt = :occurredAt")
    suspend fun existsAt(contactId: Long, occurredAt: Instant): Int

    /**
     * NOTE-05: the calls that wait for a note, one per person, newest first.
     * A call waits when it came from the call log (`CALL_LOG`: not a
     * connection logged by hand, not an attempt), lasted at least
     * [minSeconds] (either direction: a call they made is as worth
     * remembering as one the user made), started at or after [since], its
     * person is neither ignored nor archived and is on at least one list that
     * is not archived, and no note about that person was written at or after
     * the call started. The user's dismissals are applied on top of this, from
     * DataStore ([app.orbit.data.repository.WaitingCalls]).
     *
     * One row per person: the latest qualifying call stands for any earlier
     * one (the `ce.id = (...)` subquery), so Kai called twice is one entry.
     * The latest is picked before the note test on purpose: a note written
     * after the latest call is also after every earlier one, so the note test
     * can only drop the person, never promote an older call in their place.
     *
     * The note test is per call, not per person, as NOTE-02's banner query
     * was: an older note, or a retroactive one back-dated to an older call,
     * never covers a newer call. A retroactive note dated to this very call
     * (`createdAt = occurredAt`) does cover it.
     *
     * Replaced `latestUnnotedOutgoing` (a ten-minute, outgoing-only window,
     * one call at a time) on 2026-10-07 (HOME-14). Room re-runs it whenever
     * any of the five tables it reads changes, which is how Home's stack
     * updates live when a note is saved on another screen.
     */
    @Query(
        "SELECT ce.id AS callEventId, ce.contactId AS contactId, ce.occurredAt AS occurredAt, " +
            "  ce.direction AS direction, ce.durationSeconds AS durationSeconds, " +
            "  c.displayName AS displayName, c.photoUri AS photoUri " +
            "FROM call_events ce " +
            "INNER JOIN contacts c ON c.id = ce.contactId " +
            "WHERE ce.source = 'CALL_LOG' " +
            "  AND ce.durationSeconds >= :minSeconds " +
            "  AND ce.occurredAt >= :since " +
            "  AND c.isIgnored = 0 " +
            "  AND c.isArchived = 0 " +
            "  AND EXISTS ( " +
            "      SELECT 1 FROM list_memberships m " +
            "      INNER JOIN lists l ON l.id = m.listId " +
            "      WHERE m.contactId = ce.contactId AND l.isArchived = 0 " +
            "  ) " +
            "  AND ce.id = ( " +
            "      SELECT latest.id FROM call_events latest " +
            "      WHERE latest.contactId = ce.contactId " +
            "        AND latest.source = 'CALL_LOG' " +
            "        AND latest.durationSeconds >= :minSeconds " +
            "        AND latest.occurredAt >= :since " +
            "      ORDER BY latest.occurredAt DESC, latest.id DESC " +
            "      LIMIT 1 " +
            "  ) " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM notes n " +
            "      WHERE n.contactId = ce.contactId " +
            "        AND n.createdAt >= ce.occurredAt " +
            "  ) " +
            "ORDER BY ce.occurredAt DESC, ce.id DESC",
    )
    fun observeWaitingForNote(since: Instant, minSeconds: Int): Flow<List<WaitingCallRow>>

    /**
     * NOTIF-16: the highest call event id so far, or 0 for an empty table.
     * The call-log sync reads it before reconciling so it can tell the calls
     * that pass inserted from the ones already there: ids are autoincrement
     * and never reused, so every row the pass writes is above it.
     */
    @Query("SELECT COALESCE(MAX(id), 0) FROM call_events")
    suspend fun maxId(): Long

    /**
     * LOG-01 — chronological feed; only correlated rows (contactId IS NOT NULL).
     *
     * `:limit` is kept for parity with [observeForContact] (Contact detail
     * reads 50) and for any future caller that wants a bounded read; the
     * leading index on `call_events.occurredAt` (schema v8) lets a LIMIT
     * short-circuit the sort. Call history itself passes `Int.MAX_VALUE` and
     * pages in memory in 200-row increments (`CallLogViewModel.PAGE_SIZE`), so
     * its "Show n more" footer can state the real remainder without a second
     * count query (features/call-history/README.md, Technical). Until
     * 2026-10-06 this comment described a limit the VM bumped on tap, a
     * design the log no longer uses.
     */
    @Query(
        "SELECT * FROM call_events WHERE contactId IS NOT NULL " +
            "ORDER BY occurredAt DESC LIMIT :limit",
    )
    fun observeForLog(limit: Int): Flow<List<CallEventEntity>>

    /**
     * Single-row lookup by primary key. Used by
     * `AddRetroactiveNoteUseCase` to fetch a single CallEventEntity in O(1) without
     * snapshotting the full feed via `observeAll().first()`. Returns null if no
     * row matches.
     *
     * Distinct from [get] (which is unused but kept for symmetry with other DAOs);
     * the retroactive-note flow binds against a renamed `getById` for
     * intent-clarity at the use-case call site.
     */
    @Query("SELECT * FROM call_events WHERE id = :id")
    suspend fun getById(id: Long): CallEventEntity?

    /** EXPORT-01 — one-shot snapshot of every call event for the export pipeline. */
    @Query("SELECT * FROM call_events ORDER BY occurredAt DESC")
    suspend fun snapshotAll(): List<CallEventEntity>

    /**
     * ONB-19 — sibling of [observeAggregatesForContacts] that drops the
     * `WHERE contactId IN (:ids)` clause for the H/β preview's all-contacts
     * recency/frequency ranking. Same column aliases as the scoped variant so
     * [CallAggRow] maps both queries.
     */
    @Query(
        "SELECT contactId, COUNT(*) AS cnt, MAX(occurredAt) AS lastAt " +
            "FROM call_events " +
            "GROUP BY contactId",
    )
    fun observeAggregatesAll(): Flow<List<CallAggRow>>
}

/**
 * Projection row for [CallEventDao.observeAggregatesForContacts].
 * Lives in this file (sibling to the DAO) for proximity; Room maps the SELECT
 * column names (`contactId`, `cnt`, `lastAt`) to the `val` properties below by
 * name, so the column aliases in the @Query are load-bearing.
 *
 * `lastAt` is nullable because `MAX()` over an empty grouping returns NULL — but
 * the @Query's GROUP BY only emits a row when at least one event matches, so in
 * practice the column is always populated. The nullable type keeps the projection
 * defensive against schema-level changes.
 */
data class CallAggRow(
    val contactId: Long,
    val cnt: Int,
    val lastAt: Instant?,
)

/**
 * Projection row for [CallEventDao.observeWaitingForNote] (NOTE-05): the
 * call, and the name and photo of the person it was with, from one query so
 * Home's stack and the post-call notification need no second read. Room maps
 * the SELECT aliases to these names, so the aliases are load-bearing.
 */
data class WaitingCallRow(
    val callEventId: Long,
    val contactId: Long,
    val occurredAt: Instant,
    val direction: CallDirection,
    val durationSeconds: Int,
    val displayName: String,
    val photoUri: String?,
)
