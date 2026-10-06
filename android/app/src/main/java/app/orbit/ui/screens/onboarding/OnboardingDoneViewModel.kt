package app.orbit.ui.screens.onboarding

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.AppPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Onboarding-done VM. Owns the single canonical write of
 * `AppPrefs.setOnboardingComplete(true)` — the looped-onboarding pain point
 * requires this be transactional and exactly-once.
 *
 * The write fires from `init` so just reaching the Done route flips the
 * flag. The screen renders a brief confirmation and surfaces an "Open Orbit"
 * CTA that's only enabled after the flag write completes
 * ([completed] flips true). If the user kills the app between the
 * route landing and the write completing, the next cold start re-enters
 * onboarding at Welcome: annoying but recoverable.
 *
 * The duplicate write that lived in OrbitNavHost's BulkAdd onContinue
 * handler is removed in the same commit that wires this screen.
 *
 * Also records that the notifications permission has been asked for
 * ([onNudgeLauncherFired], ONB-30): the Done screen is the one place
 * onboarding asks, and Settings relies on the per-permission "asked once"
 * flag to tell never-asked from turned-off (SET-12).
 */
@HiltViewModel
class OnboardingDoneViewModel @Inject constructor(
    private val appPrefs: AppPrefs
) : ViewModel() {

    private val _completed = MutableStateFlow(false)
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    init {
        viewModelScope.launch {
            appPrefs.setOnboardingComplete(true)
            // F-3 fix (2026-04-30 hot-fix-260430-hs4) — clear the resume key
            // on completion so a future re-onboarding (after Settings → Reset)
            // starts cleanly at Welcome rather than the last persisted step.
            appPrefs.setLastOnboardingStep(null)
            // The onboarding list is finished; a later re-onboarding must
            // start a new one, not reopen this (OnboardingListStarter).
            appPrefs.setOnboardingListId(null)
            _completed.value = true
        }
    }

    /**
     * The nudge ask's launcher resolved (granted or not): flip the
     * `POST_NOTIFICATIONS` "asked once" flag, the same flag the permission
     * screens flip through `OnboardingPermissionsViewModel.onLauncherFired`.
     * Before 2026-10-06 the Done screen asked without recording it, so a
     * decline here still read as "never asked" in Settings.
     */
    fun onNudgeLauncherFired() {
        viewModelScope.launch { appPrefs.setHasAsked(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
