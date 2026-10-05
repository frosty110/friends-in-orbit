package app.orbit.ui.util

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.PluralsRes
import app.orbit.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Single-source-of-truth time formatters: how long ago, how long for, which
 * day, what time. Used by every screen that says any of those, plus the data
 * feeds that pre-format them (HomeFeed, ContactMapper).
 *
 * Keeping these in one file prevents drift (e.g., the VM saying "today" while
 * the UI says "0 days ago"). Voice rule: lowercase verb fragments, no
 * exclamation, no "yesterday!" or "ages ago", quiet factual labels only.
 *
 * The words are copy, so they live in `strings_time.xml` (UX rubric 3.4) and
 * the word-building formatters return [UiText]: a ViewModel or feed can call
 * them without a Context, and the screen (or a notification, with its
 * Context) resolves the result in the user's language. Clock times stay
 * [String]: they are numbers plus the locale's own am/pm marker, which
 * java.time already supplies for every language.
 */

/**
 * The one way Orbit says how long something has been (voice.md glossary):
 * "1 day", "9 days", "2 weeks", "5 weeks", "3 months", "1 year". Every
 * "time since a call" in the app is built from this, so one screen can't say
 * "27 days ago" while the next says "3 weeks" (UX rubric D7).
 *
 * Buckets: days below 14, weeks below 60 days, months below a year, then
 * years ([spanBucket]). Singular forms come from `<plurals>`, so "1 week",
 * never "1 weeks", in every language.
 */
fun formatSpan(days: Long): UiText {
    val (unit, n) = spanBucket(days)
    return UiText.plural(unit.spanPlural, n, n)
}

/**
 * "today" / "yesterday" / "{span} ago", in [formatSpan]'s buckets:
 * "9 days ago", "3 weeks ago", "2 months ago". Each "ago" form is its own
 * plural rather than "{span} ago", because other languages inflect the
 * number word differently after "ago" (German "vor 3 Tagen", not "Tage").
 *
 * Day-relative labels compare LOCAL calendar days, not 24-hour windows: an
 * 11pm call read the next morning is "yesterday", never "today".
 * [zone] defaults to [ZoneId.systemDefault] (same convention as
 * [formatAbsolute]); existing call sites pass only `(occurredAt, now)` and
 * keep their contracts.
 */
fun formatRelative(
    occurredAt: Instant,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): UiText {
    val days = ChronoUnit.DAYS.between(
        occurredAt.atZone(zone).toLocalDate(),
        now.atZone(zone).toLocalDate(),
    ).coerceAtLeast(0L)
    return when (days) {
        0L -> UiText.res(R.string.time_ago_today)
        1L -> UiText.res(R.string.time_ago_yesterday)
        else -> spanBucket(days).let { (unit, n) -> UiText.plural(unit.agoPlural, n, n) }
    }
}

/** The four units [formatSpan] and [formatRelative] count in, with their plurals. */
private enum class SpanUnit(@PluralsRes val spanPlural: Int, @PluralsRes val agoPlural: Int) {
    Days(R.plurals.time_span_days, R.plurals.time_ago_days),
    Weeks(R.plurals.time_span_weeks, R.plurals.time_ago_weeks),
    Months(R.plurals.time_span_months, R.plurals.time_ago_months),
    Years(R.plurals.time_span_years, R.plurals.time_ago_years),
}

/** Days below 14, weeks below 60 days, months below a year, then years. Negative clamps to 0 days. */
private fun spanBucket(days: Long): Pair<SpanUnit, Int> {
    val d = days.coerceAtLeast(0L)
    return when {
        d < 14L -> SpanUnit.Days to d.toInt()
        d < 60L -> SpanUnit.Weeks to (d / 7L).toInt()
        d < 365L -> SpanUnit.Months to (d / 30L).toInt()
        else -> SpanUnit.Years to (d / 365L).toInt()
    }
}

/**
 * Whether clock times show as "16:30" or "4:30pm". Follows the phone's
 * 12/24-hour setting (it used to be hardcoded: 12-hour on call rows and
 * active hours, 24-hour in nudge summaries). Refreshed by the app on start
 * and every time it returns to the foreground, so a change in Android
 * settings shows up without a restart. Plain field, not state: screens
 * recompose on resume anyway. Also decides the 24-hour strips' tick labels
 * ([axisTickLabels]) and the time picker's dial.
 */
object TimeStyle {
    @Volatile
    var is24Hour: Boolean = false

    fun refresh(context: Context) {
        is24Hour = DateFormat.is24HourFormat(context)
    }
}

/**
 * A clock time in the phone's style: "16:30" / "16:00", or "4:30pm" / "4pm"
 * (lowercase marker, no space, minutes dropped on the hour, the app's quiet
 * 12-hour convention).
 *
 * The am/pm marker is the locale's own (java.time, [Locale.getDefault]), not
 * a hardcoded English "pm"; only the layout (number first, no space,
 * lowercase) is Orbit's.
 */
fun formatClockTime(time: LocalTime, use24Hour: Boolean = TimeStyle.is24Hour): String {
    val locale = Locale.getDefault()
    val pattern = when {
        use24Hour -> "HH:mm"
        time.minute == 0 -> "ha"
        else -> "h:mma"
    }
    val formatted = DateTimeFormatter.ofPattern(pattern, locale).format(time)
    return if (use24Hour) formatted else formatted.lowercase(locale)
}

/** Wall-clock label for call-log rows, in the phone's 12/24-hour style ([formatClockTime]). */
fun formatWallClock(
    occurredAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    use24Hour: Boolean = TimeStyle.is24Hour,
): String = formatClockTime(occurredAt.atZone(zone).toLocalTime(), use24Hour)

/**
 * Tick labels under a 24-hour strip (Card view's heat strip, a list's active
 * hours bar): midnight, 6am, noon, 6pm, midnight. "12a 6a 12p 6p 12a" on a
 * 12-hour phone, "00 06 12 18 00" on a 24-hour one, so the strip reads the
 * same way as every clock time beside it. Returns string resource ids.
 */
fun axisTickLabels(use24Hour: Boolean = TimeStyle.is24Hour): List<Int> = if (use24Hour) {
    listOf(
        R.string.time_axis_24h_midnight,
        R.string.time_axis_24h_6am,
        R.string.time_axis_24h_noon,
        R.string.time_axis_24h_6pm,
        R.string.time_axis_24h_midnight,
    )
} else {
    listOf(
        R.string.time_axis_midnight,
        R.string.time_axis_6am,
        R.string.time_axis_noon,
        R.string.time_axis_6pm,
        R.string.time_axis_midnight,
    )
}

/**
 * Calendar-day section header for the call log and Home's rhythm day sheet:
 * "Today" / "Yesterday" / "Wednesday 3 June" (year appended only when the
 * day falls outside [today]'s year, e.g. "Wednesday 3 June 2025").
 *
 * The weekday and month names come from java.time in [Locale.getDefault]
 * (the convention the nudge schedule's day chips and Card view's "on
 * Tuesday" already follow); their order is the resource's, so a translation
 * can put the month first.
 *
 * Pure over [LocalDate] so callers own the Instant→LocalDate conversion and
 * grouping + labelling can never disagree about which day a call landed on.
 */
fun formatDayHeader(day: LocalDate, today: LocalDate): UiText = when (day) {
    today -> UiText.res(R.string.time_day_today)
    today.minusDays(1) -> UiText.res(R.string.time_day_yesterday)
    else -> {
        val locale = Locale.getDefault()
        val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val month = day.month.getDisplayName(TextStyle.FULL, locale)
        if (day.year == today.year) {
            UiText.res(R.string.time_day_named, weekday, day.dayOfMonth, month)
        } else {
            UiText.res(R.string.time_day_named_year, weekday, day.dayOfMonth, month, day.year)
        }
    }
}

/**
 * How long a call lasted: "45s" / "14 min" / "1h 5m".
 *
 * A plain formatter: zero reads "0s" like any other short call. Whether a
 * zero means "no measured call" is the caller's call, and every caller
 * already decides it before getting here (manual and attempted rows say
 * "Logged" / "Attempted", and [app.orbit.data.mappers.withCallStats] leaves
 * the average empty when no call was measured). Until 2026-10-05 zero
 * returned an em dash, which leaked onto Card view's stats.
 */
fun formatDuration(seconds: Int): UiText {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> UiText.plural(R.plurals.time_duration_seconds, s, s)
        s < 3600 -> (s / 60).let { UiText.plural(R.plurals.time_duration_minutes, it, it) }
        else -> UiText.res(R.string.time_duration_hours_minutes, s / 3600, (s % 3600) / 60)
    }
}

/**
 * Pure absolute formatter for note timestamps.
 *
 * Format: "MMM d · {clock time}", e.g. "Mar 14 · 2:14pm", or "Mar 14 · 14:14"
 * when the phone uses 24-hour time ([formatClockTime]). The month name is the
 * locale's (java.time); the layout is a date-and-time pattern, not a sentence,
 * so it stays a [String].
 *
 * Pure: no `now` parameter. Uses [ZoneId.systemDefault] so the rendered date
 * matches the device's local time zone. Tests can pin a specific zone via the
 * overload that takes an explicit [zone]; the default is sufficient because
 * notes always render against the user's wall clock.
 */
fun formatAbsolute(
    occurredAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    use24Hour: Boolean = TimeStyle.is24Hour,
): String {
    val ldt = occurredAt.atZone(zone)
    val date = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(ldt)
    return "$date · ${formatClockTime(ldt.toLocalTime(), use24Hour)}"
}
