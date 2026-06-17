package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.orbit.ui.theme.OrbitTheme

/**
 * Step counter rendered in the trailing slot of [app.orbit.ui.components.OrbitAppBar]
 * across the onboarding flow. Voice: "n of 7" in eyebrow type — quiet, no
 * progress bar (per the project's design principles — unhurried).
 */
@Composable
fun OnboardingProgress(step: OnboardingStep, modifier: Modifier = Modifier) {
    Text(
        text = "${step.ordinal1} of ${step.total}",
        style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgSubtle),
        modifier = modifier.padding(horizontal = OrbitTheme.spacing.x3),
    )
}
