package app.orbit.ui.screens.contact

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.R
import app.orbit.data.CallDirection
import app.orbit.data.CallEntry
import app.orbit.data.ChipTone
import app.orbit.data.Contact
import app.orbit.data.NoteRow
import app.orbit.domain.model.PauseDuration
import app.orbit.domain.rule.RuleParams
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.ContactStatsPanel
import app.orbit.ui.components.InfoTip
import app.orbit.ui.components.ListContextChip
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitInlineNotice
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.components.PauseDurationSheet
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.components.StatEntry
import app.orbit.ui.screens.contact.sections.LogConnectionSheet
import app.orbit.ui.screens.contact.sections.NotesSection
import app.orbit.ui.screens.contact.sections.RuleOverrideSection
import app.orbit.ui.screens.contact.sections.UnpauseBanner
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.dialPhoneNumber
import app.orbit.ui.util.formatDuration
import app.orbit.ui.util.openPhoneContact
import kotlinx.coroutines.delay

/**
 * Contact Detail — read-only information surface for
 * CONTACT-01 / CONTACT-02 / CONTACT-06.
 *
 * Renders: Coil-backed photo with Avatar fallback, hero name (32sp) + phone
 * number, "On these lists" chip row, 5-row stats panel (Last call / Total
 * calls / Average length / Longest gap / Usually), recent calls list (50/page).
 *
 * Hero action row: "Call" (Primary — the screen's one terracotta element;
 * ACTION_DIAL via the shared Dialer util) and "Log a connection" (Secondary —
 * opens [LogConnectionSheet] to record a connection the carrier call log
 * can't see). The phone-number row is also tappable to dial.
 *
 * TalkBack: the app bar carries no title (the hero is the name), so the
 * screen root is the pane title and the hero name is a heading; without
 * them a new person's page was never announced (rubric D8).
 *
 * Privacy curtain (PRIV-03): hero name, pane title, overflow label and
 * lists-on chip labels render the literal "Contact" when
 * [LocalPrivacyCurtain] is true (focus-loss). The phone number is masked too
 * ("Number hidden", PRIV-07) and carries no click action at all under the
 * curtain, so it neither dials nor announces as a button; it used to show
 * through the curtain.
 */
@Composable
fun ContactDetailScreen(
    contactId: String, // VM reads via SavedStateHandle; also threaded to onAddToLists
    onBack: () -> Unit,
    onAddToLists: (contactId: String) -> Unit = {}, // BULK-06 — entry to ListPickerScreen
    onRelink: (
        contactId: Long
    ) -> Unit = {}, // CONTACT-06 — Re-link → ContactPickerScreen mode=relink
    onViewAllCalls: () -> Unit = {}, // LOG-01 — overflow → CallLogScreen
    // The call log notice's "Open settings" leads to Orbit's own Settings
    // (voice.md). Defaulted so the nav wiring can land on its own.
    onOpenSettings: () -> Unit = {},
    vm: ContactDetailViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // NOTE-02 — single FocusRequester instance, threaded down to NotesSection
    // so the deep-link path can request focus on the input. The 100ms delay
    // lets layout settle before requestFocus(); without it the request can
    // fire before the BasicTextField is composed.
    val notesInputFocusRequester = remember { FocusRequester() }
    // Single LocalLifecycleOwner reference reused by all three event-flow
    // collects below so each is gated by STARTED (no snackbar / focus / nav
    // side effect fires while the screen is STOPPED).
    val lifecycleOwner = LocalLifecycleOwner.current
    // Snackbar copy is UiText (strings_contact.xml); resolved when shown.
    val context = LocalContext.current

    // ARCH-04: READ_CALL_LOG is re-read on every resume (the Browse and Call
    // history precedent), so coming back from Settings with access granted
    // clears the notice without a restart. No seam, no binding: the screen
    // reports, the VM carries the flag.
    LifecycleResumeEffect(Unit) {
        vm.onCallLogPermissionChanged(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        )
        onPauseOrDispose { }
    }

    // Snackbar collector for note-delete + undo. Mirrors the BrowseListScreen
    // pattern (UndoStack-backed inverse closure dispatched on
    // SnackbarResult.ActionPerformed).
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.snackbarEvents.collect { event ->
                val r = snackbarHostState.showSnackbar(
                    message = event.message.asString(context),
                    actionLabel = event.actionLabel?.asString(context),
                    duration = SnackbarDuration.Short,
                    withDismissAction = false
                )
                if (r == SnackbarResult.ActionPerformed) vm.onUndo()
            }
        }
    }

    // NOTE-02 — listen for the VM's one-shot focus signal (delivered once per
    // VM instance when `focusNote=1` is in SavedStateHandle). The 100ms delay
    // mitigates "requestFocus() called before the BasicTextField is laid out"
    // on cold deep-links.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.focusNoteEvent.collect {
                delay(100)
                runCatching { notesInputFocusRequester.requestFocus() }
            }
        }
    }

    // CONTACT-06 — collect VM nav events. Re-link tap on the OrphanBanner
    // routes through here to the NavHost-supplied [onRelink] callback.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.navEvents.collect { event ->
                when (event) {
                    is ContactDetailViewModel.NavEvent.RelinkPicker ->
                        onRelink(event.contactId)
                }
            }
        }
    }

    ContactDetailContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onAddToLists = { onAddToLists(contactId) },
        onDraftChange = vm::onDraftChange,
        onAddNote = vm::addNote,
        onDeleteNote = vm::onDeleteNote,
        onEditNote = vm::onEditNote,
        onIgnore = vm::onIgnore,
        onUnignore = vm::onUnignore,
        onPauseContact = vm::onPauseContact,
        onUnpauseContact = vm::onUnpauseContact,
        onUnpauseNow = vm::onUnpauseNow,
        // LOG-04: overflow → CallLogScreen narrowed to this person. The
        // NavHost-side caller forwards this to Routes.callLogFor(contactId).
        onViewAllCalls = onViewAllCalls,
        onRetry = vm::onRetry,
        // CONTACT-06 — orphan flow callbacks. The actual NavHost wiring (Re-link
        // → ContactPickerScreen with mode=relink) is a NavHost-level concern;
        // the VM emits a NavEvent.RelinkPicker via [vm.navEvents] which the
        // NavHost-side caller consumes. For now the screen just wires the VM
        // method which fires the SharedFlow event.
        onRelink = vm::onRelink,
        onArchive = vm::onArchive,
        // CONTACT-03 — RuleOverrideSection callbacks wired to
        // the VM's setRuleOverrideJson surface. Open initialises with
        // KeepInTouch defaults; save encodes the new RuleParams; clear
        // wipes the column.
        onOpenOverride = vm::onOpenOverride,
        onSaveOverride = vm::onSaveOverride,
        onClearOverride = vm::onClearOverride,
        // LOG-03 — retroactive-note save handler (back-dated via byId O(1)
        // lookup). Wired to the inline "Add note to this call" Secondary
        // button rendered below the tinted call row when the user arrives
        // via Routes.contactWithFocus(scrollToCallEventId = ...).
        onAddRetroactiveNote = vm::onAddRetroactiveNote,
        // Manual connection log — confirm handler for LogConnectionSheet.
        // Inserts a CallEventEntity(source = MANUAL) via MarkCalledUseCase so
        // per-list nextDueAt advances; quiet "Logged." snackbar on success.
        onLogConnection = vm::onLogConnection,
        onOpenSettings = onOpenSettings,
        notesInputFocusRequester = notesInputFocusRequester
    )
}

@Composable
private fun ContactDetailContent(
    state: ContactDetailUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAddToLists: () -> Unit,
    onDraftChange: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (NoteRow) -> Unit,
    onEditNote: (NoteRow, String) -> Unit,
    onIgnore: (String) -> Unit,
    onUnignore: (String) -> Unit,
    onPauseContact: (PauseDuration) -> Unit,
    onUnpauseContact: () -> Unit,
    onUnpauseNow: () -> Unit,
    onViewAllCalls: () -> Unit,
    onRetry: () -> Unit,
    onRelink: () -> Unit,
    onArchive: (String) -> Unit,
    onOpenOverride: () -> Unit,
    onSaveOverride: (RuleParams) -> Unit,
    onClearOverride: () -> Unit,
    onAddRetroactiveNote: (Long, String) -> Unit,
    onLogConnection: (LogConnectionWhen, String, Boolean) -> Unit,
    onOpenSettings: () -> Unit = {},
    notesInputFocusRequester: FocusRequester? = null
) {
    // CONTACT-04, IGNORE-02 — overflow + pause sheet
    // visibility flags. `showOverflow` is non-saveable (transient); the
    // pause sheet flag uses rememberSaveable so a configuration change while
    // the sheet is open keeps it open (matches CreateListBottomSheet pattern).
    var showOverflow by remember { mutableStateOf(false) }
    var showPauseSheet by rememberSaveable { mutableStateOf(false) }
    // Manual connection log — same rememberSaveable rationale as the pause sheet.
    var showLogConnectionSheet by rememberSaveable { mutableStateOf(false) }

    val curtain = LocalPrivacyCurtain.current
    val context = LocalContext.current
    // The person shown, in Ready and Orphaned alike: the orphaned page keeps
    // its overflow, because "View all calls" is about history, which stays.
    val shownContact: Contact? = when (state) {
        is ContactDetailUiState.Ready -> state.contact
        is ContactDetailUiState.Orphaned -> state.contact
        else -> null
    }
    val ready = state as? ContactDetailUiState.Ready
    // PRIV-03: the pane title and the overflow's TalkBack label name the
    // person, so both are masked with every other name. Under the curtain
    // the label read "More actions for Avery Quinn" (ContactDetailCurtainTest).
    val displayName: String? = shownContact?.let {
        if (curtain) stringResource(R.string.components_curtain_contact) else it.name
    }
    val overflowName: String? = shownContact?.name?.takeUnless { curtain }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The app bar has no title here (the hero is the name), so the
            // root names the pane: TalkBack announces the person when
            // navigation swaps this screen in, as OrbitAppBar does for titled
            // screens (rubric D8). Before this a new person's page was never
            // announced and the first focusable was Back.
            .semantics { if (displayName != null) paneTitle = displayName }
    ) {
        OrbitScreen {
            OrbitAppBar(
                title = "",
                leading = {
                    OrbitIconButton(
                        icon = "arrow-left",
                        onClick = onBack,
                        contentDescription = stringResource(R.string.components_action_back)
                    )
                },
                trailing = if (shownContact != null) {
                    {
                        Box {
                            OrbitIconButton(
                                icon = "dots-three-vertical",
                                onClick = { showOverflow = true },
                                contentDescription = overflowName
                                    ?.let { stringResource(R.string.contact_more_actions_named, it) }
                                    ?: stringResource(R.string.contact_more_actions)
                            )
                            // Ordering + danger tint per the shared
                            // [OrbitDropdownMenu] contract: the everyday reads
                            // lead, and "Ignore" — which takes the person out
                            // of Orbit's rotation — sits last, below the rule.
                            OrbitDropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false },
                                actions = contactOverflowActions(
                                    resources = LocalContext.current.resources,
                                    isPaused = ready?.pausedLabel != null,
                                    isIgnored = ready?.isIgnored == true,
                                    hasPhoneContact = ready?.phoneContactId != null,
                                    orphaned = state is ContactDetailUiState.Orphaned,
                                    onViewAllCalls = onViewAllCalls,
                                    onPause = { showPauseSheet = true },
                                    onUnpause = onUnpauseNow,
                                    onOpenInContacts = {
                                        ready?.phoneContactId?.let { context.openPhoneContact(it) }
                                    },
                                    onIgnore = { overflowName?.let { onIgnore(it) } },
                                    onUnignore = { overflowName?.let { onUnignore(it) } }
                                )
                            )
                        }
                    }
                } else {
                    null
                }
            )
            when (state) {
                ContactDetailUiState.Loading -> EmptyContactShell()
                // The only action here, so it takes the accent (rules.md
                // §Design 5), the same as the Error state's Try again.
                ContactDetailUiState.NotFound -> OrbitScreenMessage(
                    icon = "user",
                    title = stringResource(R.string.contact_not_found_title),
                    body = stringResource(R.string.contact_not_found_body),
                    actionLabel = stringResource(R.string.contact_go_back),
                    onAction = onBack,
                    actionVariant = OrbitButtonVariant.Primary
                )
                // CONTACT-08: a failed read says so, with Retry, instead of
                // crashing. The only action here, so it takes the accent.
                ContactDetailUiState.Error -> OrbitScreenMessage(
                    icon = "warning-circle",
                    title = stringResource(R.string.contact_error_title),
                    body = stringResource(R.string.components_error_body),
                    actionLabel = stringResource(R.string.components_error_retry),
                    onAction = onRetry,
                    actionVariant = OrbitButtonVariant.Primary
                )
                is ContactDetailUiState.Ready -> ContactBodyLazyColumn(
                    contact = state.contact,
                    notes = state.notes,
                    draft = state.draft,
                    listsOn = state.listsOn,
                    recentCalls = state.recentCalls,
                    longestGapLabel = state.longestGapLabel,
                    unpausePromptVisible = state.unpausePromptVisible,
                    pausedLabel = state.pausedLabel,
                    isIgnored = state.isIgnored,
                    isArchived = state.isArchived,
                    callLogDenied = state.callLogDenied,
                    customScheduleVisible = state.customScheduleVisible,
                    currentTemplateName = state.currentTemplateName,
                    primaryListName = state.primaryListName,
                    hasOverride = state.hasOverride,
                    currentParams = state.currentParams,
                    scrollToCallEventId = state.scrollToCallEventId,
                    retroNoteAffordanceFor = state.retroNoteAffordanceFor,
                    callEventIds = state.recentCallEventIds,
                    recentCallIsManual = state.recentCallIsManual,
                    recentCallIsAttempt = state.recentCallIsAttempt,
                    // FINDING A — Call is the screen's single terracotta
                    // element in the Ready state (rules.md design rule 5).
                    callButtonVariant = OrbitButtonVariant.Primary,
                    onOpenLogConnection = { showLogConnectionSheet = true },
                    onAddToLists = onAddToLists,
                    notesReadOnly = false,
                    onDraftChange = onDraftChange,
                    onAddNote = onAddNote,
                    onDeleteNote = onDeleteNote,
                    onEditNote = onEditNote,
                    onUnpauseContact = onUnpauseContact,
                    onOpenOverride = onOpenOverride,
                    onSaveOverride = onSaveOverride,
                    onClearOverride = onClearOverride,
                    onAddRetroactiveNote = onAddRetroactiveNote,
                    onOpenSettings = onOpenSettings,
                    notesInputFocusRequester = notesInputFocusRequester
                )
                is ContactDetailUiState.Orphaned -> Column(modifier = Modifier.fillMaxSize()) {
                    OrphanBanner(
                        onRelink = onRelink,
                        onArchive = { onArchive(state.contact.name) }
                    )
                    ContactBodyLazyColumn(
                        contact = state.contact,
                        // Notes are Orbit's own data: readable here, editable
                        // again once re-linked ("History stays here").
                        notes = state.notes,
                        draft = "",
                        listsOn = state.listsOn,
                        recentCalls = state.recentCalls,
                        longestGapLabel = state.longestGapLabel,
                        unpausePromptVisible = false, // Orphan path bypasses unpause banner.
                        pausedLabel = null, // Orphans are not surfaced, so a pause has no effect to report.
                        isIgnored = false,
                        isArchived = false,
                        callLogDenied = state.callLogDenied,
                        // Orphan path bypasses RuleOverrideSection — section
                        // wraps in its own AnimatedVisibility on listsOnSize >= 2,
                        // and edit affordances are off per the orphan banner copy.
                        customScheduleVisible = false,
                        currentTemplateName = null,
                        primaryListName = "",
                        hasOverride = false,
                        currentParams = null,
                        // Orphan path: no CallLog deep-link surface, but the
                        // rows still carry their ids (they are the keys).
                        scrollToCallEventId = null,
                        retroNoteAffordanceFor = null,
                        callEventIds = state.recentCallEventIds,
                        recentCallIsManual = state.recentCallIsManual,
                        recentCallIsAttempt = state.recentCallIsAttempt,
                        // Orphan: the banner's Re-link button owns the
                        // screen's terracotta — Call demotes to Secondary.
                        // The number survives orphaning, so calling still works.
                        callButtonVariant = OrbitButtonVariant.Secondary,
                        onOpenLogConnection = null, // Orphan: edit affordances disabled (banner copy)
                        onAddToLists = null, // Orphan: edit affordances disabled (banner copy)
                        notesReadOnly = true,
                        onDraftChange = {},
                        onAddNote = {},
                        onDeleteNote = {},
                        onEditNote = { _, _ -> },
                        onUnpauseContact = {},
                        onOpenOverride = {},
                        onSaveOverride = {},
                        onClearOverride = {},
                        onAddRetroactiveNote = { _, _ -> },
                        onOpenSettings = onOpenSettings,
                        notesInputFocusRequester = null
                    )
                }
            }
        }
        // The shared pause sheet (menus-4) is rendered at the screen scope
        // (window-level ModalBottomSheet) so it overlays the OrbitScreen
        // content cleanly. It calls onSelect, then onDismiss once its hide
        // animation ends.
        if (showPauseSheet) {
            PauseDurationSheet(
                onSelect = onPauseContact,
                onDismiss = { showPauseSheet = false }
            )
        }
        // Manual connection log — sheet at screen scope, mirroring the pause
        // sheet. Confirm dispatches to the VM; dismissal follows the Pitfall 1
        // launch-then-flip pattern inside the sheet.
        if (showLogConnectionSheet) {
            LogConnectionSheet(
                onConfirm = onLogConnection,
                onDismiss = { showLogConnectionSheet = false }
            )
        }
        OrbitSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun EmptyContactShell() {
    // Loading shell. Was an empty surface (blank flash under the
    // AppBar while the contact row loads); now a quiet placeholder mirroring
    // the hero's geometry (120dp photo + name line) so Ready doesn't reflow.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = OrbitTheme.spacing.x6)
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(OrbitTheme.colors.bgSubtle)
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
        Box(
            modifier = Modifier
                .size(width = 160.dp, height = OrbitTheme.spacing.x6)
                .clip(OrbitTheme.shapes.md)
                .background(OrbitTheme.colors.bgSubtle)
        )
    }
}

@PreviewLightDark
@Composable
private fun EmptyContactShellPreview() {
    OrbitTheme {
        EmptyContactShell()
    }
}

/**
 * The body's LazyColumn item keys (rules.md Code 2). Call rows are keyed by
 * their call-event id (a Long), everything else by one of these strings; the
 * two can never collide. `internal` so a screen test can scroll by key.
 */
internal object ContactDetailItemKey {
    const val UNPAUSE_BANNER = "unpause-banner"
    const val HERO = "hero"
    const val LISTS = "lists"
    const val STATS = "stats"
    const val ADD_TO_LISTS = "add-to-lists"
    const val NOTES = "notes"
    const val SCHEDULE = "schedule"
    const val RECENT_CALLS = "recent-calls"
    const val NO_CALLS = "no-calls"
    const val BOTTOM_SPACE = "bottom-space"
}

private const val CONTENT_TYPE_CALL_ROW = "callRow"

@OptIn(ExperimentalLayoutApi::class) // function-scoped per Pitfall 8
@Composable
private fun ContactBodyLazyColumn(
    contact: Contact,
    notes: List<NoteRow>,
    draft: String,
    listsOn: List<String>,
    recentCalls: List<CallEntry>,
    longestGapLabel: UiText?,
    unpausePromptVisible: Boolean,
    pausedLabel: UiText?,
    isIgnored: Boolean,
    isArchived: Boolean,
    callLogDenied: Boolean,
    customScheduleVisible: Boolean,
    currentTemplateName: UiText?,
    primaryListName: String,
    hasOverride: Boolean,
    currentParams: RuleParams?,
    // LOG-03 — CallLog deep-link surface. `callEventIds` is parallel-
    // indexed with [recentCalls] (same DESC-by-occurredAt order); the ids are
    // the rows' LazyColumn keys, which is how the screen finds the item for
    // [scrollToCallEventId] and gates the inline "Add note to this call"
    // button.
    scrollToCallEventId: Long?,
    retroNoteAffordanceFor: Long?,
    callEventIds: List<Long>,
    // Parallel-indexed with [recentCalls] — true when the row is a
    // user-logged connection (CallSource.MANUAL); renders "Logged" + a
    // check-circle icon instead of duration + direction.
    recentCallIsManual: List<Boolean>,
    // Parallel-indexed with [recentCalls] — true when the row is a reach-out
    // that didn't connect (CallSource.ATTEMPT); renders "Attempted" + a
    // phone-slash icon.
    recentCallIsAttempt: List<Boolean>,
    // FINDING A — Call button variant. Primary (terracotta) in the Ready
    // state; Secondary in the Orphaned state, where the banner's Re-link
    // button owns the screen's one terracotta element (rules.md design 5).
    callButtonVariant: OrbitButtonVariant,
    // FINDING B — opens the LogConnectionSheet. Null in the Orphaned state
    // (edit affordances disabled per the banner copy), which hides the button.
    onOpenLogConnection: (() -> Unit)?,
    onAddToLists: (() -> Unit)?,
    // The orphaned page: notes stay readable, nothing about them is editable.
    notesReadOnly: Boolean,
    onDraftChange: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (NoteRow) -> Unit,
    onEditNote: (NoteRow, String) -> Unit,
    onUnpauseContact: () -> Unit,
    onOpenOverride: () -> Unit,
    onSaveOverride: (RuleParams) -> Unit,
    onClearOverride: () -> Unit,
    onAddRetroactiveNote: (Long, String) -> Unit,
    onOpenSettings: () -> Unit,
    notesInputFocusRequester: FocusRequester? = null
) {
    val curtain = LocalPrivacyCurtain.current
    val displayName = if (curtain) {
        stringResource(
            R.string.components_curtain_contact
        )
    } else {
        contact.name
    }

    // The rows are keyed by call-event id, so the two parallel lists must
    // agree. The VM derives both from one event list; a mismatch is a bug in
    // a fixture, and it fails here rather than mis-keying rows (rules.md Code 3).
    check(callEventIds.size == recentCalls.size) {
        "recentCalls (${recentCalls.size}) and callEventIds (${callEventIds.size}) must be parallel"
    }

    // One ordered list of item keys drives both the LazyColumn and the deep
    // link's scroll target, so the index can never drift from the items.
    // Until 2026-10-06 the scroll index was hand-counted ("8 = max items
    // before recent calls") and landed one row past the target for a person
    // on a single list, hiding the call and its note field above the fold
    // (contact-detail-1).
    val itemKeys: List<Any> = buildList {
        add(ContactDetailItemKey.UNPAUSE_BANNER)
        add(ContactDetailItemKey.HERO)
        add(ContactDetailItemKey.LISTS)
        add(ContactDetailItemKey.STATS)
        if (onAddToLists != null) add(ContactDetailItemKey.ADD_TO_LISTS)
        add(ContactDetailItemKey.NOTES)
        if (customScheduleVisible) add(ContactDetailItemKey.SCHEDULE)
        add(ContactDetailItemKey.RECENT_CALLS)
        if (recentCalls.isEmpty()) {
            // "No calls yet." is a claim Orbit cannot make without the call
            // log; the notice above the stats already says why it is empty.
            if (!callLogDenied) add(ContactDetailItemKey.NO_CALLS)
        } else {
            addAll(callEventIds)
        }
        add(ContactDetailItemKey.BOTTOM_SPACE)
    }

    // LOG-03 — `LazyListState` hoisted so the screen can animateScrollToItem
    // to the matching call-event row when the user arrives via the CallLog
    // deep link. The target is found by key in [itemKeys].
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToCallEventId, itemKeys) {
        val target = scrollToCallEventId ?: return@LaunchedEffect
        val index = itemKeys.indexOf(target)
        if (index < 0) return@LaunchedEffect
        runCatching { listState.animateScrollToItem(index) }
    }

    // LOG-03 — inline retro-note draft text. `rememberSaveable` so a
    // configuration change while the user is typing keeps the body. Keyed
    // on the affordance target so a future ID flip resets the draft.
    var retroNoteDraft by rememberSaveable(retroNoteAffordanceFor) { mutableStateOf("") }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = OrbitTheme.spacing.x6,
            vertical = OrbitTheme.spacing.x4
        )
    ) {
        items(
            items = itemKeys,
            key = { it },
            contentType = { key -> if (key is Long) CONTENT_TYPE_CALL_ROW else key }
        ) { key ->
            when (key) {
                // CONTACT-05 — UnpauseBanner item at top of body.
                // Renders only when pausedUntil <= now AND not the indefinite sentinel
                // (VM-derived). AnimatedVisibility uses OrbitMotion.DurBaseMs (250ms).
                ContactDetailItemKey.UNPAUSE_BANNER -> AnimatedVisibility(
                    visible = unpausePromptVisible,
                    enter = slideInVertically(
                        animationSpec = tween(OrbitMotion.DurBaseMs),
                        initialOffsetY = { -it }
                    ) + fadeIn(animationSpec = tween(OrbitMotion.DurBaseMs)),
                    exit = slideOutVertically(
                        animationSpec = tween(OrbitMotion.DurBaseMs),
                        targetOffsetY = { -it }
                    ) + fadeOut(animationSpec = tween(OrbitMotion.DurBaseMs))
                ) {
                    UnpauseBanner(
                        contactName = contact.name,
                        curtain = curtain,
                        onUnpause = onUnpauseContact
                    )
                }

                ContactDetailItemKey.HERO -> {
                    val context = LocalContext.current
                    val hasPhone = contact.phone.isNotBlank()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // The photo is masked with the name under the curtain: a face
                        // identifies a person as surely as their name does. It showed
                        // through until 2026-10-05.
                        Avatar(
                            name = displayName,
                            size = 120.dp,
                            photoUri = if (curtain) null else contact.photoUri
                        )
                        Spacer(Modifier.height(OrbitTheme.spacing.x4))
                        Text(
                            text = displayName,
                            style = OrbitTheme.type.hero,
                            color = OrbitTheme.colors.fg,
                            // The name is the page's heading, so TalkBack users can
                            // jump to it and hear whose page this is.
                            modifier = Modifier.semantics { heading() }
                        )
                        // FINDING A — tappable phone row. ACTION_DIAL via the shared
                        // Dialer util (never CALL_PHONE); 48dp target per design rule 3.
                        // PRIV-07: masked under the curtain like the name and photo, and
                        // then not a control at all: a disabled clickable would still
                        // announce as a button that does nothing.
                        val formattedPhone = if (curtain) {
                            stringResource(R.string.contact_number_hidden)
                        } else {
                            formatPhone(contact.phone)
                        }
                        val callNumberLabel =
                            stringResource(R.string.contact_call_number, formattedPhone)
                        val dials = hasPhone && !curtain
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
                                .clip(OrbitTheme.shapes.md)
                                .then(
                                    if (dials) {
                                        Modifier
                                            .clickable { context.dialPhoneNumber(contact.phone) }
                                            .semantics {
                                                role = Role.Button
                                                contentDescription = callNumberLabel
                                            }
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(horizontal = OrbitTheme.spacing.x3)
                        ) {
                            Text(
                                text = formattedPhone,
                                style = OrbitTheme.type.body,
                                color = OrbitTheme.colors.fgMuted
                            )
                        }
                        // An active pause used to be invisible here; say so plainly
                        // (the overflow offers Unpause while this shows).
                        if (pausedLabel != null) {
                            Text(
                                text = pausedLabel.asString(),
                                style = OrbitTheme.type.meta,
                                color = OrbitTheme.colors.fgMuted
                            )
                        }
                        // The same for Ignore and Archive (CONTACT-10): the state the
                        // user set, said where they set it. Ignored wins when both hold.
                        val statusRes = when {
                            isIgnored -> R.string.contact_status_ignored
                            isArchived -> R.string.contact_status_archived
                            else -> null
                        }
                        if (statusRes != null) {
                            Text(
                                text = stringResource(statusRes),
                                style = OrbitTheme.type.meta,
                                color = OrbitTheme.colors.fgMuted
                            )
                        }
                        Spacer(Modifier.height(OrbitTheme.spacing.x3))
                        // FINDING A + B — hero action row. Call opens the system
                        // dialer pre-filled (ACTION_DIAL); Log a connection records a
                        // call Orbit can't see (another app, in person).
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OrbitButton(
                                text = stringResource(R.string.contact_call),
                                onClick = { context.dialPhoneNumber(contact.phone) },
                                variant = callButtonVariant,
                                leadingIcon = "phone",
                                enabled = hasPhone,
                                modifier = Modifier.weight(1f)
                            )
                            if (onOpenLogConnection != null) {
                                OrbitButton(
                                    text = stringResource(R.string.contact_log_connection),
                                    onClick = onOpenLogConnection,
                                    variant = OrbitButtonVariant.Secondary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                ContactDetailItemKey.LISTS -> {
                    Spacer(Modifier.height(OrbitTheme.spacing.x6))
                    SectionEyebrow(stringResource(R.string.contact_section_lists))
                    Spacer(Modifier.height(OrbitTheme.spacing.x3))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
                        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2)
                    ) {
                        listsOn.forEach { listName ->
                            // ListContextChip masks the name to "List" under the
                            // curtain, the same word every list-name surface uses (it
                            // read "Contact" here). Stone is the neutral read-only tone.
                            ListContextChip(listName = listName, tone = ChipTone.Stone)
                        }
                        if (listsOn.isEmpty()) {
                            // A bare dash read as broken (vision CONTACT-3).
                            Text(
                                text = stringResource(R.string.contact_not_on_any_list),
                                style = OrbitTheme.type.body,
                                color = OrbitTheme.colors.fgMuted
                            )
                        }
                    }
                }

                ContactDetailItemKey.STATS -> {
                    Spacer(Modifier.height(OrbitTheme.spacing.x6))
                    SectionEyebrow(stringResource(R.string.contact_section_stats))
                    Spacer(Modifier.height(OrbitTheme.spacing.x3))
                    if (callLogDenied) {
                        // Without READ_CALL_LOG, "Never called" and "Not enough
                        // calls yet" would be claims Orbit cannot make (Browse
                        // drops its call meta for the same reason, rules.md Code 3).
                        // The notice says why, and each stat below shows only
                        // when it has a real value; Total calls stays, because
                        // hand-logged connections are real data.
                        OrbitInlineNotice(
                            text = stringResource(R.string.contact_call_log_denied_notice),
                            actionLabel = stringResource(R.string.components_action_open_settings),
                            onAction = onOpenSettings
                        )
                        Spacer(Modifier.height(OrbitTheme.spacing.x2))
                    }
                    // Plain words, not "Avg", and a sentence, not a bare dash,
                    // where there isn't enough history yet: a dash read as
                    // broken (vision CONTACT-3, rubric D7). A null label is
                    // "nothing to state" (no measured call, fewer than two
                    // calls), so there is no placeholder value to filter out.
                    val notEnoughCalls = stringResource(R.string.components_stat_not_enough_calls)
                    val neverCalled = stringResource(R.string.components_stat_never_called)
                    val lastCall = contact.lastCalledLabel?.asString()
                    // Three MEASURED calls, not three calls: a logged connection
                    // counts as a call but has no length, so the total let one
                    // carrier call plus two logged ones show that call's length
                    // as a mean (CONTACT-02).
                    val averageLength = contact.avgLengthLabel
                        ?.takeIf { contact.measuredCalls >= 3 }
                        ?.asString()
                    val longestGap = longestGapLabel?.asString()
                    val usually = contact.bestWindowLabel?.asString()
                    val stats = buildList {
                        if (lastCall != null || !callLogDenied) {
                            add(
                                StatEntry(
                                    label = stringResource(R.string.contact_stat_last_call),
                                    value = lastCall ?: neverCalled
                                )
                            )
                        }
                        add(
                            StatEntry(
                                label = stringResource(R.string.contact_stat_total_calls),
                                value = contact.totalCalls.toString()
                            )
                        )
                        if (averageLength != null || !callLogDenied) {
                            add(
                                StatEntry(
                                    label = stringResource(R.string.contact_stat_average_length),
                                    value = averageLength ?: notEnoughCalls
                                )
                            )
                        }
                        if (longestGap != null || !callLogDenied) {
                            add(
                                StatEntry(
                                    label = stringResource(R.string.contact_stat_longest_gap),
                                    value = longestGap ?: notEnoughCalls
                                )
                            )
                        }
                    }
                    ContactStatsPanel(stats = stats)
                    if (usually != null || !callLogDenied) {
                        HorizontalDivider(
                            color = OrbitTheme.colors.line,
                            thickness = 1.dp
                        )
                        UsuallyStatRow(value = usually ?: notEnoughCalls)
                    }
                }

                // BULK-06 — "Add to lists" entry to the reverse picker.
                // Absent when the contact is orphaned (edit affordances are off per
                // the orphan banner copy).
                ContactDetailItemKey.ADD_TO_LISTS -> {
                    Spacer(Modifier.height(OrbitTheme.spacing.x6))
                    OrbitButton(
                        text = stringResource(R.string.contact_add_to_lists),
                        onClick = { onAddToLists?.invoke() },
                        // Secondary — the hero Call button is the screen's one
                        // terracotta element (rules.md design rule 5).
                        variant = OrbitButtonVariant.Secondary,
                        leadingIcon = "user-plus",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // NOTE-01 — Notes journaling section. Read-only while the
                // contact is Orphaned: the notes stay ("History stays here"),
                // editing waits for the re-link.
                ContactDetailItemKey.NOTES -> {
                    Spacer(Modifier.height(OrbitTheme.spacing.x6))
                    NotesSection(
                        notes = notes,
                        draft = draft,
                        onDraftChange = onDraftChange,
                        onAdd = { onAddNote(draft) },
                        onDelete = onDeleteNote,
                        onEditCommit = onEditNote,
                        inputFocusRequester = notesInputFocusRequester,
                        readOnly = notesReadOnly
                    )
                }

                // CONTACT-03 — RuleOverrideSection. Visibility
                // is double-gated: the screen-side `customScheduleVisible` (VM-derived
                // from listsOn.size >= 2) decides whether to add the LazyColumn item
                // at all, and the section's own AnimatedVisibility wraps the body
                // for the in/out animation. Pitfall 6 corrupted-JSON recovery flows
                // a fresh KeepInTouch default down so the editor still renders when
                // currentParams == null, under the usual "Custom schedule" label
                // (no special copy: currentTemplateName is null and nothing shows it).
                ContactDetailItemKey.SCHEDULE -> {
                    Spacer(Modifier.height(OrbitTheme.spacing.x6))
                    RuleOverrideSection(
                        listsOnSize = listsOn.size,
                        currentTemplateName = currentTemplateName,
                        // List names are masked under the curtain (ListContextChip):
                        // null makes the section say "from its list".
                        primaryListName = if (curtain) null else primaryListName,
                        hasOverride = hasOverride,
                        currentParams = currentParams ?: RuleParams.KeepInTouch(),
                        onOverride = onOpenOverride,
                        onParamsChange = onSaveOverride,
                        onResetDefault = onClearOverride
                    )
                }

                ContactDetailItemKey.RECENT_CALLS -> {
                    Spacer(Modifier.height(OrbitTheme.spacing.x6))
                    SectionEyebrow(stringResource(R.string.contact_section_recent_calls))
                    Spacer(Modifier.height(OrbitTheme.spacing.x3))
                }

                ContactDetailItemKey.NO_CALLS -> Text(
                    text = stringResource(R.string.contact_no_calls),
                    style = OrbitTheme.type.body,
                    color = OrbitTheme.colors.fgMuted
                )

                // Bottom breathing room
                ContactDetailItemKey.BOTTOM_SPACE -> Spacer(Modifier.height(OrbitTheme.spacing.x8))

                // A call row, keyed by its call-event id. CallEntry has no
                // occurredAt or id (Model.kt: direction / relativeWhen /
                // lengthLabel only), so the parallel [callEventIds] carries the
                // primary keys: they key the rows, gate the inline retro-note
                // affordance and honour scroll-to-call (LOG-03).
                is Long -> {
                    val idx = callEventIds.indexOf(key)
                    val targeted = key == scrollToCallEventId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (targeted) {
                                    // The row the deep link pointed at, tinted so the
                                    // eye lands on it after the scroll.
                                    Modifier
                                        .clip(OrbitTheme.shapes.md)
                                        .background(OrbitTheme.colors.bgSubtle)
                                        .padding(horizontal = OrbitTheme.spacing.x3)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        CallHistoryRow(
                            call = recentCalls[idx],
                            personFirstName = displayName.substringBefore(' '),
                            isManual = recentCallIsManual.getOrNull(idx) ?: false,
                            isAttempt = recentCallIsAttempt.getOrNull(idx) ?: false
                        )
                        // LOG-03 — inline "Add note to this call" affordance. Renders
                        // below the tinted call row (the one the CallLog deep link
                        // pointed at). The composable is a small Secondary button + a
                        // BasicTextField; tapping it invokes vm::onAddRetroactiveNote
                        // which back-dates createdAt to the call's occurredAt via the
                        // byId O(1) lookup (no observeAll snapshot).
                        if (retroNoteAffordanceFor == key) {
                            RetroNoteAffordance(
                                draft = retroNoteDraft,
                                onDraftChange = { retroNoteDraft = it },
                                onSave = {
                                    if (retroNoteDraft.isNotBlank()) {
                                        onAddRetroactiveNote(key, retroNoteDraft)
                                        retroNoteDraft = ""
                                    }
                                }
                            )
                        }
                    }
                }

                // Every key is one of the constants above or a call-event id;
                // anything else is a bug in [itemKeys], and it fails loudly.
                else -> error("Unknown Contact detail item key: $key")
            }
        }
    }
}

@Composable
private fun SectionEyebrow(label: String) {
    SectionLabel(text = label)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsuallyStatRow(value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OrbitTheme.spacing.x3)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = stringResource(R.string.contact_stat_usually),
                style = OrbitTheme.type.eyebrow,
                color = OrbitTheme.colors.fgMuted
            )
            InfoTip(
                text = stringResource(R.string.contact_usually_tooltip),
                label = stringResource(R.string.contact_usually_tooltip_label)
            )
        }
        Text(
            text = value,
            style = OrbitTheme.type.statValue,
            color = OrbitTheme.colors.fg
        )
    }
}

/**
 * CONTACT-06 — orphan banner with Re-link + Archive actions.
 *
 * Visibility is `internal` (downgraded from `private`) so `OrphanBannerTest`
 * (Robolectric, under src/test) can reference the composable directly
 * without hoisting it into a `sections/` file.
 *
 * Copy:
 *   - Heading: "This contact was deleted from your phone"
 *   - Body: "History stays here. Re-link to a phone contact, or archive to remove from lists."
 */
@Composable
internal fun OrphanBanner(onRelink: () -> Unit, onArchive: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = OrbitTheme.spacing.x6,
                vertical = OrbitTheme.spacing.x4
            )
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.bgSubtle)
            .padding(OrbitTheme.spacing.x4)
    ) {
        Text(
            text = stringResource(R.string.contact_orphan_title),
            style = OrbitTheme.type.h3,
            color = OrbitTheme.colors.fg
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = stringResource(R.string.contact_orphan_body),
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fgMuted
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        Row(horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3)) {
            OrbitButton(
                text = stringResource(R.string.contact_orphan_relink),
                onClick = onRelink,
                variant = OrbitButtonVariant.Primary,
                leadingIcon = "link"
            )
            OrbitButton(
                text = stringResource(R.string.components_action_archive),
                onClick = onArchive,
                variant = OrbitButtonVariant.Secondary,
                leadingIcon = "eye-slash"
            )
        }
    }
}

/**
 * `CallEntry` carries pre-formatted strings (`relativeWhen`, `lengthLabel`)
 * populated by the VM's `toUiCallEntry` mapper using
 * `app.orbit.ui.util.RelativeTime` — this row composable just renders them.
 *
 * Direction is an icon for the eye and words for TalkBack: the icon's box
 * reads "You called" or "{first name} called", the words Call history leads
 * its rows with (strings_contact.xml keeps its own copy, one file per area).
 * Until 2026-10-06 the icon was the only carrier, so a screen-reader user
 * heard the length and the time but never who called whom (WCAG 1.1.1).
 * PhIcon itself stays decorative, as everywhere.
 *
 * [isManual] rows are user-logged connections (CallSource.MANUAL,
 * durationSeconds = 0): a check-circle icon + "Logged" replaces the
 * direction icon + duration label, which would otherwise read "—".
 *
 * [isAttempt] rows are reach-outs that didn't connect (CallSource.ATTEMPT,
 * durationSeconds = 0): a phone-slash icon + "Attempted" — distinct from a
 * logged connection, because you reached out but didn't actually talk.
 * Those two rows say what happened in their own text, so the icon needs no
 * words of its own.
 */
@Composable
private fun CallHistoryRow(
    call: CallEntry,
    personFirstName: String,
    isManual: Boolean = false,
    isAttempt: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OrbitTheme.spacing.x3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconName: String = when {
            isAttempt -> "phone-slash"
            isManual -> "check-circle"
            call.direction == CallDirection.Outgoing -> "phone-outgoing"
            else -> "phone-incoming"
        }
        val directionLabel: String? = when {
            isAttempt || isManual -> null
            call.direction == CallDirection.Outgoing -> stringResource(
                R.string.contact_call_you_called
            )
            else -> stringResource(R.string.contact_call_they_called, personFirstName)
        }
        Box(
            modifier = if (directionLabel != null) {
                Modifier.semantics { contentDescription = directionLabel }
            } else {
                Modifier
            }
        ) {
            PhIcon(name = iconName, size = 18.dp, tint = OrbitTheme.colors.fgMuted)
        }
        Spacer(Modifier.width(OrbitTheme.spacing.x3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    isAttempt -> stringResource(R.string.contact_call_attempted)
                    isManual -> stringResource(R.string.contact_call_logged)
                    else -> call.lengthLabel.asString()
                },
                style = OrbitTheme.type.body,
                color = OrbitTheme.colors.fg
            )
            Text(
                text = call.relativeWhen.asString(),
                style = OrbitTheme.type.meta,
                color = OrbitTheme.colors.fgMuted
            )
        }
    }
}

/**
 * LOG-03 — inline retroactive-note affordance rendered below the tinted
 * CallHistoryRow when the user arrives via Routes.contactWithFocus with
 * `scrollToCallEventId` set. Pragmatic v1 design: a small inline TextField +
 * Secondary "Add note to this call" button (Call is the screen's one accent,
 * rules.md §Design 5) — no dialog, no extra route, no new ViewModel state
 * surface.
 *
 * On Save:
 *   - parent invokes `vm.onAddRetroactiveNote(callEventId, draft)` which calls
 *     [AddRetroactiveNoteUseCase] with `occurredAt = event.occurredAt`
 *     (byId O(1) lookup).
 *   - the snackbar collector renders "Note saved" — the new note appears in
 *     the NotesSection above with a back-dated relative timestamp (matches the
 *     call's age, NOT "just now").
 */
@Composable
private fun RetroNoteAffordance(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = OrbitTheme.spacing.x2, bottom = OrbitTheme.spacing.x4)
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            maxLines = 4,
            textStyle = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(OrbitTheme.colors.accent),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(OrbitTheme.shapes.md)
                        .background(OrbitTheme.colors.bgSubtle)
                        .padding(
                            horizontal = OrbitTheme.spacing.x4,
                            vertical = OrbitTheme.spacing.x3
                        )
                        .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = stringResource(R.string.contact_retro_note_hint),
                            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted)
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        OrbitButton(
            text = stringResource(R.string.contact_retro_note_add),
            onClick = onSave,
            enabled = draft.isNotBlank(),
            // Secondary — the hero Call button is the screen's one terracotta
            // element (rules.md design rule 5).
            variant = OrbitButtonVariant.Secondary,
            leadingIcon = "note-pencil",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatPhone(raw: String): String {
    // PhoneNumberUtils returns null for unparseable input — fall back to raw.
    return android.telephony.PhoneNumberUtils.formatNumber(
        raw,
        java.util.Locale.getDefault().country
    ) ?: raw
}

// Preview fixture for the stateless ContactDetailContent. `internal` so the
// Robolectric screen tests (ContactDetailScreenTest, ContactDetailCurtainTest)
// render the same person the gallery does.
internal val previewContact: Contact = Contact(
    id = "preview-1",
    name = CURTAIN_FIXTURE_NAME,
    phone = "+1 555 0100",
    lastCalledLabel = UiText.plural(R.plurals.time_ago_days, 11, 11),
    avgLengthLabel = formatDuration(14 * 60),
    pickupRateLabel = "82%",
    totalCalls = 12,
    due = false,
    listIds = listOf("inner-orbit"),
    bestWindowLabel = UiText.res(R.string.time_daypart_evenings),
    heat = FloatArray(24) { 0f },
    history = emptyList(),
    notes = emptyList(),
    patternNote = "",
    measuredCalls = 12
)

internal val previewState: ContactDetailUiState.Ready = ContactDetailUiState.Ready(
    contact = previewContact,
    notes = listOf(
        NoteRow(
            id = 1L,
            contactId = 1L,
            body = "Starting the new job on Monday. Ask how the first week went.",
            createdAtMs = 0L,
            relativeTimestamp = UiText.plural(R.plurals.time_ago_days, 11, 11),
            absoluteTimestamp = "Sep 24 · 7:40pm"
        )
    ),
    listsOn = listOf("Inner orbit"),
    recentCalls = listOf(
        CallEntry(
            direction = CallDirection.Outgoing,
            relativeWhen = UiText.plural(R.plurals.time_ago_days, 11, 11),
            lengthLabel = formatDuration(14 * 60)
        ),
        CallEntry(
            direction = CallDirection.Incoming,
            relativeWhen = UiText.plural(R.plurals.time_ago_months, 1, 1),
            lengthLabel = formatDuration(32 * 60)
        )
    ),
    longestGapLabel = UiText.plural(R.plurals.time_span_days, 21, 21),
    recentCallEventIds = listOf(1L, 2L)
)

/** The stateless screen over a state, as the previews and the screen tests render it. */
@Composable
internal fun ContactDetailPreviewHost(state: ContactDetailUiState) {
    OrbitTheme {
        ContactDetailContent(
            state = state,
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onAddToLists = {},
            onDraftChange = {},
            onAddNote = {},
            onDeleteNote = {},
            onEditNote = { _, _ -> },
            onIgnore = {},
            onUnignore = {},
            onPauseContact = {},
            onUnpauseContact = {},
            onUnpauseNow = {},
            onViewAllCalls = {},
            onRetry = {},
            onRelink = {},
            onArchive = {},
            onOpenOverride = {},
            onSaveOverride = {},
            onClearOverride = {},
            onAddRetroactiveNote = { _, _ -> },
            onLogConnection = { _, _, _ -> }
        )
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun ContactDetailContentPreview() {
    ContactDetailPreviewHost(previewState)
}

/** A person with no history: every stat says so in words, not a dash. */
@PreviewLightDark
@Composable
private fun ContactDetailNewPersonPreview() {
    ContactDetailPreviewHost(
        ContactDetailUiState.Ready(
            contact = previewContact.copy(
                lastCalledLabel = null,
                totalCalls = 0,
                measuredCalls = 0,
                avgLengthLabel = null,
                bestWindowLabel = null
            ),
            notes = emptyList(),
            listsOn = emptyList(),
            recentCalls = emptyList(),
            longestGapLabel = null
        )
    )
}

/** Under the privacy curtain: no name, face, list name or note body shows. */
@PreviewLightDark
@Composable
private fun ContactDetailCurtainPreview() {
    ContactDetailCurtainContent()
}

/**
 * The curtained screen over the preview person ([CURTAIN_FIXTURE_NAME]).
 * `internal` so ContactDetailCurtainTest can check the name reaches no text
 * and no TalkBack label (the precedent is CardFaceCurtainTest's face).
 */
@Composable
internal fun ContactDetailCurtainContent() {
    CompositionLocalProvider(LocalPrivacyCurtain provides true) {
        ContactDetailPreviewHost(previewState)
    }
}

/** The preview person's name, which must never show under the curtain. */
internal const val CURTAIN_FIXTURE_NAME = "Avery Quinn"

@PreviewLightDark
@Composable
private fun ContactDetailErrorPreview() {
    ContactDetailPreviewHost(ContactDetailUiState.Error)
}

@PreviewLightDark
@Composable
private fun ContactDetailNotFoundPreview() {
    ContactDetailPreviewHost(ContactDetailUiState.NotFound)
}

/**
 * The phone contact was deleted (CONTACT-06): the banner, Call demoted to
 * Secondary, the notes readable, and only "View all calls" in the overflow.
 */
@PreviewLightDark
@Composable
private fun ContactDetailOrphanedPreview() {
    ContactDetailPreviewHost(
        ContactDetailUiState.Orphaned(
            contact = previewContact,
            listsOn = previewState.listsOn,
            recentCalls = previewState.recentCalls,
            longestGapLabel = previewState.longestGapLabel,
            notes = previewState.notes,
            recentCallEventIds = previewState.recentCallEventIds
        )
    )
}

/** Call log access is off: the notice, and no "Never called" or "Not enough calls yet". */
@PreviewLightDark
@Composable
private fun ContactDetailCallLogDeniedPreview() {
    ContactDetailPreviewHost(
        ContactDetailUiState.Ready(
            contact = previewContact.copy(
                lastCalledLabel = null,
                totalCalls = 0,
                measuredCalls = 0,
                avgLengthLabel = null,
                bestWindowLabel = null
            ),
            notes = emptyList(),
            listsOn = previewState.listsOn,
            recentCalls = emptyList(),
            longestGapLabel = null,
            callLogDenied = true
        )
    )
}

/** An ignored person: the status line under the number (CONTACT-10). */
@PreviewLightDark
@Composable
private fun ContactDetailIgnoredPreview() {
    ContactDetailPreviewHost(previewState.copy(isIgnored = true))
}

/**
 * Contact detail overflow, ordered by the shared [OrbitDropdownMenu] contract:
 * the everyday reads lead, and "Ignore" sits last in danger.
 *
 * - While a pause is in force the pause slot offers Unpause instead. There was
 *   no Unpause anywhere before, so an indefinite pause outlived its snackbar
 *   permanently.
 * - While the person is ignored (CONTACT-10) the Ignore slot offers Unignore,
 *   in the default tone (it restores, it removes nothing), and Pause is not
 *   offered: a paused ignored person surfaces nowhere either way. Before
 *   this the page offered Ignore again and the way back was two screens away.
 * - "Open in Contacts" (voice.md: "Contacts" is the phone's address book)
 *   sits between the pause slot and Ignore, only for someone the phone knows
 *   ([hasPhoneContact]); a call-log-only person has no card to open.
 * - The orphaned page keeps only "View all calls": Re-link and Archive are the
 *   banner's, and Pause or Ignore would act on someone who surfaces nowhere.
 *
 * Every row carries an icon (contract point 3: all or none). `internal` so
 * the order is unit-tested (ContactOverflowMenuTest). Takes [Resources]
 * because [OrbitMenuAction] carries resolved text (the precedent is
 * `listRowMenuActions`).
 */
internal fun contactOverflowActions(
    resources: Resources,
    isPaused: Boolean,
    isIgnored: Boolean = false,
    hasPhoneContact: Boolean = false,
    orphaned: Boolean = false,
    onViewAllCalls: () -> Unit,
    onPause: () -> Unit,
    onUnpause: () -> Unit,
    onOpenInContacts: () -> Unit = {},
    onIgnore: () -> Unit,
    onUnignore: () -> Unit = {}
): List<OrbitMenuAction> = buildList {
    add(
        OrbitMenuAction(
            label = resources.getString(R.string.contact_menu_view_all_calls),
            onClick = onViewAllCalls,
            icon = "clock-counter-clockwise"
        )
    )
    if (orphaned) return@buildList
    if (!isIgnored) {
        add(
            if (isPaused) {
                OrbitMenuAction(
                    label = resources.getString(R.string.contact_menu_unpause),
                    onClick = onUnpause,
                    icon = "play"
                )
            } else {
                OrbitMenuAction(
                    label = resources.getString(R.string.contact_menu_pause),
                    onClick = onPause,
                    icon = "pause-circle"
                )
            }
        )
    }
    if (hasPhoneContact) {
        add(
            OrbitMenuAction(
                label = resources.getString(R.string.contact_menu_open_in_contacts),
                onClick = onOpenInContacts,
                icon = "user-circle"
            )
        )
    }
    add(
        if (isIgnored) {
            OrbitMenuAction(
                label = resources.getString(R.string.contact_menu_unignore),
                onClick = onUnignore,
                icon = "eye"
            )
        } else {
            OrbitMenuAction(
                label = resources.getString(R.string.contact_menu_ignore),
                onClick = onIgnore,
                icon = "eye-slash",
                tone = OrbitMenuTone.Destructive
            )
        }
    )
}
