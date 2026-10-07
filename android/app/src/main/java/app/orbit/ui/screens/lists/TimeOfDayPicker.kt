package app.orbit.ui.screens.lists

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.window.Dialog
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitFilterChip
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.TimeStyle
import app.orbit.ui.util.formatClockTime
import java.time.LocalTime

/**
 * LIST-25: the parts of the day a list's nudges may come in, as windows on the
 * list's existing `activeHoursStart` / `activeHoursEnd` columns. Nothing new
 * is stored: a part is only a name for a window, and the gate that reads the
 * window is unchanged (`isInActiveWindow`, inclusive at both ends, wrapping
 * past midnight when the end is before the start, so [Nights] needs nothing
 * special).
 *
 * The starts are deliberate. `NudgeScheduler.effectiveSchedule` adds the
 * window's start as a nudge time when none of the list's own times falls
 * inside it, so the start is when such a nudge comes. Mornings start at 7 for
 * that reason: a part that began at 5 would mean a 5am nudge.
 */
enum class DayPart(val start: LocalTime?, val end: LocalTime?) {
    /** No window: both columns null, nudges come at the list's own times. */
    AnyTime(null, null),
    Mornings(LocalTime.of(7, 0), LocalTime.NOON),
    Afternoons(LocalTime.NOON, LocalTime.of(17, 0)),
    Evenings(LocalTime.of(17, 0), LocalTime.of(21, 0)),

    /** Overnight: 9pm to 7am, across midnight. */
    Nights(LocalTime.of(21, 0), LocalTime.of(7, 0)),
}

/** What List settings shows as the list's time of day: a part, or a stored window that is none of them. */
@Immutable
sealed interface TimeOfDay {
    @Immutable data class Part(val part: DayPart) : TimeOfDay

    /**
     * A window set before LIST-25 (the old start and end pickers allowed any
     * pair) that matches no part. It shows as its own selected chip until the
     * user picks a part, and is never rewritten on its own.
     */
    @Immutable data class Custom(val start: LocalTime, val end: LocalTime) : TimeOfDay
}

/**
 * Reads a stored window back as a [TimeOfDay]. Either end null is Any time,
 * which is what the gate and the scheduler do with it (they apply a window
 * only when both ends are set). A window equal to a part's is that part;
 * anything else is [TimeOfDay.Custom], kept exactly as stored.
 */
internal fun timeOfDayFor(start: LocalTime?, end: LocalTime?): TimeOfDay {
    if (start == null || end == null) return TimeOfDay.Part(DayPart.AnyTime)
    val part = DayPart.entries.firstOrNull { it.start == start && it.end == end }
    return if (part != null) TimeOfDay.Part(part) else TimeOfDay.Custom(start, end)
}

/**
 * LIST-25: "Time of day" on List settings and Make your first list. One
 * single-choice group, the precedent Settings and onboarding use for the
 * import range (`ImportRangeChipGroup`): [OrbitFilterChip]s with RadioButton
 * semantics inside one `selectableGroup` in a FlowRow, so TalkBack announces
 * one radio group ("Mornings, radio button, selected, 2 of 5") and the chips
 * wrap at 200% instead of crushing the last one.
 *
 * It replaced the active-hours editor ("Always active" with start and end time
 * pickers) on the owner's review: a switch that said "always" next to two
 * clocks read as three different ideas, when the question is which part of
 * the day.
 *
 * A [TimeOfDay.Custom] window adds one more chip, selected, with its times
 * ("Custom: 9am to 5pm"). Tapping it does nothing: it is the current state,
 * not something to choose again. Tapping the part that is already selected
 * writes nothing either, so the nudge chain is not rescheduled for no change.
 *
 * Under the group, one line says what the choice does, with the hours in the
 * phone's 12 or 24 hour format ([formatHour12], the helper the nudge times
 * use).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeOfDayPicker(
    selection: TimeOfDay,
    onSelect: (DayPart) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x3),
    ) {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        ) {
            DayPart.entries.forEach { part ->
                val selected = selection == TimeOfDay.Part(part)
                OrbitFilterChip(
                    label = stringResource(labelFor(part)),
                    selected = selected,
                    onClick = { if (!selected) onSelect(part) },
                    role = Role.RadioButton,
                )
            }
            if (selection is TimeOfDay.Custom) {
                OrbitFilterChip(
                    label = stringResource(
                        R.string.lists_time_custom,
                        formatHour12(selection.start),
                        formatHour12(selection.end),
                    ),
                    selected = true,
                    onClick = {},
                    role = Role.RadioButton,
                )
            }
        }
        Text(
            text = timeOfDayNote(selection),
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OrbitTheme.spacing.x2),
        )
    }
}

@StringRes
private fun labelFor(part: DayPart): Int = when (part) {
    DayPart.AnyTime -> R.string.lists_time_any
    DayPart.Mornings -> R.string.lists_time_mornings
    DayPart.Afternoons -> R.string.lists_time_afternoons
    DayPart.Evenings -> R.string.lists_time_evenings
    DayPart.Nights -> R.string.lists_time_nights
}

/**
 * The line under the group: one whole sentence per choice, the hours as its
 * arguments, so a translator keeps the sentence together (voice.md).
 */
@Composable
private fun timeOfDayNote(selection: TimeOfDay): String {
    val (note, start, end) = when (selection) {
        is TimeOfDay.Custom -> Triple(R.string.lists_time_note_custom, selection.start, selection.end)
        is TimeOfDay.Part -> when (selection.part) {
            DayPart.AnyTime -> return stringResource(R.string.lists_time_note_any)
            DayPart.Mornings -> Triple(R.string.lists_time_note_mornings, selection.part.start, selection.part.end)
            DayPart.Afternoons -> Triple(R.string.lists_time_note_afternoons, selection.part.start, selection.part.end)
            DayPart.Evenings -> Triple(R.string.lists_time_note_evenings, selection.part.start, selection.part.end)
            DayPart.Nights -> Triple(R.string.lists_time_note_nights, selection.part.start, selection.part.end)
        }
    }
    return stringResource(note, formatHour12(checkNotNull(start)), formatHour12(checkNotNull(end)))
}

// region Clock helpers shared with the nudge schedule
//
// These lived in ActiveHoursEditor.kt with the editor this file replaced; the
// nudge schedule (NudgeScheduleSection, and NudgeSchedule's window check) still
// uses them, so they stay in this package under the same names.

/** True when the window [start]..[end] crosses midnight (Nights, or a custom 10pm to 2am). */
internal fun spansMidnight(start: LocalTime, end: LocalTime): Boolean = end < start

/**
 * A time of day in the phone's 12/24-hour style. Delegates to the app's one
 * clock formatter ([formatClockTime]); the name is kept for its callers and
 * tests, which pin the 12-hour form (JVM tests default to 12-hour).
 */
internal fun formatHour12(t: LocalTime): String = formatClockTime(t)

/**
 * Material3's [TimePicker] in a hand-rolled dialog (Material3 1.3.x has no
 * `TimePickerDialog` composable), for the nudge schedule's "Add time" and
 * "Change time".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialogOrbit(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    // The dial follows the phone's 12 or 24 hour setting, like every clock
    // time beside it (it was always 12-hour until 2026-10-05).
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = TimeStyle.is24Hour,
    )
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = OrbitTheme.shapes.lg) {
            Column(Modifier.padding(OrbitTheme.spacing.x6)) {
                TimePicker(state = state)
                Spacer(Modifier.height(OrbitTheme.spacing.x2))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3, Alignment.End),
                ) {
                    OrbitButton(
                        text = stringResource(R.string.components_action_cancel),
                        onClick = onDismiss,
                        variant = OrbitButtonVariant.Ghost,
                    )
                    OrbitButton(
                        text = stringResource(R.string.lists_hours_ok),
                        onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) },
                    )
                }
            }
        }
    }
}

// endregion

// region Previews

@Composable
private fun TimeOfDayPreviewHost(selection: TimeOfDay) {
    OrbitTheme {
        Box(Modifier.background(OrbitTheme.colors.surface)) {
            TimeOfDayPicker(selection = selection, onSelect = {})
        }
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun TimeOfDayPickerAnyTimePreview() {
    TimeOfDayPreviewHost(TimeOfDay.Part(DayPart.AnyTime))
}

@PreviewLightDark
@Composable
private fun TimeOfDayPickerNightsPreview() {
    TimeOfDayPreviewHost(TimeOfDay.Part(DayPart.Nights))
}

// A window from the old pickers that is no part: one more chip, selected.
@PreviewLightDark
@PreviewFontScale
@Composable
private fun TimeOfDayPickerCustomPreview() {
    TimeOfDayPreviewHost(TimeOfDay.Custom(LocalTime.of(9, 0), LocalTime.of(17, 0)))
}

// endregion
