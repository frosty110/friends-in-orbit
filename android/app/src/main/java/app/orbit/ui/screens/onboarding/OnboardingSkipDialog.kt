package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * ONB-15 — calm "Skip for now?" confirmation dialog. Surfaced from each
 * permission rationale screen when the user taps the secondary
 * "Continue without it" CTA. Names what won't work without that specific
 * permission, in voice (sentence case, no exclamation, no scare copy).
 *
 * Pattern precedent: DeleteListDialog — same M3 AlertDialog shell. The Skip
 * confirm uses Ghost variant (NOT Destructive) because skip is reversible —
 * the user can grant later in Settings or via the Open-Android-Settings deep
 * link.
 *
 * Copy is locked by the design spec (title + button labels, and the
 * per-permission "what's lost" body, per [SkipPermission]).
 */
@Composable
fun OnboardingSkipDialog(
    permission: SkipPermission,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = "Skip for now?",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            Text(
                text = whatsLostCopy(permission),
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
        },
        confirmButton = {
            OrbitButton(
                text = "Skip",
                onClick = onConfirm,
                variant = OrbitButtonVariant.Ghost,
            )
        },
        dismissButton = {
            OrbitButton(
                text = "Go back",
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
            )
        },
    )
}

/**
 * Identifies which permission the user is about to skip — drives the body
 * copy via [whatsLostCopy]. Mirrors the three runtime permissions onboarding
 * gates: READ_CONTACTS, READ_CALL_LOG, POST_NOTIFICATIONS.
 */
enum class SkipPermission { Contacts, CallLog, Notifications }

private fun whatsLostCopy(permission: SkipPermission): String = when (permission) {
    SkipPermission.Contacts ->
        "Without contacts access, Orbit can't build your lists from your phone book."
    SkipPermission.CallLog ->
        "Without call log access, Orbit can't notice when you've already called someone, " +
            "so the same person may keep coming up."
    SkipPermission.Notifications ->
        "Without notifications, Orbit won't be able to remind you when it's time to reach out."
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingSkipDialogPreview() {
    OrbitTheme {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            OnboardingSkipDialog(
                permission = SkipPermission.CallLog,
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}
