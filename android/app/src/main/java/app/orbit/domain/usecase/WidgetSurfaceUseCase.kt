package app.orbit.domain.usecase

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.ListRepository
import app.orbit.domain.clock.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * WIDGET-01/02 — cross-list primary + alternatives contact source for
 * home-screen widgets.
 *
 * Resolves the "per-list vs. cross-list" ambiguity: [SurfaceNextUseCase] is
 * scoped to a single list, but
 * widgets need the best contact across ALL active lists. This use case fans
 * out to every active (non-archived) list, collects one `SurfaceResult.Found`
 * per list, deduplicates by contact id (the same person may be on multiple
 * lists), and returns a [WidgetSurfaceData] snapshot.
 *
 * **IGNORE-08 / archive exclusion:** This class performs NO independent
 * contact query — it consumes only [SurfaceNextUseCase] output, which already
 * filters `isIgnored == false`, `isArchived == false`, and `pausedUntil` checks
 * (T-11-02 mitigation). Adding a duplicate filter here would create a second
 * source of truth and risk divergence.
 *
 * **Ordering (ADR 0008):** candidates are sorted by an *effective* key —
 * `nextDueAt + timeOfDayPenalty(list.activeWindow)` — ASC, then `contact.id` ASC.
 * This is the one cross-list ranker, so it is where the soft time-of-day weight
 * actually reorders: at noon, a member surfaced by a late-night list sinks below
 * a member surfaced by a daytime list (bounded by [DEFAULT_TIME_OF_DAY_PENALTY_CAP]),
 * without ever being excluded. The real `nextDueAt` still rides each
 * `SurfaceResult.Found` for the card eyebrow; only the sort key is adjusted.
 * [WidgetSurfaceData.primary] is the head; [WidgetSurfaceData.alternatives] the
 * next 0–2 entries.
 */
class WidgetSurfaceUseCase @Inject constructor(
    private val surfaceNext: SurfaceNextUseCase,
    private val listRepo: ListRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
) {

    /**
     * One-shot snapshot of the cross-list widget surface. Suspends until
     * the first emission of each active list's surface flow, then returns.
     *
     * Callers are expected to use this inside a coroutine scope started from
     * `GlanceAppWidget.provideGlance` (or a `CoroutineWorker.doWork`), where
     * a cold one-shot read is the correct pattern (unlike in-app ViewModels
     * which keep a live Flow collected).
     */
    suspend operator fun invoke(): WidgetSurfaceData {
        val activeLists = listRepo.observeActive().first()
        val now = clock.now()

        // Collect one SurfaceResult.Found per active list. NO dedup here —
        // the same contact may surface on multiple lists with different
        // effective keys, and we must keep ALL of them until after sorting.
        // `effectiveKey` = real nextDueAt + the list's ADR-0008 time-of-day
        // penalty (0 inside the window / no window). This is the only place the
        // penalty reorders anything: it differentiates contacts surfaced by
        // lists with *different* windows.
        data class Candidate(val contact: ContactEntity, val effectiveKey: java.time.Instant)

        val candidates = mutableListOf<Candidate>()

        for (list in activeLists) {
            val result = surfaceNext(list.id).first()
            if (result is SurfaceResult.Found) {
                val penalty = timeOfDayPenalty(
                    now = now,
                    zoneId = zoneId,
                    activeHoursStart = list.activeHoursStart,
                    activeHoursEnd = list.activeHoursEnd,
                )
                candidates += Candidate(result.contact, result.nextDueAt.plus(penalty))
            }
        }

        // Sort by effectiveKey ASC (then contact.id ASC for deterministic
        // tiebreaks) BEFORE deduplicating. Sorted ASC means the first
        // occurrence of each contact carries its EARLIEST effective key across
        // all lists, so `distinctBy` keeps the minimum — dedup-before-sort
        // would keep whichever list happened to iterate first and could
        // demote the most-overdue contact (review WR-01).
        val sorted = candidates
            .sortedWith(compareBy<Candidate> { it.effectiveKey }.thenBy { it.contact.id })
            .distinctBy { it.contact.id }

        return WidgetSurfaceData(
            primary      = sorted.firstOrNull()?.contact,
            alternatives = sorted.drop(1).take(2).map { it.contact },
        )
    }
}

/**
 * Snapshot returned by [WidgetSurfaceUseCase].
 *
 * [primary] — the contact with the earliest `nextDueAt` across all active
 * lists, or `null` when no active list has a surfaceable contact.
 *
 * [alternatives] — the next 0–2 contacts ordered by `nextDueAt` ASC. Used
 * by the 4×2 widget to populate the two smaller alternative cards.
 *
 * Both fields are deduped: the same contact can only appear once across
 * [primary] and [alternatives] combined.
 */
@Immutable
data class WidgetSurfaceData(
    val primary: ContactEntity?,
    val alternatives: List<ContactEntity>,
)
