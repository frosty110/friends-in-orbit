package app.orbit.ui.screens.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * Permission rationale card.
 *
 * Centred Column on `OrbitTheme.colors.bg` — NOT a surface card (deliberately
 * drops the [androidx.compose.material3.Card] wrapper that the
 * onboarding `PermissionCard` uses). The rationale here is a one-shot
 * gate, not a permission inventory.
 *
 * Locked copy:
 *   - Title:   "Allow access to your contacts"
 *   - Body:    "Orbit reads your phone contacts so you can add them to lists.
 *               Nothing is uploaded — your contacts stay on this device."
 *   - Primary: "Grant access"
 *   - Ghost:   "Not now" (optional secondary; surfaces only if [onDismiss]
 *               is non-null — the picker passes `onBack` so the user can back
 *               out to the entry-point screen).
 */
@Composable
fun RationaleCard(
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = OrbitTheme.spacing.x6)
            .padding(top = OrbitTheme.spacing.x10),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
    ) {
        PhIcon(
            name = "shield-check",
            size = OrbitTheme.spacing.x7,
            tint = OrbitTheme.colors.accent,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = "Allow access to your contacts",
            style = OrbitTheme.type.h3,
            color = OrbitTheme.colors.fg,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Orbit reads your phone contacts so you can add them to lists. Nothing is uploaded — your contacts stay on this device.",
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fgMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        OrbitButton(
            text = "Grant access",
            onClick = onGrant,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onDismiss != null) {
            OrbitButton(
                text = "Not now",
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
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
