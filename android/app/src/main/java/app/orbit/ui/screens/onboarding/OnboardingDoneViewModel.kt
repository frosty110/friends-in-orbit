package app.orbit.ui.screens.onboarding

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.AppPrefs
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * A write that throws (DataStore failing) is not swallowed (rules.md Code 3):
 * the user reads "Couldn't save your change" with a Try again that runs the
 * same write again ([onRetryComplete]), and "Open Orbit" stays disabled until
 * a write lands. Before 2026-10-06 the failure left the button disabled for
 * ever with nothing said. The three writes are idempotent, so a retry after a
 * partial success is safe.
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

    /**
     * One-off messages for the screen's snackbar, as [UiText] (voice.md:
     * ViewModels hold no Context): a failed completion write (with a Try
     * again action) or a failed "asked once" write.
     */
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    init {
        completeOnboarding()
    }

    /**
     * The completion write. Runs once from `init`; runs again only from
     * [onRetryComplete] after a failure. Cancellation passes through
     * (rules.md Code 5); any other failure becomes the Try again snackbar.
     */
    private fun completeOnboarding() {
        viewModelScope.launch {
            try {
                appPrefs.setOnboardingComplete(true)
                // F-3 fix (2026-04-30 hot-fix-260430-hs4): clear the resume key
                // on completion so a future re-onboarding (after Settings > Reset)
                // starts cleanly at Welcome rather than the last persisted step.
                appPrefs.setLastOnboardingStep(null)
                // The onboarding list is finished; a later re-onboarding must
                // start a new one, not reopen this (OnboardingListStarter).
                appPrefs.setOnboardingListId(null)
                _completed.value = true
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _snackbarEvents.tryEmit(
                    SnackbarEvent(
                        message = UiText.res(R.string.components_snackbar_save_failed),
                        actionLabel = UiText.res(R.string.components_error_retry),
                    ),
                )
            }
        }
    }

    /** The failure snackbar's Try again: run the completion write again. */
    fun onRetryComplete() {
        if (_completed.value) return
        completeOnboarding()
    }

    /**
     * The nudge ask's launcher resolved (granted or not): flip the
     * `POST_NOTIFICATIONS` "asked once" flag, the same flag the permission
     * screens flip through `OnboardingPermissionsViewModel.onLauncherFired`.
     * Before 2026-10-06 the Done screen asked without recording it, so a
     * decline here still read as "never asked" in Settings.
     *
     * A failed write tells the user (rules.md Code 3) instead of leaving
     * Settings quietly saying "Not allowed" for a permission the phone will no
     * longer ask about; cancellation passes through (Code 5).
     */
    fun onNudgeLauncherFired() {
        viewModelScope.launch {
            try {
                appPrefs.setHasAsked(Manifest.permission.POST_NOTIFICATIONS)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _snackbarEvents.tryEmit(
                    SnackbarEvent(UiText.res(R.string.components_snackbar_save_failed)),
                )
            }
        }
    }
}
