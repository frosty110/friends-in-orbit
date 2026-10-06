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
 * Stats block for Contact Detail (CONTACT-02). Card View has its own compact
 * stat row inline; Detail expands to the full set, in the glossary's labels
 * (voice.md): Last call / Total calls / Average length / Longest gap, with
 * Usually on its own row below.
 *
 * Each row: eyebrow label (`type.eyebrow` + `colors.fgMuted`) + value
 * (`type.statValue` MT-02 + `colors.fg`). Hairline `colors.line` between rows.
 *
 * Voice (UI-SPEC §CONTACT-02): no "overdue", no "haven't called", no
 * time-since framing beyond factual labels. A stat with nothing to say says
 * so in words ("Not enough calls yet", "Never called"; the shared
 * `components_stat_*` strings), never a dash: the caller passes the words,
 * and a stat Orbit cannot know (no call log access) is left out rather than
 * claimed.
 */
@Immutable
data class StatEntry(
    val label: String,
    val value: String
)

@Composable
fun ContactStatsPanel(stats: List<StatEntry>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        stats.forEachIndexed { index, entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = OrbitTheme.spacing.x3)
            ) {
                Text(
                    text = entry.label,
                    style = OrbitTheme.type.eyebrow,
                    color = OrbitTheme.colors.fgMuted,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = entry.value,
                    style = OrbitTheme.type.statValue,
                    color = OrbitTheme.colors.fg
                )
            }
            if (index < stats.lastIndex) {
                HorizontalDivider(
                    color = OrbitTheme.colors.line,
                    thickness = 1.dp
                )
            }
        }
    }
}
