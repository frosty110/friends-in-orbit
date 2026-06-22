package app.orbit.ui.screens.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import app.orbit.calllog.CallLogPermissionState
import app.orbit.data.PickerThresholds
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.PhIcon
import app.orbit.ui.screens.lists.SettingGroup
import app.orbit.ui.screens.settings.export.ExportPassphraseSheet
import app.orbit.ui.screens.settings.export.ExportSnackbar
import app.orbit.ui.screens.settings.export.ExportViewModel
import app.orbit.ui.screens.settings.export.ImportPassphraseSheet
import app.orbit.ui.screens.settings.export.ImportSnackbar
import app.orbit.ui.screens.settings.export.ImportUiState
import app.orbit.ui.screens.settings.export.ImportViewModel
import app.orbit.ui.theme.OrbitTheme
import kotlinx.coroutines.launch

/**
 * Settings screen (SET-04 / SET-06 / SET-07; SET-08 propagation) — the
 * post-2026-04-28 layout.
 *
 * Visible sections, top-down:
 *   1. **Permissions** — three [PermissionsRow]s (Contacts, Call log,
 *      Notifications). Granted rows show a quiet "Allowed"; Denied rows
 *      fire the runtime launcher; PermanentlyDenied rows deep-link to
 *      Android Settings.
 *   2. **Call history** — [CallSyncStatusRow] + [ImportRangeRow] +
 *      [PickerThresholdsRow] + Ignored entry row + Call history entry row.
 *   3. **Data** — Export my data row + Import backup row (SAF open →
 *      passphrase → validate → confirm-replace) + [ResetDataRow]
 *      (destructive; on completion the task restarts into onboarding).
 *   4. **About** — [AboutSection] (version, feedback mailto, links,
 *      licenses dialog).
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
                    ExportSnackbar.Success -> "Saved your encrypted backup."
                    ExportSnackbar.Failure -> "Couldn't save the file. Try again?"
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            importVm.snackbarEvents.collect { event ->
                val message = when (event) {
                    ImportSnackbar.Restored -> "Backup restored."
                    ImportSnackbar.Unreadable ->
                        "That file couldn't be read. Check it's an Orbit backup."
                    ImportSnackbar.VersionTooNew ->
                        "This backup was made by a newer version of Orbit. Update Orbit first."
                    ImportSnackbar.ApplyFailed ->
                        "Couldn't restore the backup. Nothing was changed."
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    // Once ResetService finishes (works cancelled, observers
    // stopped, Room + DataStore wiped) the user must land somewhere honest.
    // Restarting the task is the simplest reliable mechanism: the relaunched
    // MainActivity re-resolves its start destination from the now-cleared
    // onboarding flag and lands on the welcome screen. An in-place
    // nav.navigate would leave stale back-stack entries and ViewModels
    // holding pre-reset state; Activity.recreate() keeps the nav back stack.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.resetCompleteEvents.collect {
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                    context.startActivity(launchIntent)
                }
                (context as? Activity)?.finish()
            }
        }
    }

    val callLogPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
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
    // refreshAllPermissionStates rather than VM-side dedicated handlers
    // because (a) neither enables any side-effect on grant
    // (no observer bind, no cleanup branch — that's call-log-only), and
    // (b) the ON_RESUME observer below already runs the same refresh path.
    val contactsRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
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

    // Source-code link. No published privacy-policy URL exists
    // in the repo yet (that row reads "Coming soon"); the repository URL is
    // the source-available home per the PRD distribution constraint.
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
            state = state,
            onBack = onBack,
            onOpenIgnored = onOpenIgnored,
            onOpenCallHistory = onOpenCallHistory,
            onOpenAndroidSettings = openAppSettings,
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
        SnackbarHost(
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
private fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenIgnored: () -> Unit,
    onOpenCallHistory: () -> Unit,
    onOpenAndroidSettings: () -> Unit,
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
    val pickerThresholds = ready?.pickerThresholds ?: PickerThresholds.DEFAULT
    val ignoredContactCount = ready?.ignoredContactCount ?: 0
    val colorTheme = ready?.colorTheme ?: app.orbit.ui.theme.OrbitThemeId.DEFAULT
    val darkMode = ready?.darkMode ?: app.orbit.ui.theme.OrbitDarkMode.DEFAULT
    val accentHue = ready?.accentHue

    // PICK-07 — dialog visibility hoisted at the screen
    // level so dismissals route through onDismiss without unwinding parent state.
    var showThresholdsDialog by remember { mutableStateOf(false) }
    // SET-06 — Reset Orbit confirmation dialog. Saveable
    // so a config change mid-confirmation doesn't drop the user out of the
    // dialog (an already-committed user shouldn't have to re-tap on rotate).
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    OrbitScreen {
        OrbitAppBar(
            title = "Settings",
            leading = { OrbitIconButton("arrow-left", onBack, contentDescription = "Back") },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollContainer()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .padding(bottom = 32.dp),
        ) {
            SettingGroup(title = "Appearance") {
                AppearanceSection(
                    themeId = colorTheme,
                    darkMode = darkMode,
                    accentHue = accentHue,
                    onSelectTheme = onSelectTheme,
                    onSelectDarkMode = onSelectDarkMode,
                    onAccentHue = onAccentHue,
                )
            }

            SettingGroup(title = "Permissions") {
                PermissionsRow(
                    label = "Contacts",
                    status = contactsPermission,
                    onRequestPermission = onRequestContactsPermission,
                    onOpenAndroidSettings = onOpenAndroidSettings,
                )
                Divider()
                PermissionsRow(
                    label = "Call log",
                    status = callLogPermission.toPermissionStatus(),
                    onRequestPermission = onRequestCallLogPermission,
                    onOpenAndroidSettings = onOpenAndroidSettings,
                )
                Divider()
                PermissionsRow(
                    label = "Notifications",
                    status = notificationsPermission,
                    onRequestPermission = onRequestNotificationsPermission,
                    onOpenAndroidSettings = onOpenAndroidSettings,
                )
            }

            SettingGroup(title = "Contacts") {
                ContactsSyncRow(
                    lastSyncedAtMs = lastContactsSyncAtMs,
                    inFlight = contactsSyncInFlight,
                    enabled = contactsPermission == PermissionStatus.Granted,
                    onSyncNow = onManualContactsResync,
                )
            }

            SettingGroup(title = "Call history") {
                CallSyncStatusRow(
                    lastSyncedAtMs = lastSyncedAtMs,
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
                PickerThresholdsRow(onClick = { showThresholdsDialog = true })
                Divider()
                IgnoredEntryRow(count = ignoredContactCount, onClick = onOpenIgnored)
                Divider()
                CallHistoryEntryRow(onClick = onOpenCallHistory)
            }

            SettingGroup(title = "Data") {
                ExportEntryRow(onClick = onExport)
                Divider()
                ImportEntryRow(onClick = onImport)
                Divider()
                ResetDataRow(onClick = { showResetDialog = true })
            }

            SettingGroup(title = "About") {
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
 * The day options were raw M3 FilledTonal/Outlined buttons
 * (M3 default palette, off-token). Restyled on the picker FilterChipsRow
 * idiom: accentTint selected container, fg label, 48dp tap floor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportRangeRow(
    selectedDays: Int,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 16/14 row padding matches every sibling row in this screen.
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text("Import range", style = OrbitTheme.type.body, color = OrbitTheme.colors.fg)
        Text(
            "How far back to read",
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.padding(top = OrbitTheme.spacing.x2),
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        ) {
            IMPORT_DAY_OPTIONS.forEach { days ->
                FilterChip(
                    selected = selectedDays == days,
                    onClick = { onChange(days) },
                    label = { Text("${days}d") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OrbitTheme.colors.accentTint,
                        selectedLabelColor = OrbitTheme.colors.fg,
                    ),
                    modifier = Modifier.defaultMinSize(minHeight = OrbitTheme.spacing.tapMin),
                )
            }
        }
    }
}

private val IMPORT_DAY_OPTIONS: List<Int> = listOf(30, 90, 180, 365)

/**
 * Data section "Export my data" entry. Tap routes
 * through the [onClick] callback which the bottom-sheet
 * implementation hooks to the passphrase form. The default no-op callback in
 * [SettingsScreen] keeps the row visible for screenshot-based design
 * review without dispatching an actual export.
 */
@Composable
private fun ExportEntryRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Export my data",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = "Encrypted JSON, password protected",
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        PhIcon(name = "caret-right", size = 16.dp, tint = OrbitTheme.colors.fgSubtle)
    }
}

/**
 * Data section "Import backup" entry, the restore half of
 * the export row above it. Tap fires the SAF ACTION_OPEN_DOCUMENT picker
 * via [ImportViewModel.onImportRequested].
 */
@Composable
private fun ImportEntryRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Import backup",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = "Replace what's here with an exported file",
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        PhIcon(name = "caret-right", size = 16.dp, tint = OrbitTheme.colors.fgSubtle)
    }
}

/**
 * Entry-point row for Settings → Ignored.
 *
 * Subtitle reads "{N} ignored" when [count] > 0, else "No ignored contacts" —
 * sentence case, no exclamation (IGNORE-10 voice gate). Leading icon is
 * `eye-slash` (matches the empty-state icon on SettingsIgnoredScreen).
 */
@Composable
private fun IgnoredEntryRow(count: Int, onClick: () -> Unit) {
    val subtitle = if (count > 0) "$count ignored" else "No ignored contacts"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        PhIcon(name = "eye-slash", size = 18.dp, tint = OrbitTheme.colors.fgMuted)
        Column(Modifier.weight(1f)) {
            Text(
                text = "Ignored",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = subtitle,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = 2.dp),
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        PhIcon(name = "clock-counter-clockwise", size = 18.dp, tint = OrbitTheme.colors.fgMuted)
        Column(Modifier.weight(1f)) {
            Text(
                text = "Call history",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = "Every call to people on your lists",
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = 2.dp),
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

// Preview fixture for the stateless SettingsContent
// (THEME-04 / THEME-05 — D-06). Uses Ready.INITIAL so the empty-state copy
// renders ("No ignored contacts" subtitle, etc.).
private val previewState: SettingsUiState = SettingsUiState.Ready.INITIAL

@PreviewLightDark
@PreviewFontScale
@Composable
private fun SettingsContentPreview() {
    OrbitTheme {
        SettingsContent(
            state = previewState,
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
