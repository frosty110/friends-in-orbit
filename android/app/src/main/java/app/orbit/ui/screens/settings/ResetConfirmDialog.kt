package app.orbit.ui.screens.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * SET-06 — destructive Reset confirmation. Body copy is verbatim from the
 * reset confirmation dialog spec.
 *
 * Pattern precedent: DeleteListDialog — same M3 AlertDialog shell,
 * Destructive confirm button, Ghost dismiss button.
 */
@Composable
fun ResetConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = "Reset Orbit?",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            Text(
                text = "Every list, contact, note, and call record on this phone will be erased. " +
                    "Your phone's contacts and call log are not touched.",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
        },
        confirmButton = {
            OrbitButton(
                text = "Reset",
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
