package app.orbit.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString

/**
 * "Aim for every 14 days": the keep-in-touch interval, chosen on the day
 * wheel (ADR 0011). List settings' "How often" and Contact detail's custom
 * schedule both draw this one composable. They were two copies of a slider
 * that ADR 0010 said must stay in lockstep, and they had drifted: the list's
 * moved continuously and announced percentages, the contact's moved a day at
 * a time.
 *
 * The sentence stays whole above the wheel and follows it as it turns, so a
 * translator can order the words (voice.md, "keep a sentence whole"). The
 * range is 1 to 60 days with the 2-day default, per ADR 0010: only the
 * control changed. [onCommit] receives whole days, once per gesture.
 *
 * It is LIST-30's one "How often" control: List settings draws it for every
 * list (a Late night list shows "Aim for every 3 days"), New list's How often
 * step and Contact detail's custom schedule draw it too. What a commit does
 * to the rule is the caller's; this only picks a number. One day reads "Aim
 * for every day", because "every 1 day" is not how anyone says it.
 */
@Composable
fun IntervalDaysPicker(
    currentHours: Int,
    onCommit: (days: Int) -> Unit,
    modifier: Modifier = Modifier,
    // False when currentHours is only where the wheel opens (a list with no
    // rhythm yet): choosing that interval then saves it too.
    valueIsSet: Boolean = true,
) {
    val saved = intervalDaysFromHours(currentHours)
    // What the sentence reads while the wheel turns; the wheel owns the value
    // and reports it here, and a saved value from outside resets it.
    var shown by remember(saved) { mutableIntStateOf(saved) }
    Column(modifier.fillMaxWidth().padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x4)) {
        Text(
            text = howOftenAimLabel(shown).asString(),
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.fillMaxWidth(),
        )
        OrbitWheelPicker(
            value = saved,
            range = INTERVAL_DAYS,
            onValueChange = { shown = it },
            onValueCommit = onCommit,
            label = stringResource(R.string.lists_interval_label),
            valueDescription = { d -> howOftenEveryLabel(d).asString() },
            caption = { d -> dayLandmark(d) },
            modifier = Modifier.padding(top = OrbitTheme.spacing.x3),
            valueIsSet = valueIsSet,
        )
    }
}

/**
 * Whole days, as the Lists row words an interval: 48h reads "2 days", and
 * anything under a day reads one day. Unlike [intervalDaysFromHours] it has no
 * upper bound: the wheel can only sit inside its range, but words describe
 * whatever is stored.
 */
internal fun intervalDaysFor(hours: Int): Int = (hours / 24).coerceAtLeast(INTERVAL_DAYS.first)

/**
 * A rhythm in the words the Lists row and the wheel's TalkBack value use:
 * "Every 14 days", and "Every day" for one.
 */
internal fun howOftenEveryLabel(days: Int): UiText =
    if (days == 1) {
        UiText.res(R.string.lists_interval_every_day)
    } else {
        UiText.plural(R.plurals.lists_interval_every_days, days, days)
    }

/** The sentence over the wheel: "Aim for every 14 days", and "Aim for every day" for one. */
internal fun howOftenAimLabel(days: Int): UiText =
    if (days == 1) {
        UiText.res(R.string.lists_interval_aim_every_day)
    } else {
        UiText.res(R.string.lists_interval_aim, UiText.plural(R.plurals.lists_interval_days, days, days))
    }

/** 1 to 60 days (ADR 0010; the floor is not to rise without a new ADR). */
val INTERVAL_DAYS: IntRange = 1..60

/**
 * Stored hours to the whole days the wheel shows. Hours that are not a whole
 * number of days (none are written today) round down, and nothing reads
 * under 1 day; the stored value is left alone until the person turns the
 * wheel, so opening the screen never rewrites a rhythm.
 */
fun intervalDaysFromHours(hours: Int): Int = (hours / 24).coerceIn(INTERVAL_DAYS)

/**
 * The quiet word under a landmark number on a day wheel: a week, two, three,
 * a month, two, three, six, a year. Null for every other day.
 */
@Composable
fun dayLandmark(day: Int): String? = when (day) {
    7, 14, 21 -> pluralStringResource(R.plurals.lists_interval_tick_weeks, day / 7, day / 7)
    30, 60, 90, 180 -> pluralStringResource(R.plurals.lists_interval_tick_months, day / 30, day / 30)
    365 -> pluralStringResource(R.plurals.lists_interval_tick_years, 1, 1)
    else -> null
}

@Preview(name = "IntervalDaysPicker, the 2-day default, light", showBackground = true)
@Composable
private fun IntervalDaysPickerDefaultPreview() {
    OrbitTheme(darkTheme = false) {
        Column(Modifier.background(OrbitTheme.colors.surface)) {
            IntervalDaysPicker(currentHours = 48, onCommit = {})
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "IntervalDaysPicker, 2 weeks, dark", showBackground = true)
@Composable
private fun IntervalDaysPickerTwoWeeksPreview() {
    OrbitTheme(darkTheme = true) {
        Column(Modifier.background(OrbitTheme.colors.surface)) {
            IntervalDaysPicker(currentHours = 14 * 24, onCommit = {})
        }
    }
}
