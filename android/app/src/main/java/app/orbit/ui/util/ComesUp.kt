package app.orbit.ui.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * When someone next comes up, in the buckets Orbit words it by: later today,
 * tomorrow, a weekday within the week, then a span ("2 weeks"). One function
 * for the buckets, so Card view's Later, Sooner and Log a connection
 * snackbars ("Sam will come up again on Thursday.", the lowercase fragments
 * in strings_card.xml), its All quiet line, the card's swipe hints ("Later ·
 * Thursday", CARD-09) and Browse's rows ("Thursday", BROWSE-07,
 * strings_browse.xml) can never put the same person in two different
 * buckets. Each screen owns its own words: a fragment that slots
 * mid-sentence and a label that stands alone are separate strings for
 * translators.
 *
 * The buckets are days on the phone's calendar: the local date of [due] in
 * [zone] against the local date of [now], the way [formatRelative] words a
 * call's age. Tomorrow at 9am, seen today at 10pm, is [Tomorrow]. Until
 * 2026-10-08 they counted whole 24-hour spans from [now]
 * (`Duration.toDays`), and a stored time keeps the clock time of the call or
 * the move that set it, so any time at an earlier hour than the moment of
 * looking read a bucket early: tomorrow morning "Later today", the day after
 * "Tomorrow", next week today's own weekday. Dates also keep a 23 or 25 hour
 * daylight-saving day from moving a bucket.
 *
 * [now] is the instant to count from. Where [due] was just worked out from an
 * instant (a move, a log), pass that instant, not a fresh clock read: two
 * reads either side of midnight are a day apart, and the words would name a
 * day early (CARD-02).
 *
 * Callers decide what a time at or before [now] means: Browse says "Up now"
 * before asking; the card passes only future times, or Sooner's own instant
 * when it lands on now.
 */
sealed interface ComesUp {
    /** On [now]'s own date, or before it. */
    data object LaterToday : ComesUp

    /** The next date. */
    data object Tomorrow : ComesUp

    /** Two to six dates out: the weekday the time falls on, never today's or tomorrow's. */
    data class OnDay(val day: DayOfWeek) : ComesUp

    /** Seven dates or more out: the count of dates, for [formatSpan] ("7 days", "2 weeks"). */
    data class InDays(val days: Long) : ComesUp
}

fun comesUp(due: Instant, now: Instant, zone: ZoneId): ComesUp {
    val dueDate = due.atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), dueDate)
    return when {
        days <= 0L -> ComesUp.LaterToday
        days == 1L -> ComesUp.Tomorrow
        days < 7L -> ComesUp.OnDay(dueDate.dayOfWeek)
        else -> ComesUp.InDays(days)
    }
}
