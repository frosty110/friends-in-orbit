package app.orbit.ui.screens.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.R
import app.orbit.calllog.CallLogPermissionState
import app.orbit.data.PickerThresholds
import app.orbit.ui.components.ImportRangeChipGroup
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.components.PhIcon
import app.orbit.ui.screens.lists.SettingGroup
import app.orbit.ui.screens.settings.export.ExportPassphraseSheet
import app.orbit.ui.screens.settings.export.ExportSnackbar
import app.orbit.ui.screens.settings.export.ExportUiState
import app.orbit.ui.screens.settings.export.ExportViewModel
import app.orbit.ui.screens.settings.export.ImportPassphraseSheet
import app.orbit.ui.screens.settings.export.ImportSnackbar
import app.orbit.ui.screens.settings.export.ImportUiState
import app.orbit.ui.screens.settings.export.ImportViewModel
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.asString
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Settings screen (SET-04 / SET-06 / SET-07; SET-08 propagation) — the
 * post-2026-04-28 layout.
 *
 * Visible sections, top-down (features/settings/README.md, Behavior):
 *   1. **Appearance**: [AppearanceSection]: theme, light/dark, accent dial.
 *      First because it is the one section every user has a reason to open.
 *   2. **Permissions**: three [PermissionsRow]s (Contacts, Call log,
 *      Notifications). Granted rows show a quiet "Allowed"; Denied rows
 *      fire the runtime launcher; PermanentlyDenied rows open the phone's
 *      settings (SET-07, SET-12, SET-14).
 *   3. **Contacts**: [ContactsSyncRow].
 *   4. **Call history**: [CallSyncStatusRow] + [ImportRangeRow] + the
 *      Call history entry row: everything about what Orbit reads from the
 *      phone's call log.
 *   5. **People**: [PickerThresholdsRow] + the Ignored entry row: how Orbit
 *      groups the people it knows, and who it leaves out. These two used to
 *      sit under Call history, where "Groups when adding people" and
 *      "Ignored" read as sync settings.
 *   6. **Data**: Export your data row + Import backup row (SAF open →
 *      passphrase → validate → confirm-replace) + [ResetDataRow]
 *      (destructive; MainActivity acts on the outcome, restarting the task
 *      into onboarding or showing the failure, since the reset outlives
 *      this screen).
 *   plus **About**: [AboutSection] (version, the privacy promise, feedback
 *      mailto, links, licenses dialog).
 *
 * Removed in this rewrite: the disabled global-digest `ToggleRow`, the
 * standalone notifications-section block, the biometric / minimal-mode toggles
 * in the privacy block (those features were retired 2026-04-28 — see ADR 0003
 * supersession), and the in-file `CallLogPermissionRow` + `NavRow`
 * helpers (responsibilities split across the dedicated row composables).
 *
 * Two-layer composable — the outer [SettingsScreen] wires Hilt + lifecycle-
 * aware collection + the THREE runtime permission launchers (Contacts /
 * Call log / Notifications); the inner [SettingsContent] is stateless +
 * preview-friendly. The VM never imports Compose / Activity APIs — clean
 * separation per project architecture conventions: ViewModels never know about composables.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenIgnored: () -> Unit = {},
    onOpenCallHistory: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
    exportVm: ExportViewModel = hiltViewModel(),
    importVm: ImportViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val exportState by exportVm.uiState.collectAsStateWithLifecycle()
    val importState by importVm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // SET-05 / EXPORT-01 — encrypted-export bottom sheet.
    // Visibility hoisted at the screen level via rememberSaveable so a config
    // change mid-flow doesn't drop the user out of the form.
    var showExportSheet by rememberSaveable { mutableStateOf(false) }
    val exportSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Import passphrase sheet; visibility is VM-owned
    // (ImportUiState.AwaitingPassphrase) because the flow spans a SAF
    // round-trip that outlives composition.
    val importSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // SAF launcher — fires on Export tap, returns the user-chosen Uri (or null
    // on cancel). The launcher's `input: String` is the default filename
    // requested by the ExportViewModel ("orbit-export-{epochSec}.bin").
    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        exportVm.onExportDestinationPicked(uri)
    }

    // Subscribe to SAF launch requests from the VM (one-shot Strings carrying
    // the default filename). repeatOnLifecycle(STARTED) so a backgrounded
    // screen doesn't fire the picker.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            exportVm.safLaunchRequests.collect { defaultName ->
                createDocLauncher.launch(defaultName)
            }
        }
    }

    // SAF open-document launcher for "Import backup". The
    // mime filter is "*/*" because document providers report exported
    // `.bin` files inconsistently (octet-stream, x-binary, or nothing);
    // a strict filter would hide the user's own backup. Validation happens
    // on the bytes, not the extension.
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        importVm.onImportSourcePicked(uri)
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            importVm.safOpenRequests.collect {
                openDocLauncher.launch(arrayOf("*/*"))
            }
        }
    }

    // Snackbar host hoisted at the screen level so the success/failure
    // messages from ExportViewModel + ImportViewModel land here.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            exportVm.snackbarEvents.collect { event ->
                val message = when (event) {
                    ExportSnackbar.Success -> R.string.settings_export_saved
                    ExportSnackbar.Failure -> R.string.settings_export_failed
                }
                snackbarHostState.showSnackbar(context.getString(message))
            }
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            importVm.snackbarEvents.collect { event ->
                val message = when (event) {
                    ImportSnackbar.Restored -> R.string.settings_import_restored
                    ImportSnackbar.Unreadable -> R.string.settings_import_unreadable
                    ImportSnackbar.VersionTooNew -> R.string.settings_import_too_new
                    ImportSnackbar.ApplyFailed -> R.string.settings_import_failed
                }
                snackbarHostState.showSnackbar(context.getString(message))
            }
        }
    }
    // The Settings VM's own messages (today: a failed import-range write).
    // The reset's outcome is not collected here: the reset outlives this
    // screen, so MainActivity reads it from ResetService through AppViewModel
    // and restarts the task or shows the failure wherever the user is (SET-06).
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.snackbarEvents.collect { message ->
                snackbarHostState.showSnackbar(message.asString(context))
            }
        }
    }

    // SET-06: while the wipe runs, Back (the gesture and the app bar's arrow)
    // is held. Leaving is no longer harmful, since the restart lands from the
    // Activity, but a pop mid-wipe would show Home over half-cleared tables for
    // the moment before it; the Reset row reads "Resetting…" meanwhile, so the
    // held Back is not a silent short-circuit.
    val isResetting = (state as? SettingsUiState.Ready)?.isResetting == true
    BackHandler(enabled = isResetting) {}

    // SET-12: every launcher callback records that the OS was asked, before
    // the refresh, so "Off in your phone's settings" can only ever follow a
    // system dialog the user actually saw.
    val callLogPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        vm.onLauncherFired(Manifest.permission.READ_CALL_LOG)
        val activity = context as? Activity
        val resolved = when {
            granted -> CallLogPermissionState.Granted
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.READ_CALL_LOG,
            ) -> CallLogPermissionState.Denied
            else -> CallLogPermissionState.PermanentlyDenied
        }
        vm.onPermissionResult(resolved)
    }

    // Contacts + Notifications launchers. Both feed
    // refreshAllPermissionStates rather than VM-side dedicated handlers: the
    // refresh compares the prior and next readings and runs the grant side
    // effects itself (a Contacts grant registers the contacts observer and
    // runs one forced ingest, SET-07), so a grant from this row, from the
    // ON_RESUME observer below (the phone's settings) and from onboarding all
    // take the one path.
    val contactsRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        vm.onLauncherFired(Manifest.permission.READ_CONTACTS)
        val activity = context as? Activity
        val rationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.READ_CONTACTS,
            )
        vm.refreshAllPermissionStates(
            callLogRationale = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.READ_CALL_LOG,
                ),
            contactsRationale = rationale,
            notifsRationale = activity != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.POST_NOTIFICATIONS,
                ),
        )
    }

    // Notifications launcher (API 33+; below 33 the row is
    // always Granted so the request action never renders). Feeds the same
    // refreshAllPermissionStates path as the contacts launcher.
    val notificationsRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        vm.onLauncherFired(Manifest.permission.POST_NOTIFICATIONS)
        val activity = context as? Activity
        vm.refreshAllPermissionStates(
            callLogRationale = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.READ_CALL_LOG,
                ),
            contactsRationale = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.READ_CONTACTS,
                ),
            notifsRationale = activity != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.POST_NOTIFICATIONS,
                ),
        )
    }

    // Refresh permission state on every resume — the user may have toggled
    // any of the three permissions via system Settings while the app was
    // backgrounded, and the deep-link Open-Android-Settings CTA depends on
    // a post-resume refresh to surface the new state.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val activity = context as? Activity
                val callLogRationale = activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_CALL_LOG,
                    )
                val contactsRationale = activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_CONTACTS,
                    )
                val notifsRationale = activity != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.POST_NOTIFICATIONS,
                    )
                vm.refreshAllPermissionStates(
                    callLogRationale = callLogRationale,
                    contactsRationale = contactsRationale,
                    notifsRationale = notifsRationale,
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openAppSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        // FLAG_ACTIVITY_NEW_TASK so this works whether `context` is the
        // Activity or wrapped (Compose preview / fragment host).
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // SET-14: the Notifications row's "Open phone settings" lands on the
    // app's notification page, where the switch that reads "off" actually
    // is, not on the app details page the other two rows open.
    val openNotificationSettings: () -> Unit = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // Source-code link: the repository URL is the source-available home per
    // the PRD distribution constraint. (The privacy policy row lives in
    // AboutSection and opens the hosted policy, RELEASE-05.)
    val openSourceCode: () -> Unit = {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://github.com/frosty110/friends-in-orbit"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsContent(
            onRetry = vm::onRetry,
            state = state,
            exportState = exportState,
            importState = importState,
            onBack = { if (!isResetting) onBack() },
            onOpenIgnored = onOpenIgnored,
            onOpenCallHistory = onOpenCallHistory,
            onOpenAndroidSettings = openAppSettings,
            onOpenNotificationSettings = openNotificationSettings,
            onRequestContactsPermission = {
                contactsRequestLauncher.launch(Manifest.permission.READ_CONTACTS)
            },
            onRequestCallLogPermission = {
                callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
            },
            onRequestNotificationsPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationsRequestLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onManualResync = vm::onManualResync,
            onManualContactsResync = vm::onManualContactsResync,
            onImportDaysChanged = vm::onImportDaysChanged,
            onCommitThresholds = vm::onCommitThresholds,
            onExport = { showExportSheet = true },
            onImport = importVm::onImportRequested,
            onResetConfirmed = vm::onResetConfirmed,
            onSourceCode = openSourceCode,
            onSelectTheme = vm::onSelectTheme,
            onSelectDarkMode = vm::onSelectDarkMode,
            onAccentHue = vm::onAccentHue,
        )
        OrbitSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(OrbitTheme.spacing.x4),
        )
    }

    if (showExportSheet) {
        ExportPassphraseSheet(
            sheetState = exportSheetState,
            onSubmit = { passphrase ->
                exportVm.onPassphraseSubmitted(passphrase)
                scope.launch { exportSheetState.hide() }
                    .invokeOnCompletion { showExportSheet = false }
            },
            onDismiss = {
                scope.launch { exportSheetState.hide() }
                    .invokeOnCompletion { showExportSheet = false }
            },
        )
    }

    // Import flow surfaces, driven by the ImportViewModel
    // state machine (file picked → passphrase → validate → confirm → apply).
    if (importState is ImportUiState.AwaitingPassphrase) {
        ImportPassphraseSheet(
            sheetState = importSheetState,
            onSubmit = importVm::onPassphraseSubmitted,
            onDismiss = importVm::onCancelled,
        )
    }
    (importState as? ImportUiState.AwaitingConfirm)?.let { confirm ->
        ImportConfirmDialog(
            listCount = confirm.listCount,
            contactCount = confirm.contactCount,
            onConfirm = importVm::onReplaceConfirmed,
            onDismiss = importVm::onCancelled,
        )
    }
}

@Composable
internal fun SettingsContent(
    onRetry: () -> Unit = {},
    state: SettingsUiState,
    exportState: ExportUiState = ExportUiState.Idle,
    importState: ImportUiState = ImportUiState.Idle,
    onBack: () -> Unit,
    onOpenIgnored: () -> Unit,
    onOpenCallHistory: () -> Unit,
    onOpenAndroidSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit = onOpenAndroidSettings,
    onRequestContactsPermission: () -> Unit,
    onRequestCallLogPermission: () -> Unit,
    onRequestNotificationsPermission: () -> Unit,
    onManualResync: () -> Unit,
    onManualContactsResync: () -> Unit,
    onImportDaysChanged: (Int) -> Unit,
    onCommitThresholds: (PickerThresholds) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onResetConfirmed: () -> Unit,
    onSourceCode: () -> Unit,
    onSelectTheme: (app.orbit.ui.theme.OrbitThemeId) -> Unit,
    onSelectDarkMode: (app.orbit.ui.theme.OrbitDarkMode) -> Unit,
    onAccentHue: (Int?) -> Unit,
) {
    val ready = state as? SettingsUiState.Ready
    val callLogPermission = ready?.callLogPermissionState ?: CallLogPermissionState.Denied
    val contactsPermission = ready?.contactsPermissionState ?: PermissionStatus.Denied
    val notificationsPermission = ready?.notificationsPermissionState ?: PermissionStatus.Denied
    val callLogImportDays = ready?.callLogImportDays ?: 90
    val callLogSyncInFlight = ready?.callLogSyncInFlight ?: false
    val lastSyncedAtMs = ready?.lastCallLogSyncAtMs ?: 0L
    val contactsSyncInFlight = ready?.contactsSyncInFlight ?: false
    val lastContactsSyncAtMs = ready?.lastContactsSyncAtMs ?: 0L
    val now = ready?.now ?: Instant.EPOCH
    val pickerThresholds = ready?.pickerThresholds ?: PickerThresholds.DEFAULT
    val ignoredContactCount = ready?.ignoredContactCount ?: 0
    val colorTheme = ready?.colorTheme ?: app.orbit.ui.theme.OrbitThemeId.DEFAULT
    val darkMode = ready?.darkMode ?: app.orbit.ui.theme.OrbitDarkMode.DEFAULT
    val accentHue = ready?.accentHue
    val isResetting = ready?.isResetting ?: false

    // PICK-07: dialog visibility hoisted at the screen level so dismissals
    // route through onDismiss without unwinding parent state. Saveable, like
    // the reset dialog below: a rotation mid-edit used to close it and drop
    // the four edits (rubric G1, "no lost work"); the dialog's own values
    // are saveable too.
    var showThresholdsDialog by rememberSaveable { mutableStateOf(false) }
    // SET-06 — Reset Orbit confirmation dialog. Saveable
    // so a config change mid-confirmation doesn't drop the user out of the
    // dialog (an already-committed user shouldn't have to re-tap on rotate).
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    // SET-05 / SET-06: while a backup is being written, checked or restored,
    // or Orbit is being reset, the three Data rows wait. A second export
    // mid-write, or a reset mid-restore, would race the file or the tables.
    // The busy row's subtitle says what is happening and the other two say
    // they are waiting: a muted title alone, or a vanished chevron under an
    // unchanged line, left colour as the only signal (vision/ux-rubric.md D8).
    val exportInFlight = exportState is ExportUiState.InFlight
    val importBusy = importState is ImportUiState.Validating || importState is ImportUiState.Applying
    val dataRowsEnabled = !exportInFlight && !importBusy && !isResetting

    OrbitScreen {
        OrbitAppBar(
            title = stringResource(R.string.settings_title),
            leading = {
                OrbitIconButton("arrow-left", onBack, contentDescription = stringResource(R.string.components_action_back))
            },
        )

        // SET-09: nothing but the app bar until the saved settings load. The
        // defaults below (Warm, "Not allowed", 90 days) used to render first
        // and then jump to the real values, so a user on Plum saw Warm
        // selected for a moment (rubric D6). Same quiet-chrome policy as Home
        // (ADR 0006).
        if (state is SettingsUiState.Error) {
            // SET-11: say what happened and offer Try again.
            OrbitScreenMessage(
                icon = "warning-circle",
                title = stringResource(R.string.settings_error_title),
                body = stringResource(R.string.components_error_body),
                actionLabel = stringResource(R.string.components_error_retry),
                onAction = onRetry,
            )
            return@OrbitScreen
        }
        if (ready == null) return@OrbitScreen

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollContainer()
                .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x1)
                .padding(bottom = OrbitTheme.spacing.x7),
        ) {
            SettingGroup(title = stringResource(R.string.settings_section_appearance)) {
                AppearanceSection(
                    themeId = colorTheme,
                    darkMode = darkMode,
                    accentHue = accentHue,
                    onSelectTheme = onSelectTheme,
                    onSelectDarkMode = onSelectDarkMode,
                    onAccentHue = onAccentHue,
                )
            }

            SettingGroup(title = stringResource(R.string.settings_section_permissions)) {
                PermissionsRow(
                    label = stringResource(R.string.settings_perm_contacts),
                    status = contactsPermission,
                    onRequestPermission = onRequestContactsPermission,
                    onOpenAndroidSettings = onOpenAndroidSettings,
                )
                Divider()
                PermissionsRow(
                    label = stringResource(R.string.settings_perm_call_log),
                    status = callLogPermission.toPermissionStatus(),
                    onRequestPermission = onRequestCallLogPermission,
                    onOpenAndroidSettings = onOpenAndroidSettings,
                )
                Divider()
                PermissionsRow(
                    label = stringResource(R.string.settings_perm_notifications),
                    status = notificationsPermission,
                    onRequestPermission = onRequestNotificationsPermission,
                    onOpenAndroidSettings = onOpenNotificationSettings,
                )
            }

            SettingGroup(title = stringResource(R.string.settings_section_contacts)) {
                ContactsSyncRow(
                    lastSyncedAtMs = lastContactsSyncAtMs,
                    now = now,
                    inFlight = contactsSyncInFlight,
                    enabled = contactsPermission == PermissionStatus.Granted,
                    onSyncNow = onManualContactsResync,
                )
            }

            SettingGroup(title = stringResource(R.string.settings_section_call_history)) {
                CallSyncStatusRow(
                    lastSyncedAtMs = lastSyncedAtMs,
                    now = now,
                    inFlight = callLogSyncInFlight,
                    enabled = callLogPermission is CallLogPermissionState.Granted,
                    onSyncNow = onManualResync,
                )
                Divider()
                ImportRangeRow(
                    selectedDays = callLogImportDays,
                    onChange = onImportDaysChanged,
                )
                Divider()
                CallHistoryEntryRow(onClick = onOpenCallHistory)
            }

            SettingGroup(title = stringResource(R.string.settings_section_people)) {
                PickerThresholdsRow(onClick = { showThresholdsDialog = true })
                Divider()
                IgnoredEntryRow(count = ignoredContactCount, onClick = onOpenIgnored)
            }

            SettingGroup(title = stringResource(R.string.settings_section_data)) {
                ExportEntryRow(
                    onClick = onExport,
                    enabled = dataRowsEnabled,
                    subtitle = stringResource(
                        when {
                            exportInFlight -> R.string.settings_export_in_progress
                            importBusy || isResetting -> R.string.settings_data_wait
                            else -> R.string.settings_export_sub
                        },
                    ),
                )
                Divider()
                ImportEntryRow(
                    onClick = onImport,
                    enabled = dataRowsEnabled,
                    subtitle = stringResource(
                        when (importState) {
                            ImportUiState.Validating -> R.string.settings_import_checking
                            ImportUiState.Applying -> R.string.settings_import_in_progress
                            else -> when {
                                exportInFlight || isResetting -> R.string.settings_data_wait
                                else -> R.string.settings_import_sub
                            }
                        },
                    ),
                )
                Divider()
                ResetDataRow(
                    onClick = { showResetDialog = true },
                    enabled = dataRowsEnabled,
                    subtitle = stringResource(
                        when {
                            isResetting -> R.string.settings_reset_in_progress
                            !dataRowsEnabled -> R.string.settings_data_wait
                            else -> R.string.settings_reset_sub
                        },
                    ),
                )
            }

            SettingGroup(title = stringResource(R.string.settings_section_about)) {
                AboutSection(onSourceCode = onSourceCode)
            }
        }
    }

    if (showThresholdsDialog) {
        PickerThresholdsDialog(
            initial = pickerThresholds,
            onSave = { t ->
                onCommitThresholds(t)
                showThresholdsDialog = false
            },
            onDismiss = { showThresholdsDialog = false },
        )
    }

    if (showResetDialog) {
        ResetConfirmDialog(
            onConfirm = {
                showResetDialog = false
                onResetConfirmed()
            },
            onDismiss = { showResetDialog = false },
        )
    }
}

/**
 * Adapter from [CallLogPermissionState] to
 * the unified [PermissionStatus] used by [PermissionsRow]. The call-log
 * permission's underlying state class stays in place for back-compat with
 * the ContentObserverController plumbing; the row just needs the
 * shared 3-state shape.
 */
private fun CallLogPermissionState.toPermissionStatus(): PermissionStatus = when (this) {
    is CallLogPermissionState.Granted -> PermissionStatus.Granted
    is CallLogPermissionState.Denied -> PermissionStatus.Denied
    is CallLogPermissionState.PermanentlyDenied -> PermissionStatus.PermanentlyDenied
}

/**
 * "Import range": the title, "How far back to read", and the four windows as
 * [ImportRangeChipGroup], the radio group onboarding's sync step draws, so the
 * setting looks, reads and announces the same on both screens. Until
 * 2026-10-07 this row drew a Material FilterChip (checkbox semantics, an
 * accent-tint fill) over its own private copy of the options and labels.
 */
@Composable
private fun ImportRangeRow(
    selectedDays: Int,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 16/14 row padding matches every sibling row in this screen.
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY),
    ) {
        Text(stringResource(R.string.settings_import_range), style = OrbitTheme.type.body, color = OrbitTheme.colors.fg)
        Text(
            stringResource(R.string.settings_import_range_sub),
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            modifier = Modifier.padding(top = OrbitTheme.spacing.hair),
        )
        ImportRangeChipGroup(
            selectedDays = selectedDays,
            onSelect = onChange,
            modifier = Modifier.padding(top = OrbitTheme.spacing.x2),
        )
    }
}

/**
 * Data section "Export your data" entry. Tap routes through the [onClick]
 * callback which the bottom-sheet implementation hooks to the passphrase
 * form. [subtitle] is the default line or "Saving…" while the file is being
 * written, when [enabled] is false (SET-05).
 */
@Composable
private fun ExportEntryRow(onClick: () -> Unit, enabled: Boolean, subtitle: String) {
    DataEntryRow(
        title = stringResource(R.string.settings_export_title),
        subtitle = subtitle,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * Data section "Import backup" entry, the restore half of the export row
 * above it. Tap fires the SAF ACTION_OPEN_DOCUMENT picker via
 * [ImportViewModel.onImportRequested]. [subtitle] says "Checking the file…"
 * or "Restoring…" while the flow is busy, when [enabled] is false (SET-05).
 */
@Composable
private fun ImportEntryRow(onClick: () -> Unit, enabled: Boolean, subtitle: String) {
    DataEntryRow(
        title = stringResource(R.string.settings_import_title),
        subtitle = subtitle,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * The export and import rows' shared shape. Disabled, the title drops to
 * fgMuted and the chevron goes, so a waiting row does not look tappable.
 */
@Composable
private fun DataEntryRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = OrbitTheme.type.body.copy(
                    color = if (enabled) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted,
                ),
            )
            Text(
                text = subtitle,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = OrbitTheme.spacing.hair),
            )
        }
        if (enabled) {
            PhIcon(name = "caret-right", size = 16.dp, tint = OrbitTheme.colors.fgSubtle)
        }
    }
}

/**
 * Entry-point row for Settings → Ignored.
 *
 * Subtitle reads "{N} ignored" when [count] > 0, else "No one ignored":
 * sentence case, no exclamation, people not contacts (IGNORE-10 voice gate).
 * Leading icon is `eye-slash` (matches the empty-state icon on
 * SettingsIgnoredScreen).
 */
@Composable
private fun IgnoredEntryRow(count: Int, onClick: () -> Unit) {
    val subtitle = if (count > 0) {
        pluralStringResource(R.plurals.settings_ignored_count, count, count)
    } else {
        stringResource(R.string.settings_ignored_none)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY),
    ) {
        PhIcon(name = "eye-slash", size = 18.dp, tint = OrbitTheme.colors.fgMuted)
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_ignored_title),
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = subtitle,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = OrbitTheme.spacing.hair),
            )
        }
        PhIcon(name = "caret-right", size = 16.dp, tint = OrbitTheme.colors.fgSubtle)
    }
}

/**
 * Entry-point row for Settings → Call history (LOG-01).
 *
 * Leading icon `clock-counter-clockwise` matches the ContactDetail overflow
 * "View all calls" menu item (visual consistency across the two entry points).
 * Sentence case, no exclamation marks (voice gate).
 */
@Composable
private fun CallHistoryEntryRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY),
    ) {
        PhIcon(name = "clock-counter-clockwise", size = 18.dp, tint = OrbitTheme.colors.fgMuted)
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_call_history_title),
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = stringResource(R.string.settings_call_history_sub),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = OrbitTheme.spacing.hair),
            )
        }
        PhIcon(name = "caret-right", size = 16.dp, tint = OrbitTheme.colors.fgSubtle)
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(OrbitTheme.colors.lineSoft),
    )
}

/**
 * Convenience modifier wrapper for the verticalScroll + rememberScrollState
 * pair so the SettingsContent body stays readable.
 */
@Composable
private fun Modifier.verticalScrollContainer(): Modifier {
    val scrollState = rememberScrollState()
    return this.then(verticalScroll(scrollState))
}

// Previews of the stateless SettingsContent (THEME-04 / THEME-05), one per
// state so each renders in the screenshot gallery and its audits. The
// fresh-install state uses Ready.INITIAL ("Not allowed" rows, "No one
// ignored"); the second is a phone with everything allowed, a call-log sync
// running and a backup being written, so the spinner, "Saving…" and the
// waiting Data rows are rendered; the third is mid-reset ("Resetting…");
// then Loading (app bar only, SET-09) and Error (SET-11).
private val previewState: SettingsUiState = SettingsUiState.Ready.INITIAL

private val previewNow: Instant = Instant.parse("2026-10-06T10:00:00Z")

private val previewGrantedSyncing: SettingsUiState = SettingsUiState.Ready(
    callLogPermissionState = CallLogPermissionState.Granted,
    callLogImportDays = 365,
    callLogSyncInFlight = true,
    contactsPermissionState = PermissionStatus.Granted,
    notificationsPermissionState = PermissionStatus.Granted,
    lastCallLogSyncAtMs = previewNow.minusSeconds(5 * 60).toEpochMilli(),
    lastContactsSyncAtMs = previewNow.minusSeconds(3 * 3600).toEpochMilli(),
    now = previewNow,
    ignoredContactCount = 3,
)

@Composable
private fun SettingsContentPreviewHost(
    state: SettingsUiState,
    exportState: ExportUiState = ExportUiState.Idle,
) {
    OrbitTheme {
        SettingsContent(
            state = state,
            exportState = exportState,
            onBack = {},
            onOpenIgnored = {},
            onOpenCallHistory = {},
            onOpenAndroidSettings = {},
            onRequestContactsPermission = {},
            onRequestCallLogPermission = {},
            onRequestNotificationsPermission = {},
            onManualResync = {},
            onManualContactsResync = {},
            onImportDaysChanged = {},
            onCommitThresholds = {},
            onExport = {},
            onImport = {},
            onResetConfirmed = {},
            onSourceCode = {},
            onSelectTheme = {},
            onSelectDarkMode = {},
            onAccentHue = {},
        )
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun SettingsContentPreview() {
    SettingsContentPreviewHost(state = previewState)
}

@PreviewLightDark
@Composable
private fun SettingsContentGrantedSyncingPreview() {
    SettingsContentPreviewHost(state = previewGrantedSyncing, exportState = ExportUiState.InFlight)
}

@PreviewLightDark
@Composable
private fun SettingsContentResettingPreview() {
    SettingsContentPreviewHost(state = SettingsUiState.Ready.INITIAL.copy(isResetting = true))
}

@PreviewLightDark
@Composable
private fun SettingsContentLoadingPreview() {
    SettingsContentPreviewHost(state = SettingsUiState.Loading)
}

@PreviewLightDark
@Composable
private fun SettingsContentErrorPreview() {
    SettingsContentPreviewHost(state = SettingsUiState.Error)
}
