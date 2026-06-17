package app.orbit.ui.screens.settings.export

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme
import kotlinx.coroutines.launch

/**
 * Passphrase prompt for restoring an encrypted backup.
 * Single field (no confirm — the password already exists; a typo just
 * fails decryption with the calm "couldn't be read" snackbar).
 *
 * Shell mirrors [ExportPassphraseSheet]: M3 ModalBottomSheet, transparent
 * TextField over bgSubtle, Ghost cancel + Primary submit pair. On submit
 * the caller ([ImportViewModel]) owns wiping the CharArray.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPassphraseSheet(
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
        ImportPassphraseContent(
            onSubmit = { pass ->
                onSubmit(pass)
                scope.launch { sheetState.hide() }
            },
            onCancel = onDismiss,
        )
    }
}

@Composable
private fun ImportPassphraseContent(
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    val canSubmit = password.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(
                horizontal = OrbitTheme.spacing.x6,
                vertical = OrbitTheme.spacing.x4,
            ),
    ) {
        Text(
            text = "Open your backup",
            style = OrbitTheme.type.h2.copy(color = OrbitTheme.colors.fg),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = "Enter the password you chose when you exported this file.",
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))

        Text(
            text = "Password",
            style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        TextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = transparentImportFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(OrbitTheme.shapes.md)
                .background(OrbitTheme.colors.bgSubtle),
        )

        Spacer(Modifier.height(OrbitTheme.spacing.x6))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x4),
        ) {
            OrbitButton(
                text = "Cancel",
                onClick = onCancel,
                variant = OrbitButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
            )
            OrbitButton(
                text = "Continue",
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
private fun transparentImportFieldColors() = TextFieldDefaults.colors(
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

@Preview(name = "ImportPassphraseSheet · light", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun ImportPassphraseSheetLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.surface)) {
            ImportPassphraseContent(onSubmit = {}, onCancel = {})
        }
    }
}

@Preview(name = "ImportPassphraseSheet · dark", showBackground = true, backgroundColor = 0xFF0E0F12)
@Composable
private fun ImportPassphraseSheetDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.surface)) {
            ImportPassphraseContent(onSubmit = {}, onCancel = {})
        }
    }
}
