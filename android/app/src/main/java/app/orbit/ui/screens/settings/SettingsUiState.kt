package app.orbit.ui.screens.settings

import androidx.compose.runtime.Immutable
import app.orbit.calllog.CallLogPermissionState
import app.orbit.data.PickerThresholds

/**
 * Settings screen state contract (ARCH-02). Carries the call-log fields
 * (CALL-01/CALL-02/CALL-04/CALL-06): `callLogPermissionState`,
 * `callLogImportDays`, `callLogSyncInFlight`.
 *
 * [Ready] carries `pickerThresholds` (PICK-07). Default value matches
 * `PickerThresholds.DEFAULT` so existing test fixtures (e.g. `Ready.INITIAL`,
 * `Ready(...)` constructions in `SettingsViewModelTest`) keep compiling without
 * per-call-site updates — a fixture-stability convention used throughout.
 * `lastCallLogSyncAtMs` carries epoch-millis of the last successful call-log
 * sync, sourced from `AppPrefs.lastCallLogSyncAt`. Sentinel `0L` means 'never
 * synced' — UI hides the line per D-08.
 *
 * `ignoredContactCount: Int = 0` drives the "{N} ignored" subtitle on the
 * Settings → Ignored entry row. Default-valued for fixture stability.
 *
 * SET-07/SET-08: there is no daily-digest-hour field, and two per-permission
 * status fields (`contactsPermissionState`, `notificationsPermissionState`)
 * back the Permissions section. Both default to `PermissionStatus.Denied` so
 * existing fixture call sites keep compiling unchanged. The Call log permission
 * stays on `CallLogPermissionState` for back-compat with
 * ContentObserverController.
 *
 * Sealed interface with 2 variants — every variant is `@Immutable` so Compose
 * can skip recomposition when the enclosing state reference is unchanged.
 *
 * Settings has no genuine "empty" state (the toggles, call-log fields, and
 * permission state all have default values from AppPrefs / OS) — Loading is only
 * observable synchronously before the first DataStore emission; Ready carries
 * everything the Settings screen renders.
 */
sealed interface SettingsUiState {
    @Immutable data object Loading : SettingsUiState

    @Immutable
    data class Ready(
        val callLogPermissionState: CallLogPermissionState,
        val callLogImportDays: Int,
        val callLogSyncInFlight: Boolean,
        val contactsPermissionState: PermissionStatus = PermissionStatus.Denied,
        val notificationsPermissionState: PermissionStatus = PermissionStatus.Denied,
        val lastCallLogSyncAtMs: Long = 0L,
        // Contacts-resync surface, parallel to the call-log fields above.
        // `contactsSyncInFlight` drives the spinner; `lastContactsSyncAtMs` is
        // epoch-millis of the last successful ingest (sentinel `0L` = never).
        val contactsSyncInFlight: Boolean = false,
        val lastContactsSyncAtMs: Long = 0L,
        val pickerThresholds: PickerThresholds = PickerThresholds.DEFAULT,
        // Drives Settings "Ignored" row subtitle ("{N} ignored" / "No ignored
        // contacts"). Default-valued so existing test fixtures keep compiling
        // unchanged.
        val ignoredContactCount: Int = 0,
    ) : SettingsUiState {
        companion object {
            /**
             * Cold-start default — only used by tests / preview composables; the live
             * VM always builds Ready from the AppPrefs combine.
             */
            val INITIAL = Ready(
                callLogPermissionState = CallLogPermissionState.Denied,
                callLogImportDays = 90,
                callLogSyncInFlight = false,
                contactsPermissionState = PermissionStatus.Denied,
                notificationsPermissionState = PermissionStatus.Denied,
                lastCallLogSyncAtMs = 0L,
                pickerThresholds = PickerThresholds.DEFAULT,
                ignoredContactCount = 0,
            )
        }
    }
}
