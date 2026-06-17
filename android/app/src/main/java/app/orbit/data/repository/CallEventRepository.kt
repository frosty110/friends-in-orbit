package app.orbit.data.repository

import app.orbit.data.entity.CallEventEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Bridges DAO calls for the `call_events` table.
 *
 * `markCalledAtomic` is the multi-table transactional write contract — the Room
 * implementation MUST wrap the body in `@Transaction` (delegates to
 * `ContactDao.insertCallEventAndTouchContact` + per-list `ListMembershipDao` updates).
 *
 * The legacy `observeAll(): Flow<List<CallEventEntity>>` was a
 * full-table read sentinel (`callEventDao.observeRecent(Int.MAX_VALUE)`) routed
 * through four consumers; every emission of any call_event row triggered a full
 * re-read across the picker, contact detail, smart-list engine, and global search.
 * It was deleted in favour of the two scoped variants below: [observeForContact]
 * (single-contact recent feed with explicit limit) and [observeAggregatesForContacts]
 * (per-contact COUNT + lastAt aggregate keyed by contactId set).
 */
interface CallEventRepository {

    /**
     * Contact-scoped recent events with explicit `limit`.
     * The DAO already filters by contactId and applies the LIMIT, so callers
     * read at most `limit` rows for the focused contact (ContactDetail uses
     * `limit = 50`).
     */
    fun observeForContact(contactId: Long, limit: Int): Flow<List<CallEventEntity>>

    /**
     * Per-contact aggregates (count + lastAt) keyed by contactId
     * for picker / search / smart-list decoration. Replaces the in-memory
     * `events.groupBy { it.contactId }` reductions that the deleted `observeAll`
     * used to feed. Ids with zero events are absent from the returned map;
     * callers default-coalesce (`map[id]?.count ?: 0`).
     */
    fun observeAggregatesForContacts(ids: List<Long>): Flow<Map<Long, CallAgg>>

    /**
     * Observes call events for contacts currently on the given list, most-recent-first.
     * Used by `SurfaceNextUseCase` for the `(nextDueAt ASC, lastCalledAt ASC)` tiebreak;
     * scoping to list members keeps Flow emission volume bounded.
     */
    fun observeRecentForListContacts(listId: Long): Flow<List<CallEventEntity>>

    /**
     * List-scoped per-contact "latest call" event keyed by contactId.
     * Replaces [observeRecentForListContacts] + in-memory `events.groupBy +
     * maxByOrNull` in [app.orbit.domain.usecase.SurfaceNextUseCase] when only the
     * latest call per member is needed for the tiebreak AND for the rule engines'
     * short-call / incoming-call cooldown adjustments. Members with zero events
     * are absent from the map; callers default-coalesce (`map[id] == null` means
     * "never called").
     *
     * Returns the FULL [CallEventEntity] per contact (not just MAX(occurredAt))
     * because [app.orbit.domain.rule] engines read `durationSeconds`, `direction`,
     * and `source` from the last-call event for cooldown decisions. See
     * [app.orbit.data.dao.CallEventDao.observeLatestPerContactInList] §Deviation
     * note for the full rationale.
     */
    fun observeLatestPerContactInList(listId: Long): Flow<Map<Long, CallEventEntity>>

    /** One-shot insert of a call event row; returns the generated rowId. */
    suspend fun insert(event: CallEventEntity): Long

    /**
     * Atomic multi-table write for MarkCalledUseCase (DOM-06 cross-list propagation):
     * inserts the call event, touches the contact row, and updates `nextDueAt` +
     * resets `skipCount` for every list-membership in `nextDueByListId`. Wrapped
     * in a single Room `@Transaction` boundary by the impl.
     *
     * Note: `ContactEntity` has no touch-timestamp column in v1 — `event.occurredAt`
     * is the authoritative "when was this contact last reached" signal. If a future
     * phase adds a `lastTouchedAt` column, reintroduce a `now: Instant` parameter at
     * that time; dropped here to keep the contract honest with the impl.
     *
     * @param contactId      the contact whose call is being recorded
     * @param event          the call event row to insert (its `contactId` field MUST equal `contactId`)
     * @param nextDueByListId map of listId → next due Instant for every list this contact is on;
     *                       null value means "clear the nextDueAt"
     */
    suspend fun markCalledAtomic(
        contactId: Long,
        event: CallEventEntity,
        nextDueByListId: Map<Long, Instant?>,
    )

    /**
     * LOG-01 — chronological feed of correlated calls (contactId IS NOT NULL).
     *
     * CallLogViewModel now observes the full correlated set
     * (`limit = Int.MAX_VALUE`, bounded in practice by the 90-day import
     * window) and paginates in-memory in PAGE_SIZE (200) increments behind
     * an honest "Show n more" footer; the old LIMIT-200-then-bump toggle is
     * gone. The `limit` parameter remains for callers that want a bounded
     * read; the leading index on `call_events.occurredAt` (schema v8) lets
     * SQLite short-circuit the LIMIT path when one is supplied.
     */
    fun observeForLog(limit: Int): Flow<List<CallEventEntity>>

    /** NOTE-02 — derived "unnoted outgoing within `since`" check; null when banner not warranted. */
    suspend fun latestUnnotedOutgoing(since: Instant): CallEventEntity?

    /** O(1) primary-key lookup; used by the retroactive-note flow. */
    suspend fun byId(id: Long): CallEventEntity?

    /**
     * EXPORT-01 — one-shot read of every call event row for the export
     * envelope. Returns a `List` (not a `Flow`) so the caller is
     * structurally prevented from accidentally subscribing to a hot stream
     * during a one-time export operation. The export pipeline calls this once
     * per [app.orbit.domain.export.ExportService.export] invocation.
     *
     * Cost: O(n) where n = total call events. Bounded by the user's call-log
     * history; a heavy user with 5+ years of calls might land in the tens of
     * thousands. The export is dispatched on `Dispatchers.IO` so this read
     * doesn't block Main.
     */
    suspend fun snapshotAll(): List<CallEventEntity>

    /**
     * ONB-19 — per-contact aggregates (count + lastAt) across ALL
     * contacts. Sibling of [observeAggregatesForContacts] that drops the
     * `WHERE contactId IN (:ids)` clause; used by the H/β preview VM
     * to rank contacts by recency × frequency without first
     * having to enumerate every contact id from the contacts repository.
     *
     * Same return shape as [observeAggregatesForContacts]: contacts with zero
     * events are absent from the map; callers default-coalesce.
     */
    fun observeAggregatesAll(): Flow<Map<Long, CallAgg>>
}
