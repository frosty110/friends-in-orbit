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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
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

@Composable
private fun ExportPassphraseContent(
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < 8
    val mismatch = confirm.isNotEmpty() && confirm != password
    val canSubmit = password.length >= 8 && confirm == password

    // Resolved here: semantics blocks are not composable.
    val passwordLabel = stringResource(R.string.settings_password)
    val confirmLabel = stringResource(R.string.settings_password_confirm)
    val tooShortMessage = stringResource(R.string.settings_password_too_short)
    val mismatchMessage = stringResource(R.string.settings_password_mismatch)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // imePadding alone only shrinks the sheet; the scroll is what lets
            // the focused field move up into what's left of it.
            .verticalScroll(rememberScrollState())
            .imePadding()
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

        Text(
            text = stringResource(R.string.settings_password),
            style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            // Spoken as the field's own label instead (below), so TalkBack
            // says "Password, edit box" rather than an unlabelled edit box.
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        TextField(
            value = password,
            onValueChange = { password = it },
            isError = tooShort,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = transparentFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(OrbitTheme.shapes.md)
                .background(OrbitTheme.colors.bgSubtle)
                .semantics {
                    contentDescription = passwordLabel
                    if (tooShort) error(tooShortMessage)
                },
        )
        Text(
            text = stringResource(if (tooShort) R.string.settings_password_too_short else R.string.settings_password_hint),
            style = OrbitTheme.type.meta.copy(
                color = if (tooShort) OrbitTheme.colors.danger else OrbitTheme.colors.fgMuted,
            ),
            modifier = Modifier.padding(top = OrbitTheme.spacing.x1, start = OrbitTheme.spacing.x1),
        )

        Spacer(Modifier.height(OrbitTheme.spacing.x4))

        Text(
            text = stringResource(R.string.settings_password_confirm),
            style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            // Spoken as the field's own label instead (below), so TalkBack
            // says "Password, edit box" rather than an unlabelled edit box.
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        TextField(
            value = confirm,
            onValueChange = { confirm = it },
            isError = mismatch,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = transparentFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(OrbitTheme.shapes.md)
                .background(OrbitTheme.colors.bgSubtle)
                .semantics {
                    contentDescription = confirmLabel
                    if (mismatch) error(mismatchMessage)
                },
        )
        if (mismatch) {
            Text(
                text = stringResource(R.string.settings_password_mismatch),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.danger),
                modifier = Modifier.padding(top = OrbitTheme.spacing.x1, start = OrbitTheme.spacing.x1),
            )
        }

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

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    focusedTextColor = OrbitTheme.colors.fg,
    unfocusedTextColor = OrbitTheme.colors.fg,
)

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
