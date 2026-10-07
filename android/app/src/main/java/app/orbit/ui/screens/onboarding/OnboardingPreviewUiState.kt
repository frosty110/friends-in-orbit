package app.orbit.ui.screens.onboarding

import androidx.compose.runtime.Immutable
import app.orbit.R
import app.orbit.ui.util.UiText

/**
 * ONB-19: preview UI contract.
 *
 * `Ready.candidates` is empty when fewer than 3 contacts match the
 * recency x frequency formula; the screen LaunchedEffect routes to
 * the manual first-list path in that case (no error surface).
 *
 * [Error] is a failed read (Room threw): the screen says so and offers Try
 * again, with "Start blank" still live as the way out. Before 2026-10-06 the
 * pipeline had no catch, so a failing read crashed the first run.
 *
 * Each candidate row renders the contact's name and a meta line (last-call
 * relative time, e.g. "Called 4 days ago"). The screen primary CTA
 * "Make this my first list" passes the candidate IDs forward to
 * OrbitNavHost which triggers the list creation.
 */
sealed interface OnboardingPreviewUiState {
    @Immutable data object Loading : OnboardingPreviewUiState

    @Immutable data object Error : OnboardingPreviewUiState

    @Immutable
    data class Ready(
        val candidates: List<PreviewCandidate>,
        // The suggested list's starting name, resolved when the user accepts.
        val defaultName: UiText = UiText.res(R.string.onb_preview_default_name),
    ) : OnboardingPreviewUiState
}

@Immutable
data class PreviewCandidate(
    val contactId: Long,
    val displayName: String,
    // "Called {when}" built by the VM; {when} is formatRelative's "4 days ago".
    val lastCallRelative: UiText,
)
