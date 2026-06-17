package app.orbit.calllog

import androidx.compose.runtime.Immutable

/**
 * Permission-state sealed class for CALL-01 / CALL-06.
 *
 * - [Granted]: READ_CALL_LOG is granted; ContentObserver + worker are live.
 * - [Denied]: not granted, but `shouldShowRequestPermissionRationale()` returned true
 *   — the user has declined once or has not yet been asked. Re-request is possible.
 * - [PermanentlyDenied]: `shouldShowRequestPermissionRationale()` returned false after
 *   a denial — "Don't ask again" was selected or system policy blocks future prompts.
 *   Recovery path is the system Settings deep-link (a minimal
 *   ACTION_APPLICATION_DETAILS_SETTINGS launcher in SettingsScreen).
 */
@Immutable
sealed class CallLogPermissionState {
    data object Granted : CallLogPermissionState()
    data object Denied : CallLogPermissionState()
    data object PermanentlyDenied : CallLogPermissionState()
}
