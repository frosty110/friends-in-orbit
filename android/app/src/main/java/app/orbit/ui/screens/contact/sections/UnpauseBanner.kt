package app.orbit.ui.screens.contact.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * CONTACT-05 — unpause prompt at expiry. Renders when
 * `pausedUntil <= clock.now() && !PauseContactUseCase.isIndefinite(pausedUntil)`
 * (the indefinite sentinel never auto-expires; the user explicitly chose
 * "until I unpause"). Stateless: caller manages visibility via
 * `AnimatedVisibility`.
 *
 * Tap-anywhere on the banner clears the pause. The dismiss-x is the
 * explicit affordance for the same action — both call `onUnpause()`. Voice
 * rule: the user isn't dismissing the EVENT (unpause already happened);
 * they're acknowledging the notice. Sentence case copy is locked.
 *
 * Privacy curtain (PRIV-03): when `curtain` is true, heading reads
 * "Contact is unpaused" — generic, no contact-specific phrasing.
 *
 * Shape language matches OrphanBanner shell: `shapes.lg`, `bgSubtle`,
 * `x4` padding.
 */
@Composable
fun UnpauseBanner(
    contactName: String,
    curtain: Boolean,
    onUnpause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayHeading = if (curtain) "Contact is unpaused" else "$contactName is unpaused"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = OrbitTheme.spacing.x6,
                vertical = OrbitTheme.spacing.x4,
            )
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.bgSubtle)
            .clickable { onUnpause() }
            .padding(OrbitTheme.spacing.x4),
        verticalAlignment = Alignment.Top,
    ) {
        PhIcon(
            name = "phone-pause",
            size = 18.dp,
            tint = OrbitTheme.colors.fgMuted,
        )
        Spacer(Modifier.width(OrbitTheme.spacing.x3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayHeading,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x1))
            Text(
                text = "They'll surface again on this list.",
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            )
        }
        // Note: the OrbitIconButton signature is
        // (icon, onClick, modifier, tint, contentDescription) — there is NO
        // `size` constructor param. The default 22dp icon glyph inside a 48dp
        // touch target is the canonical app-bar / inline icon button shape;
        // matches the existing trailing-x dismiss affordances elsewhere in
        // the app.
        OrbitIconButton(
            icon = "x",
            onClick = onUnpause,
            contentDescription = "Dismiss unpause notice",
        )
    }
}

// region Previews

@Preview(name = "UnpauseBanner — light", showBackground = true)
@Composable
private fun UnpauseBannerLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            UnpauseBanner(
                contactName = "Alex Chen",
                curtain = false,
                onUnpause = {},
            )
        }
    }
}

@Preview(name = "UnpauseBanner — dark", showBackground = true)
@Composable
private fun UnpauseBannerDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            UnpauseBanner(
                contactName = "Alex Chen",
                curtain = false,
                onUnpause = {},
            )
        }
    }
}

@Preview(name = "UnpauseBanner — curtain on", showBackground = true)
@Composable
private fun UnpauseBannerCurtainPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            UnpauseBanner(
                contactName = "Alex Chen",
                curtain = true,
                onUnpause = {},
            )
        }
    }
}

// endregion
