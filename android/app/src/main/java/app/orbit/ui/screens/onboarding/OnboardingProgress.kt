package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import app.orbit.R
import app.orbit.ui.theme.OrbitTheme

/**
 * Step counter rendered in the trailing slot of [app.orbit.ui.components.OrbitAppBar]
 * across the onboarding flow. Voice: "n of 4" in eyebrow type, quiet, no
 * progress bar (unhurried, per the project's design principles). A plural
 * keyed on the total, because other languages inflect "of N" (voice.md).
 */
@Composable
fun OnboardingProgress(step: OnboardingStep, modifier: Modifier = Modifier) {
    Text(
        text = pluralStringResource(R.plurals.onb_progress, step.total, step.ordinal1, step.total),
        style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgSubtle),
        modifier = modifier.padding(horizontal = OrbitTheme.spacing.x3),
    )
}
