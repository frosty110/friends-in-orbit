package app.orbit.data.feed

import app.orbit.data.AppPrefs
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.di.ApplicationScope
import app.orbit.domain.clock.Clock
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.domain.usecase.SurfaceResult
import app.orbit.ui.screens.home.ListTileState
import app.orbit.ui.screens.home.RhythmCall
import app.orbit.ui.screens.home.RhythmDay
import app.orbit.ui.util.formatDuration
import app.orbit.ui.util.formatWallClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

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
 *
 * **Failure is data (HOME-10).** Both projections catch what their sources
 * throw and report it through [failed]; HomeViewModel maps that to
 * `HomeUiState.Error`. Uncaught, a failing `observeActive()` escaped the
 * handler-less `@ApplicationScope` and crashed the app (the shape BrowseFeed
 * had, and fixed, for BROWSE-06). [retry] re-subscribes both.
 */
@Singleton
open class HomeFeed @Inject constructor(
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val appPrefs: AppPrefs,
    private val surfaceNext: SurfaceNextUseCase,
    private val callEventRepo: CallEventRepository,
    private val contactRepo: ContactRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    // HOME-10: bumped by [retry]; `tiles` and `enrichment` are flatMapLatest
    // over it, so a bump drops the failed subscription and opens a fresh one.
    // `tiles` and `enrichment` are plain vals (no per-key cache to evict, as
    // BrowseFeed has), so this is the one seam that can rebuild them.
    // Declared before them: their initializers read it.
    private val retryCount = MutableStateFlow(0)

    // One flag per projection, each with one writer (its own flow, rules.md
    // Code 7): set in its catch, cleared by its next successful emission.
    // Cleared there and not in [retry] on purpose: cleared at retry, the VM
    // would see "not failed" with the stale value still in the StateFlow and
    // flash Empty (the first-install CTA) until the fresh read answered.
    private val tilesFailed = MutableStateFlow(false)
    private val enrichmentFailed = MutableStateFlow(false)

    /**
     * HOME-10: true while a source behind [tiles] or [enrichment] has thrown
     * and not yet recovered. HomeViewModel renders it as `HomeUiState.Error`
     * with Try again, which calls [retry]. The failing flow keeps its last
     * value, so a consumer reading `tiles.value` must check this first.
     */
    open val failed: StateFlow<Boolean> =
        combine(tilesFailed, enrichmentFailed) { t, e -> t || e }
            .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = false)

    // HOME-12: the local date Home last resumed on. [buildRhythm] buckets by
    // `clock.now()` only when a Room flow re-emits, so with no call or
    // membership change overnight the strip's last column kept meaning
    // yesterday while the screen's weekday letters moved on. HomeViewModel
    // reports each resume's date through [noteToday]; a StateFlow conflates
    // equal values, so the per-list combine re-buckets only when the day has
    // actually changed, not on every resume (ADR 0006: no projection re-fire
    // on navigation).
    private val today = MutableStateFlow<LocalDate?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    open val tiles: StateFlow<List<ListTileState>> =
        retryCount
            .flatMapLatest {
                listRepo.observeActive()
                    .map { rows -> rows.map { it.toTileState() } }
                    .reportingFailure(tilesFailed)
            }
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
        retryCount
            .flatMapLatest {
                listRepo.observeActive()
                    .flatMapLatest { lists ->
                        if (lists.isEmpty()) {
                            flowOf(emptyMap())
                        } else {
                            combine(lists.map { enrichOne(it.id) }) { it.toMap() }
                        }
                    }
                    .reportingFailure(enrichmentFailed)
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap(),
            )

    /** HOME-10: the Error state's Try again. Re-subscribes [tiles] and [enrichment]. */
    open fun retry() {
        retryCount.update { it + 1 }
    }

    /**
     * HOME-12: Home resumed on [date]. Re-buckets every list's rhythm when the
     * day has changed since the last call; a no-op otherwise.
     */
    open fun noteToday(date: LocalDate) {
        today.value = date
    }

    /**
     * Clears [flag] on each value and sets it when the upstream throws, then
     * completes; the caller's `flatMapLatest` over [retryCount] is what starts
     * again. The flow's last value stays in its StateFlow, which is why
     * [failed] has to be read alongside it. No logging: the flag is the report
     * (rules.md Code 4), and CancellationException passes through (Code 5).
     */
    private fun <T> Flow<T>.reportingFailure(flag: MutableStateFlow<Boolean>): Flow<T> =
        onEach { flag.value = false }
            .catch { t ->
                if (t is CancellationException) throw t
                flag.value = true
            }

    private fun enrichOne(listId: Long) =
        combine(
            surfaceNext(listId),
            callEventRepo.observeRecentForListContacts(listId),
            // HOME-8 — the rhythm strip taps through to "who did I talk to that
            // day", so each bar needs a name and a face, not just a contactId.
            // Room shares the underlying query with SurfaceNextUseCase's own
            // member read, so this is not an extra round trip per list.
            contactRepo.observeForListMembers(listId),
            // HOME-12: only a trigger; buildRhythm still reads clock.now().
            today,
        ) { surface, calls, members, _ ->
            val byId = members.associateBy { it.id }
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
            listId to ListEnrichment(nextUp = nextUp, rhythm = buildRhythm(calls, byId))
        }

    /**
     * The trailing 7 local days (index 0 = six days ago, index 6 = today),
     * through the one bucketing the Week screen shares ([bucketRhythm]).
     * Bars/colors are the UI's job; this only places each call on its day.
     */
    private fun buildRhythm(
        calls: List<CallEventEntity>,
        contactsById: Map<Long, ContactEntity>,
    ): List<RhythmDay> {
        val zone = ZoneId.systemDefault()
        val today = clock.now().atZone(zone).toLocalDate()
        return bucketRhythm(calls, contactsById, firstDay = today.minusDays(6), days = 7, zone = zone)
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
    }
}

/**
 * HOME-7: calls shorter than 3 minutes stay off the rhythm strip, and so off
 * the Week screen (HOME-13), which draws the same calls.
 */
internal const val MIN_RHYTHM_SECONDS: Int = 180

/**
 * HOME-7 / HOME-13: the one bucketing of a list's calls into local days.
 * Home's strip asks for the seven days ending today; the Week screen asks for
 * every whole week back to the list's first call. One function, so the two
 * can never disagree about which calls count, which day a call fell on, or
 * the order within a day (the owner review, decision 2: the Week screen
 * shows the strip's calls).
 *
 * - **Which calls**: at least [MIN_RHYTHM_SECONDS]. A connection logged by
 *   hand is written with 0 seconds, so it never qualifies.
 * - **Which day**: the local date the call started on, in [zone] (the
 *   device's). A call that runs past midnight belongs to the day it began,
 *   on the strip and on the Week screen alike. A day is a calendar date, not
 *   a 24 hour window, so a 23 or 25 hour day when the clocks change keeps
 *   every call that started on it.
 * - **Where in the day**: [RhythmCall.minuteOfDay], the wall-clock start in
 *   [zone], for the same reason.
 * - **Order**: oldest first within the day, so the strip's stacked bars read
 *   top-down in the order the day sheet lists them.
 *
 * Returns [days] entries, index 0 = [firstDay]. Calls outside the range are
 * dropped.
 */
internal fun bucketRhythm(
    calls: List<CallEventEntity>,
    contactsById: Map<Long, ContactEntity>,
    firstDay: LocalDate,
    days: Int,
    zone: ZoneId,
): List<RhythmDay> {
    val lastDay = firstDay.plusDays(days.toLong() - 1)
    val byDate = calls.asSequence()
        .filter { it.durationSeconds >= MIN_RHYTHM_SECONDS }
        .mapNotNull { ev ->
            val d = ev.occurredAt.atZone(zone).toLocalDate()
            if (d.isBefore(firstDay) || d.isAfter(lastDay)) null else d to ev
        }
        .groupBy({ it.first }, { it.second })
    return (0 until days).map { offset ->
        val date = firstDay.plusDays(offset.toLong())
        RhythmDay(
            calls = (byDate[date] ?: emptyList())
                .sortedWith(compareBy({ it.occurredAt }, { it.id }))
                .map { ev ->
                    val contact = contactsById[ev.contactId]
                    val start = ev.occurredAt.atZone(zone).toLocalTime()
                    RhythmCall(
                        callEventId = ev.id,
                        contactId = ev.contactId,
                        // A member removed from the list between the call
                        // and now still has its bar; a null name renders as
                        // "Someone" (strings_home.xml), which keeps the day
                        // honest rather than dropping the call.
                        contactName = contact?.displayName,
                        photoUri = contact?.photoUri,
                        durationSeconds = ev.durationSeconds,
                        direction = ev.direction,
                        durationLabel = formatDuration(ev.durationSeconds),
                        timeLabel = formatWallClock(ev.occurredAt, zone),
                        minuteOfDay = start.hour * 60 + start.minute,
                    )
                },
        )
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
