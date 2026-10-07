package app.orbit.ui.screens.lists

import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.R
import app.orbit.ui.components.OrbitSlider
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString

/**
 * LIST-24: "How often", the one rhythm control, for every list: Keep in touch,
 * Late night, Energize and smart lists alike. It shows [intervalHours] as whole
 * days ("Aim for every 3 days" for a Late night list, whose base is 72h) and
 * hands back the chosen interval in hours. What a commit does to the list is
 * the caller's (List settings converts a Late night or Energize list to Keep in
 * touch through `toKeepInTouchEvery`); this composable only picks a number.
 *
 * Self-contained on purpose: it takes a number and a callback and reads no
 * screen state, so the step-by-step New list flow (planned in the owner's
 * review of 2026-10-07, its "How often" step) can call it as it
 * is. It moved here from ListConfigBody's private `IntervalSliderLocal` for
 * that reason.
 *
 * One sentence over the slider ("Aim for every 14 days"), with the interval as
 * its argument, rather than a label on the left and the value on the right: a
 * translator can then put the words in their language's order (voice.md,
 * "keep a sentence whole"). An interval of one day has its own sentence ("Aim
 * for every day"), because "every 1 day" is not how anyone says it.
 *
 * Every release commits, even one where the slider started. Whether a value
 * is a change is the caller's to decide against what is stored: List
 * settings' ViewModel writes nothing for the interval a list already has (so
 * touching a Late night list's slider without moving it keeps it Late night),
 * and writes any choice for a list with no readable rhythm, which this
 * composable shows at Keep in touch's starting interval and could not tell
 * apart from a real 2 days.
 */
@Composable
fun HowOftenSlider(
    intervalHours: Int,
    onCommit: (intervalHours: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var days by remember(intervalHours) { mutableFloatStateOf(intervalDaysFor(intervalHours).toFloat()) }
    val rounded = days.toInt().coerceAtLeast(INTERVAL_MIN_DAY)
    Column(modifier.padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x4)) {
        Text(
            text = howOftenAimLabel(rounded).asString(),
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.fillMaxWidth(),
        )
        OrbitSlider(
            value = days,
            onValueChange = { days = it },
            onValueChangeFinished = {
                onCommit(days.toInt().coerceAtLeast(INTERVAL_MIN_DAY) * HOURS_PER_DAY)
            },
            valueRange = INTERVAL_MIN_DAY.toFloat()..INTERVAL_MAX_DAY.toFloat(),
            label = stringResource(R.string.lists_interval_label),
            valueDescription = howOftenEveryLabel(rounded).asString(),
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
        )
        IntervalScaleLabels(modifier = Modifier.fillMaxWidth().padding(top = OrbitTheme.spacing.x1))
    }
}

/**
 * Whole days, as the slider and the Lists row show an interval: 48h reads
 * "2 days", and anything under a day reads one day (nothing below the UI
 * enforces a floor wider than `withIntervalHours`' one hour).
 */
internal fun intervalDaysFor(hours: Int): Int = (hours / HOURS_PER_DAY).coerceAtLeast(INTERVAL_MIN_DAY)

/**
 * A list's rhythm in the words the Lists row and the slider's TalkBack value
 * use: "Every 14 days", and "Every day" for one.
 */
internal fun howOftenEveryLabel(days: Int): UiText =
    if (days == 1) {
        UiText.res(R.string.lists_interval_every_day)
    } else {
        UiText.plural(R.plurals.lists_interval_every_days, days, days)
    }

/** The sentence over the slider: "Aim for every 14 days", and "Aim for every day" for one. */
internal fun howOftenAimLabel(days: Int): UiText =
    if (days == 1) {
        UiText.res(R.string.lists_interval_aim_every_day)
    } else {
        UiText.res(R.string.lists_interval_aim, UiText.plural(R.plurals.lists_interval_days, days, days))
    }

private const val HOURS_PER_DAY = 24

/**
 * 1..60 days per ADR 0010. The floor is deliberately 1, not 2: Energize
 * already defaults to a 24h rhythm, and nothing below the UI enforces a wider
 * gap (`withIntervalHours` floors at 1 hour). Do not raise it to "fix" the
 * default 48h list rendering its thumb at about 1.7% of the track, flush
 * against the "1 day" tick: that reads as a mismatch but is a correct state on
 * a linear scale, and raising the floor to hide it also silently rewrote 24h
 * rows to 48h. If the compressed low end needs fixing, change the scale, not
 * the floor.
 */
private const val INTERVAL_MIN_DAY = 1
private const val INTERVAL_MAX_DAY = 60

/** One tick under the interval slider: where it sits, and its words. */
private data class IntervalTick(val day: Int, @PluralsRes val label: Int, val count: Int)

private val INTERVAL_TICKS: List<IntervalTick> = listOf(
    // Words, not "1d / 2w / 1m / 2m" (rubric D7).
    IntervalTick(day = 1, label = R.plurals.lists_interval_days, count = 1),
    IntervalTick(day = 14, label = R.plurals.lists_interval_tick_weeks, count = 2),
    IntervalTick(day = 30, label = R.plurals.lists_interval_tick_months, count = 1),
    IntervalTick(day = 60, label = R.plurals.lists_interval_tick_months, count = 2),
)

/**
 * Linear placement on a [minDay]..[maxDay] day axis. F-4 fix: the prior Row
 * with `Arrangement.SpaceBetween` placed labels at fractions 0/0.33/0.67/1.0,
 * visually saying 2w=20d and 1m=40d. The slider's true thumb fraction is
 * `(value - minDay) / (maxDay - minDay)`, so labels must follow the same math:
 * 14d at 0.22, 30d at 0.49, 60d at 1.0.
 */
internal fun intervalLabelFraction(day: Int, minDay: Int, maxDay: Int): Float {
    val span = (maxDay - minDay).coerceAtLeast(1)
    return ((day - minDay).coerceAtLeast(0).toFloat() / span).coerceIn(0f, 1f)
}

/**
 * Which tick labels to draw, given each one's left edge and width on the
 * track, so no two are printed over each other. At 200% text "1 day", "2
 * weeks" and "1 month" are wider than the gaps between their days (14 and 30
 * sit at 22% and 49% of the track) and were drawn on top of one another. The
 * two ends always show; a middle tick shows only when it clears the last one
 * drawn and the end by [gap]. TalkBack reads the slider's value in words, so
 * a dropped tick loses nothing it would have heard.
 */
internal fun ticksThatFit(lefts: List<Int>, widths: List<Int>, gap: Int): Set<Int> {
    if (lefts.isEmpty()) return emptySet()
    val last = lefts.lastIndex
    val shown = mutableSetOf(0)
    var lastRight = lefts[0] + widths[0]
    for (i in 1 until last) {
        val clearOfPrevious = lefts[i] >= lastRight + gap
        val clearOfEnd = lefts[i] + widths[i] + gap <= lefts[last]
        if (clearOfPrevious && clearOfEnd) {
            shown += i
            lastRight = lefts[i] + widths[i]
        }
    }
    shown += last
    return shown
}

@Composable
private fun IntervalScaleLabels(modifier: Modifier = Modifier) {
    val tickGap = OrbitTheme.spacing.x2
    Layout(
        modifier = modifier,
        content = {
            INTERVAL_TICKS.forEach { tick ->
                Text(
                    text = pluralStringResource(tick.label, tick.count, tick.count),
                    style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map {
            it.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val xs = placeables.mapIndexed { index, p ->
            val day = INTERVAL_TICKS[index].day
            val fraction = intervalLabelFraction(day, INTERVAL_MIN_DAY, INTERVAL_MAX_DAY)
            val centered = (fraction * width).toInt() - p.width / 2
            centered.coerceIn(0, (width - p.width).coerceAtLeast(0))
        }
        val shown = ticksThatFit(lefts = xs, widths = placeables.map { it.width }, gap = tickGap.roundToPx())
        layout(width, height) {
            placeables.forEachIndexed { index, p ->
                if (index in shown) p.placeRelative(xs[index], 0)
            }
        }
    }
}

// region Previews

// One day ("Aim for every day", an Energize list's base) and the thumb at the
// track's start; at 200% the sentence and the ticks must still fit.
@PreviewLightDark
@PreviewFontScale
@Composable
private fun HowOftenSliderEveryDayPreview() {
    OrbitTheme {
        Box(Modifier.background(OrbitTheme.colors.surface)) {
            HowOftenSlider(intervalHours = 24, onCommit = {})
        }
    }
}

@Preview(name = "HowOftenSlider, 3 days (Late night's base), light", showBackground = true)
@Preview(
    name = "HowOftenSlider, 3 days (Late night's base), dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HowOftenSliderThreeDaysPreview() {
    OrbitTheme {
        Box(Modifier.background(OrbitTheme.colors.surface)) {
            HowOftenSlider(intervalHours = 72, onCommit = {})
        }
    }
}

// endregion
