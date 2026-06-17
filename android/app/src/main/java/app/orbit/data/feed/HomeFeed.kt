package app.orbit.data.feed

import app.orbit.data.AppPrefs
import app.orbit.data.entity.ListEntity
import app.orbit.data.repository.ListRepository
import app.orbit.di.ApplicationScope
import app.orbit.domain.clock.Clock
import app.orbit.ui.screens.home.ListTileState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
