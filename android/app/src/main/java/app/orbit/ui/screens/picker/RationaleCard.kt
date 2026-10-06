package app.orbit.ui.screens.picker

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.theme.OrbitTheme

/**
 * Permission rationale: the picker's one-shot gate before the system dialog.
 *
 * The shared [OrbitScreenMessage] (one component per job, DESIGN.md), the same
 * shape as [PermissionDeniedEmpty]: a muted shield on `bgSubtle`, the title,
 * the body, "Grant access" as the Primary action and "Not now" in the Ghost
 * secondary slot. Until 2026-10-06 this was its own Column with the shield
 * tinted in the accent beside the accent button, two accent elements on one
 * screen (rules.md §Design 5); the shared message also brings the scroll that
 * keeps "Grant access" reachable in landscape (gate G3), which this file used
 * to carry itself.
 *
 * Copy: "Allow access to your contacts" / "Orbit reads your phone contacts so
 * you can add them to lists. Nothing is uploaded: your contacts stay on this
 * device." / "Grant access" / "Not now" (only when [onDismiss] is given; the
 * picker passes `onBack`, so the user can back out to the entry screen).
 */
@Composable
fun RationaleCard(
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    OrbitScreenMessage(
        icon = "shield-check",
        title = stringResource(R.string.picker_rationale_title),
        body = stringResource(R.string.picker_rationale_body),
        actionLabel = stringResource(R.string.picker_rationale_grant),
        onAction = onGrant,
        // The thing this screen most wants tapped, so it takes the accent.
        actionVariant = OrbitButtonVariant.Primary,
        secondaryLabel = if (onDismiss != null) stringResource(R.string.picker_rationale_not_now) else null,
        onSecondary = onDismiss,
        modifier = modifier,
    )
}

@Preview(name = "RationaleCard — light", showBackground = true)
@Composable
private fun RationaleCardPreviewLight() {
    OrbitTheme(darkTheme = false) {
        RationaleCard(onGrant = {}, onDismiss = {})
    }
}

@Preview(name = "RationaleCard — dark", showBackground = true)
@Composable
private fun RationaleCardPreviewDark() {
    OrbitTheme(darkTheme = true) {
        RationaleCard(onGrant = {}, onDismiss = {})
    }
}
