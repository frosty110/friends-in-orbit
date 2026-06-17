package app.orbit.ui.screens.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * Destructive confirmation shown AFTER the backup has been decrypted and
 * validated (a wrong passphrase never reaches this dialog).
 *
 * Pattern precedent: [ResetConfirmDialog] — same M3 AlertDialog shell,
 * Destructive confirm + Ghost dismiss. Body names what's at stake explicitly
 * per the settings spec's confirmation rule.
 */
@Composable
fun ImportConfirmDialog(
    listCount: Int,
    contactCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = "Restore this backup?",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            val lists = if (listCount == 1) "1 list" else "$listCount lists"
            val contacts = if (contactCount == 1) "1 contact" else "$contactCount contacts"
            Text(
                text = "This replaces everything in Orbit with the backup — " +
                    "$lists and $contacts. What's on this phone now will be erased.",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
        },
        confirmButton = {
            OrbitButton(
                text = "Replace",
                onClick = onConfirm,
                variant = OrbitButtonVariant.Destructive,
            )
        },
        dismissButton = {
            OrbitButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
            )
        },
    )
}

@PreviewLightDark
@Composable
private fun ImportConfirmDialogPreview() {
    OrbitTheme {
        ImportConfirmDialog(
            listCount = 4,
            contactCount = 52,
            onConfirm = {},
            onDismiss = {},
        )
    }
}
