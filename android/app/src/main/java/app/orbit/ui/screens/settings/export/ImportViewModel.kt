package app.orbit.ui.screens.settings.export

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.di.ApplicationScope
import app.orbit.domain.export.ImportFormatException
import app.orbit.domain.export.ImportPayload
import app.orbit.domain.export.ImportService
import app.orbit.domain.export.ImportVersionTooNewException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates the SAF ACTION_OPEN_DOCUMENT handoff with the domain
 * [ImportService]. Mirrors [ExportViewModel]'s SharedFlow-launch pattern (the
 * screen owns the `rememberLauncherForActivityResult` launcher; the VM signals
 * when to fire it).
 *
 * Two-stage by design: [ImportService.read] validates the file BEFORE the
 * destructive confirmation dialog appears, so a wrong passphrase or a
 * corrupt file never gets as far as "replace everything?". The validated
 * payload is held in [pendingPayload] between validation and confirmation;
 * cancel or failure clears it.
 *
 * Every entry point checks the state it expects (SET-05): a second row tap
 * while a file is being checked, or a SAF result arriving after a cancel,
 * used to restart the flow underneath the running one. The screen also
 * disables the Data rows while [ImportUiState.Validating] or
 * [ImportUiState.Applying], so the guards are the second line.
 *
 * [onReplaceConfirmed] runs the apply on the application scope (rules.md
 * Code 6): it replaces every table in one transaction, and a back press on
 * `viewModelScope` mid-way would roll it back without telling the user.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importService: ImportService,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    /** One-shot requests to launch the SAF open-document picker. */
    private val _safOpenRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val safOpenRequests: SharedFlow<Unit> = _safOpenRequests.asSharedFlow()

    private val _snackbarEvents = MutableSharedFlow<ImportSnackbar>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<ImportSnackbar> = _snackbarEvents.asSharedFlow()

    private var pendingUri: Uri? = null
    private var pendingPayload: ImportPayload? = null

    /** "Import backup" row tap → fire the SAF picker. Only from Idle. */
    fun onImportRequested() {
        if (_uiState.value !is ImportUiState.Idle) return
        viewModelScope.launch { _safOpenRequests.emit(Unit) }
    }

    /**
     * SAF picker resolved. Null = user cancelled: quietly return to Idle.
     * Ignored unless Idle: a late result must not restart a flow already past
     * the picker.
     */
    fun onImportSourcePicked(uri: Uri?) {
        if (_uiState.value !is ImportUiState.Idle) return
        if (uri == null) {
            reset()
            return
        }
        pendingUri = uri
        _uiState.value = ImportUiState.AwaitingPassphrase
    }

    /**
     * Passphrase sheet submitted. Decrypt + validate off the main thread;
     * the passphrase array is wiped regardless of outcome. Only while
     * [ImportUiState.AwaitingPassphrase]: a repeat submit during Validating
     * would read the file twice and race the two results.
     */
    fun onPassphraseSubmitted(passphrase: CharArray) {
        if (_uiState.value !is ImportUiState.AwaitingPassphrase) {
            passphrase.fill(0.toChar())
            return
        }
        val uri = pendingUri
        if (uri == null) {
            // Defensive: the sheet is visible without a picked file.
            passphrase.fill(0.toChar())
            reset()
            return
        }
        _uiState.value = ImportUiState.Validating
        viewModelScope.launch {
            val result = runCatching { importService.read(uri, passphrase) }
            passphrase.fill(0.toChar())
            result.fold(
                onSuccess = { payload ->
                    pendingPayload = payload
                    _uiState.value = ImportUiState.AwaitingConfirm(
                        listCount = payload.lists.size,
                        contactCount = payload.contacts.size,
                    )
                },
                onFailure = { e ->
                    reset()
                    _snackbarEvents.emit(
                        when (e) {
                            is ImportVersionTooNewException -> ImportSnackbar.VersionTooNew
                            is ImportFormatException -> ImportSnackbar.Unreadable
                            else -> ImportSnackbar.Unreadable
                        },
                    )
                },
            )
        }
    }

    /** Destructive confirmation accepted: wipe-and-restore in one transaction. */
    fun onReplaceConfirmed() {
        val payload = pendingPayload
        if (payload == null) {
            reset()
            return
        }
        _uiState.value = ImportUiState.Applying
        appScope.launch {
            val result = runCatching { importService.apply(payload) }
            reset()
            _snackbarEvents.tryEmit(
                if (result.isSuccess) ImportSnackbar.Restored else ImportSnackbar.ApplyFailed,
            )
        }
    }

    /** Sheet or dialog dismissed: drop everything held in flight. */
    fun onCancelled() {
        reset()
    }

    private fun reset() {
        pendingUri = null
        pendingPayload = null
        _uiState.value = ImportUiState.Idle
    }
}
