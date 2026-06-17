package app.orbit.domain.usecase

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * ADR 0008 — soft time-of-day weighting. Default cap on how far a wrong
 * time-of-day can sink a contact in the surfacing order. Bounded well below
 * real cadences (days–weeks) so overdue-ness stays dominant: a misaligned clock
 * only reorders contacts whose due times are already within this window of each
 * other, and can never bury a genuinely overdue call.
 */
val DEFAULT_TIME_OF_DAY_PENALTY_CAP: Duration = Duration.ofHours(6)

/**
 * ADR 0008 — the penalty [Duration] to ADD to a contact's `nextDue` sort key for
 * a list with the given active-hours window. Returns:
 *   - [Duration.ZERO] when the list has no window set, or `now` is inside it
 *     (preferred time → no penalty, sort by due-ness alone);
 *   - otherwise a bounded value that **decays to zero as the window's next
 *     opening approaches** — `min(maxPenalty, minutesUntilWindowOpens)`.
 *
 * Pure over (`now`, `zoneId`, window): no randomness, no per-contact learned
 * state — the rule engine's determinism guarantee is preserved.
 *
 * NOTE the window is a *list-level* property, so this value is identical for
 * every member of a list. It therefore only reorders candidates across lists
 * with different windows ([WidgetSurfaceUseCase]); within a single list it is
 * uniform and order-neutral. See ADR 0008 Implementation amendment.
 */
fun timeOfDayPenalty(
    now: Instant,
    zoneId: ZoneId,
    activeHoursStart: LocalTime?,
    activeHoursEnd: LocalTime?,
    maxPenalty: Duration = DEFAULT_TIME_OF_DAY_PENALTY_CAP,
): Duration {
    val start = activeHoursStart ?: return Duration.ZERO
    val end = activeHoursEnd ?: return Duration.ZERO
    val localNow = now.atZone(zoneId).toLocalTime()
    if (inActiveWindow(localNow, start, end)) return Duration.ZERO

    val nowMinutes = localNow.toSecondOfDay() / 60
    val startMinutes = start.toSecondOfDay() / 60
    // Minutes from now forward to the window's next opening (handles midnight wrap).
    val minutesUntilOpen = Math.floorMod(startMinutes - nowMinutes, 24 * 60).toLong()
    val cappedMinutes = minOf(maxPenalty.toMinutes(), minutesUntilOpen)
    return Duration.ofMinutes(cappedMinutes)
}

/**
 * Whether [now] falls inside the `[start, end]` window. Same-day window when
 * `start <= end` (e.g. 09:00..17:00); wraps midnight otherwise (e.g. 21:00..02:00
 * means "21:00 or later OR 02:00 or earlier"). Endpoints inclusive.
 */
private fun inActiveWindow(now: LocalTime, start: LocalTime, end: LocalTime): Boolean =
    if (start <= end) {
        !now.isBefore(start) && !now.isAfter(end)
    } else {
        !now.isBefore(start) || !now.isAfter(end)
    }
