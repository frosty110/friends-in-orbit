package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.theme.OrbitTheme

/**
 * Shared layout chrome for every onboarding screen.
 *
 * Captures the repeated structure (top app bar with back + step counter,
 * scrollable hero/body, sticky bottom CTA row) so individual screens stay
 * focused on their own copy and form. Hoists the back/skip/primary action
 * surface so adding a step doesn't require re-deriving the chrome.
 *
 * `title` is the screen's name for TalkBack: the app bar stays visually empty
 * (the step's heading is drawn large in the body), so the pane title goes on
 * the screen root instead of through [OrbitAppBar]. In one activity there is
 * no window change to announce a new screen otherwise; before 2026-10-06 no
 * onboarding screen set one, so a TalkBack user heard nothing on any of the
 * seven screen changes (rubric D8). Each body title also carries `heading()`
 * so the screen can be reached by heading navigation.
 *
 * `step = null` is allowed for Welcome and Done (no progress counter).
 *
 * `onBack = null` hides the arrow. A step that resumes after a relaunch is the
 * first entry on the back stack, and `popBackStack()` on a one-entry stack
 * leaves the NavHost with nothing to show; the nav graph passes null there, so
 * there is no control rather than a tap that empties the screen (rules.md
 * Code 3).
 *
 * `secondary` = optional skip-style ghost CTA above primary. Pain-point #1
 * "no dead-end" requires every step except Welcome and Done to expose a
 * reachable forward path even when the user doesn't fulfill the ask.
 *
 * `snackbarHostState` = where a step's snackbars show, over the bottom of the
 * content and just above the CTAs so Done stays reachable. A step that emits
 * snackbars must pass it: `showSnackbar` with no host on screen suspends
 * forever, and every later message queues behind it.
 *
 * `scrollable = false` lays the content out in the remaining height without a
 * scroll of its own. A message state ([app.orbit.ui.components.OrbitScreenMessage])
 * scrolls itself, and a vertical scroll nested in this slot's vertical scroll
 * is measured with infinite height and throws (the F-1 hazard in
 * OnboardingFirstListScreen's preview KDoc).
 */
@Composable
fun OnboardingScaffold(
    title: String,
    step: OnboardingStep?,
    onBack: (() -> Unit)?,
    primary: OnboardingAction,
    secondary: OnboardingAction? = null,
    snackbarHostState: SnackbarHostState? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    OrbitScreen(modifier = Modifier.semantics { paneTitle = title }) {
        OrbitAppBar(
            title = "",
            subtle = true,
            leading = if (onBack != null) {
                {
                    OrbitIconButton(
                        "arrow-left",
                        onBack,
                        contentDescription = stringResource(R.string.components_action_back),
                    )
                }
            } else {
                null
            },
            trailing = if (step != null) {
                { OnboardingProgress(step) }
            } else {
                null
            }
        )

        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(
                        horizontal = OrbitTheme.spacing.x5,
                        vertical = OrbitTheme.spacing.x2
                    )
                    .padding(bottom = OrbitTheme.spacing.x5),
                content = content
            )
            if (snackbarHostState != null) {
                OrbitSnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        OnboardingFooter(primary = primary, secondary = secondary)
    }
}

@Composable
private fun OnboardingFooter(primary: OnboardingAction, secondary: OnboardingAction?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(OrbitTheme.colors.bg)
            .padding(
                horizontal = OrbitTheme.spacing.x5,
                vertical = OrbitTheme.spacing.x3
            )
            .padding(bottom = OrbitTheme.spacing.x4)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (secondary != null) {
                OrbitButton(
                    text = secondary.label,
                    onClick = secondary.onClick,
                    variant = OrbitButtonVariant.Ghost,
                    enabled = secondary.enabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                OrbitButton(
                    text = primary.label,
                    onClick = primary.onClick,
                    enabled = primary.enabled,
                    height = 52.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

data class OnboardingAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true
)
