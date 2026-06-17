package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * D-25 — destructive confirmation for hard-deleting an archived list.
 *
 * Reachable only from `ArchivedListRow` (PRD: Delete sits behind an
 * explicit prior archive step — the two-step gesture is the warm/unhurried
 * "beat to reconsider").
 *
 * Copy is verbatim from `features/orbit-lists/README.md` and is locked
 * (lowercase body is intentional — do NOT capitalize).
 *
 * Pattern precedent: ConvertToStaticDialog — same Material3
 * AlertDialog shell, Destructive confirm button, Ghost dismiss button.
 */
@Composable
fun DeleteListDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = "Delete this list?",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            Text(
                // Lowercase verbatim — locked by PRD line 46.
                text = "this removes the list. people stay in your contacts.",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
        },
        confirmButton = {
            OrbitButton(
                text = "Delete",
                onClick = onConfirm,
                variant = OrbitButtonVariant.Destructive,
            )
        },
        dismissButton = {
            OrbitButton(
                text = "Keep",
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
            )
        },
    )
}

@Preview(name = "DeleteListDialog — light", showBackground = true)
@Composable
private fun DeleteListDialogLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            DeleteListDialog(onConfirm = {}, onDismiss = {})
        }
    }
}

@Preview(name = "DeleteListDialog — dark", showBackground = true)
@Composable
private fun DeleteListDialogDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            DeleteListDialog(onConfirm = {}, onDismiss = {})
        }
    }
}
