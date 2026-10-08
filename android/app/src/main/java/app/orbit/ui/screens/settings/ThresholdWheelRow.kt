package app.orbit.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.components.OrbitWheelPicker
import app.orbit.ui.theme.OrbitTheme

/**
 * PICK-07: one row inside [PickerThresholdsDialog].
 *
 * Layout: the whole sentence on top ("Commonly called: the top 20%"), the
 * number wheel beneath it ([OrbitWheelPicker], ADR 0011), the helper line
 * (meta, fgMuted) under that. The sentence carries the value as an argument
 * so a translator can put the number anywhere in it (voice.md: keep a
 * sentence whole); the caller words it from the value it holds, which the
 * wheel updates as it turns.
 *
 * It was a [−] value [+] stepper, one unit a tap, over ranges that run to
 * 3650 days: taking "Recently added" from 30 days to a year was 335 taps. The
 * wheel keeps what the stepper was chosen for (whole numbers, a precise
 * value, no fuzzy drag) and crosses the range in a flick.
 *
 * TalkBack hears [name] ("Recently added") as the control and the sentence as
 * its value, and adjusts it one unit per swipe.
 *
 * [range] is also the write-time clamp's window in AppPrefs; a value outside
 * it widens the wheel rather than being moved.
 */
@Composable
fun ThresholdWheelRow(
    label: String,
    name: String,
    helper: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    caption: @Composable (Int) -> String? = { null },
) {
    Column(
        modifier = modifier.padding(
            horizontal = OrbitTheme.spacing.x4,
            vertical = OrbitTheme.spacing.x3,
        ),
    ) {
        Text(
            text = label,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.fillMaxWidth(),
        )
        OrbitWheelPicker(
            value = value,
            range = minOf(range.first, value)..maxOf(range.last, value),
            // The dialog holds its edits until Save, so the live value is the
            // one that matters; there is nothing to write when it settles.
            onValueChange = onChange,
            onValueCommit = onChange,
            label = name,
            valueDescription = { label },
            caption = caption,
            modifier = Modifier.padding(top = OrbitTheme.spacing.x2),
        )
        Text(
            text = helper,
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            modifier = Modifier.padding(top = OrbitTheme.spacing.x2),
        )
    }
}

@Preview(name = "ThresholdWheelRow, light", showBackground = true)
@Composable
private fun ThresholdWheelRowLightPreview() {
    OrbitTheme(darkTheme = false) {
        ThresholdWheelRow(
            label = stringResource(R.string.settings_thresholds_commonly, 20),
            name = stringResource(R.string.settings_thresholds_commonly_name),
            helper = stringResource(R.string.settings_thresholds_percent_helper),
            value = 20,
            range = 5..50,
            onChange = {},
        )
    }
}

@Preview(
    name = "ThresholdWheelRow, dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThresholdWheelRowDarkPreview() {
    OrbitTheme(darkTheme = true) {
        ThresholdWheelRow(
            label = pluralStringResource(R.plurals.settings_thresholds_recently_added, 30, 30),
            name = stringResource(R.string.settings_thresholds_recently_added_name),
            helper = stringResource(R.string.settings_thresholds_recently_added_helper),
            value = 30,
            range = 1..3650,
            onChange = {},
        )
    }
}
