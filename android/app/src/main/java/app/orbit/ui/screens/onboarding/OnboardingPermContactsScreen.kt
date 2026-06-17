package app.orbit.ui.screens.onboarding

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.ui.theme.OrbitTheme

/**
 * Onboarding step 1 (post-2026-04-28 reorder): rationale + system prompt
 * for `READ_CONTACTS`.
 *
 * Most-impactful permission — landed first so a half-bail still leaves Orbit
 * functional.
 *
 * Denial means lists can be created but contacts must be added later via
 * Settings → permission grant. Skip taps open OnboardingSkipDialog with
 * SkipPermission.Contacts before advancing.
 */
@Composable
fun OnboardingPermContactsScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    vm: OnboardingPermissionsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ready = state as? OnboardingPermissionsUiState.Ready
    val granted = ready?.hasContacts ?: false
    val hasBeenAsked = ready?.hasAskedContacts ?: false
    OnboardingPermScreen(
        step = OnboardingStep.PermContacts,
        permission = Manifest.permission.READ_CONTACTS,
        skipPermission = SkipPermission.Contacts,
        iconName = "users",
        title = "Build lists from your people",
        body = "Orbit reads your phone contacts so you can pick who goes on each list by name.",
        promiseTitle = "Stays on your device",
        promise = "We don't upload your address book.",
        deniedNote = "You can still create lists. To add contacts, allow access in your phone's settings.",
        granted = granted,
        hasBeenAsked = hasBeenAsked,
        onRefresh = vm::onRefresh,
        onLauncherFired = { vm.onLauncherFired(Manifest.permission.READ_CONTACTS) },
        onBack = onBack,
        onContinue = onContinue,
    )
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingPermContactsScreenPreview() {
    // Pitfall 4: previews cannot resolve hiltViewModel() — target the inner
    // stateless OnboardingPermScreen with this route's copy.
    OrbitTheme {
        OnboardingPermScreen(
            step = OnboardingStep.PermContacts,
            permission = Manifest.permission.READ_CONTACTS,
            skipPermission = SkipPermission.Contacts,
            iconName = "users",
            title = "Build lists from your people",
            body = "Orbit reads your phone contacts so you can pick who goes on each list by name.",
            promiseTitle = "Stays on your device",
            promise = "We don't upload your address book.",
            deniedNote = "You can still create lists. To add contacts, allow access in your phone's settings.",
            granted = false,
            hasBeenAsked = false,
            onRefresh = {},
            onLauncherFired = {},
            onBack = {},
            onContinue = {},
        )
    }
}
