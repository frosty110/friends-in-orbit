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
 * Confirmation dialog for the one-way SMART → STATIC conversion (LIST-08).
 * Atomicity is owned by [app.orbit.data.repository.ListRepository.convertSmartToStatic]
 * (the `db.withTransaction` wrap). This composable is the UI bridge: it
 * surfaces the consequence ("the rule will no longer update membership"),
 * names how many people get snapshotted, and lists the first three names so
 * the user can read what they're locking in.
 *
 * Copy is verbatim from the convert-to-static dialog spec:
 *  - Title: "Convert to a static list?"
 *  - Body (singular vs plural toggles on N == 1)
 *  - Optional preview line listing first up to 3 names + "and N more"
 *  - Confirm button: "Convert" (Destructive variant)
 *  - Cancel button: "Cancel" (Ghost variant)
 *
 * No undo affordance — the post-confirm Snackbar reads
 * `List converted — membership locked.` and is owned by the calling screen.
 */
@Composable
fun ConvertToStaticDialog(
    memberCount: Int,
    firstNames: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = "Convert to a static list?",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            val noun = if (memberCount == 1) "person" else "people"
            val sentence =
                "This snapshots $memberCount $noun as permanent members. " +
                    "The rule will no longer update membership."
            val previewLine = buildPreviewLine(memberCount, firstNames)
            val body = if (previewLine != null) "$sentence\n\n$previewLine" else sentence
            Text(
                text = body,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
        },
        confirmButton = {
            OrbitButton(
                text = "Convert",
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

/**
 * Composes the preview line per UI-SPEC: "Including: {first 3 names}" + (if
 * memberCount > 3) " and {memberCount - 3} more". Returns null when there are
 * no names to show (matches "Body (preview list, optional)" in the spec — the
 * preview is omitted entirely when membership is empty).
 */
internal fun buildPreviewLine(memberCount: Int, firstNames: List<String>): String? {
    if (firstNames.isEmpty()) return null
    val head = firstNames.take(3).joinToString(", ")
    val remainder = memberCount - 3
    val tail = if (remainder > 0) " and $remainder more" else ""
    return "Including: $head$tail"
}

// region Previews

@Preview(name = "ConvertToStaticDialog — light, plural with overflow", showBackground = true)
@Composable
private fun ConvertToStaticDialogLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            ConvertToStaticDialog(
                memberCount = 5,
                firstNames = listOf("Alex", "Sam", "Jordan", "Taylor", "Morgan"),
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(name = "ConvertToStaticDialog — dark, single member", showBackground = true)
@Composable
private fun ConvertToStaticDialogDarkSinglePreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            ConvertToStaticDialog(
                memberCount = 1,
                firstNames = listOf("Alex"),
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(name = "ConvertToStaticDialog — dark, empty members", showBackground = true)
@Composable
private fun ConvertToStaticDialogDarkEmptyPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            ConvertToStaticDialog(
                memberCount = 0,
                firstNames = emptyList(),
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}

// endregion
