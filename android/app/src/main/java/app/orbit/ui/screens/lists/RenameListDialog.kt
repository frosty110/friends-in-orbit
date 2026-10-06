package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.components.CurtainMask
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * F-11 — quick rename from the Lists Manager overflow menu, surfaced without
 * forcing the user to drill into List Configuration. Pattern precedent:
 * [DeleteListDialog] / [ConvertToStaticDialog] — Material3 [AlertDialog] shell
 * with an [OrbitButton] confirm + ghost dismiss, sourced from [OrbitTheme].
 *
 * Behaviour:
 *  - The text field is pre-filled with [currentName] and auto-focuses on
 *    composition so the keyboard raises immediately.
 *  - IME action is "Done"; tapping it triggers the same commit path as the
 *    Save button.
 *  - Empty / blank trimmed names are treated as cancel (the dialog dismisses
 *    without dispatching to [onSave]). The VM also double-guards.
 *  - Save dispatches the trimmed name through [onSave]
 *    (wired in [ListsManagerScreen] to `vm::renameList` →
 *    `ListRepository.updateName`); the dialog never touches the DAO directly.
 */
@Composable
fun RenameListDialog(
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var nameText by rememberSaveable(currentName) { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    fun commit() {
        val trimmed = nameText.trim()
        if (trimmed.isNotEmpty() && trimmed != currentName) {
            onSave(trimmed)
        }
        onDismiss()
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = stringResource(R.string.lists_rename_title),
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            // PRIV-03: under the curtain the field draws "List" over the
            // buffer, as List settings' rename row does (CurtainMask leaves
            // the buffer alone, so Save still gets the real text). Until
            // 2026-10-06 this dialog was the one name field without the mask,
            // so a list name stayed readable in the app switcher.
            val curtainList = stringResource(R.string.components_curtain_list)
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                visualTransformation = if (LocalPrivacyCurtain.current) CurtainMask(curtainList) else VisualTransformation.None,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(OrbitTheme.type.body),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    commit()
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            OrbitButton(
                text = stringResource(R.string.components_action_save),
                onClick = { commit() },
                variant = OrbitButtonVariant.Primary,
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

@Preview(name = "RenameListDialog — light", showBackground = true)
@Composable
private fun RenameListDialogLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            RenameListDialog(
                currentName = "Inner orbit",
                onSave = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(name = "RenameListDialog — dark", showBackground = true)
@Composable
private fun RenameListDialogDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            RenameListDialog(
                currentName = "Inner orbit",
                onSave = {},
                onDismiss = {},
            )
        }
    }
}
