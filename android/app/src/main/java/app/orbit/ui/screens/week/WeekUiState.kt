package app.orbit.ui.screens.week

import androidx.compose.runtime.Immutable
import app.orbit.ui.screens.home.RhythmDay
import java.time.LocalDate

/**
 * HOME-13: the Week screen's one state contract (ARCH-02).
 *
 * Which week is on screen is not in here. It is the pager's, in the
 * composable (rules.md Code 7: one owner for screen-local state), and the
 * ViewModel hands over every week the list has, so a step or a swipe is a
 * page change and never a round trip.
 */
sealed interface WeekUiState {

    /** Nothing read yet: quiet chrome, never a false "No calls this week." */
    @Immutable
    data object Loading : WeekUiState

    /**
     * The list could not be read ([canRetry], with Try again), or there is no
     * list to read: the route's id did not parse, or no list has it any more.
     * Then Try again could change nothing, so the screen offers Go back alone
     * (Browse's precedent, BROWSE-06).
     */
    @Immutable
    data class Error(val canRetry: Boolean) : WeekUiState

    /**
     * [weeks] is never empty. Index 0 is this week: the seven days ending
     * [today], exactly the strip's seven days on Home. Index 1 is the seven
     * days before, and so on back to the week holding the list's earliest
     * qualifying call (the call-history import window bounds it). A list
     * with no calls has this week alone.
     */
    @Immutable
    data class Ready(
        val listName: String,
        val today: LocalDate,
        val weeks: List<WeekPage>,
    ) : WeekUiState
}

/**
 * Seven days of one list's calls, oldest first, in the strip's own
 * [RhythmDay] (so the day sheet takes a day unchanged). Derived values are
 * computed once here, not in a getter the screen reads on every frame
 * (ARCH-02).
 */
@Immutable
data class WeekPage(
    val firstDay: LocalDate,
    val days: List<RhythmDay>,
) {
    val lastDay: LocalDate = firstDay.plusDays(days.size.toLong() - 1)

    /** True when no day has a call: "No calls this week." over the empty grid. */
    val isEmpty: Boolean = days.all { it.calls.isEmpty() }

    /**
     * The earliest start time in the week, as a minute of the day, or null
     * for a quiet week. The chart opens scrolled to it (8am when null).
     */
    val earliestMinute: Int? = days.flatMap { it.calls }.minOfOrNull { it.minuteOfDay }
}
