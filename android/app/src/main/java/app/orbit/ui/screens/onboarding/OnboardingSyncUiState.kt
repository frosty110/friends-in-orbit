package app.orbit.ui.screens.onboarding

import androidx.compose.runtime.Immutable

/**
 * ONB-16/17/18 — UI contract for the blocking call-log sync gate.
 *
 * The sealed [SyncState] inside [Ready] forks the visual:
 *   - [SyncState.InProgress]   → indeterminate progress + friendly count.
 *   - [SyncState.Empty]        → "We'll learn as you go." copy (ONB-17).
 *   - [SyncState.Succeeded]    → enables Continue.
 *   - [SyncState.Failed]       → inline retry (ONB-18); after [retryCount] >= 1
 *                                the screen flips to allow "Continue anyway".
 *   - [SyncState.Skipped]      → READ_CALL_LOG not granted; no sync to wait on.
 *                                Continue stays enabled so the advertised
 *                                "Continue without it" path never dead-ends.
 */
sealed interface OnboardingSyncUiState {
    @Immutable data object Loading : OnboardingSyncUiState

    @Immutable
    data class Ready(
        val syncState: SyncState,
        val callCount: Int,
        val contactCount: Int,
    ) : OnboardingSyncUiState
}

@Immutable
sealed interface SyncState {
    @Immutable data object InProgress : SyncState
    @Immutable data object Empty : SyncState
    @Immutable data object Succeeded : SyncState
    @Immutable data object Skipped : SyncState
    @Immutable data class Failed(val retryCount: Int) : SyncState
}
