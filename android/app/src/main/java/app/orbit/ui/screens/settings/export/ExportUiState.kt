package app.orbit.ui.screens.settings.export

import androidx.compose.runtime.Immutable

/**
 * SET-05 — UI contract for the encrypted-export bottom sheet.
 *
 * The sheet is open / closed via Compose-side state on SettingsScreen
 * (rememberSaveable); this contract carries the in-flight + completion
 * cues only. SAF launch is a SharedFlow event (one-shot Uri request).
 */
sealed interface ExportUiState {
    @Immutable data object Idle : ExportUiState
    @Immutable data object InFlight : ExportUiState
}

/** Snackbar feedback events emitted post-export. */
@Immutable
sealed interface ExportSnackbar {
    @Immutable data object Success : ExportSnackbar
    @Immutable data object Failure : ExportSnackbar
}
