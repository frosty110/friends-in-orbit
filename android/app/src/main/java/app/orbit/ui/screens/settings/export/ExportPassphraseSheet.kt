package app.orbit.ui.screens.settings.export

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitTextField
import app.orbit.ui.theme.OrbitTheme
import kotlinx.coroutines.launch

/**
 * SET-05 — encrypted-export passphrase bottom sheet.
 *
 * Material3 ModalBottomSheet shell mirroring
 * [app.orbit.ui.screens.lists.CreateListBottomSheet]. On submit, the sheet
 * calls [onSubmit] with the passphrase as a `CharArray`; the caller
 * (ExportViewModel) is responsible for wiping the array after the export
 * call resolves.
 *
 * Validation:
 *   - both fields must be at least 8 chars.
 *   - confirm must equal password.
 *   - Export CTA is disabled until both validations pass.
 *
 * Voice: locked copy.
 *
 * Note on passphrase residency: `password.toCharArray()` creates a fresh
 * array at submit time; the caller wipes it. The Compose state itself
 * holds the `String`, which is unfortunately immutable on the JVM —
 * full mitigation would require a `Char[]`-backed TextField (follow-up).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportPassphraseSheet(
    sheetState: SheetState,
    onSubmit: (passphrase: CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = OrbitTheme.shapes.bottomSheet,
        containerColor = OrbitTheme.colors.surface,
    ) {
        ExportPassphraseContent(
            onSubmit = { pass ->
                onSubmit(pass)
                scope.launch { sheetState.hide() }
            },
            onCancel = onDismiss,
        )
    }
}

// Internal, not private, so a test can drive the fields without a sheet host
// (CreateListContent's precedent).
@Composable
internal fun ExportPassphraseContent(
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < 8
    val mismatch = confirm.isNotEmpty() && confirm != password
    val canSubmit = password.length >= 8 && confirm == password

    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The sheet pads its content by the keyboard itself (Material's
            // contentWindowInsets); this scroll lets the focused field move
            // up into what is left, and the field asks it to (OrbitTextField).
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = OrbitTheme.spacing.x6,
                vertical = OrbitTheme.spacing.x4,
            ),
    ) {
        Text(
            text = stringResource(R.string.settings_export_sheet_title),
            style = OrbitTheme.type.h2.copy(color = OrbitTheme.colors.fg),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = stringResource(R.string.settings_export_sheet_body),
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))

        // Each label sits above its field and is the field's TalkBack name,
        // so "Password, edit box" needs no semantics patching. A password
        // keyboard: no suggestions, no learning the passphrase.
        OrbitTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.settings_password),
            supportingText = stringResource(R.string.settings_password_hint),
            errorText = if (tooShort) stringResource(R.string.settings_password_too_short) else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        )

        Spacer(Modifier.height(OrbitTheme.spacing.x4))

        OrbitTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = stringResource(R.string.settings_password_confirm),
            errorText = if (mismatch) stringResource(R.string.settings_password_mismatch) else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            // Done exports once both agree, like the button; otherwise it
            // just puts the keyboard away.
            keyboardActions = KeyboardActions(onDone = {
                if (canSubmit) onSubmit(password.toCharArray()) else focusManager.clearFocus()
            }),
        )

        Spacer(Modifier.height(OrbitTheme.spacing.x6))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x4),
        ) {
            OrbitButton(
                text = stringResource(R.string.components_action_cancel),
                onClick = onCancel,
                variant = OrbitButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
            )
            OrbitButton(
                text = stringResource(R.string.settings_export_cta),
                onClick = {
                    if (canSubmit) onSubmit(password.toCharArray())
                },
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview(name = "ExportPassphraseSheet · light", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun ExportPassphraseSheetLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface),
        ) {
            ExportPassphraseContent(onSubmit = {}, onCancel = {})
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "ExportPassphraseSheet · dark", showBackground = true, backgroundColor = 0xFF0E0F12)
@Composable
private fun ExportPassphraseSheetDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface),
        ) {
            ExportPassphraseContent(onSubmit = {}, onCancel = {})
        }
    }
}
