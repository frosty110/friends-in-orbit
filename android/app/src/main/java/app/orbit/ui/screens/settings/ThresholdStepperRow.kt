package app.orbit.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.theme.OrbitTheme

/**
 * PICK-07: one row inside [PickerThresholdsDialog].
 *
 * Layout: the whole sentence on top ("Commonly called: the top 20%"), the
 * [−] value [+] stepper beneath it, the helper line (meta, fgMuted) under
 * that. The sentence carries the value as an argument so a translator can put
 * the number anywhere in it (voice.md: keep a sentence whole, never build copy
 * from label + control + unit fragments). Until 2026-10-06 the label, the
 * value and a unit string sat left to right on one line, which fixed the word
 * order and squeezed the steppers under 48dp at 360dp (gate G3).
 *
 * [name] is the short noun for TalkBack's button labels ("Decrease Commonly
 * called"); the sentence itself would repeat the value on every tap.
 *
 * Coercion bounds are enforced at edit time via `coerceAtLeast(minValue)` /
 * `coerceAtMost(maxValue)` so a stuck tap can never escape the [minValue,
 * maxValue] window, belt-and-suspenders to AppPrefs' own `coerceIn(min, max)`
 * write-time clamp.
 *
 * A discrete ± stepper, not a slider: thresholds are integer-valued and rarely
 * re-tuned, so explicit increments make intent clearer than a fuzzy drag.
 */
@Composable
fun ThresholdStepperRow(
    label: String,
    name: String,
    helper: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
        ) {
            OrbitIconButton(
                icon = "minus",
                onClick = { onChange((value - 1).coerceAtLeast(minValue)) },
                contentDescription = stringResource(R.string.settings_thresholds_decrease, name),
            )
            Text(
                text = "$value",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x3),
            )
            OrbitIconButton(
                icon = "plus",
                onClick = { onChange((value + 1).coerceAtMost(maxValue)) },
                contentDescription = stringResource(R.string.settings_thresholds_increase, name),
            )
        }
        Text(
            text = helper,
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
        )
    }
}

@Preview(name = "ThresholdStepperRow — light", showBackground = true)
@Composable
private fun ThresholdStepperRowLightPreview() {
    OrbitTheme(darkTheme = false) {
        ThresholdStepperRow(
            label = stringResource(R.string.settings_thresholds_commonly, 20),
            name = stringResource(R.string.settings_thresholds_commonly_name),
            helper = stringResource(R.string.settings_thresholds_percent_helper),
            value = 20,
            minValue = 5,
            maxValue = 50,
            onChange = {},
        )
    }
}

@Preview(
    name = "ThresholdStepperRow — dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThresholdStepperRowDarkPreview() {
    OrbitTheme(darkTheme = true) {
        ThresholdStepperRow(
            label = pluralStringResource(R.plurals.settings_thresholds_recently_added, 30, 30),
            name = stringResource(R.string.settings_thresholds_recently_added_name),
            helper = stringResource(R.string.settings_thresholds_recently_added_helper),
            value = 30,
            minValue = 1,
            maxValue = 3650,
            onChange = {},
        )
    }
}
