package app.orbit.ui.util

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * When someone next comes up, in the buckets Orbit words it by: later today,
 * tomorrow, a weekday within the week, then a span ("2 weeks"). One function
 * for the buckets, so Card view's Later and Sooner snackbars ("Sam will come
 * up again on Thursday.", the lowercase fragments in strings_card.xml), the
 * card's swipe hints ("Later · Thursday", CARD-09) and Browse's rows
 * ("Thursday", BROWSE-07, strings_browse.xml) can never put the same person in
 * two different buckets. Each screen owns its own words: a
 * fragment that slots mid-sentence and a label that stands alone are separate
 * strings for translators.
 *
 * Moved here from `CardViewViewModel.futureDueLabel` on 2026-10-07 with its
 * behaviour unchanged, which includes counting whole 24-hour spans from [now]
 * rather than calendar days (a time at 1am tomorrow, seen at 10pm, is
 * [LaterToday]). Callers decide what a time at or before [now] means: Browse
 * says "Up now" before asking; the card has only ever passed future times or
 * `now` itself.
 */
sealed interface ComesUp {
    data object LaterToday : ComesUp
    data object Tomorrow : ComesUp

    /** Two to six days out: the weekday the time falls on, in [zone]. */
    data class OnDay(val day: DayOfWeek) : ComesUp

    /** A week or more out: whole days, for [formatSpan]. */
    data class InDays(val days: Long) : ComesUp
}

fun comesUp(due: Instant, now: Instant, zone: ZoneId): ComesUp {
    val days = Duration.between(now, due).toDays()
    return when {
        days <= 0L -> ComesUp.LaterToday
        days == 1L -> ComesUp.Tomorrow
        days < 7L -> ComesUp.OnDay(due.atZone(zone).dayOfWeek)
        else -> ComesUp.InDays(days)
    }
}
