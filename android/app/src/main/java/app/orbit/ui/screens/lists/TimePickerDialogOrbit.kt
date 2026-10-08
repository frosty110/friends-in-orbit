package app.orbit.ui.screens.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.TimeStyle
import app.orbit.ui.util.formatClockTime
import java.time.LocalTime

// The nudge schedule's clock helpers and its one time picker. They lived in
// ActiveHoursEditor.kt, then TimeOfDayPicker.kt, beside the controls for a
// list's active-hours window; that window was retired on 2026-10-08 (LIST-25:
// When to nudge is the one place a list's nudge timing is set), and these are
// what the nudge schedule (NudgeScheduleSection) still uses.

/**
 * A time of day in the phone's 12/24-hour style. Delegates to the app's one
 * clock formatter ([formatClockTime]); the name is kept for its callers and
 * tests, which pin the 12-hour form (JVM tests default to 12-hour).
 */
internal fun formatHour12(t: LocalTime): String = formatClockTime(t)

/**
 * The one time picker, for the nudge schedule's "Add time" and "Change time":
 * Material3's [TimePicker] in a hand-rolled dialog (Material3 1.3.x has no
 * `TimePickerDialog` composable), with the rest of Material's time-picker
 * pattern around it. A headline says what it is for, and a keyboard toggle
 * swaps the dial for typed hours and minutes over the same state. Dragging a
 * hand to the exact minute is the hard part of a dial; typing "10:30" is not,
 * for anyone who finds the drag fiddly or reaches it with a switch or a
 * keyboard. It was the dial alone until 2026-10-07.
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
    var typing by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = OrbitTheme.shapes.lg) {
            Column(Modifier.padding(OrbitTheme.spacing.x6)) {
                Text(
                    text = stringResource(R.string.lists_hours_dialog_title),
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                    modifier = Modifier
                        .padding(bottom = OrbitTheme.spacing.x4)
                        .semantics { heading() },
                )
                if (typing) TimeInput(state = state) else TimePicker(state = state)
                Spacer(Modifier.height(OrbitTheme.spacing.x2))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                ) {
                    OrbitIconButton(
                        icon = if (typing) "clock" else "keyboard",
                        onClick = { typing = !typing },
                        tint = OrbitTheme.colors.fgMuted,
                        contentDescription = stringResource(
                            if (typing) R.string.lists_hours_use_clock else R.string.lists_hours_type_time,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
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

