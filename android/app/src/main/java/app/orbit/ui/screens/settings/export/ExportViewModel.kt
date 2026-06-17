package app.orbit.ui.screens.settings.export

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.domain.export.ExportService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SET-05 / EXPORT-01 — coordinates the SAF picker handoff with
 * the domain ExportService.
 *
 * Holds the user-entered passphrase as a `CharArray` between the sheet's
 * "Export" tap and the SAF result callback. Wipes the array after the
 * export call returns (success OR failure) so it never lives longer
 * than necessary.
 *
 * SAF launch is signalled as a SharedFlow event ([safLaunchRequests]);
 * SettingsScreen subscribes via `LaunchedEffect` and calls the existing
 * `rememberLauncherForActivityResult` launcher with the requested
 * default filename.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val exportService: ExportService,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private val _safLaunchRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val safLaunchRequests: SharedFlow<String> = _safLaunchRequests.asSharedFlow()

    private val _snackbarEvents = MutableSharedFlow<ExportSnackbar>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<ExportSnackbar> = _snackbarEvents.asSharedFlow()

    private var pendingPassphrase: CharArray? = null

    /**
     * Called by [ExportPassphraseSheet] after the user taps Export and the
     * fields validate. Stores the passphrase in [pendingPassphrase] and
     * emits a SAF launch request with a default filename.
     */
    fun onPassphraseSubmitted(passphrase: CharArray) {
        pendingPassphrase = passphrase
        val defaultName = "orbit-export-${java.time.Instant.now().epochSecond}.bin"
        viewModelScope.launch { _safLaunchRequests.emit(defaultName) }
    }

    /**
     * Called from the SettingsScreen's `rememberLauncherForActivityResult`
     * callback once the SAF picker resolves. Performs the export call,
     * surfaces a snackbar, and zeroes the cached passphrase.
     */
    fun onExportDestinationPicked(uri: Uri?) {
        val passphrase = pendingPassphrase
        pendingPassphrase = null

        if (uri == null) {
            // User cancelled the SAF picker — no error, no snackbar.
            passphrase?.fill(0.toChar())
            return
        }
        if (passphrase == null) {
            // Defensive: shouldn't happen — sheet must call onPassphraseSubmitted first.
            viewModelScope.launch { _snackbarEvents.emit(ExportSnackbar.Failure) }
            return
        }

        _uiState.value = ExportUiState.InFlight
        viewModelScope.launch {
            val result = runCatching { exportService.export(uri, passphrase) }
            passphrase.fill(0.toChar())             // wipe regardless of outcome
            _uiState.value = ExportUiState.Idle
            _snackbarEvents.emit(
                if (result.isSuccess) ExportSnackbar.Success else ExportSnackbar.Failure,
            )
        }
    }

    override fun onCleared() {
        pendingPassphrase?.fill(0.toChar())
        pendingPassphrase = null
        super.onCleared()
    }
}
