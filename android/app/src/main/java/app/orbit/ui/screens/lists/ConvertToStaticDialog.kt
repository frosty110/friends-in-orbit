package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString

/**
 * Confirmation dialog for the one-way SMART → STATIC conversion (LIST-08).
 * Atomicity is owned by [app.orbit.data.repository.ListRepository.convertSmartToStatic]
 * (the `db.withTransaction` wrap). This composable is the UI bridge: it
 * surfaces the consequence (the list stops adding people by itself), names
 * how many people stay, and lists the first three names so the user can read
 * what they're keeping.
 *
 * Copy (strings_lists.xml) uses the same words as the "Make this a regular
 * list" button that opens it and the note under that button; until
 * 2026-10-05 it said "Convert to a static list?" and "snapshots ... as
 * permanent members", engineering words beside a plain-words button:
 *  - Title: "Make this a regular list?"
 *  - Body: "The N people here now stay, and the list stops adding people by
 *    itself. This can't be undone." (plural; "No one is on this list right
 *    now, ..." when it matches no one)
 *  - Optional preview line listing first up to 3 names + "and N more"
 *  - Confirm button: "Make it regular" (Destructive variant)
 *  - Cancel button: "Cancel" (Ghost variant)
 *
 * No undo affordance: the post-confirm snackbar reads "This is now a regular
 * list." and is owned by the calling screen.
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
                text = stringResource(R.string.lists_convert_title),
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            // "The 0 people here now stay" read oddly; an empty list says so.
            val sentence = if (memberCount == 0) {
                stringResource(R.string.lists_convert_body_empty)
            } else {
                pluralStringResource(R.plurals.lists_convert_body, memberCount, memberCount)
            }
            val previewLine = buildPreviewLine(memberCount, firstNames)?.asString()
            val body = if (previewLine != null) {
                stringResource(R.string.lists_convert_body_with_preview, sentence, previewLine)
            } else {
                sentence
            }
            Text(
                text = body,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
        },
        confirmButton = {
            OrbitButton(
                text = stringResource(R.string.lists_convert_confirm),
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

/**
 * Composes the preview line per UI-SPEC: "Including: {first 3 names}" + (if
 * memberCount > 3) " and {memberCount - 3} more". Returns null when there are
 * no names to show (matches "Body (preview list, optional)" in the spec — the
 * preview is omitted entirely when membership is empty).
 */
internal fun buildPreviewLine(memberCount: Int, firstNames: List<String>): UiText? {
    if (firstNames.isEmpty()) return null
    val head = firstNames.take(3).joinToString(", ")
    val remainder = memberCount - 3
    return if (remainder > 0) {
        UiText.plural(R.plurals.lists_convert_preview_more, remainder, head, remainder)
    } else {
        UiText.res(R.string.lists_convert_preview, head)
    }
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
