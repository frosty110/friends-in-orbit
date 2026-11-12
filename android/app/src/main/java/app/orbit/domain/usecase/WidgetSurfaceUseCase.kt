package app.orbit.domain.usecase

import androidx.compose.runtime.Immutable
import app.orbit.data.WidgetSource
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
 *
 * **One list (WIDGET-13):** a widget pointed at one list reads that list's
 * queue from [SurfaceQueueUseCase] instead. The loop above takes one person
 * per list, its head, so fed a single list it would yield one person and leave
 * "Call suggestions" with nobody beside the lead. [SurfaceQueueUseCase] is the
 * documented sibling of [SurfaceNextUseCase], with the same filter chain and
 * comparator and the same head, so this adds no second copy of the filters and
 * the IGNORE-08 note above still holds. The time-of-day penalty is uniform
 * inside one list (ADR 0008), so it does not apply.
 */
class WidgetSurfaceUseCase @Inject constructor(
    private val surfaceNext: SurfaceNextUseCase,
    private val surfaceQueue: SurfaceQueueUseCase,
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
    suspend operator fun invoke(
        source: WidgetSource = WidgetSource.EveryList,
        max: Int = MAX_SHOWN,
    ): WidgetSurfaceData = when (source) {
        WidgetSource.EveryList -> everyList(max)
        is WidgetSource.OneList -> oneList(source.listId, max)
    }

    /**
     * WIDGET-13, WIDGET-14: the first [max] of one list's queue, or
     * [WidgetSurfaceData.sourceMissing] when that list is archived or deleted.
     * Archived lists are not active, so restoring one brings its widget back.
     */
    private suspend fun oneList(listId: Long, max: Int): WidgetSurfaceData {
        if (listRepo.observeActive().first().none { it.id == listId }) {
            return WidgetSurfaceData(primary = null, alternatives = emptyList(), sourceMissing = true)
        }
        val shown = surfaceQueue(listId).first().take(max).map { it.contact }
        return WidgetSurfaceData(
            primary = shown.firstOrNull(),
            alternatives = shown.drop(1),
            listIdByContactId = shown.associate { it.id to listId },
        )
    }

    private suspend fun everyList(max: Int): WidgetSurfaceData {
        val activeLists = listRepo.observeActive().first()
        val now = clock.now()

        // Collect one SurfaceResult.Found per active list. NO dedup here —
        // the same contact may surface on multiple lists with different
        // effective keys, and we must keep ALL of them until after sorting.
        // `effectiveKey` = real nextDueAt + the list's ADR-0008 time-of-day
        // penalty (0 inside the window / no window). This is the only place the
        // penalty reorders anything: it differentiates contacts surfaced by
        // lists with *different* windows.
        data class Candidate(
            val contact: ContactEntity,
            val effectiveKey: java.time.Instant,
            val listId: Long,
        )

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
                candidates += Candidate(result.contact, result.nextDueAt.plus(penalty), list.id)
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

        val shown = sorted.take(max)
        return WidgetSurfaceData(
            primary      = shown.firstOrNull()?.contact,
            alternatives = shown.drop(1).map { it.contact },
            // The list that surfaced each person: the one where they rank
            // earliest, which `distinctBy` above kept. Tapping them opens that
            // list's deck, where they are the head.
            listIdByContactId = shown.associate { it.contact.id to it.listId },
        )
    }

    companion object {
        /** The most any widget shows: "Call suggestions"' lead and two more (WIDGET-02). */
        const val MAX_SHOWN = 3
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
 *
 * [listIdByContactId] maps each shown person to the list that surfaced them.
 */
@Immutable
data class WidgetSurfaceData(
    val primary: ContactEntity?,
    val alternatives: List<ContactEntity>,
    /**
     * The list each shown person was surfaced by, keyed by contact id. A tap on
     * a person (widget body, the "Call next" shortcut) opens that list's deck
     * with them on top (WIDGET-08, LAUNCH-01). Empty for callers that only
     * need names.
     */
    val listIdByContactId: Map<Long, Long> = emptyMap(),
    /**
     * WIDGET-14: the widget's chosen list is archived or deleted, so it shows
     * "Choose a list" instead of people. Never true for every list.
     */
    val sourceMissing: Boolean = false,
)
