package app.orbit.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.data.PickerThresholds
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * PICK-07 — Material3 AlertDialog wrapping 4 [ThresholdStepperRow] rows
 * for the picker's threshold knobs (`commonlyTopPct`, `rarelyBottomPct`, `recentlyAddedDays`,
 * `longGapDays`). Save commits all four through [SettingsViewModel.onCommitThresholds]; Cancel
 * discards (local `remember { mutableIntStateOf(...) }` state never round-trips to DataStore
 * until Save runs — mitigation against stale-state writes).
 *
 * Pattern: ConvertToStaticDialog is the precedent — same Material3 AlertDialog
 * primitive, same `confirmButton` / `dismissButton` shape, same OrbitButton + Ghost variant
 * for Cancel.
 *
 * Title, subtitle, and four field labels are locked copy. Helper texts and unit
 * suffixes track the spec's per-field guidance.
 */
@Composable
fun PickerThresholdsDialog(
    initial: PickerThresholds,
    onSave: (PickerThresholds) -> Unit,
    onDismiss: () -> Unit,
) {
    var commonlyTop by remember { mutableIntStateOf(initial.commonlyTopPct) }
    var rarelyBottom by remember { mutableIntStateOf(initial.rarelyBottomPct) }
    var recentlyAdded by remember { mutableIntStateOf(initial.recentlyAddedDays) }
    var longGap by remember { mutableIntStateOf(initial.longGapDays) }

    // The two percentile bands must not overlap (a contact
    // can't be both commonly and rarely called). Save disables with a quiet
    // helper line while they do.
    val contradictionLine = thresholdsContradictionLine(commonlyTop, rarelyBottom)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Column {
                Text(
                    text = "Picker thresholds",
                    style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
                )
                Text(
                    text = "These set how the picker chips group your contacts.",
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                    modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ThresholdStepperRow(
                    label = "Commonly called — top",
                    helper = "% of contacts with at least one call",
                    unit = "%",
                    value = commonlyTop,
                    minValue = 5,
                    maxValue = 50,
                    onChange = { commonlyTop = it },
                )
                HorizontalDivider(color = OrbitTheme.colors.lineSoft)
                ThresholdStepperRow(
                    label = "Rarely called — bottom",
                    helper = "% of contacts with at least one call",
                    unit = "%",
                    value = rarelyBottom,
                    minValue = 10,
                    maxValue = 90,
                    onChange = { rarelyBottom = it },
                )
                HorizontalDivider(color = OrbitTheme.colors.lineSoft)
                ThresholdStepperRow(
                    label = "Recently added",
                    helper = "Days since first seen by Orbit",
                    unit = "days",
                    value = recentlyAdded,
                    minValue = 1,
                    maxValue = 3650,
                    onChange = { recentlyAdded = it },
                )
                HorizontalDivider(color = OrbitTheme.colors.lineSoft)
                ThresholdStepperRow(
                    label = "Long gap",
                    helper = "Days since last call",
                    unit = "days",
                    value = longGap,
                    minValue = 1,
                    maxValue = 3650,
                    onChange = { longGap = it },
                )
                if (contradictionLine != null) {
                    Text(
                        text = contradictionLine,
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                        modifier = Modifier.padding(top = OrbitTheme.spacing.x3),
                    )
                }
            }
        },
        confirmButton = {
            OrbitButton(
                text = "Save",
                enabled = contradictionLine == null,
                onClick = {
                    onSave(
                        PickerThresholds(
                            commonlyTopPct = commonlyTop,
                            rarelyBottomPct = rarelyBottom,
                            recentlyAddedDays = recentlyAdded,
                            longGapDays = longGap,
                        ),
                    )
                },
                variant = OrbitButtonVariant.Primary,
            )
        },
        dismissButton = {
            OrbitButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
            )
        },
    )
}

/**
 * Contradiction check for the two percentile bands. The picker
 * computes "commonly called" as the TOP [commonlyTopPct]% of called contacts
 * and "rarely called" as the BOTTOM [rarelyBottomPct]% (see
 * `ContactPickerViewModel`); if the two sum past 100 the bands overlap and a
 * contact could be both at once. Returns the quiet helper line to render, or
 * null when the values are consistent. Pure + internal so the JVM unit test
 * can pin the boundary.
 */
internal fun thresholdsContradictionLine(commonlyTopPct: Int, rarelyBottomPct: Int): String? =
    if (commonlyTopPct + rarelyBottomPct > 100) {
        "These two bands overlap — together they can't be more than 100%."
    } else {
        null
    }

@Preview(name = "PickerThresholdsDialog — light", showBackground = true)
@Composable
private fun PickerThresholdsDialogLightPreview() {
    OrbitTheme(darkTheme = false) {
        PickerThresholdsDialog(
            initial = PickerThresholds.DEFAULT,
            onSave = {},
            onDismiss = {},
        )
    }
}

@Preview(
    name = "PickerThresholdsDialog — dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PickerThresholdsDialogDarkPreview() {
    OrbitTheme(darkTheme = true) {
        PickerThresholdsDialog(
            initial = PickerThresholds.DEFAULT,
            onSave = {},
            onDismiss = {},
        )
    }
}
