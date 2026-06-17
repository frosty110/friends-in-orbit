package app.orbit.ui.screens.settings.export

import androidx.compose.runtime.Immutable

/**
 * UI contract for the backup-restore flow.
 *
 * State machine (driven by [ImportViewModel]):
 *
 *   Idle → (SAF file picked) → AwaitingPassphrase → (submit) → Validating
 *        → AwaitingConfirm → (confirm) → Applying → Idle (+ snackbar)
 *
 * Any failure or cancel collapses back to Idle with a one-shot snackbar.
 * The screen renders the passphrase sheet during [AwaitingPassphrase] and
 * the destructive confirmation dialog during [AwaitingConfirm].
 */
sealed interface ImportUiState {
    @Immutable data object Idle : ImportUiState
    @Immutable data object AwaitingPassphrase : ImportUiState
    @Immutable data object Validating : ImportUiState

    /** Envelope decrypted + validated; waiting on the replace-everything confirmation. */
    @Immutable
    data class AwaitingConfirm(
        val listCount: Int,
        val contactCount: Int,
    ) : ImportUiState

    @Immutable data object Applying : ImportUiState
}

/** One-shot snackbar feedback for the import flow. */
@Immutable
sealed interface ImportSnackbar {
    /** "Backup restored." */
    @Immutable data object Restored : ImportSnackbar

    /** Wrong passphrase / corrupt file / not an Orbit backup. */
    @Immutable data object Unreadable : ImportSnackbar

    /** Envelope written by a newer Orbit than this build. */
    @Immutable data object VersionTooNew : ImportSnackbar

    /** Validated fine but the transactional apply failed; nothing changed. */
    @Immutable data object ApplyFailed : ImportSnackbar
}
