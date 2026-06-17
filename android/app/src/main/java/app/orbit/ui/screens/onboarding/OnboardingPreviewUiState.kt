package app.orbit.ui.screens.onboarding

import androidx.compose.runtime.Immutable

/**
 * ONB-19 — preview UI contract.
 *
 * `Ready.candidates` is empty when fewer than 3 contacts match the
 * recency × frequency formula — the screen LaunchedEffect routes to
 * the manual first-list path in that case (no error surface).
 *
 * Each candidate row renders the contact's name and a meta line (last-call
 * relative time, e.g. "Called 4 days ago"). The screen primary CTA
 * "Make this my first list" passes the candidate IDs forward to
 * OrbitNavHost which triggers the list creation.
 */
sealed interface OnboardingPreviewUiState {
    @Immutable data object Loading : OnboardingPreviewUiState

    @Immutable
    data class Ready(
        val candidates: List<PreviewCandidate>,
        val defaultName: String = "In touch",
    ) : OnboardingPreviewUiState
}

@Immutable
data class PreviewCandidate(
    val contactId: Long,
    val displayName: String,
    val lastCallRelative: String, // pre-formatted by VM (DateUtils relative-time)
)
