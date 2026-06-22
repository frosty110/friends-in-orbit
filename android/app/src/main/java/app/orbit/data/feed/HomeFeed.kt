package app.orbit.data.feed

import app.orbit.data.AppPrefs
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ListRepository
import app.orbit.di.ApplicationScope
import app.orbit.domain.clock.Clock
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.domain.usecase.SurfaceResult
import app.orbit.ui.screens.home.ListTileState
import app.orbit.ui.screens.home.RhythmCall
import app.orbit.ui.screens.home.RhythmDay
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Process-scoped HomeFeed (ADR 0006 §Rule 1).
 *
 * Owns the `tiles: StateFlow<List<ListTileState>>` over the lifetime of the
 * process. HomeViewModel subscribes; HomeViewModel does NOT own the source
 * flow. Re-entering Home reads the cached value synchronously — no projection
 * re-fire on screen navigation.
 *
 * `SharingStarted.Eagerly` is the explicit authorization in ADR 0006 §Rule 1:
 * singleton lifecycle = process lifecycle, no consumer-count gate to defeat.
 * `WhileSubscribed` would cause `tiles` to re-fire upstream 5s after the last
 * subscriber detached — defeating the principle.
 *
 * `dueCount` reads directly from `ListEntity.dueCount` (the denormalized
 * column, kept fresh by the seven mutator use cases). The legacy
 * `combine(observeMembersOfList × N)` N+1 pattern is gone — Home is one query.
 *
 * `prime()` triggers the first emission so `OrbitApp.onCreate` can pay the
 * SQLCipher first-open cost behind the launch image (ADR 0006 §Rule 3).
 * Idempotent.
 *
 * `clock` is injected for the 5-minute staleness gate
 * (`refreshDueCountsIfStale`); not used in the steady-state read.
 *
 * `open class` per the [app.orbit.domain.usecase.MarkCalledUseCase]
 * precedent — test fixtures subclass to inject a deterministic `tiles` flow
 * without forcing the test to satisfy `@ApplicationScope CoroutineScope`
 * construction.
 */
@Singleton
open class HomeFeed @Inject constructor(
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val appPrefs: AppPrefs,
    private val surfaceNext: SurfaceNextUseCase,
    private val callEventRepo: CallEventRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    open val tiles: StateFlow<List<ListTileState>> =
        listRepo.observeActive()
            .map { rows -> rows.map { it.toTileState() } }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    /**
     * HOME-3 / HOME-7 — per-list enrichment: the head of each list's queue
     * ("Next up") and the last-7-days call rhythm, keyed by listId.
     *
     * This is deliberately separate from [tiles]: `tiles` stays the cheap
     * denormalized one-query projection (ADR 0006), while enrichment is the
     * heavier per-list fan-out (one [SurfaceNextUseCase] + one call-events
     * observer per active list). Like `tiles` it is `Eagerly`-started on the
     * process scope, so the fan-out is computed once and re-entering Home reads
     * the cached map synchronously — the "Next up" recommendation never blinks
     * empty on navigation. List counts are small by design, so the N flows are
     * bounded.
     *
     * "Next up" reuses the exact Card View surfacing contract
     * ([SurfaceNextUseCase]); the `why` line is formatted in the ViewModel
     * (presentation), so this layer carries the raw `lastCalledAt`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    open val enrichment: StateFlow<Map<Long, ListEnrichment>> =
        listRepo.observeActive()
            .flatMapLatest { lists ->
                if (lists.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(lists.map { enrichOne(it.id) }) { it.toMap() }
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap(),
            )

    private fun enrichOne(listId: Long) =
        combine(
            surfaceNext(listId),
            callEventRepo.observeRecentForListContacts(listId),
        ) { surface, calls ->
            val nextUp = (surface as? SurfaceResult.Found)?.let { found ->
                val last = calls.asSequence()
                    .filter { it.contactId == found.contact.id }
                    .maxByOrNull { it.occurredAt }
                NextUpRaw(
                    contactId = found.contact.id,
                    name = found.contact.displayName,
                    photoUri = found.contact.photoUri,
                    lastCalledAt = last?.occurredAt,
                )
            }
            listId to ListEnrichment(nextUp = nextUp, rhythm = buildRhythm(calls))
        }

    /**
     * Buckets the list's qualifying calls into the trailing 7 local days
     * (index 0 = six days ago, index 6 = today). "Qualifying" = at least
     * [MIN_RHYTHM_SECONDS] (HOME-7 drops sub-3-min calls). Bars/colors are the
     * UI's job; this only places each call on its day.
     */
    private fun buildRhythm(calls: List<CallEventEntity>): List<RhythmDay> {
        val zone = ZoneId.systemDefault()
        val today = clock.now().atZone(zone).toLocalDate()
        val start = today.minusDays(6)
        val byDate = calls.asSequence()
            .filter { it.durationSeconds >= MIN_RHYTHM_SECONDS }
            .mapNotNull { ev ->
                val d = ev.occurredAt.atZone(zone).toLocalDate()
                if (d.isBefore(start) || d.isAfter(today)) null else d to ev
            }
            .groupBy({ it.first }, { it.second })
        return (0..6).map { offset ->
            val date = start.plusDays(offset.toLong())
            RhythmDay(
                calls = (byDate[date] ?: emptyList()).map {
                    RhythmCall(contactId = it.contactId, durationSeconds = it.durationSeconds)
                },
            )
        }
    }

    /**
     * R3.A — trigger first emission so the SQLCipher first-open cost is paid
     * on `appScope` (Dispatchers.Default) before MainActivity composes.
     * Idempotent — second call is a free read of the cached value.
     */
    open suspend fun prime() {
        tiles.first()
    }

    /**
     * Periodic dueCount recompute for time-based staleness.
     *
     * `lists.dueCount` is a column-backed denormalization. Write-triggered
     * recomputes cover every membership and `nextDueAt` mutation, but a
     * contact whose `nextDueAt` crosses
     * `clock.now()` without any write is invisible to the column until
     * something else writes to that list. The 5-minute foreground gate
     * eliminates user-perceptible staleness without adding a tick worker.
     *
     * No-ops when called within 5 minutes of the last refresh. Idempotent —
     * calling twice in a row is cheap (one DataStore read + a guard return).
     * The TTL is the only thing preventing recompute spam across rapid
     * `Lifecycle.Event.ON_START` events (rotations, theme switches).
     */
    open suspend fun refreshDueCountsIfStale() {
        val nowMs = clock.now().toEpochMilli()
        val lastMs = appPrefs.lastDueCountRecomputeAt.first()
        // WR-04 — clock-rollback guard. `nowMs - lastMs` is signed: when the
        // user (or NTP) rolls the system clock backward, the delta becomes
        // negative and a naive `< FIVE_MINUTES_MS` check fires open on every
        // ON_START, defeating the throttle. Mirrors the `ContactsIngestWorker`
        // fix (`!now.isBefore(last)` shape, commit df229ba). The rollback case
        // falls through to the recompute branch
        // — `setLastDueCountRecomputeAt(nowMs)` at the end heals the drift so
        // the next call is throttled.
        val delta = nowMs - lastMs
        if (delta in 0 until FIVE_MINUTES_MS) return
        // WR-02 — single SQL UPDATE across every active list, atomic by
        // SQLite row-level locking. Replaces the previous N-statement loop
        // (one `recomputeDueCountForList` per active list) which left a
        // partial-success window if the process died mid-loop. Faster too
        // (no application-side iteration, no Flow subscription dance).
        listRepo.recomputeDueCountForActive(clock.now())
        appPrefs.setLastDueCountRecomputeAt(nowMs)
    }

    private fun ListEntity.toTileState(): ListTileState = ListTileState(
        id = id,
        name = name,
        dueCount = dueCount,
        type = type,
        notificationsEnabled = notificationsEnabled,
    )

    private companion object {
        const val FIVE_MINUTES_MS: Long = 5 * 60 * 1000L

        /** HOME-7 — calls shorter than 3 minutes are dropped from the rhythm strip. */
        const val MIN_RHYTHM_SECONDS: Int = 180
    }
}

/**
 * Data-layer enrichment for one list (see [HomeFeed.enrichment]). The ViewModel
 * maps this to [app.orbit.ui.screens.home.ListTileState] — formatting [NextUpRaw]
 * into a `NextUp` (with the warm `why` line) and attaching the rhythm.
 */
data class ListEnrichment(
    val nextUp: NextUpRaw?,
    val rhythm: List<RhythmDay>,
)

/** Raw "head of queue" for a list. `lastCalledAt` feeds the VM's recency `why` line. */
data class NextUpRaw(
    val contactId: Long,
    val name: String,
    val photoUri: String?,
    val lastCalledAt: Instant?,
)
