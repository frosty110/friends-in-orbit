package app.orbit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

/**
 * 5-row stats block for Contact Detail (CONTACT-02). Card View has its own
 * 3-stat row inline (Last called / Avg length / Pickup) — Detail expands to
 * the full set: Last call / Total calls / Avg length / Longest gap / Usually.
 *
 * Each row: eyebrow label (`type.eyebrow` + `colors.fgMuted`) + value
 * (`type.statValue` MT-02 + `colors.fg`). Hairline `colors.line` between rows.
 *
 * Voice (UI-SPEC §CONTACT-02): no "overdue", no "haven't called", no
 * time-since framing beyond factual labels. Empty rows render `"—"`.
 */
@Immutable
data class StatEntry(
    val label: String,
    val value: String,
)

@Composable
fun ContactStatsPanel(
    stats: List<StatEntry>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        stats.forEachIndexed { index, entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = OrbitTheme.spacing.x3),
            ) {
                Text(
                    text = entry.label,
                    style = OrbitTheme.type.eyebrow,
                    color = OrbitTheme.colors.fgMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = entry.value,
                    style = OrbitTheme.type.statValue,
                    color = OrbitTheme.colors.fg,
                )
            }
            if (index < stats.lastIndex) {
                HorizontalDivider(
                    color = OrbitTheme.colors.line,
                    thickness = 1.dp,
                )
            }
        }
    }
}
