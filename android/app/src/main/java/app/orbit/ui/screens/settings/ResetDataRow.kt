package app.orbit.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.ui.theme.OrbitTheme

/**
 * SET-06 — Data section "Reset Orbit" row. Tap opens the
 * confirmation dialog [ResetConfirmDialog]. Destructive intent is signaled
 * via the `colors.danger` foreground on the primary label; subtitle stays
 * fgMuted because two danger-tinted strings on one row reads as alarmist.
 *
 * [enabled] is false while an export or a restore is running: a reset in the
 * middle of either would race the file being written or the tables being
 * replaced. The label drops to fgMuted so the row reads as waiting.
 */
@Composable
fun ResetDataRow(onClick: () -> Unit, enabled: Boolean = true) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY),
    ) {
        Text(
            text = stringResource(R.string.settings_reset_title),
            style = OrbitTheme.type.body.copy(
                color = if (enabled) OrbitTheme.colors.danger else OrbitTheme.colors.fgMuted,
            ),
        )
        Text(
            text = stringResource(R.string.settings_reset_sub),
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            modifier = Modifier.padding(top = OrbitTheme.spacing.hair),
        )
    }
}
