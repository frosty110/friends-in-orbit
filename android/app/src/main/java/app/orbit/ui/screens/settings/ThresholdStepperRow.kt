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
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.theme.OrbitTheme

/**
 * PICK-07 — one row inside [PickerThresholdsDialog].
 *
 * Layout: label (body) on the left, [−] OrbitIconButton + value (body) + [+] OrbitIconButton
 * + unit (meta) on the right; helper line (meta, fgMuted) underneath. Coercion bounds are
 * enforced at edit time via `coerceAtLeast(minValue)` / `coerceAtMost(maxValue)` so a stuck
 * tap can never escape the [minValue, maxValue] window — belt-and-suspenders to AppPrefs'
 * own `coerceIn(min, max)` write-time clamp.
 *
 * Replaces the IntervalSlider's slider primitive (ListConfigScreen) with a discrete
 * ± stepper. Slider continuous-drag is wrong here because thresholds are integer-valued
 * and rarely re-tuned — explicit increments make intent clearer than a fuzzy drag.
 */
@Composable
fun ThresholdStepperRow(
    label: String,
    helper: String,
    unit: String,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier.weight(1f),
            )
            OrbitIconButton(
                icon = "minus",
                onClick = { onChange((value - 1).coerceAtLeast(minValue)) },
                contentDescription = "Decrease $label",
            )
            Text(
                text = "$value",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x3),
            )
            OrbitIconButton(
                icon = "plus",
                onClick = { onChange((value + 1).coerceAtMost(maxValue)) },
                contentDescription = "Increase $label",
            )
            Text(
                text = unit,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgSubtle),
                modifier = Modifier.padding(start = OrbitTheme.spacing.x2),
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
            label = "Commonly called — top",
            helper = "% of contacts with at least one call",
            unit = "%",
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
            label = "Recently added",
            helper = "Days since first seen by Orbit",
            unit = "days",
            value = 30,
            minValue = 1,
            maxValue = 3650,
            onChange = {},
        )
    }
}
