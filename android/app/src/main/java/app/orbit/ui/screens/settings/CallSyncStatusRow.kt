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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * SET-04 — Call history section row. Renders 'Last synced …' or
 * 'Never synced.' plus a 'Sync now' primary button (with inline spinner
 * when [inFlight]). Thin wrapper over [SyncStatusRow].
 */
@Composable
fun CallSyncStatusRow(
    lastSyncedAtMs: Long,
    inFlight: Boolean,
    enabled: Boolean,
    onSyncNow: () -> Unit
) = SyncStatusRow(
    lastSyncedAtMs = lastSyncedAtMs,
    inFlight = inFlight,
    enabled = enabled,
    onSyncNow = onSyncNow
)

/**
 * Contacts section sync row — manual "Sync contacts" trigger. Same shape as
 * [CallSyncStatusRow] (the only difference is which worker the tap drives, and
 * the section it lives in), so it shares [SyncStatusRow]. The button is
 * disabled without READ_CONTACTS ([enabled] = false) or while a sync is in
 * flight.
 */
@Composable
fun ContactsSyncRow(
    lastSyncedAtMs: Long,
    inFlight: Boolean,
    enabled: Boolean,
    onSyncNow: () -> Unit
) = SyncStatusRow(
    lastSyncedAtMs = lastSyncedAtMs,
    inFlight = inFlight,
    enabled = enabled,
    onSyncNow = onSyncNow
)

/**
 * Shared sync-status row: a 'Last synced …' / 'Never synced.' meta line over a
 * 'Sync now' secondary button with an inline spinner when [inFlight].
 *
 * Secondary, not Primary: Settings shows this row twice (contacts and call
 * history), and a sync is maintenance, not the one action the screen exists
 * for. Two Primary buttons spent the accent twice (rules.md Design 5).
 *
 * The button is disabled when [enabled] is false (the backing permission is not
 * granted) or when [inFlight] is true (a sync is already running). The
 * relative-time line is normalized to sentence case for voice consistency
 * (project voice guidelines: "no exclamation marks, sentence case").
 */
@Composable
private fun SyncStatusRow(
    lastSyncedAtMs: Long,
    inFlight: Boolean,
    enabled: Boolean,
    onSyncNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY)
    ) {
        val now = System.currentTimeMillis()
        // DateUtils' relative time is already localized; it slots into the
        // resource sentence ("Last synced {5 minutes ago}").
        val relative = remember(lastSyncedAtMs, now) {
            if (lastSyncedAtMs <= 0L) {
                null
            } else {
                DateUtils.getRelativeTimeSpanString(
                    lastSyncedAtMs, now, DateUtils.MINUTE_IN_MILLIS
                ).toString().lowercase()
            }
        }
        val rel = if (relative == null) {
            stringResource(R.string.settings_sync_never)
        } else {
            stringResource(R.string.settings_sync_last, relative)
        }
        Text(
            text = rel,
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
            modifier = Modifier.padding(top = OrbitTheme.spacing.x2)
        ) {
            OrbitButton(
                text = stringResource(if (inFlight) R.string.settings_syncing else R.string.settings_sync_now),
                onClick = onSyncNow,
                enabled = enabled && !inFlight,
                variant = OrbitButtonVariant.Secondary
            )
            if (inFlight) {
                CircularProgressIndicator(
                    color = OrbitTheme.colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
