package app.orbit.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * PICK-07 — Settings nav row that opens [PickerThresholdsDialog].
 *
 * Mirrors the existing private `NavRow` shape inside [SettingsScreen] (caret-right chevron,
 * label + sub Text pair, 16dp horizontal / 14dp vertical padding) but adds a leading
 * `sliders-horizontal` Phosphor icon as the affordance. The row's title is functional copy
 * ("Picker thresholds"), not a list name, so the privacy curtain (PRIV-*) doesn't apply to
 * the label itself.
 *
 * Copy: title "Picker thresholds" + subtitle "Edit chip-match thresholds".
 */
@Composable
fun PickerThresholdsRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        PhIcon(
            name = "sliders-horizontal",
            size = 20.dp,
            tint = OrbitTheme.colors.fgMuted,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "Picker thresholds",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = "Edit chip-match thresholds",
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        PhIcon(
            name = "caret-right",
            size = 16.dp,
            tint = OrbitTheme.colors.fgSubtle,
        )
    }
}

@Preview(name = "PickerThresholdsRow — light", showBackground = true)
@Composable
private fun PickerThresholdsRowLightPreview() {
    OrbitTheme(darkTheme = false) {
        PickerThresholdsRow(onClick = {})
    }
}

@Preview(
    name = "PickerThresholdsRow — dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PickerThresholdsRowDarkPreview() {
    OrbitTheme(darkTheme = true) {
        PickerThresholdsRow(onClick = {})
    }
}
