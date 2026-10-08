package app.orbit.ui.screens.settings

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.data.PickerThresholds
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.dayLandmark
import app.orbit.ui.theme.OrbitTheme

/**
 * PICK-07: a Material3 AlertDialog wrapping 4 [ThresholdWheelRow] rows
 * for the picker's threshold knobs (`commonlyTopPct`, `rarelyBottomPct`, `recentlyAddedDays`,
 * `longGapDays`). Save commits all four through [SettingsViewModel.onCommitThresholds]; Cancel
 * discards (the local state never round-trips to DataStore until Save runs, a
 * mitigation against stale-state writes).
 *
 * The four values are `rememberSaveable`, like the dialog's own visibility in
 * SettingsScreen: a rotation mid-edit used to close the dialog and drop the
 * edits (rubric G1, "no lost work"), while the reset dialog beside it survived.
 *
 * Pattern: ConvertToStaticDialog is the precedent — same Material3 AlertDialog
 * primitive, same `confirmButton` / `dismissButton` shape, same OrbitButton + Ghost variant
 * for Cancel.
 *
 * Each row's label is a whole sentence with the value in it (SET-10,
 * voice.md), rendered by [ThresholdWheelRow] above its number wheel.
 */
@Composable
fun PickerThresholdsDialog(
    initial: PickerThresholds,
    onSave: (PickerThresholds) -> Unit,
    onDismiss: () -> Unit,
) {
    var commonlyTop by rememberSaveable { mutableIntStateOf(initial.commonlyTopPct) }
    var rarelyBottom by rememberSaveable { mutableIntStateOf(initial.rarelyBottomPct) }
    var recentlyAdded by rememberSaveable { mutableIntStateOf(initial.recentlyAddedDays) }
    var longGap by rememberSaveable { mutableIntStateOf(initial.longGapDays) }

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
                    text = stringResource(R.string.settings_thresholds_title),
                    style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
                )
                Text(
                    text = stringResource(R.string.settings_thresholds_body),
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                    modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
                )
            }
        },
        text = {
            // Scrolls when the window is short (landscape): an AlertDialog's
            // text slot does not, so the rows were squeezed and the last
            // controls fell under 48dp (gate G3).
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                ThresholdWheelRow(
                    label = stringResource(R.string.settings_thresholds_commonly, commonlyTop),
                    name = stringResource(R.string.settings_thresholds_commonly_name),
                    helper = stringResource(R.string.settings_thresholds_percent_helper),
                    value = commonlyTop,
                    range = 5..50,
                    onChange = { commonlyTop = it },
                )
                HorizontalDivider(color = OrbitTheme.colors.lineSoft)
                ThresholdWheelRow(
                    label = stringResource(R.string.settings_thresholds_rarely, rarelyBottom),
                    name = stringResource(R.string.settings_thresholds_rarely_name),
                    helper = stringResource(R.string.settings_thresholds_percent_helper),
                    value = rarelyBottom,
                    range = 10..90,
                    onChange = { rarelyBottom = it },
                )
                HorizontalDivider(color = OrbitTheme.colors.lineSoft)
                ThresholdWheelRow(
                    label = pluralStringResource(R.plurals.settings_thresholds_recently_added, recentlyAdded, recentlyAdded),
                    name = stringResource(R.string.settings_thresholds_recently_added_name),
                    helper = stringResource(R.string.settings_thresholds_recently_added_helper),
                    value = recentlyAdded,
                    range = 1..3650,
                    onChange = { recentlyAdded = it },
                    caption = { d -> dayLandmark(d) },
                )
                HorizontalDivider(color = OrbitTheme.colors.lineSoft)
                ThresholdWheelRow(
                    label = pluralStringResource(R.plurals.settings_thresholds_long_gap, longGap, longGap),
                    name = stringResource(R.string.settings_thresholds_long_gap_name),
                    helper = stringResource(R.string.settings_thresholds_long_gap_helper),
                    value = longGap,
                    range = 1..3650,
                    onChange = { longGap = it },
                    caption = { d -> dayLandmark(d) },
                )
                if (contradictionLine != null) {
                    Text(
                        text = stringResource(contradictionLine),
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                        modifier = Modifier.padding(top = OrbitTheme.spacing.x3),
                    )
                }
            }
        },
        confirmButton = {
            OrbitButton(
                text = stringResource(R.string.components_action_save),
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
                text = stringResource(R.string.components_action_cancel),
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
 * contact could be both at once. Returns the quiet helper line to render (a
 * string resource id), or null when the values are consistent. Pure +
 * internal so the unit test can pin the boundary.
 */
@StringRes
internal fun thresholdsContradictionLine(commonlyTopPct: Int, rarelyBottomPct: Int): Int? =
    if (commonlyTopPct + rarelyBottomPct > 100) {
        R.string.settings_thresholds_overlap
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
