package app.orbit.ui.screens.onboarding

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.ui.theme.OrbitTheme

/**
 * Onboarding step 2 (post-2026-04-28 reorder): rationale + system prompt
 * for `READ_CALL_LOG`.
 *
 * `onBack` is null when this is the resumed start destination (nothing to pop
 * to); the nav graph decides from `previousBackStackEntry`.
 *
 * Denial degrades to manual log mode (per ONB-04). The user can still create
 * lists and add contacts; only auto-detection of recent calls is lost. Skip
 * taps open OnboardingSkipDialog with SkipPermission.CallLog before advancing.
 */
@Composable
fun OnboardingPermCallLogScreen(
    onBack: (() -> Unit)?,
    onContinue: () -> Unit,
    vm: OnboardingPermissionsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ready = state as? OnboardingPermissionsUiState.Ready
    val granted = ready?.hasCallLog ?: false
    val hasBeenAsked = ready?.hasAskedCallLog ?: false
    OnboardingPermScreen(
        step = OnboardingStep.PermCallLog,
        permission = Manifest.permission.READ_CALL_LOG,
        skipPermission = SkipPermission.CallLog,
        iconName = "phone-call",
        title = stringResource(R.string.onb_perm_call_log_title),
        body = stringResource(R.string.onb_perm_call_log_body),
        promiseTitle = stringResource(R.string.onb_perm_promise_on_device),
        promise = stringResource(R.string.onb_perm_call_log_promise),
        deniedNote = stringResource(R.string.onb_perm_call_log_denied),
        granted = granted,
        hasBeenAsked = hasBeenAsked,
        onRefresh = vm::onRefresh,
        onLauncherFired = { vm.onLauncherFired(Manifest.permission.READ_CALL_LOG) },
        onBack = onBack,
        onContinue = onContinue,
    )
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingPermCallLogScreenPreview() {
    OrbitTheme {
        OnboardingPermScreen(
            step = OnboardingStep.PermCallLog,
            permission = Manifest.permission.READ_CALL_LOG,
            skipPermission = SkipPermission.CallLog,
            iconName = "phone-call",
            title = stringResource(R.string.onb_perm_call_log_title),
            body = stringResource(R.string.onb_perm_call_log_body),
            promiseTitle = stringResource(R.string.onb_perm_promise_on_device),
            promise = stringResource(R.string.onb_perm_call_log_promise),
            deniedNote = stringResource(R.string.onb_perm_call_log_denied),
            granted = false,
            hasBeenAsked = false,
            onRefresh = {},
            onLauncherFired = {},
            onBack = {},
            onContinue = {},
        )
    }
}
