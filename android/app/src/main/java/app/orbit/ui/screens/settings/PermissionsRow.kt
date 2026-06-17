package app.orbit.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * SET-07 — per-permission status row inside the Settings Permissions section.
 * The row's trailing action matches the actual state instead of always
 * deep-linking to Android Settings:
 *
 *   - [PermissionStatus.Granted] — quiet "Allowed" label, no action.
 *     There is nothing for the user to do; a button here was noise.
 *   - [PermissionStatus.Denied] — the system dialog can still be shown
 *     (`shouldShowRequestPermissionRationale == true`), so the "Allow"
 *     button fires the runtime permission launcher directly.
 *   - [PermissionStatus.PermanentlyDenied] — the OS will silently
 *     auto-deny a launcher request, so the only honest action is the
 *     "Open Android Settings" deep link.
 *
 * Reused for Contacts and Notifications. The call-log permission still
 * uses [app.orbit.calllog.CallLogPermissionState] at the VM layer for
 * back-compat with the ContentObserverController plumbing; the screen
 * maps it onto [PermissionStatus] before rendering this row.
 */
@Composable
fun PermissionsRow(
    label: String,
    status: PermissionStatus,
    onRequestPermission: () -> Unit,
    onOpenAndroidSettings: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = status.label,
                style = OrbitTheme.type.meta.copy(color = status.color()),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        when (status.rowAction()) {
            PermissionRowAction.None -> Unit
            PermissionRowAction.Request -> OrbitButton(
                text = "Allow",
                onClick = onRequestPermission,
                variant = OrbitButtonVariant.Ghost,
            )
            PermissionRowAction.OpenSettings -> OrbitButton(
                text = "Open Android Settings",
                onClick = onOpenAndroidSettings,
                variant = OrbitButtonVariant.Ghost,
            )
        }
    }
}

/**
 * SET-07 — three-state per-permission status used by Contacts and
 * Notifications rows. The Call log permission stays on
 * [app.orbit.calllog.CallLogPermissionState] so the existing
 * ContentObserverController plumbing keeps compiling unchanged.
 *
 * `PermanentlyDenied.label` names the recovery path explicitly because the
 * Open-Android-Settings button is the only way out of that state.
 */
enum class PermissionStatus(val label: String) {
    Granted("Allowed"),
    Denied("Not allowed"),
    PermanentlyDenied("Off in your phone's settings"),
}

/** What the trailing slot of a [PermissionsRow] should do. */
enum class PermissionRowAction { None, Request, OpenSettings }

/**
 * Pure status→action mapping, extracted so the row's
 * behavior contract is unit-testable without composition.
 */
internal fun PermissionStatus.rowAction(): PermissionRowAction = when (this) {
    PermissionStatus.Granted -> PermissionRowAction.None
    PermissionStatus.Denied -> PermissionRowAction.Request
    PermissionStatus.PermanentlyDenied -> PermissionRowAction.OpenSettings
}

@Composable
private fun PermissionStatus.color(): Color = when (this) {
    PermissionStatus.Granted -> OrbitTheme.colors.positive
    PermissionStatus.Denied,
    PermissionStatus.PermanentlyDenied -> OrbitTheme.colors.fgMuted
}

@PreviewLightDark
@Composable
private fun PermissionsRowPreview() {
    OrbitTheme {
        Column {
            PermissionsRow(
                label = "Contacts",
                status = PermissionStatus.Granted,
                onRequestPermission = {},
                onOpenAndroidSettings = {},
            )
            PermissionsRow(
                label = "Call log",
                status = PermissionStatus.Denied,
                onRequestPermission = {},
                onOpenAndroidSettings = {},
            )
            PermissionsRow(
                label = "Notifications",
                status = PermissionStatus.PermanentlyDenied,
                onRequestPermission = {},
                onOpenAndroidSettings = {},
            )
        }
    }
}
