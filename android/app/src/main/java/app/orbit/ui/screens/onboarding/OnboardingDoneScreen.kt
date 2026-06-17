package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * Onboarding-done confirmation. The `OnboardingDoneViewModel.init`
 * block has already fired the `setOnboardingComplete(true)` write by the
 * time this composes; the CTA is enabled once the write completes.
 *
 * No back button (returning to onboarding after completion is wrong UX
 * and would also let the user rerun the welcome flow on a "completed"
 * install). No step counter (Done is framing, not counted).
 */
@Composable
fun OnboardingDoneScreen(
    onFinish: () -> Unit,
    vm: OnboardingDoneViewModel = hiltViewModel(),
) {
    val completed by vm.completed.collectAsStateWithLifecycle()
    OnboardingDoneContent(
        completed = completed,
        onFinish = onFinish,
    )
}

/**
 * Stateless inner extracted so `@PreviewLightDark` +
 * `@PreviewFontScale` (D-06) can render without `hiltViewModel()` /
 * `collectAsStateWithLifecycle()` at preview time.
 */
@Composable
private fun OnboardingDoneContent(
    completed: Boolean,
    onFinish: () -> Unit,
) {
    OnboardingScaffold(
        step = null,
        onBack = null,
        primary = OnboardingAction(
            label = "Open Orbit",
            onClick = onFinish,
            enabled = completed,
        ),
    ) {
        Spacer(Modifier.height(OrbitTheme.spacing.x10))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(OrbitTheme.shapes.full)
                    .background(OrbitTheme.colors.positiveTint),
            ) {
                PhIcon(name = "check", size = 32.dp, tint = OrbitTheme.colors.positive)
            }
            Spacer(Modifier.height(OrbitTheme.spacing.x6))
            Text(
                text = "You're set up.",
                style = OrbitTheme.type.title.copy(color = OrbitTheme.colors.fg),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
            // Teach the core loop (one card, yes or no), not list-browsing.
            Text(
                text = "Orbit hands you one name at a time. Call, or pass — they'll come back around.",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x4),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x8))
            SwipeHint()
        }
    }
}

/**
 * One quiet teaching beat for the swipe mechanic: a mini stand-in card
 * flanked by the two swipe directions, with
 * the same labels Card View commits under ("Later" left, "Sooner" right).
 * Static, muted tones only — the "Open Orbit" CTA below keeps the screen's
 * single accent element.
 */
@Composable
private fun SwipeHint() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x5),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "Swipe left for later, swipe right for sooner."
        },
    ) {
        SwipeHintSide(icon = "arrow-left", label = "Later")
        // Mini card: a quiet surface with an avatar dot and a name-length
        // bar — just enough to read as "a person's card".
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            modifier = Modifier
                .clip(OrbitTheme.shapes.lg)
                .background(OrbitTheme.colors.surface)
                .border(1.dp, OrbitTheme.colors.lineSoft, OrbitTheme.shapes.lg)
                .padding(horizontal = OrbitTheme.spacing.x5, vertical = OrbitTheme.spacing.x4),
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(OrbitTheme.shapes.full)
                    .background(OrbitTheme.colors.bgSubtle),
            )
            Box(
                Modifier
                    .size(width = 40.dp, height = 6.dp)
                    .clip(OrbitTheme.shapes.full)
                    .background(OrbitTheme.colors.lineSoft),
            )
        }
        SwipeHintSide(icon = "arrow-right", label = "Sooner")
    }
}

@Composable
private fun SwipeHintSide(icon: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PhIcon(name = icon, size = 16.dp, tint = OrbitTheme.colors.fgMuted)
        Spacer(Modifier.height(OrbitTheme.spacing.x1))
        Text(
            text = label,
            style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgMuted),
        )
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingDoneContentPreview() {
    OrbitTheme {
        OnboardingDoneContent(
            completed = true,
            onFinish = {},
        )
    }
}
