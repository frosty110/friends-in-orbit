package app.orbit.ui.screens.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.R
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
                text = stringResource(R.string.settings_import_confirm_title),
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            val lists = pluralStringResource(R.plurals.settings_import_confirm_lists, listCount, listCount)
            val contacts = pluralStringResource(R.plurals.settings_import_confirm_people, contactCount, contactCount)
            Text(
                text = stringResource(R.string.settings_import_confirm_body, lists, contacts),
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
        },
        confirmButton = {
            OrbitButton(
                text = stringResource(R.string.settings_import_confirm_replace),
                onClick = onConfirm,
                variant = OrbitButtonVariant.Destructive,
            )
        },
        dismissButton = {
            OrbitButton(
                text = stringResource(R.string.components_action_cancel),
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
