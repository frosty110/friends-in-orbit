package app.orbit.ui.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.ui.theme.OrbitTheme

/**
 * Onboarding step 3 (post-2026-04-28 reorder): rationale + system prompt
 * for `POST_NOTIFICATIONS` (Android 13+).
 *
 * On API ≤32 there's no runtime permission for notifications — the
 * implicit grant from manifest declaration suffices. We auto-skip in that
 * case so users on older devices don't see a meaningless screen.
 *
 * Denial means scheduled per-list nudges won't fire; the user can flip the
 * permission back on later. Skip taps open OnboardingSkipDialog
 * with SkipPermission.Notifications before advancing.
 */
@Composable
fun OnboardingPermNotificationsScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    vm: OnboardingPermissionsViewModel = hiltViewModel(),
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // Android 12 and below — no runtime permission. Skip cleanly.
        LaunchedEffect(Unit) { onContinue() }
        return
    }

    val state by vm.uiState.collectAsStateWithLifecycle()
    val ready = state as? OnboardingPermissionsUiState.Ready
    val granted = ready?.hasNotifications ?: false
    val hasBeenAsked = ready?.hasAskedNotifications ?: false
    OnboardingPermScreen(
        step = OnboardingStep.PermNotifications,
        permission = Manifest.permission.POST_NOTIFICATIONS,
        skipPermission = SkipPermission.Notifications,
        iconName = "bell",
        title = "Stay in the loop",
        body = "Orbit can let you know when it's time to reach out to someone.",
        promiseTitle = "Quiet by design",
        promise = "One quiet nudge per list per day. You can change this any time.",
        deniedNote = "You can still use Orbit. To get reminders, allow notifications in your phone's settings.",
        granted = granted,
        hasBeenAsked = hasBeenAsked,
        onRefresh = vm::onRefresh,
        onLauncherFired = { vm.onLauncherFired(Manifest.permission.POST_NOTIFICATIONS) },
        onBack = onBack,
        onContinue = onContinue,
    )
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingPermNotificationsScreenPreview() {
    OrbitTheme {
        OnboardingPermScreen(
            step = OnboardingStep.PermNotifications,
            permission = Manifest.permission.POST_NOTIFICATIONS,
            skipPermission = SkipPermission.Notifications,
            iconName = "bell",
            title = "Stay in the loop",
            body = "Orbit can let you know when it's time to reach out to someone.",
            promiseTitle = "Quiet by design",
            promise = "One quiet nudge per list per day. You can change this any time.",
            deniedNote = "You can still use Orbit. To get reminders, allow notifications in your phone's settings.",
            granted = false,
            hasBeenAsked = false,
            onRefresh = {},
            onLauncherFired = {},
            onBack = {},
            onContinue = {},
        )
    }
}
