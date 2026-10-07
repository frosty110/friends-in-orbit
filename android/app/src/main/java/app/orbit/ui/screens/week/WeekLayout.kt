package app.orbit.ui.screens.week

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Immutable
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.feed.MIN_RHYTHM_SECONDS
import app.orbit.data.feed.bucketRhythm
import app.orbit.ui.screens.home.RhythmCall
import app.orbit.ui.util.formatClockTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * HOME-13: the Week screen's pure parts, so the rules a reader would argue
 * with (which calls, which week, where a block sits, what TalkBack hears) are
 * pinned by plain tests (WeekLayoutTest) rather than by pixels.
 */

/** The chart's day, in minutes: midnight at the top edge to midnight at the bottom. */
internal const val DAY_MINUTES: Int = 24 * 60

/**
 * Every week of one list's calls, newest first: index 0 is the seven days
 * ending [today] (the strip's seven days, HOME-7), index 1 the seven before,
 * back to the week that holds the earliest qualifying call. Weeks are
 * rolling, not Monday to Sunday, because "This week" has to be exactly what
 * the strip shows (the owner review, decision 2).
 *
 * The days come from [bucketRhythm], the strip's own bucketing, run once over
 * the whole range; nothing here decides again which calls count or which
 * day a call fell on. The earliest call only bounds how far back the pages
 * go, and it uses the same floor, so a two-minute call from last spring does
 * not open a run of empty weeks.
 */
internal fun weekPages(
    calls: List<CallEventEntity>,
    contactsById: Map<Long, ContactEntity>,
    today: LocalDate,
    zone: ZoneId,
): List<WeekPage> {
    val earliest = calls.asSequence()
        .filter { it.durationSeconds >= MIN_RHYTHM_SECONDS }
        .minOfOrNull { it.occurredAt }
        ?.atZone(zone)
        ?.toLocalDate()
    val daysBack = earliest?.let { ChronoUnit.DAYS.between(it, today) }?.coerceAtLeast(0L) ?: 0L
    val count = (daysBack / DAYS_IN_WEEK).toInt() + 1
    val firstDay = today.minusDays(DAYS_IN_WEEK.toLong() * count - 1)
    val days = bucketRhythm(calls, contactsById, firstDay = firstDay, days = DAYS_IN_WEEK * count, zone = zone)
    return (0 until count).map { weeksBack ->
        // days[0] is the oldest day; this week's last day is the last entry.
        val end = days.size - DAYS_IN_WEEK * weeksBack
        val start = end - DAYS_IN_WEEK
        WeekPage(firstDay = firstDay.plusDays(start.toLong()), days = days.subList(start, end).toList())
    }
}

/**
 * One call placed in its day's column. [top] to [bottom] is the block as
 * drawn, [touchTop] to [touchBottom] the part of the column a tap on it
 * covers, all in minutes from the top of the day. [lane] of [lanes] is its
 * slice of the column's width.
 */
@Immutable
internal data class PlacedCall(
    val call: RhythmCall,
    val top: Int,
    val bottom: Int,
    val touchTop: Int,
    val touchBottom: Int,
    val lane: Int,
    val lanes: Int,
)

/**
 * Where each of a day's calls sits on the time axis.
 *
 * - **Top**: its start time ([RhythmCall.minuteOfDay], the wall clock).
 * - **Height**: as long as it lasted, but never under [minBlockMinutes], so a
 *   three-minute call is still a mark you can see with its direction rim.
 *   A call that runs past midnight stops at the bottom edge: it belongs to
 *   the day it started (the strip puts it there too), and the next column
 *   is another day.
 * - **Touch**: never under [minTouchMinutes] (48dp, rules.md Design 3),
 *   centred on the block, so a short block keeps a full-size target.
 * - **Side by side**: calls whose touch targets would overlap share the
 *   column in lanes, the calendar convention. Overlap is judged on the touch
 *   targets, not the drawn blocks, because two targets on top of each other
 *   would leave one of them untappable. Lanes are counted per cluster of
 *   calls that overlap one another, so a crowded evening does not narrow a
 *   quiet morning.
 *
 * Near midnight the block, and then its target, move up rather than run off
 * the bottom of the chart; that is the only case a block starts above its
 * time, by at most [minBlockMinutes].
 */
internal fun placeDay(
    calls: List<RhythmCall>,
    minBlockMinutes: Int,
    minTouchMinutes: Int,
): List<PlacedCall> {
    class Span(val call: RhythmCall, val top: Int, val bottom: Int, val touchTop: Int, val touchBottom: Int)

    val spans = calls.map { call ->
        val start = call.minuteOfDay.coerceIn(0, DAY_MINUTES - 1)
        val length = ((call.durationSeconds + 59) / 60).coerceAtLeast(1)
        val end = (start + length).coerceAtMost(DAY_MINUTES)
        val top = start.coerceAtMost(DAY_MINUTES - minBlockMinutes)
        val bottom = maxOf(end, top + minBlockMinutes)
        val touchLength = maxOf(bottom - top, minTouchMinutes)
        val touchTop = ((top + bottom) / 2 - touchLength / 2).coerceIn(0, DAY_MINUTES - touchLength)
        Span(call, top, bottom, touchTop, touchTop + touchLength)
    }.sortedWith(compareBy({ it.touchTop }, { it.top }, { it.call.callEventId }))

    val placed = ArrayList<PlacedCall>(spans.size)
    val cluster = ArrayList<Pair<Span, Int>>()
    val laneEnds = ArrayList<Int>()
    var clusterEnd = 0
    fun closeCluster() {
        cluster.forEach { (s, lane) ->
            placed += PlacedCall(s.call, s.top, s.bottom, s.touchTop, s.touchBottom, lane, laneEnds.size)
        }
        cluster.clear()
        laneEnds.clear()
    }
    for (span in spans) {
        if (cluster.isNotEmpty() && span.touchTop >= clusterEnd) closeCluster()
        val free = laneEnds.indexOfFirst { it <= span.touchTop }
        val lane = if (free >= 0) free else laneEnds.size.also { laneEnds += 0 }
        laneEnds[lane] = span.touchBottom
        clusterEnd = if (cluster.isEmpty()) span.touchBottom else maxOf(clusterEnd, span.touchBottom)
        cluster += span to lane
    }
    closeCluster()
    return placed
}

/**
 * What TalkBack says for one day column: "Wednesday 30 September, 2 calls:
 * You called Sarah Chen at 6:40pm for 14 min. Alex Kim called you at 1:15pm
 * for 9 min." in time order, or "Friday 2 October, No calls" for a quiet
 * day (the strip's own quiet wording). [dayLabel] is the spoken day
 * (`formatDayHeader`: "Today", "Yesterday", "Wednesday 30 September").
 *
 * Under the privacy curtain, and for someone who has left the list since,
 * the person is "someone" in a sentence of its own ("You called someone at
 * 6:40pm for 14 min."), never a capitalised stand-in mid-sentence (PRIV-03).
 * Each call is a whole sentence from resources, joined by a space.
 */
internal fun weekDayDescription(
    context: Context,
    dayLabel: String,
    calls: List<RhythmCall>,
    curtain: Boolean,
): String {
    if (calls.isEmpty()) {
        return context.getString(
            R.string.home_rhythm_day_quiet_a11y,
            dayLabel,
            context.getString(R.string.home_direction_none),
        )
    }
    val sentences = calls.joinToString(" ") { call ->
        val duration = call.durationLabel.asString(context)
        val name = call.contactName.takeUnless { curtain }
        val outgoing = call.direction == CallDirection.OUTGOING
        when {
            name == null && outgoing ->
                context.getString(R.string.home_week_call_you_called_someone, call.timeLabel, duration)
            name == null ->
                context.getString(R.string.home_week_call_someone_called, call.timeLabel, duration)
            outgoing -> context.getString(R.string.home_week_call_you_called, name, call.timeLabel, duration)
            else -> context.getString(R.string.home_week_call_they_called, name, call.timeLabel, duration)
        }
    }
    return context.getString(
        R.string.home_week_day_a11y,
        dayLabel,
        context.resources.getQuantityString(R.plurals.home_rhythm_day_calls, calls.size, calls.size),
        sentences,
    )
}

/**
 * The header's dates for a week that is not this one: "28 Sep to 4 Oct",
 * with the year on both ends when either falls outside [today]'s year
 * ("29 Dec 2025 to 4 Jan 2026"). The pattern is the resource's, so a
 * translation can reorder it.
 */
internal fun weekRangeLabel(resources: Resources, firstDay: LocalDate, lastDay: LocalDate, today: LocalDate): String {
    val withYear = firstDay.year != today.year || lastDay.year != today.year
    val pattern = resources.getString(
        if (withYear) R.string.home_week_day_year_pattern else R.string.home_week_day_pattern,
    )
    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    return resources.getString(R.string.home_week_range, firstDay.format(formatter), lastDay.format(formatter))
}

/**
 * The time axis's labels: every three hours from 3am to 9pm, in the phone's
 * clock style through the app's one clock formatter ("3am", "12pm" on a
 * 12-hour phone, "03:00", "15:00" on a 24-hour one), so the axis reads like
 * every time beside it. Hour lines run every hour; the first below the top
 * edge is 1am, which is what the owner asked for at the top.
 */
internal fun hourLabels(use24Hour: Boolean): List<Pair<Int, String>> =
    LABELLED_HOURS.map { hour -> hour to formatClockTime(LocalTime.of(hour, 0), use24Hour) }

private val LABELLED_HOURS = listOf(3, 6, 9, 12, 15, 18, 21)
private const val DAYS_IN_WEEK = 7
