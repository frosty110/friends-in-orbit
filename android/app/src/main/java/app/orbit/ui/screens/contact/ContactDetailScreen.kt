package app.orbit.ui.screens.contact

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
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
import app.orbit.ui.components.InfoTip
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.ContactStatsPanel
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.ListContextChip
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.StatEntry
import app.orbit.ui.screens.contact.sections.LogConnectionSheet
import app.orbit.ui.screens.contact.sections.NotesSection
import app.orbit.ui.screens.contact.sections.PauseSheet
import app.orbit.ui.screens.contact.sections.RuleOverrideSection
import app.orbit.ui.screens.contact.sections.UnpauseBanner
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.dialPhoneNumber
import app.orbit.ui.util.formatDuration
import kotlinx.coroutines.delay

/**
 * Contact Detail — read-only information surface for
 * CONTACT-01 / CONTACT-02 / CONTACT-06.
 *
 * Renders: Coil-backed photo with Avatar fallback, hero name (32sp) + phone
 * number, "On these lists" chip row, 5-row stats panel (Last call / Total
 * calls / Avg length / Longest gap / Usually), recent calls list (50/page).
 *
 * Hero action row: "Call" (Primary — the screen's one terracotta element;
 * ACTION_DIAL via the shared Dialer util) and "Log a connection" (Secondary —
 * opens [LogConnectionSheet] to record a connection the carrier call log
 * can't see). The phone-number row is also tappable to dial.
 *
 * Privacy curtain (PRIV-03): hero name + lists-on chip labels render the
 * literal "Contact" when [LocalPrivacyCurtain] is true (focus-loss). The
 * phone number is masked too ("Number hidden", PRIV-07): a number identifies
 * a person as surely as a name, and it used to show through the curtain.
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

    // NOTE-02 — listen for the VM's one-shot focus signal (set by the
    // ContactDetailViewModel `init` block when `focusNote=1` is in
    // SavedStateHandle). The 100ms delay mitigates "requestFocus() called
    // before the BasicTextField is laid out" on cold deep-links.
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
        // lookup). Wired to the inline "Add note to this call" Primary
        // button rendered below the highlighted call row when the user
        // arrives via Routes.contactWithFocus(scrollToCallEventId = ...).
        onAddRetroactiveNote = vm::onAddRetroactiveNote,
        // Manual connection log — confirm handler for LogConnectionSheet.
        // Inserts a CallEventEntity(source = MANUAL) via MarkCalledUseCase so
        // per-list nextDueAt advances; quiet "Logged." snackbar on success.
        onLogConnection = vm::onLogConnection,
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
    notesInputFocusRequester: FocusRequester? = null
) {
    // CONTACT-04, IGNORE-02 — overflow + pause sheet
    // visibility flags. `showOverflow` is non-saveable (transient); the
    // PauseSheet flag uses rememberSaveable so a configuration change while
    // the sheet is open keeps it open (matches CreateListBottomSheet pattern).
    var showOverflow by remember { mutableStateOf(false) }
    var showPauseSheet by rememberSaveable { mutableStateOf(false) }
    // Manual connection log — same rememberSaveable rationale as PauseSheet.
    var showLogConnectionSheet by rememberSaveable { mutableStateOf(false) }

    // PRIV-03: the overflow's TalkBack label names the person, so it is
    // masked with every other name. Under the curtain it read "More actions
    // for Avery Quinn" (ContactDetailCurtainTest).
    val curtain = LocalPrivacyCurtain.current
    val readyContactName: String? = (state as? ContactDetailUiState.Ready)?.contact?.name?.takeUnless { curtain }

    Box(modifier = Modifier.fillMaxSize()) {
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
                trailing = if (state is ContactDetailUiState.Ready) {
                    {
                        Box {
                            OrbitIconButton(
                                icon = "dots-three-vertical",
                                onClick = { showOverflow = true },
                                contentDescription = readyContactName
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
                                    isPaused = (state as? ContactDetailUiState.Ready)?.pausedLabel != null,
                                    onViewAllCalls = onViewAllCalls,
                                    onPause = { showPauseSheet = true },
                                    onUnpause = onUnpauseNow,
                                    onIgnore = { readyContactName?.let { onIgnore(it) } }
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
                ContactDetailUiState.NotFound -> OrbitScreenMessage(
                    icon = "user",
                    title = stringResource(R.string.contact_not_found_title),
                    body = stringResource(R.string.contact_not_found_body),
                    actionLabel = stringResource(R.string.contact_go_back),
                    onAction = onBack
                )
                // CONTACT-08: a failed read says so, with Retry, instead of
                // crashing. The only action here, so it takes the accent.
                ContactDetailUiState.Error -> OrbitScreenMessage(
                    icon = "warning-circle",
                    title = stringResource(R.string.contact_error_title),
                    body = stringResource(R.string.contact_error_body),
                    actionLabel = stringResource(R.string.contact_try_again),
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
                    onDraftChange = onDraftChange,
                    onAddNote = onAddNote,
                    onDeleteNote = onDeleteNote,
                    onEditNote = onEditNote,
                    onUnpauseContact = onUnpauseContact,
                    onOpenOverride = onOpenOverride,
                    onSaveOverride = onSaveOverride,
                    onClearOverride = onClearOverride,
                    onAddRetroactiveNote = onAddRetroactiveNote,
                    notesInputFocusRequester = notesInputFocusRequester
                )
                is ContactDetailUiState.Orphaned -> Column(modifier = Modifier.fillMaxSize()) {
                    OrphanBanner(
                        onRelink = onRelink,
                        onArchive = { onArchive(state.contact.name) }
                    )
                    ContactBodyLazyColumn(
                        contact = state.contact,
                        notes = emptyList(), // Orphan: notes section hidden
                        draft = "",
                        listsOn = state.listsOn,
                        recentCalls = state.recentCalls,
                        longestGapLabel = state.longestGapLabel,
                        unpausePromptVisible = false, // Orphan path bypasses unpause banner.
                        pausedLabel = null, // Orphans are not surfaced, so a pause has no effect to report.
                        // Orphan path bypasses RuleOverrideSection — section
                        // wraps in its own AnimatedVisibility on listsOnSize >= 2,
                        // and edit affordances are off per the orphan banner copy.
                        customScheduleVisible = false,
                        currentTemplateName = null,
                        primaryListName = "",
                        hasOverride = false,
                        currentParams = null,
                        // Orphan path: no CallLog deep-link surfaces; the
                        // overflow menu is also disabled upstream so this is
                        // belt-and-suspenders.
                        scrollToCallEventId = null,
                        retroNoteAffordanceFor = null,
                        callEventIds = emptyList(),
                        recentCallIsManual = state.recentCallIsManual,
                        recentCallIsAttempt = state.recentCallIsAttempt,
                        // Orphan: the banner's Re-link button owns the
                        // screen's terracotta — Call demotes to Secondary.
                        // The number survives orphaning, so calling still works.
                        callButtonVariant = OrbitButtonVariant.Secondary,
                        onOpenLogConnection = null, // Orphan: edit affordances disabled (banner copy)
                        onAddToLists = null, // Orphan: edit affordances disabled (banner copy)
                        onDraftChange = {},
                        onAddNote = {},
                        onDeleteNote = {},
                        onEditNote = { _, _ -> },
                        onUnpauseContact = {},
                        onOpenOverride = {},
                        onSaveOverride = {},
                        onClearOverride = {},
                        onAddRetroactiveNote = { _, _ -> },
                        notesInputFocusRequester = null
                    )
                }
            }
        }
        // PauseSheet is rendered at the screen scope (window-level
        // ModalBottomSheet) so it overlays the OrbitScreen content cleanly.
        if (showPauseSheet) {
            PauseSheet(
                onSelect = { duration ->
                    onPauseContact(duration)
                    // Pitfall 1 dismissal happens inside PauseSheet via the
                    // launch-then-flip pattern; we just mirror the flag here.
                    showPauseSheet = false
                },
                onDismiss = { showPauseSheet = false }
            )
        }
        // Manual connection log — sheet at screen scope, mirroring PauseSheet.
        // Confirm dispatches to the VM; dismissal follows the Pitfall 1
        // launch-then-flip pattern inside the sheet.
        if (showLogConnectionSheet) {
            LogConnectionSheet(
                onConfirm = onLogConnection,
                onDismiss = { showLogConnectionSheet = false }
            )
        }
        SnackbarHost(
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
    customScheduleVisible: Boolean,
    currentTemplateName: UiText?,
    primaryListName: String,
    hasOverride: Boolean,
    currentParams: RuleParams?,
    // LOG-03 — CallLog deep-link surface. `callEventIds` is parallel-
    // indexed with [recentCalls] (same DESC-by-occurredAt order); the screen
    // uses it to find the LazyColumn item index for [scrollToCallEventId] and
    // to gate the inline "Add note to this call" Primary button visibility.
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
    onDraftChange: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (NoteRow) -> Unit,
    onEditNote: (NoteRow, String) -> Unit,
    onUnpauseContact: () -> Unit,
    onOpenOverride: () -> Unit,
    onSaveOverride: (RuleParams) -> Unit,
    onClearOverride: () -> Unit,
    onAddRetroactiveNote: (Long, String) -> Unit,
    notesInputFocusRequester: FocusRequester? = null
) {
    val curtain = LocalPrivacyCurtain.current
    val displayName = if (curtain) stringResource(R.string.components_curtain_contact) else contact.name

    // LOG-03 — `LazyListState` hoisted so the screen can
    // animateScrollToItem to the matching call-event row when the user
    // arrives via the CallLog deep link. The `scrollToCallEventId` arg
    // points at a specific row; we resolve it to a LazyColumn item index
    // by walking the (fixed-shape) item list above and adding the
    // call-event row offset within the recentCalls section.
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToCallEventId, callEventIds) {
        val target = scrollToCallEventId ?: return@LaunchedEffect
        val callRowIdx = callEventIds.indexOf(target)
        if (callRowIdx < 0) return@LaunchedEffect
        // Item layout above the call rows is fixed-shape; rather than
        // hard-coding the offset (which silently breaks if a future plan
        // inserts a section above), we treat the LazyListState's last
        // visible item as a guide and animate to "the call row's
        // position" by the recentCalls list's contribution. The simplest
        // resilient approach: scroll to a generous lower bound (call rows
        // sit roughly past the hero/lists/stats/notes/override + section
        // header items) — Compose clamps overshoots automatically. The
        // actual index math: count of items above `recentCalls` equals
        // 6 fixed items (UnpauseBanner placeholder, Hero, ListsOn,
        // Stats, optional AddToLists, optional Notes, optional Override,
        // RecentCalls eyebrow). Conditional sections introduce variance,
        // so we add a generous floor and let `animateScrollToItem` clamp.
        // 8 = max items before recent calls (most-conservative case);
        // adding callRowIdx aligns the target row near the top.
        val approxIndex = 8 + callRowIdx
        runCatching { listState.animateScrollToItem(approxIndex) }
    }

    // LOG-03 — inline retro-note draft text. `rememberSaveable` so a
    // configuration change while the user is typing keeps the body. Keyed
    // on the affordance target so a future ID flip resets the draft.
    var retroNoteDraft by rememberSaveable(retroNoteAffordanceFor) { mutableStateOf("") }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = OrbitTheme.spacing.x6,
            vertical = OrbitTheme.spacing.x4
        )
    ) {
        // CONTACT-05 — UnpauseBanner item at top of body.
        // Renders only when pausedUntil <= now AND not the indefinite sentinel
        // (VM-derived). AnimatedVisibility uses OrbitMotion.DurBaseMs (250ms).
        item {
            AnimatedVisibility(
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
        }

        // Hero
        item {
            val context = LocalContext.current
            val hasPhone = contact.phone.isNotBlank()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // The photo is masked with the name under the curtain: a face
                // identifies a person as surely as their name does. It showed
                // through until 2026-10-05.
                Avatar(name = displayName, size = 120.dp, photoUri = if (curtain) null else contact.photoUri)
                Spacer(Modifier.height(OrbitTheme.spacing.x4))
                Text(
                    text = displayName,
                    style = OrbitTheme.type.hero,
                    color = OrbitTheme.colors.fg
                )
                // FINDING A — tappable phone row. ACTION_DIAL via the shared
                // Dialer util (never CALL_PHONE); 48dp target per design rule 3.
                // PRIV-07: masked under the curtain like the name and photo.
                val formattedPhone = if (curtain) {
                    stringResource(R.string.contact_number_hidden)
                } else {
                    formatPhone(contact.phone)
                }
                val callNumberLabel = stringResource(R.string.contact_call_number, formattedPhone)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
                        .clip(OrbitTheme.shapes.md)
                        .clickable(enabled = hasPhone && !curtain) { context.dialPhoneNumber(contact.phone) }
                        .semantics {
                            role = Role.Button
                            contentDescription = callNumberLabel
                        }
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

        // On these lists
        item {
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

        // Stats
        item {
            Spacer(Modifier.height(OrbitTheme.spacing.x6))
            SectionEyebrow(stringResource(R.string.contact_section_stats))
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
            val notEnoughCalls = stringResource(R.string.contact_stat_not_enough_calls)
            ContactStatsPanel(
                stats = listOf(
                    StatEntry(
                        label = stringResource(R.string.contact_stat_last_call),
                        value = contact.lastCalledLabel?.asString()
                            ?: stringResource(R.string.contact_stat_never_called)
                    ),
                    StatEntry(
                        label = stringResource(R.string.contact_stat_total_calls),
                        value = contact.totalCalls.toString()
                    ),
                    // Plain words, not "Avg", and a sentence, not a bare dash,
                    // where there isn't enough history yet: a dash read as
                    // broken (vision CONTACT-3, rubric D7). A null label is
                    // "nothing to state" (no measured call, fewer than two
                    // calls), so there is no placeholder value to filter out.
                    StatEntry(
                        label = stringResource(R.string.contact_stat_average_length),
                        value = contact.avgLengthLabel
                            ?.takeIf { contact.totalCalls >= 3 }
                            ?.asString()
                            ?: notEnoughCalls
                    ),
                    StatEntry(
                        label = stringResource(R.string.contact_stat_longest_gap),
                        value = longestGapLabel?.asString() ?: notEnoughCalls
                    )
                )
            )
            HorizontalDivider(
                color = OrbitTheme.colors.line,
                thickness = 1.dp
            )
            UsuallyStatRow(value = contact.bestWindowLabel?.asString() ?: notEnoughCalls)
        }

        // BULK-06 — "Add to lists" entry to the reverse picker.
        // Hidden when the contact is orphaned (edit affordances are off per
        // the orphan banner copy).
        if (onAddToLists != null) {
            item {
                Spacer(Modifier.height(OrbitTheme.spacing.x6))
                OrbitButton(
                    text = stringResource(R.string.contact_add_to_lists),
                    onClick = onAddToLists,
                    // Secondary — the hero Call button is the screen's one
                    // terracotta element (rules.md design rule 5).
                    variant = OrbitButtonVariant.Secondary,
                    leadingIcon = "user-plus",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // NOTE-01 — Notes journaling section. Hidden when
        // the contact is Orphaned (parent passes notes = emptyList() with no-op
        // callbacks; we still render the eyebrow only when there's edit room,
        // i.e. onAddToLists != null mirrors the "this contact is editable"
        // signal upstream).
        if (onAddToLists != null) {
            item {
                Spacer(Modifier.height(OrbitTheme.spacing.x6))
                NotesSection(
                    notes = notes,
                    draft = draft,
                    onDraftChange = onDraftChange,
                    onAdd = { onAddNote(draft) },
                    onDelete = onDeleteNote,
                    onEditCommit = onEditNote,
                    inputFocusRequester = notesInputFocusRequester
                )
            }
        }

        // CONTACT-03 — RuleOverrideSection. Visibility
        // is double-gated: the screen-side `customScheduleVisible` (VM-derived
        // from listsOn.size >= 2) decides whether to add the LazyColumn item
        // at all, and the section's own AnimatedVisibility wraps the body
        // for the in/out animation. Pitfall 6 corrupted-JSON recovery flows
        // a fresh KeepInTouch default down so the editor still renders when
        // currentParams == null; the eyebrow flips to "Custom schedule
        // (recovering)" via VM-derived currentTemplateName.
        if (customScheduleVisible) {
            item {
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
        }

        // Recent calls
        item {
            Spacer(Modifier.height(OrbitTheme.spacing.x6))
            SectionEyebrow(stringResource(R.string.contact_section_recent_calls))
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
        }
        if (recentCalls.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.contact_no_calls),
                    style = OrbitTheme.type.body,
                    color = OrbitTheme.colors.fgMuted
                )
            }
        } else {
            // CallEntry has no occurredAt field (Model.kt:39 — direction /
            // relativeWhen / lengthLabel only). The parallel-indexed
            // [callEventIds] list (LOG-03) carries the primary
            // keys so we can (a) gate the inline retro-note affordance
            // below the matching row and (b) honour scroll-to-call.
            items(
                count = recentCalls.size,
                key = { idx -> "call-$idx" },
                contentType = { "callRow" }
            ) { idx ->
                CallHistoryRow(
                    call = recentCalls[idx],
                    isManual = recentCallIsManual.getOrNull(idx) ?: false,
                    isAttempt = recentCallIsAttempt.getOrNull(idx) ?: false
                )
                // LOG-03 — inline "Add note to this call" affordance. Renders
                // below the highlighted call row (the one the CallLog deep link
                // pointed at). The composable is a small Primary button + a
                // BasicTextField; tapping Save invokes vm::onAddRetroactiveNote
                // which back-dates createdAt to the call's occurredAt via the
                // byId O(1) lookup (no observeAll snapshot).
                val callEventId = callEventIds.getOrNull(idx)
                if (callEventId != null && retroNoteAffordanceFor == callEventId) {
                    RetroNoteAffordance(
                        draft = retroNoteDraft,
                        onDraftChange = { retroNoteDraft = it },
                        onSave = {
                            if (retroNoteDraft.isNotBlank()) {
                                onAddRetroactiveNote(callEventId, retroNoteDraft)
                                retroNoteDraft = ""
                            }
                        }
                    )
                }
            }
        }

        // Bottom breathing room
        item { Spacer(Modifier.height(OrbitTheme.spacing.x8)) }
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
 * Visibility is `internal` (downgraded from `private`) so
 * `OrphanBannerTest` (androidTest) can reference the composable directly
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
 * [isManual] rows are user-logged connections (CallSource.MANUAL,
 * durationSeconds = 0): a check-circle icon + "Logged" replaces the
 * direction icon + duration label, which would otherwise read "—".
 *
 * [isAttempt] rows are reach-outs that didn't connect (CallSource.ATTEMPT,
 * durationSeconds = 0): a phone-slash icon + "Attempted" — distinct from a
 * logged connection, because you reached out but didn't actually talk.
 */
@Composable
private fun CallHistoryRow(call: CallEntry, isManual: Boolean = false, isAttempt: Boolean = false) {
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
        PhIcon(name = iconName, size = 18.dp, tint = OrbitTheme.colors.fgMuted)
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
 * LOG-03 — inline retroactive-note affordance rendered below the
 * highlighted CallHistoryRow when the user arrives via Routes.contactWithFocus
 * with `scrollToCallEventId` set. Pragmatic v1 design: a small inline
 * TextField + Primary Save button — no dialog, no extra route, no new
 * ViewModel state surface.
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

// Preview fixture for the stateless ContactDetailContent.
private val previewContact: app.orbit.data.Contact = app.orbit.data.Contact(
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
    patternNote = ""
)

private val previewState: ContactDetailUiState = ContactDetailUiState.Ready(
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
    longestGapLabel = UiText.plural(R.plurals.time_span_days, 21, 21)
)

@Composable
private fun ContactDetailPreviewHost(state: ContactDetailUiState) {
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
 * Contact detail overflow, ordered by the shared [OrbitDropdownMenu] contract:
 * the everyday reads lead, and "Ignore" sits last in danger.
 *
 * While a pause is in force the pause slot offers Unpause instead. There was
 * no Unpause anywhere before, so an indefinite pause outlived its snackbar
 * permanently. `internal` so the order is unit-tested (ContactOverflowMenuTest).
 * Takes [Resources] because [OrbitMenuAction] carries resolved text (the
 * precedent is `listRowMenuActions`).
 */
internal fun contactOverflowActions(
    resources: Resources,
    isPaused: Boolean,
    onViewAllCalls: () -> Unit,
    onPause: () -> Unit,
    onUnpause: () -> Unit,
    onIgnore: () -> Unit
): List<OrbitMenuAction> = listOf(
    OrbitMenuAction(
        label = resources.getString(R.string.contact_menu_view_all_calls),
        onClick = onViewAllCalls,
        icon = "clock-counter-clockwise"
    ),
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
    },
    OrbitMenuAction(
        label = resources.getString(R.string.contact_menu_ignore),
        onClick = onIgnore,
        icon = "eye-slash",
        tone = OrbitMenuTone.Destructive
    )
)
