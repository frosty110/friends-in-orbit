package app.orbit.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.orbit.R
import app.orbit.calllog.CallLogPermissionState
import app.orbit.calllog.ContactsIngestWorker
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.PickerThresholds
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ResetService
import app.orbit.di.ApplicationScope
import app.orbit.domain.clock.Clock
import app.orbit.ui.theme.OrbitDarkMode
import app.orbit.ui.theme.OrbitThemeId
import app.orbit.ui.util.UiText
import app.orbit.widget.WidgetUpdateScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings VM (ARCH-02 + ARCH-04), with the call-log sync surface (CALL-01 /
 * CALL-02 / CALL-04 / CALL-06):
 *
 * - [permissionState]: a `Flow<CallLogPermissionState>` driven by [refreshPermissionState]
 *   which the screen calls onResume and after the system permission dialog resolves.
 * - [onPermissionResult]: screen calls this after the permission launcher resolves;
 *   on `Granted`, starts the observer and enqueues a full resync (Pitfall 5: observer
 *   registration requires READ_CALL_LOG, so deferred start on grant is mandatory).
 *   No stored "sync enabled" flag mirrors the permission any more: the OS is the
 *   only switch (ARCH-04), and the flag had no reader.
 * - [onManualResync]: Settings "Sync now" button → full-resync work via the existing
 *   unique-work name on [ContentObserverController].
 * - [onImportDaysChanged]: write-through to [AppPrefs.setCallLogImportDays]; widening
 *   the window also runs a full resync so the new months show up (SET-04).
 * - Worker state observation: exposes `callLogSyncInFlight` = `WorkInfo.State` in
 *   `(ENQUEUED, RUNNING)` for the observer's unique work, driving the spinner UI.
 *
 * SET-07/SET-08: provides two per-permission status flows
 * for the Settings Permissions section: [contactsPermissionState] and
 * [notificationsPermissionState]. Both follow the same MutableStateFlow + compute
 * pattern as the existing [permissionState] for the call-log permission. The
 * [refreshAllPermissionStates] callback is invoked from the screen's
 * `Lifecycle.Event.ON_RESUME` observer with the three rationale flags, and runs the
 * grant and revocation side effects for the observer-backed permissions in both
 * directions, so a permission flipped in the phone's settings behaves like one
 * granted from the row (SET-07). The [onResetConfirmed] entry point delegates to
 * the injected [ResetService] for the destructive Reset Orbit confirmation.
 *
 * SET-12: the OS cannot tell "never asked" from "asked and refused for good"
 * (`shouldShowRequestPermissionRationale` is false in both), so the raw state is
 * resolved against [AppPrefs.hasAskedFor]: a permission that was never requested
 * reads Denied (the row offers Allow), and "Off in your phone's settings" appears
 * only after the OS has been asked once. [onLauncherFired] records the ask.
 *
 * SET-14: Notifications read Granted only when the app's notifications are actually
 * enabled ([NotificationManagerCompat.areNotificationsEnabled]) and, on 33+, the
 * permission is held. On 31 and 32 there is no runtime permission, so "off" is
 * PermanentlyDenied and the row's action opens the app's notification settings.
 *
 * `stateIn(WhileSubscribed(5_000L))` keeps the upstream combine alive for 5 seconds
 * after the last subscriber detaches so rotation / dark-mode toggle don't recompute
 * the flow (ARCH-02 config-change survival). The `initialValue = Loading` is only
 * observable synchronously before DataStore emits; once the first read settles,
 * the flow is always `Ready` (or `Error`, SET-11).
 *
 * Type-safety note: the multi-flow composition is implemented as staged
 * `combine(...) { ... }` calls of at most five flows: one folds the raw OS
 * permission reads, one the "asked once" flags, one builds the [SettingsSnapshot],
 * one the [SyncStatus], and the outer combine stitches them with the thresholds,
 * the ignored count and the appearance. Every stage uses Kotlin's type-safe
 * overloads (max arity 5), avoiding the fragile `vararg` + `args[N] as T`
 * unchecked-cast pattern.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPrefs: AppPrefs,
    private val contentObserverController: ContentObserverController,
    // Drives the Settings "Ignored" row subtitle.
    private val contactRepo: ContactRepository,
    // SET-06: destructive Reset Orbit handler.
    private val resetService: ResetService,
    // "Last synced ..." is worded against this clock inside the state, never
    // read in composition.
    private val clock: Clock,
    // rules.md Code 6: the reset must finish even if the user leaves Settings
    // mid-way; `viewModelScope` would cancel it between the wipe and the
    // widget refresh.
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val workManager: WorkManager = WorkManager.getInstance(context)

    private val _permissionState = MutableStateFlow(computeCurrentPermissionState())
    val permissionState: StateFlow<CallLogPermissionState> = _permissionState.asStateFlow()

    // SET-07: per-permission status for the Settings
    // Permissions section. Contacts and Notifications use the simpler 3-state
    // [PermissionStatus] enum; the call log permission stays on
    // [CallLogPermissionState] for back-compat with the observer plumbing.
    // These hold the RAW OS reading; SET-12's "never asked" resolution happens
    // in [snapshot], where the stored flags are available.
    private val _contactsPermissionState =
        MutableStateFlow(computeContactsPermissionState())
    val contactsPermissionState: StateFlow<PermissionStatus> =
        _contactsPermissionState.asStateFlow()

    private val _notificationsPermissionState =
        MutableStateFlow(computeNotificationsPermissionState())
    val notificationsPermissionState: StateFlow<PermissionStatus> =
        _notificationsPermissionState.asStateFlow()

    // SET-14: whether Android will show this app's notifications at all. On
    // 33+ this is a second gate behind the permission (the user can switch an
    // app's notifications off in the phone's settings while the permission
    // stays granted); below 33 it is the only gate.
    private val _notificationsEnabled = MutableStateFlow(computeNotificationsEnabled())

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
     * state. Drives the spinner next to the "Sync contacts" button; mirrors
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

    /** The three raw OS readings plus the notifications switch, folded for the arity ceiling. */
    private data class RawPermissions(
        val callLog: CallLogPermissionState,
        val contacts: PermissionStatus,
        val notifications: PermissionStatus,
        val notificationsEnabled: Boolean,
    )

    private val rawPermissions: Flow<RawPermissions> =
        combine(
            permissionState,
            contactsPermissionState,
            notificationsPermissionState,
            _notificationsEnabled,
        ) { callLog, contacts, notifications, enabled ->
            RawPermissions(callLog, contacts, notifications, enabled)
        }

    /** SET-12: whether the OS has been asked for each permission at least once. */
    private data class AskedOnce(
        val contacts: Boolean,
        val callLog: Boolean,
        val notifications: Boolean,
    )

    private val askedOnce: Flow<AskedOnce> =
        combine(
            appPrefs.hasAskedContacts,
            appPrefs.hasAskedCallLog,
            appPrefs.hasAskedNotifications,
        ) { contacts, callLog, notifications ->
            AskedOnce(contacts, callLog, notifications)
        }

    /**
     * Intermediate tuple of the resolved permission states + import window.
     * Folded into the outer `uiState` combine alongside [syncStatus],
     * thresholds, and the ignored count.
     */
    private data class SettingsSnapshot(
        val callLogPerm: CallLogPermissionState,
        val contactsPerm: PermissionStatus,
        val notifsPerm: PermissionStatus,
        val importDays: Int,
    )

    private val snapshot: Flow<SettingsSnapshot> =
        combine(
            rawPermissions,
            askedOnce,
            appPrefs.callLogImportDays,
        ) { raw, asked, importDays ->
            SettingsSnapshot(
                callLogPerm = raw.callLog.resolveAskedOnce(asked.callLog),
                contactsPerm = raw.contacts.resolveAskedOnce(asked.contacts),
                notifsPerm = resolveNotifications(raw.notifications, raw.notificationsEnabled, asked.notifications),
                importDays = importDays,
            )
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
     * Settings "Ignored" row subtitle ("{N} ignored" / "No one ignored").
     * Reads the same `observeIgnored()` rows the Settings → Ignored screen
     * lists, which exclude archived contacts in the query itself. This count
     * used to include ignored-and-archived contacts, so "1 ignored" could open
     * onto an empty Ignored screen.
     */
    private val ignoredContactCountFlow: Flow<Int> =
        contactRepo.observeIgnored().map { it.size }

    /**
     * THEMING 2026-06-22: Appearance selection, mapped from the raw AppPrefs
     * primitives. Bundled into one flow so the outer `uiState` combine stays
     * within Kotlin's type-safe arity-5 ceiling.
     */
    private data class AppearanceState(
        val themeId: OrbitThemeId,
        val darkMode: OrbitDarkMode,
        val accentHue: Int?,
    )

    private val appearance: Flow<AppearanceState> =
        combine(
            appPrefs.colorTheme,
            appPrefs.darkMode,
            appPrefs.accentHue,
        ) { themeKey, darkKey, hue ->
            AppearanceState(
                themeId = OrbitThemeId.fromKey(themeKey),
                darkMode = OrbitDarkMode.fromKey(darkKey),
                accentHue = if (hue < 0) null else hue,
            )
        }

    // SET-11: bumped by [onRetry] to re-subscribe after a failure.
    private val retryCount = MutableStateFlow(0)

    /** The Error state's Try again. */
    fun onRetry() {
        retryCount.update { it + 1 }
    }

    // SET-06: true from the Reset confirmation until ResetService.resetAll
    // returns, whichever way. Screen-side it disables the Data rows and holds
    // Back; the outcome itself is MainActivity's to act on.
    private val _isResetting = MutableStateFlow(false)

    /** The saved settings and the OS readings, before the reset flag is stitched in. */
    private val readyState: Flow<SettingsUiState.Ready> =
        combine(
            snapshot,
            syncStatus,
            appPrefs.pickerThresholds,
            ignoredContactCountFlow,
            appearance,
        ) { snap, sync, thresholds, ignoredCount, appr ->
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
                // Taken when the state is built, so the screen never reads a
                // clock in composition. A sync finishing updates the
                // timestamp above, which rebuilds the state and the "now".
                now = clock.now(),
                colorTheme = appr.themeId,
                darkMode = appr.darkMode,
                accentHue = appr.accentHue,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SettingsUiState> = retryCount.flatMapLatest {
        // A second stage for the reset flag: the five-flow combine above is at
        // Kotlin's type-safe arity ceiling.
        combine(readyState, _isResetting) { ready, resetting ->
            ready.copy(isResetting = resetting)
        }.map<SettingsUiState.Ready, SettingsUiState> { it }.catch { t ->
            if (t is CancellationException) throw t
            emit(SettingsUiState.Error)
        }
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
     * Runs the side effects for a transition in either direction, so a flip made
     * in the phone's settings while Orbit was backgrounded behaves like one made
     * from the row: `Granted → not-Granted` stops the observer (the same cleanup
     * [onPermissionResult] runs on a direct refusal); `not-Granted → Granted`
     * starts it and imports the window, as a grant from the row would.
     */
    fun refreshPermissionState(rationalePending: Boolean) {
        val prior = _permissionState.value
        val next = computeCurrentPermissionState(rationalePending)
        _permissionState.value = next
        when {
            prior is CallLogPermissionState.Granted && next !is CallLogPermissionState.Granted ->
                contentObserverController.stop()
            prior !is CallLogPermissionState.Granted && next is CallLogPermissionState.Granted ->
                onCallLogGranted()
        }
    }

    /**
     * SET-07: refresh all three permission states from a
     * single call site (the screen's `Lifecycle.Event.ON_RESUME` observer and
     * the Contacts / Notifications launcher callbacks). The screen passes one
     * rationale flag per permission; the call log path runs its transitions via
     * [refreshPermissionState], and a Contacts `not-Granted → Granted` flip
     * registers the contacts observer and runs one forced ingest, exactly what
     * the onboarding grant does. Without this a permission granted from this
     * row, or in the phone's settings, left the address book unmirrored until
     * the next cold start.
     */
    fun refreshAllPermissionStates(
        callLogRationale: Boolean,
        contactsRationale: Boolean,
        notifsRationale: Boolean,
    ) {
        refreshPermissionState(callLogRationale)
        val priorContacts = _contactsPermissionState.value
        val nextContacts = computeContactsPermissionState(contactsRationale)
        _contactsPermissionState.value = nextContacts
        if (priorContacts != PermissionStatus.Granted && nextContacts == PermissionStatus.Granted) {
            contentObserverController.start()
            contentObserverController.enqueueImmediateContactsIngest()
        }
        _notificationsPermissionState.value = computeNotificationsPermissionState(notifsRationale)
        _notificationsEnabled.value = computeNotificationsEnabled()
    }

    /**
     * SET-12: the screen calls this from each `rememberLauncherForActivityResult`
     * callback, before it refreshes, so the OS has been asked exactly once before
     * a row can ever read "Off in your phone's settings". The write is a DataStore
     * flag; the resolved state follows through [askedOnce] when it lands.
     */
    fun onLauncherFired(permission: String) {
        viewModelScope.launch { appPrefs.setHasAsked(permission) }
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

    /**
     * The POST_NOTIFICATIONS permission alone. It is API 33+; below that it is an
     * implicit grant, and whether nudges can show is decided by
     * [computeNotificationsEnabled] (SET-14).
     */
    private fun computeNotificationsPermissionState(
        rationalePending: Boolean = false,
    ): PermissionStatus {
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

    private fun computeNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * SET-12: `shouldShowRequestPermissionRationale == false` means either "never
     * asked" or "refused for good". Until the OS has been asked once, the honest
     * row is Denied with an Allow button, because a launcher request WILL show the
     * system dialog. Granted and Denied pass through unchanged.
     */
    private fun PermissionStatus.resolveAskedOnce(asked: Boolean): PermissionStatus =
        if (this == PermissionStatus.PermanentlyDenied && !asked) PermissionStatus.Denied else this

    private fun CallLogPermissionState.resolveAskedOnce(asked: Boolean): CallLogPermissionState =
        if (this is CallLogPermissionState.PermanentlyDenied && !asked) CallLogPermissionState.Denied else this

    /**
     * SET-14: Granted only when Android will actually show the app's notifications.
     * With the permission held (always, below 33) but the app's notifications
     * switched off, the only way back on is the phone's settings, so the row reads
     * PermanentlyDenied and its action opens them; the "never asked" resolution
     * applies only when the permission itself is the thing missing, since a
     * launcher cannot flip the notifications switch.
     */
    private fun resolveNotifications(
        permission: PermissionStatus,
        enabled: Boolean,
        asked: Boolean,
    ): PermissionStatus = when {
        permission == PermissionStatus.Granted && enabled -> PermissionStatus.Granted
        permission == PermissionStatus.Granted -> PermissionStatus.PermanentlyDenied
        else -> permission.resolveAskedOnce(asked)
    }

    /**
     * Invoked by the screen after the call-log permission launcher resolves. On
     * `Granted`: defensively start the observer (idempotent) and enqueue a full
     * initial import. On `Denied` / `PermanentlyDenied`: stop the observer, the
     * same cleanup an observed revocation runs. Both run on the caller's thread
     * before this returns; there is no stored flag to write first.
     */
    fun onPermissionResult(state: CallLogPermissionState) {
        _permissionState.value = state
        when (state) {
            is CallLogPermissionState.Granted -> onCallLogGranted()
            is CallLogPermissionState.Denied,
            is CallLogPermissionState.PermanentlyDenied -> contentObserverController.stop()
        }
    }

    /**
     * Shared grant path for the call log, whether the grant came from the row's
     * launcher or was noticed on resume. Pitfall 5: the observer can only be
     * registered once READ_CALL_LOG is held, so `start()` is re-run here; the
     * full resync imports the configured window.
     */
    private fun onCallLogGranted() {
        contentObserverController.start()
        contentObserverController.enqueueImmediateSync(fullResync = true)
    }

    /**
     * Settings "Sync now" button. Enqueues a full-resync work request via the
     * existing unique-work name; `ExistingWorkPolicy.REPLACE` collapses any pending
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
     * never a destructive overwrite; see [ContentObserverController.enqueueImmediateContactsIngest].
     */
    fun onManualContactsResync() {
        if (_contactsPermissionState.value != PermissionStatus.Granted) return
        contentObserverController.enqueueImmediateContactsIngest()
    }

    /**
     * Import-window picker: 30 / 90 / 180 / 365 days. Writes through AppPrefs which
     * the next worker invocation reads.
     *
     * SET-04: widening the window (with the call log readable) also runs a full
     * resync right away. The incremental worker only reads forward from the last
     * sync, so without this a user who moved from 3 months to 1 year saw nothing
     * new until "Sync now". Narrowing changes nothing on screen (the log keeps
     * what it already has), so it stays a plain write.
     */
    fun onImportDaysChanged(days: Int) {
        viewModelScope.launch {
            val previous = appPrefs.callLogImportDays.first()
            appPrefs.setCallLogImportDays(days)
            if (days > previous && _permissionState.value is CallLogPermissionState.Granted) {
                contentObserverController.enqueueImmediateSync(fullResync = true)
            }
        }
    }

    /**
     * PICK-07: commit all four picker thresholds in one shot.
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
     * One-off messages for the screen's snackbar, as [UiText] (voice.md: ViewModels
     * hold no Context). Nothing emits here today. The reset's
     * failure is not here: it is reported through [ResetService.outcome], which
     * MainActivity shows wherever the user is, because this flow has no
     * collector once Settings is popped.
     */
    private val _snackbarEvents = MutableSharedFlow<UiText>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<UiText> = _snackbarEvents.asSharedFlow()

    /**
     * SET-06: destructive Reset Orbit. Wired to [ResetConfirmDialog]'s confirm
     * button via the screen's `vm::onResetConfirmed` lambda.
     * [ResetService.resetAll] cancels the unique WorkManager jobs and stops the
     * content observers before wiping Room + DataStore (per
     * features/settings/README.md) and records its outcome; MainActivity reads
     * that outcome and restarts the task into onboarding, or tells the user it
     * failed.
     *
     * Runs on the application scope (rules.md Code 6): a back press while the
     * tables were half cleared used to cancel the job mid-way and leave a wiped
     * database with the onboarding flag still set. This ViewModel only marks
     * the reset as in flight ([SettingsUiState.Ready.isResetting]) for as long
     * as it runs; `resetAll` throws nothing but cancellation, so there is no
     * failure to report from here.
     */
    fun onResetConfirmed() {
        _isResetting.value = true
        appScope.launch {
            try {
                resetService.resetAll()
            } finally {
                _isResetting.value = false
            }
        }
    }

    // ---- THEMING 2026-06-22: Appearance write-throughs. Each persists to
    // AppPrefs; AppViewModel's themeSettings collector retints the whole app. ----

    fun onSelectTheme(id: OrbitThemeId) {
        viewModelScope.launch {
            appPrefs.setColorTheme(id.key)
            WidgetUpdateScheduler.scheduleImmediate(context)
        }
    }

    fun onSelectDarkMode(mode: OrbitDarkMode) {
        viewModelScope.launch {
            appPrefs.setDarkMode(mode.key)
            WidgetUpdateScheduler.scheduleImmediate(context)
        }
    }

    /** Accent-dial commit; null clears the override back to the theme's accent. */
    fun onAccentHue(hue: Int?) {
        viewModelScope.launch {
            appPrefs.setAccentHue(hue)
            WidgetUpdateScheduler.scheduleImmediate(context)
        }
    }
}
