package app.orbit.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

/**
 * SET-06 — Data section "Reset Orbit" row. Tap opens the
 * confirmation dialog [ResetConfirmDialog]. Destructive intent is signaled
 * via the `colors.danger` foreground on the primary label; subtitle stays
 * fgMuted because two danger-tinted strings on one row reads as alarmist.
 */
@Composable
fun ResetDataRow(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "Reset Orbit",
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.danger),
        )
        Text(
            text = "Erase every list, contact, and note on this phone",
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
