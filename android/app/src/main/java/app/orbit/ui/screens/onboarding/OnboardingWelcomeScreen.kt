package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.ui.theme.OrbitTheme

/**
 * First screen of the onboarding flow. Frame the app and set the voice
 * before any permission asks: never ask for sensitive permissions before
 * the user understands what the app does.
 *
 * No back button (this is the entry point), no step counter (Welcome is
 * framing, not a counted step), single primary CTA "Let's go" → first
 * permission rationale (Contacts).
 *
 * Brand presentation:
 *   - "Orbit" wordmark in [OrbitTheme.type.hero] (32sp SemiBold)
 *   - One-line tagline in [OrbitTheme.type.body]
 *   - Three quiet value beats in [OrbitTheme.type.meta] (choice, the one-card
 *     loop, on-device privacy).
 *
 * Voice: sentence case, no exclamation marks, non-performative.
 */
@Composable
fun OnboardingWelcomeScreen(
    onContinue: () -> Unit,
) {
    OnboardingScaffold(
        step = null,
        onBack = null,
        primary = OnboardingAction(
            label = "Let's go",
            onClick = onContinue,
        ),
    ) {
        Spacer(Modifier.height(OrbitTheme.spacing.x10))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Orbit",
                style = OrbitTheme.type.hero.copy(color = OrbitTheme.colors.fg),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x6))
            Text(
                text = "Call the people you keep meaning to call.",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x4),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x6))
            // Three quiet value beats replace the privacy line + feedback
            // mailto. The mailto was the
            // most colorful element on screen and sold nothing; a Settings
            // placement is a separate decision.
            ValueBeat("Not all contacts are friends — you choose who matters.")
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
            ValueBeat("One name at a time, with enough context to say yes.")
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
            ValueBeat("Everything stays on your phone — no cloud, no tracking.")
        }
    }
}

/** One short value line — warm, muted, centered. */
@Composable
private fun ValueBeat(text: String) {
    Text(
        text = text,
        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x4),
    )
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingWelcomeScreenPreview() {
    OrbitTheme {
        OnboardingWelcomeScreen(
            onContinue = {},
        )
    }
}
