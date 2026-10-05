package app.orbit.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.R
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
                text = stringResource(R.string.onb_skip_title),
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            Text(
                text = stringResource(whatsLostCopy(permission)),
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            )
        },
        confirmButton = {
            OrbitButton(
                text = stringResource(R.string.onb_skip_confirm),
                onClick = onConfirm,
                variant = OrbitButtonVariant.Ghost,
            )
        },
        dismissButton = {
            OrbitButton(
                text = stringResource(R.string.onb_skip_go_back),
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

@StringRes
private fun whatsLostCopy(permission: SkipPermission): Int = when (permission) {
    SkipPermission.Contacts -> R.string.onb_skip_lost_contacts
    SkipPermission.CallLog -> R.string.onb_skip_lost_call_log
    SkipPermission.Notifications -> R.string.onb_skip_lost_notifications
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
