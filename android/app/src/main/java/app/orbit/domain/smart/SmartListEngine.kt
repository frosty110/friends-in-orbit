package app.orbit.domain.smart

import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.CallAgg
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.domain.clock.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Reactive smart-list membership computation (SMART-03). The [membership] flow
 * re-emits whenever either underlying repository flow changes — a new contact
 * synced, a call event logged, an ignore flag toggled.
 *
 * Invariants enforced at the end of [compute] regardless of rule type:
 *   - Ignored contacts are excluded (SMART-05)
 *   - Output ordered deterministically by `contact.id` ASC
 *
 * Percentile semantics (locked 2026-04-22):
 *   - CommonlyCalled(topPercent=N): sort callers by callCount ASC, take last ceil(N% of callers)
 *   - RarelyCalled(bottomPercent=N): sort callers by callCount ASC, take first floor(N% of callers)
 *   - "callers" = contacts with callCount > 0 (SMART-07)
 *
 * Dispatcher — the engine no longer reads `callEventRepo.observeAll()`;
 * per-rule data paths are:
 *
 *   - [SmartListRule.NeverCalled] — SQL push-down via
 *     [ContactRepository.observeNeverCalled] (LEFT JOIN call_events GROUP BY
 *     HAVING COUNT = 0). Snapshot variant
 *     [ContactRepository.snapshotNeverCalled] for the convert flow.
 *   - [SmartListRule.RecentlyAddedNotCalled] — `aggregates[id] == null` (no
 *     events) AND firstSeenByAppAt within window. Aggregates come from
 *     [CallEventRepository.observeAggregatesForContacts].
 *   - [SmartListRule.LongGap] — `aggregates[id]?.lastAt` older than threshold.
 *   - [SmartListRule.CommonlyCalled] / [SmartListRule.RarelyCalled] — percentile
 *     buckets over `aggregates[id]?.count`.
 *
 * This is a cold Flow transform — it does not cache state. Consumers that need
 * memoization should apply `.distinctUntilChanged()` or `.stateIn(...)` at the
 * ViewModel boundary (a consumer concern, not here).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartListEngine @Inject constructor(
    private val contactRepo: ContactRepository,
    private val callEventRepo: CallEventRepository,
    private val clock: Clock,
) {

    /** Reactive entry point — SMART-03. */
    fun membership(rule: SmartListRule): Flow<List<ContactEntity>> = when (rule) {
        // NeverCalled — SQL push-down via ContactRepository.observeNeverCalled.
        // The DAO @Query LEFT JOINs call_events and HAVING COUNT(ce.id) = 0, so
        // zero-call contacts arrive pre-filtered; we still apply the SMART-05
        // ignore filter and the deterministic id sort here.
        SmartListRule.NeverCalled -> contactRepo.observeNeverCalled().map { contacts ->
            contacts
                .filter { !it.isIgnored }
                .sortedBy { it.id }
        }

        // The four aggregate-driven rule kinds share a common upstream: contacts
        // flow → derive id set → flatMapLatest → aggregate observer. Combining
        // contacts + aggregates lets compute(...) operate on a Map<Long, CallAgg>
        // instead of the deleted full call_events table.
        is SmartListRule.RecentlyAddedNotCalled,
        is SmartListRule.LongGap,
        is SmartListRule.CommonlyCalled,
        is SmartListRule.RarelyCalled,
        -> {
            val contactsFlow = contactRepo.observeAll()
            val aggregatesFlow = contactsFlow
                .map { it.map(ContactEntity::id) }
                .distinctUntilChanged()
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) flowOf(emptyMap<Long, CallAgg>())
                    else callEventRepo.observeAggregatesForContacts(ids)
                }
            combine(contactsFlow, aggregatesFlow) { contacts, aggregates ->
                compute(rule, contacts, aggregates, clock.now())
            }
        }
    }
        // Coalesce rapid sync bursts (call-log ingest fires upstream emissions
        // per row). Without this every contact/event change would kick a full
        // filter + sortedBy cycle.
        .distinctUntilChanged()

    /**
     * Pure membership computation over an aggregate map — exposed `internal` so
     * unit tests can exercise rule logic without wiring Flows. The
     * `events: List<CallEventEntity>` parameter shape was replaced with
     * `aggregates: Map<Long, CallAgg>` to match the SQL push-down. Tests that
     * still want to construct fixtures from events should use the
     * [computeFromEvents] thin wrapper below — it preserves the historical
     * test surface.
     *
     * Note: this overload does NOT execute the NeverCalled path (the engine's
     * Flow entry routes that to a SQL @Query directly). For test parity,
     * [computeFromEvents] still handles all five kinds in-memory.
     */
    internal fun compute(
        rule: SmartListRule,
        contacts: List<ContactEntity>,
        aggregates: Map<Long, CallAgg>,
        now: Instant,
    ): List<ContactEntity> {
        val callCount: (ContactEntity) -> Int = { aggregates[it.id]?.count ?: 0 }
        val lastCallAt: (ContactEntity) -> Instant? = { aggregates[it.id]?.lastAt }

        val selected: List<ContactEntity> = when (rule) {
            is SmartListRule.RecentlyAddedNotCalled -> {
                val windowStart = now.minus(Duration.ofDays(rule.daysWindow.toLong()))
                contacts.filter { c ->
                    !c.firstSeenByAppAt.isBefore(windowStart) && callCount(c) == 0
                }
            }

            is SmartListRule.LongGap -> {
                val cutoff = now.minus(Duration.ofDays(rule.daysThreshold.toLong()))
                contacts.filter { c ->
                    val last = lastCallAt(c) ?: return@filter false
                    last.isBefore(cutoff)
                }
            }

            is SmartListRule.CommonlyCalled -> {
                val callers = contacts.filter { callCount(it) > 0 }.sortedBy(callCount)
                if (callers.isEmpty()) emptyList()
                else {
                    val take = ceil(callers.size * rule.topPercent / 100.0).toInt()
                        .coerceIn(0, callers.size)
                    callers.takeLast(take)
                }
            }

            is SmartListRule.RarelyCalled -> {
                val callers = contacts.filter { callCount(it) > 0 }.sortedBy(callCount)
                if (callers.isEmpty()) emptyList()
                else {
                    val take = floor(callers.size * rule.bottomPercent / 100.0).toInt()
                        .coerceIn(0, callers.size)
                    callers.take(take)
                }
            }

            SmartListRule.NeverCalled -> {
                contacts.filter { callCount(it) == 0 }
            }
        }

        return selected
            .filter { !it.isIgnored }     // SMART-05 — applies to every rule type
            .sortedBy { it.id }            // deterministic — no randomness
    }

    /**
     * Thin test-friendly wrapper that folds a `List<CallEventEntity>` into the
     * `Map<Long, CallAgg>` shape [compute] expects. Preserves the historical
     * test fixture pattern of seeding events directly without forcing every
     * test to hand-build aggregates.
     */
    internal fun computeFromEvents(
        rule: SmartListRule,
        contacts: List<ContactEntity>,
        events: List<CallEventEntity>,
        now: Instant,
    ): List<ContactEntity> = compute(rule, contacts, aggregatesOf(events), now)

    /**
     * Fold a list of call events into the aggregate map shape the engine
     * consumes. Mirrors what
     * [CallEventRepository.observeAggregatesForContacts] returns from SQL.
     * Visible to the engine's unit tests via package-private scope.
     */
    internal fun aggregatesOf(events: List<CallEventEntity>): Map<Long, CallAgg> =
        events.groupBy { it.contactId }
            .mapValues { (_, evs) ->
                CallAgg(
                    count = evs.size,
                    lastAt = evs.maxOfOrNull { it.occurredAt },
                )
            }

    /**
     * One-shot snapshot of smart-list membership for the convert flow (LIST-08).
     * Reads the first emission of [membership] for the given rule. Used inside
     * [app.orbit.data.repository.ListRepositoryImpl.convertSmartToStatic]'s
     * `db.withTransaction { … }` so the snapshot is taken with a deterministic
     * clock value at exactly the moment of the type-flip.
     *
     * Collapsed to `membership(rule).first()` because every rule kind now
     * routes through the same Flow entry point (NeverCalled goes to
     * the SQL @Query, the other four through the aggregate-driven combine).
     * Routing through `.first()` keeps the snapshot semantics aligned with the
     * reactive path so a future SQL refinement only needs to be made once.
     */
    suspend fun snapshotOnce(rule: SmartListRule): List<ContactEntity> =
        membership(rule).first()
}
