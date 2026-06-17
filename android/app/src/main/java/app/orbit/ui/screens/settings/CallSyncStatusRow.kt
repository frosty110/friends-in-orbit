package app.orbit.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.theme.OrbitTheme

/**
 * SET-04 — Call history section row. Renders 'Last synced …' or
 * 'Never synced.' plus a 'Sync now' primary button (with inline spinner
 * when [inFlight]).
 *
 * The button is disabled when [enabled] is false (call-log permission not
 * granted) or when [inFlight] is true (a sync is already running). The
 * relative-time line is normalized to sentence case for voice consistency
 * (project voice guidelines: "no exclamation marks, sentence case").
 */
@Composable
fun CallSyncStatusRow(
    lastSyncedAtMs: Long,
    inFlight: Boolean,
    enabled: Boolean,
    onSyncNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val now = System.currentTimeMillis()
        val rel = remember(lastSyncedAtMs, now) {
            if (lastSyncedAtMs <= 0L) {
                "Never synced."
            } else {
                "Last synced " +
                    DateUtils.getRelativeTimeSpanString(
                        lastSyncedAtMs, now, DateUtils.MINUTE_IN_MILLIS,
                    ).toString().lowercase()
            }
        }
        Text(
            text = rel,
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            OrbitButton(
                text = if (inFlight) "Syncing…" else "Sync now",
                onClick = onSyncNow,
                enabled = enabled && !inFlight,
            )
            if (inFlight) {
                CircularProgressIndicator(
                    color = OrbitTheme.colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
