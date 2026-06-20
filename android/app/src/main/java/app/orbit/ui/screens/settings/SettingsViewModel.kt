package app.orbit.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.orbit.calllog.CallLogPermissionState
import app.orbit.calllog.ContactsIngestWorker
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.PickerThresholds
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ResetService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings VM (ARCH-02 + ARCH-04), with the call-log sync surface (CALL-01 /
 * CALL-02 / CALL-04 / CALL-06):
 *
 * - [permissionState]: a `Flow<CallLogPermissionState>` driven by [refreshPermissionState]
 *   which the screen calls onResume and after the system permission dialog resolves.
 * - [onPermissionResult]: screen calls this after the permission launcher resolves;
 *   on `Granted`, enqueues full-resync work + persists the `isCallLogSyncEnabled` flag +
 *   defensively starts the observer (Pitfall 5 — observer registration
 *   requires READ_CALL_LOG, so deferred start on grant is mandatory).
 * - [onManualResync]: Settings "Sync now" button → full-resync work via the existing
 *   unique-work name on [ContentObserverController].
 * - [onImportDaysChanged]: write-through to [AppPrefs.setCallLogImportDays].
 * - Worker state observation: exposes `callLogSyncInFlight` = `WorkInfo.State` in
 *   `(ENQUEUED, RUNNING)` for the observer's unique work, driving the spinner UI.
 *
 * SET-07/SET-08: provides two per-permission status flows
 * for the Settings Permissions section: [contactsPermissionState] and
 * [notificationsPermissionState]. Both follow the same MutableStateFlow + compute
 * pattern as the existing [permissionState] for the call-log permission. The
 * [refreshAllPermissionStates] callback is invoked from the screen's
 * `Lifecycle.Event.ON_RESUME` observer with the three rationale flags. The
 * [onResetConfirmed] entry point delegates to the injected [ResetService] for the
 * destructive Reset Orbit confirmation.
 *
 * `stateIn(WhileSubscribed(5_000L))` keeps the upstream combine alive for 5 seconds
 * after the last subscriber detaches so rotation / dark-mode toggle don't recompute
 * the flow (ARCH-02 config-change survival). The `initialValue = Loading` is only
 * observable synchronously before DataStore emits — once the first read settles,
 * the flow is always `Ready`.
 *
 * Type-safety note: the multi-flow composition is implemented as staged
 * 4-arg `combine(...) { ... }` calls — one builds the [SettingsSnapshot]
 * (permissions + import window), one builds the [SyncStatus] (call-log +
 * contacts in-flight flags and last-synced timestamps), and the outer
 * `combine(snapshot, syncStatus, pickerThresholds, ignoredContactCount) { ... }`
 * stitches them. Every stage uses Kotlin's type-safe overloads (max arity 5),
 * avoiding the fragile `vararg` + `args[N] as T` unchecked-cast pattern.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPrefs: AppPrefs,
    private val contentObserverController: ContentObserverController,
    // Drives the Settings "Ignored" row subtitle.
    private val contactRepo: ContactRepository,
    // SET-06 — destructive Reset Orbit handler.
    private val resetService: ResetService,
) : ViewModel() {

    private val workManager: WorkManager = WorkManager.getInstance(context)

    private val _permissionState = MutableStateFlow(computeCurrentPermissionState())
    val permissionState: StateFlow<CallLogPermissionState> = _permissionState.asStateFlow()

    // SET-07 — per-permission status for the Settings
    // Permissions section. Contacts and Notifications use the simpler 3-state
    // [PermissionStatus] enum; the call log permission stays on
    // [CallLogPermissionState] for back-compat with the observer plumbing.
    private val _contactsPermissionState =
        MutableStateFlow(computeContactsPermissionState())
    val contactsPermissionState: StateFlow<PermissionStatus> =
        _contactsPermissionState.asStateFlow()

    private val _notificationsPermissionState =
        MutableStateFlow(computeNotificationsPermissionState())
    val notificationsPermissionState: StateFlow<PermissionStatus> =
        _notificationsPermissionState.asStateFlow()

    /**
     * Whether the call-log sync unique work is in `ENQUEUED` or `RUNNING` state.
     * Drives the spinner next to the resync button.
     */
    private val callLogSyncInFlight: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(ContentObserverController.UNIQUE_NAME_SYNC)
            .map { infos ->
                infos.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                }
            }

    /**
     * Whether the contacts-ingest unique work is in `ENQUEUED` or `RUNNING`
     * state. Drives the spinner next to the "Sync contacts" button — mirrors
     * [callLogSyncInFlight] for the call-log path.
     */
    private val contactsSyncInFlight: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(ContactsIngestWorker.UNIQUE_NAME)
            .map { infos ->
                infos.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                }
            }

    /** Last successful contacts ingest as epoch-millis; `0L` = never synced. */
    private val lastContactsSyncAtMs: Flow<Long> =
        appPrefs.lastContactsIngestedAt.map { it?.toEpochMilli() ?: 0L }

    /**
     * Intermediate 4-tuple of the permission + import-window flows — uses
     * Kotlin's type-safe `combine(a, b, c, d) { ... }` overload to avoid
     * `vararg` + unchecked casts. Folded into the outer `uiState` combine
     * alongside [syncStatus], thresholds, and the ignored count.
     */
    private data class SettingsSnapshot(
        val callLogPerm: CallLogPermissionState,
        val contactsPerm: PermissionStatus,
        val notifsPerm: PermissionStatus,
        val importDays: Int,
    )

    private val snapshot: Flow<SettingsSnapshot> =
        combine(
            permissionState,
            contactsPermissionState,
            notificationsPermissionState,
            appPrefs.callLogImportDays,
        ) { callLogPerm, contactsPerm, notifsPerm, importDays ->
            SettingsSnapshot(callLogPerm, contactsPerm, notifsPerm, importDays)
        }

    /**
     * The four sync-status signals (call-log + contacts, each an in-flight
     * flag and a last-synced timestamp) folded into one value so the outer
     * `uiState` combine stays within Kotlin's type-safe arity-5 ceiling
     * instead of reaching for the `vararg` + unchecked-cast overload.
     */
    private data class SyncStatus(
        val callLogInFlight: Boolean,
        val lastCallLogSyncAtMs: Long,
        val contactsInFlight: Boolean,
        val lastContactsSyncAtMs: Long,
    )

    private val syncStatus: Flow<SyncStatus> =
        combine(
            callLogSyncInFlight,
            appPrefs.lastCallLogSyncAt,
            contactsSyncInFlight,
            lastContactsSyncAtMs,
        ) { callLogInFlight, lastCallLog, contactsInFlight, lastContacts ->
            SyncStatus(callLogInFlight, lastCallLog, contactsInFlight, lastContacts)
        }

    /**
     * Count of currently-ignored contacts. Drives the
     * Settings "Ignored" row subtitle ("{N} ignored" / "No ignored contacts").
     * Note: this counts ALL `isIgnored = true` rows including any that are also
     * archived. The Settings → Ignored screen itself filters to !isArchived for
     * display, but the row subtitle prefers the simpler total — the screen
     * is the management surface, the count is informational. The two values
     * differ only when a contact is both ignored AND archived (rare).
     */
    private val ignoredContactCountFlow: Flow<Int> =
        contactRepo.observeIgnored().map { it.size }

    val uiState: StateFlow<SettingsUiState> =
        combine(
            snapshot,
            syncStatus,
            appPrefs.pickerThresholds,
            ignoredContactCountFlow,
        ) { snap, sync, thresholds, ignoredCount ->
            SettingsUiState.Ready(
                callLogPermissionState = snap.callLogPerm,
                callLogImportDays = snap.importDays,
                callLogSyncInFlight = sync.callLogInFlight,
                contactsPermissionState = snap.contactsPerm,
                notificationsPermissionState = snap.notifsPerm,
                pickerThresholds = thresholds,
                ignoredContactCount = ignoredCount,
                lastCallLogSyncAtMs = sync.lastCallLogSyncAtMs,
                contactsSyncInFlight = sync.contactsInFlight,
                lastContactsSyncAtMs = sync.lastContactsSyncAtMs,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = SettingsUiState.Loading,
        )

    /**
     * Called from [SettingsScreen] on resume + after permission dialog resolves.
     * Reads the current permission state from the OS (no caching; the OS is the
     * source of truth). The [rationalePending] parameter lets the screen pass the
     * result of `shouldShowRequestPermissionRationale` so we can distinguish
     * `Denied` (rationale allowed) from `PermanentlyDenied` (rationale blocked).
     *
     * If the OS-truth state transitioned `Granted → not-Granted` since the last
     * read (i.e. the user revoked permission via system Settings while the app was
     * backgrounded), runs the same cleanup as [onPermissionResult] would on a
     * direct revocation: clears `isCallLogSyncEnabled` and stops the observer.
     */
    fun refreshPermissionState(rationalePending: Boolean) {
        val prior = _permissionState.value
        val next = computeCurrentPermissionState(rationalePending)
        _permissionState.value = next
        if (prior is CallLogPermissionState.Granted &&
            next !is CallLogPermissionState.Granted
        ) {
            viewModelScope.launch { applyRevocationCleanup() }
        }
    }

    /**
     * SET-07 — refresh all three permission states from a
     * single call site (the screen's `Lifecycle.Event.ON_RESUME` observer). The
     * screen passes one rationale flag per permission; the call log path also
     * runs the revocation-cleanup branch via [refreshPermissionState].
     */
    fun refreshAllPermissionStates(
        callLogRationale: Boolean,
        contactsRationale: Boolean,
        notifsRationale: Boolean,
    ) {
        refreshPermissionState(callLogRationale)
        _contactsPermissionState.value = computeContactsPermissionState(contactsRationale)
        _notificationsPermissionState.value = computeNotificationsPermissionState(notifsRationale)
    }

    private fun computeCurrentPermissionState(
        rationalePending: Boolean = false,
    ): CallLogPermissionState {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG,
        ) == PackageManager.PERMISSION_GRANTED
        return when {
            granted -> CallLogPermissionState.Granted
            rationalePending -> CallLogPermissionState.Denied
            else -> CallLogPermissionState.PermanentlyDenied
        }
    }

    private fun computeContactsPermissionState(
        rationalePending: Boolean = false,
    ): PermissionStatus {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        return when {
            granted -> PermissionStatus.Granted
            rationalePending -> PermissionStatus.Denied
            else -> PermissionStatus.PermanentlyDenied
        }
    }

    private fun computeNotificationsPermissionState(
        rationalePending: Boolean = false,
    ): PermissionStatus {
        // POST_NOTIFICATIONS is API 33+. On API <33 it's an implicit grant;
        // returning Granted keeps the row visually consistent without a
        // permanent "Denied" label that the user can do nothing about.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return PermissionStatus.Granted
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return when {
            granted -> PermissionStatus.Granted
            rationalePending -> PermissionStatus.Denied
            else -> PermissionStatus.PermanentlyDenied
        }
    }

    /**
     * Invoked by the screen after the permission launcher resolves. On `Granted`:
     * flag sync as enabled, defensively start the observer (idempotent), and enqueue
     * a full initial import. On `Denied` / `PermanentlyDenied`: clear the
     * `isCallLogSyncEnabled` flag and stop the observer — keeping persisted state
     * honest about the OS truth.
     */
    fun onPermissionResult(state: CallLogPermissionState) {
        _permissionState.value = state
        when (state) {
            is CallLogPermissionState.Granted -> {
                viewModelScope.launch {
                    appPrefs.setCallLogSyncEnabled(true)
                    contentObserverController.start()
                    contentObserverController.enqueueImmediateSync(fullResync = true)
                }
            }
            is CallLogPermissionState.Denied,
            is CallLogPermissionState.PermanentlyDenied -> {
                viewModelScope.launch { applyRevocationCleanup() }
            }
        }
    }

    /**
     * Shared cleanup path for an observed `Granted → not-Granted` transition.
     * Idempotent — `setCallLogSyncEnabled(false)` and `stop()` are both no-ops if
     * already in that state.
     */
    private suspend fun applyRevocationCleanup() {
        appPrefs.setCallLogSyncEnabled(false)
        contentObserverController.stop()
    }

    /**
     * Settings "Sync now" button. Enqueues a full-resync work request via the
     * existing unique-work name — `ExistingWorkPolicy.REPLACE` collapses any pending
     * debounced sync in favour of this one. No-ops when permission is not granted.
     */
    fun onManualResync() {
        if (_permissionState.value !is CallLogPermissionState.Granted) return
        contentObserverController.enqueueImmediateSync(fullResync = true)
    }

    /**
     * Settings → Contacts "Sync contacts" button. Enqueues a forced, expedited
     * [ContactsIngestWorker] run via [ContentObserverController]. No-ops when
     * READ_CONTACTS is not granted (the worker reads an empty cursor and exits
     * cleanly, but gating here avoids waking WorkManager pointlessly).
     *
     * The ingest path is delta-sync / reconcile (insert + refresh + orphan),
     * never a destructive overwrite — see [ContentObserverController.enqueueImmediateContactsIngest].
     */
    fun onManualContactsResync() {
        if (_contactsPermissionState.value != PermissionStatus.Granted) return
        contentObserverController.enqueueImmediateContactsIngest()
    }

    /**
     * Import-window picker — 30 / 90 / 180 / 365 days. Writes through AppPrefs which
     * the next worker invocation reads.
     */
    fun onImportDaysChanged(days: Int) {
        viewModelScope.launch { appPrefs.setCallLogImportDays(days) }
    }

    /**
     * PICK-07 — commit all four picker thresholds in one shot.
     * Called from [PickerThresholdsDialog]'s Save button. Each setter applies its own
     * `coerceIn(min, max)` clamp at write time, so an out-of-bounds value from a future
     * UI bug cannot poison DataStore (T-07-30 mitigation).
     */
    fun onCommitThresholds(t: PickerThresholds) {
        viewModelScope.launch {
            appPrefs.setCommonlyCalledTopPct(t.commonlyTopPct)
            appPrefs.setRarelyCalledBottomPct(t.rarelyBottomPct)
            appPrefs.setRecentlyAddedDays(t.recentlyAddedDays)
            appPrefs.setLongGapDays(t.longGapDays)
        }
    }

    /**
     * One-shot signal that [ResetService.resetAll] finished.
     * The screen collects this and restarts the task (the onboarding flag
     * was just cleared, so the relaunched MainActivity resolves the
     * onboarding start destination) instead of leaving the user in a
     * ghost app with empty state.
     */
    private val _resetCompleteEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resetCompleteEvents: SharedFlow<Unit> = _resetCompleteEvents.asSharedFlow()

    /**
     * SET-06 — destructive Reset Orbit. Wired to [ResetConfirmDialog]'s confirm
     * button via the screen's `vm::onResetConfirmed` lambda.
     * [ResetService.resetAll] cancels the unique WorkManager jobs and stops the
     * content observers
     * before wiping Room + DataStore (per features/settings/README.md);
     * once it returns, [resetCompleteEvents] fires so the screen can
     * restart the task into onboarding.
     */
    fun onResetConfirmed() {
        viewModelScope.launch {
            resetService.resetAll()
            _resetCompleteEvents.emit(Unit)
        }
    }
}
