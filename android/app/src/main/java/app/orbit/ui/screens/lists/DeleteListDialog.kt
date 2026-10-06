package app.orbit.ui.screens.lists

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * D-25: the confirmation before a list is deleted.
 *
 * Reachable from two places, which must behave the same
 * (features/orbit-lists/README.md, "the same delete-with-Undo behavior"):
 * the delete control on `ArchivedListRow` in the Lists manager, and Delete in
 * Home's long-press menu, which needs no prior archive step. Either way the
 * confirm stages a deferred delete: the row hides, "List deleted." offers
 * Undo, and the purge runs only when that snackbar goes
 * (features/home/README.md).
 *
 * Copy is `lists_delete_title` / `lists_delete_body`, in sentence case per
 * voice.md (UX rubric decision 8, 2026-10-05). An earlier PRD line pinned a
 * lowercase body and this header told editors not to capitalise it; that
 * line was updated with the decision, so the header no longer argues with
 * the code below.
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
                text = stringResource(R.string.lists_delete_title),
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            Text(
                // Sentence case per voice.md (UX rubric decision 8, 2026-10-05);
                // the PRD line that pinned lowercase was updated with it.
                text = stringResource(R.string.lists_delete_body),
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
        },
        confirmButton = {
            OrbitButton(
                text = stringResource(R.string.components_action_delete),
                onClick = onConfirm,
                variant = OrbitButtonVariant.Destructive,
            )
        },
        dismissButton = {
            OrbitButton(
                text = stringResource(R.string.lists_delete_keep),
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

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "DeleteListDialog — dark", showBackground = true)
@Composable
private fun DeleteListDialogDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            DeleteListDialog(onConfirm = {}, onDismiss = {})
        }
    }
}
