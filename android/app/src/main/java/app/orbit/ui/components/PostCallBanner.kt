package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow

/**
 * NOTE-02 post-call prompt banner. Reusable on Home and ContactDetail.
 *
 * Stateless — visibility is owned by the consumer screen (wraps in
 * AnimatedVisibility for slideInVertically + fadeIn enter / symmetric
 * exit). The DAO query that derives `postCallPrompt`
 * lives in AppViewModel; Pitfall 2
 * mitigation: AppViewModel uses `LifecycleResumeEffect` (NOT
 * `LaunchedEffect(Unit)`); the rest of
 * the codebase uses the older LifecycleEventObserver + DisposableEffect
 * pattern (SettingsScreen.kt, ContactPickerScreen.kt). Documented
 * divergence — lifecycle-runtime-compose 2.8.7 IS in the catalog
 * (libs.versions.toml).
 *
 * Privacy curtain (PRIV-03): when curtain is true, heading reads "You
 * just made a call" — generic, no contact-specific phrasing.
 */
@Composable
fun PostCallBanner(
    contactName: String?,
    curtain: Boolean,
    onAddNote: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayHeading = if (curtain || contactName.isNullOrBlank()) {
        "You just made a call"
    } else {
        "You just called $contactName"
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x6, vertical = OrbitTheme.spacing.x3)
            .orbitCardShadow(shape = OrbitTheme.shapes.lg, isDark = OrbitTheme.colors.isDark)
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.surface)
            .padding(OrbitTheme.spacing.x4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhIcon(name = "note-pencil", size = 18.dp, tint = OrbitTheme.colors.accent)
            Spacer(Modifier.width(OrbitTheme.spacing.x3))
            Text(
                text = displayHeading,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
        }
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        // Inset = PhIcon size (18) + Row spacing (x3 = 12) = 30dp so the body
        // and buttons align under the heading text rather than the icon.
        Text(
            text = "Add a note while it's fresh",
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            modifier = Modifier.padding(start = 30.dp),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
        Row(
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
            modifier = Modifier.padding(start = 30.dp),
        ) {
            OrbitButton(
                text = "Add a note",
                onClick = onAddNote,
                variant = OrbitButtonVariant.Primary,
            )
            OrbitButton(
                text = "Dismiss",
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPostCallBannerLight() {
    OrbitTheme(darkTheme = false) {
        PostCallBanner(
            contactName = "Alex Chen",
            curtain = false,
            onAddNote = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPostCallBannerDark() {
    OrbitTheme(darkTheme = true) {
        PostCallBanner(
            contactName = "Alex Chen",
            curtain = false,
            onAddNote = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPostCallBannerCurtain() {
    OrbitTheme(darkTheme = false) {
        PostCallBanner(
            contactName = "Alex Chen",
            curtain = true,
            onAddNote = {},
            onDismiss = {},
        )
    }
}
