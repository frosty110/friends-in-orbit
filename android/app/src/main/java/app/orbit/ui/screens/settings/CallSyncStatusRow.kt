package app.orbit.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.asString
import app.orbit.ui.util.formatRelativeFine
import java.time.Instant

/**
 * SET-04: Call history section row. Renders "Last synced 5 minutes ago" or
 * "Never synced" over a "Sync now" Secondary button (with inline spinner
 * when [inFlight]). Thin wrapper over [SyncStatusRow].
 */
@Composable
fun CallSyncStatusRow(
    lastSyncedAtMs: Long,
    now: Instant,
    inFlight: Boolean,
    enabled: Boolean,
    onSyncNow: () -> Unit
) = SyncStatusRow(
    lastSyncedAtMs = lastSyncedAtMs,
    now = now,
    inFlight = inFlight,
    enabled = enabled,
    onSyncNow = onSyncNow
)

/**
 * Contacts section sync row: manual "Sync contacts" trigger. Same shape as
 * [CallSyncStatusRow] (the only difference is which worker the tap drives, and
 * the section it lives in), so it shares [SyncStatusRow]. The button is
 * disabled without READ_CONTACTS ([enabled] = false) or while a sync is in
 * flight.
 */
@Composable
fun ContactsSyncRow(
    lastSyncedAtMs: Long,
    now: Instant,
    inFlight: Boolean,
    enabled: Boolean,
    onSyncNow: () -> Unit
) = SyncStatusRow(
    lastSyncedAtMs = lastSyncedAtMs,
    now = now,
    inFlight = inFlight,
    enabled = enabled,
    onSyncNow = onSyncNow
)

/**
 * Shared sync-status row: a "Last synced ..." / "Never synced" meta line over a
 * "Sync now" secondary button with an inline spinner when [inFlight].
 *
 * Secondary, not Primary: Settings shows this row twice (contacts and call
 * history), and a sync is maintenance, not the one action the screen exists
 * for. Two Primary buttons spent the accent twice (rules.md Design 5).
 *
 * The button is disabled when [enabled] is false (the backing permission is not
 * granted) or when [inFlight] is true (a sync is already running).
 *
 * The time is worded by [formatRelativeFine], the app's one time formatter
 * (voice.md glossary), against the [now] the ViewModel put in the state: no
 * clock is read in composition, so the row is a pure projection and the
 * preview gallery renders a fixed label. It used to call
 * `DateUtils.getRelativeTimeSpanString` with `System.currentTimeMillis()` and
 * lowercase the result, which gave a second wording ("5 min. ago") beside
 * every other screen's.
 */
@Composable
private fun SyncStatusRow(
    lastSyncedAtMs: Long,
    now: Instant,
    inFlight: Boolean,
    enabled: Boolean,
    onSyncNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY)
    ) {
        val rel = if (lastSyncedAtMs <= 0L) {
            stringResource(R.string.settings_sync_never)
        } else {
            // "Last synced {5 minutes ago}": the formatter's UiText is the argument.
            stringResource(
                R.string.settings_sync_last,
                formatRelativeFine(Instant.ofEpochMilli(lastSyncedAtMs), now).asString(),
            )
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
