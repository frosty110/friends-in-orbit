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
 * `step = null` is allowed for Welcome and Done (no progress counter).
 *
 * `secondary` = optional skip-style ghost CTA above primary. Pain-point #1
 * "no dead-end" requires every step except Welcome and Done to expose a
 * reachable forward path even when the user doesn't fulfill the ask.
 *
 * `snackbarHostState` = where a step's snackbars show, over the bottom of the
 * content and just above the CTAs so Done stays reachable. A step that emits
 * snackbars must pass it: `showSnackbar` with no host on screen suspends
 * forever, and every later message queues behind it.
 */
@Composable
fun OnboardingScaffold(
    step: OnboardingStep?,
    onBack: (() -> Unit)?,
    primary: OnboardingAction,
    secondary: OnboardingAction? = null,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    OrbitScreen {
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
                    .verticalScroll(rememberScrollState())
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
