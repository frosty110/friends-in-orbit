package app.orbit.ui.screens.browse

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import app.orbit.data.ChipTone
import app.orbit.data.Contact
import app.orbit.data.entity.ListEntity
import app.orbit.domain.model.PauseDuration
import app.orbit.ui.components.BrowseRow
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitChip
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitSearchField
import app.orbit.ui.components.PhIcon
import app.orbit.ui.screens.contact.sections.PauseSheet
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.dialPhoneNumber
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Browse outer composable with multi-select gesture-and-bar substrate.
 *
 * Two-layer Hilt pattern preserved:
 *   - outer reads `vm.uiState`, `vm.searchQuery`, `vm.activeFilters`, `vm.lists`
 *     and forwards callbacks to the inner stateless [BrowseContent].
 *   - inner owns the local debounced TextField buffer (via `snapshotFlow` +
 *     `debounce(250)`) and forwards committed query strings to `vm::onSearchChanged`.
 *
 * BROWSE-01: per-list contacts sorted `lastCallAt DESC NULLS LAST` (VM-side).
 * BROWSE-02: 250ms debounced search + 2 filter chips (chip×chip = UNION per
 *            user decision).
 * BROWSE-04: long-press on a row enters multi-select; combinedClickable
 *            modifier chain identical in both modes. Haptic fires ONLY on entry.
 * BROWSE-05: trailing phone icon on each row → `dialPhoneNumber`.
 * BULK-05  : trailing "+" in single-select app-bar → BULK-05 picker entry.
 * MOVE-01  : combinedClickable + LocalHapticFeedback on entry.
 * MOVE-02  : AnimatedContent fadeIn/fadeOut(250) cross-fade swap of
 *            OrbitAppBar ↔ MultiSelectActionBar — replacement, not floating.
 * MOVE-03/04: Move/Copy via inline [ListSelectorSheet].
 * MOVE-05  : VM-side onSelectAllVisible / onSelectAllMatching.
 * MOVE-06  : BackHandler(enabled = isMultiSelect) consumes back gesture.
 * MOVE-07  : Snackbar undo backed by [UndoStack].
 * PRIV-03:   app-bar title + row primary names obey `LocalPrivacyCurtain.current`.
 */
@Composable
fun BrowseListScreen(
    @Suppress("UNUSED_PARAMETER") listId: String,  // route arg; VM reads from SavedStateHandle
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    onAddContacts: (listId: String?) -> Unit,
    vm: BrowseViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val initialQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val activeFilters by vm.activeFilters.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val listName by vm.listName.collectAsStateWithLifecycle()

    // 2026-06-09 #19 — real READ_CALL_LOG state, refreshed on every ON_RESUME
    // (CardViewScreen precedent) so returning from Settings clears the notice.
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        vm.onCallLogPermissionChanged(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG,
            ) != PackageManager.PERMISSION_GRANTED,
        )
        onPauseOrDispose { }
    }

    BrowseContent(
        state = state,
        initialQuery = initialQuery,
        activeFilters = activeFilters,
        lists = lists,
        listId = listId,
        listName = listName,
        onSearchChanged = vm::onSearchChanged,
        onToggleFilter = vm::onToggleFilter,
        onClearFilters = vm::onClearFilters,
        onBack = onBack,
        onOpenContact = onOpenContact,
        onAddContacts = onAddContacts,
        onEnterMultiSelect = vm::onEnterMultiSelect,
        onToggleSelect = vm::onToggleSelect,
        onExitMultiSelect = vm::onExitMultiSelect,
        onBulkRemove = vm::onBulkRemove,
        onBulkIgnore = vm::onBulkIgnore,
        onBulkPause = vm::onBulkPause,
        onBulkMove = vm::onBulkMove,
        onBulkCopy = vm::onBulkCopy,
        onSingleRowIgnore = vm::onSingleRowIgnore,
        onSingleRowPause = vm::onSingleRowPause,
        onUndo = vm::onUndo,
        onContactIdParseFail = vm::onContactIdParseFail,
        snackbarEvents = vm.snackbarEvents,
    )
}

@OptIn(FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
private fun BrowseContent(
    state: BrowseUiState,
    initialQuery: String,
    activeFilters: Set<BrowseFilter>,
    lists: List<ListEntity>,
    listId: String,
    listName: String,
    onSearchChanged: (String) -> Unit,
    onToggleFilter: (BrowseFilter) -> Unit,
    onClearFilters: () -> Unit,
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    onAddContacts: (listId: String?) -> Unit,
    onEnterMultiSelect: (Long) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onExitMultiSelect: () -> Unit,
    onBulkRemove: () -> Unit,
    onBulkIgnore: () -> Unit,
    onBulkPause: (PauseDuration) -> Unit,
    onBulkMove: (Long, String) -> Unit,
    onBulkCopy: (Long, String) -> Unit,
    onSingleRowIgnore: (Long, String) -> Unit,
    onSingleRowPause: (Long, String, PauseDuration) -> Unit,
    onUndo: () -> Unit,
    onContactIdParseFail: () -> Unit,
    snackbarEvents: kotlinx.coroutines.flow.SharedFlow<app.orbit.ui.screens.picker.SnackbarEvent>,
) {
    val curtain = LocalPrivacyCurtain.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var queryText by rememberSaveable { mutableStateOf(initialQuery) }

    // 250ms debounce on local TextField buffer; only debounced values reach the VM.
    LaunchedEffect(Unit) {
        snapshotFlow { queryText }
            .debounce(250)
            .distinctUntilChanged()
            .collect { onSearchChanged(it) }
    }

    // Snackbar event collector. VM emits a SnackbarEvent on Bulk* commit; tap
    // on Undo runs the inverse closure recorded on UndoStack.
    // Gated by STARTED so the snackbar does not fire on a
    // backgrounded screen (SnackbarEvent SharedFlow has replay = 0).
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snackbarEvents.collect { event ->
                val r = snackbarHostState.showSnackbar(
                    message = event.message,
                    actionLabel = event.actionLabel,
                    duration = SnackbarDuration.Short,
                    withDismissAction = false,
                )
                if (r == SnackbarResult.ActionPerformed) onUndo()
            }
        }
    }

    val searchPlaceholder = if (curtain) "Search people" else "Search your people"

    val isMs = (state as? BrowseUiState.Ready)?.isMultiSelect ?: false
    val selectedIds: Set<Long> = (state as? BrowseUiState.Ready)?.selectedIds ?: emptySet()
    val sourceListIdLong: Long? = listId.toLongOrNull()

    // BackHandler — MOVE-06. Enabled only in multi-select to avoid double-consuming back.
    BackHandler(enabled = isMs) { onExitMultiSelect() }

    // Move/Copy/Pause inline triggers (DECISION — see ListSelectorSheet KDoc).
    var showPauseDialog by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showCopySheet by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    // Single-row long-press DropdownMenu state.
    // `menuAnchorContactId` tracks WHICH row's menu is open (null = none);
    // `pauseSheetForContactId` opens the PauseSheet for the chosen
    // single-row Pause action. `pauseSheetForContactName` carries the display
    // name for the snackbar copy ("Paused {Name} for 1 week"). Both clear on
    // dismiss. Multi-select preempts these (long-press is a no-op when
    // `isMultiSelect` is true).
    var menuAnchorContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pauseSheetForContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pauseSheetForContactName by rememberSaveable { mutableStateOf("") }

    if (showPauseDialog) {
        PauseDurationDialog(
            onSelect = { duration ->
                onBulkPause(duration)
                showPauseDialog = false
            },
            onDismiss = { showPauseDialog = false },
        )
    }
    if (showMoveSheet || showCopySheet) {
        ListSelectorSheet(
            mode = if (showMoveSheet) Mode.Move else Mode.Copy,
            lists = lists,
            currentListId = sourceListIdLong,
            onPick = { targetListId, targetListName ->
                if (showMoveSheet) onBulkMove(targetListId, targetListName)
                else onBulkCopy(targetListId, targetListName)
                showMoveSheet = false
                showCopySheet = false
            },
            onDismiss = {
                showMoveSheet = false
                showCopySheet = false
            },
        )
    }

    // Single-row Pause sheet. REUSED from
    // `app.orbit.ui.screens.contact.sections.PauseSheet`; no duplicate
    // composable. Sheet uses the `OrbitTheme.shapes.bottomSheet` token.
    pauseSheetForContactId?.let { cid ->
        PauseSheet(
            onSelect = { duration ->
                onSingleRowPause(cid, pauseSheetForContactName, duration)
                pauseSheetForContactId = null
                pauseSheetForContactName = ""
            },
            onDismiss = {
                pauseSheetForContactId = null
                pauseSheetForContactName = ""
            },
        )
    }

    OrbitScreen {
        // App-bar swap for Browse multi-select integration.
        // Duration via the OrbitMotion token, not a literal.
        AnimatedContent(
            targetState = isMs,
            transitionSpec = {
                fadeIn(tween(OrbitMotion.DurBaseMs)) togetherWith
                    fadeOut(tween(OrbitMotion.DurBaseMs))
            },
            label = "browse-app-bar",
        ) { multiSelectMode ->
            if (multiSelectMode) {
                Box {
                    MultiSelectActionBar(
                        count = selectedIds.size,
                        onExit = onExitMultiSelect,
                        onMove = { showMoveSheet = true },
                        onCopy = { showCopySheet = true },
                        onRemove = onBulkRemove,
                        onOverflow = { menuExpanded = true },
                    )
                    MultiSelectOverflowMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onIgnoreAll = {
                            onBulkIgnore()
                            menuExpanded = false
                        },
                        onPauseAll = {
                            menuExpanded = false
                            showPauseDialog = true
                        },
                    )
                }
            } else {
                OrbitAppBar(
                    // 2026-06-09 #19 — show the real list name (the VM had it
                    // all along). Curtain hides the user-authored name (list
                    // names can be sensitive — "people who ground me"); the
                    // generic title also covers the pre-emission blank.
                    title = if (curtain || listName.isBlank()) "Your people" else listName,
                    leading = {
                        OrbitIconButton(
                            icon = "arrow-left",
                            onClick = onBack,
                            contentDescription = "Back",
                        )
                    },
                    trailing = {
                        // Browse renders in queue order; the BULK-05 "+" affordance
                        // is the only trailing action.
                        OrbitIconButton(
                            icon = "plus",
                            onClick = { onAddContacts(listId) },
                            contentDescription = "Add contacts",
                        )
                    },
                )
            }
        }

        // Search field row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x3,
                ),
        ) {
            OrbitSearchField(
                query = queryText,
                onQueryChange = { queryText = it },
                placeholder = searchPlaceholder,
            )
        }

        // Filter chips row — chip×chip composition is UNION per user decision.
        Row(
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x2,
                ),
        ) {
            FilterChipPill(
                label = "Called recently",
                active = BrowseFilter.CalledRecently in activeFilters,
                onClick = { onToggleFilter(BrowseFilter.CalledRecently) },
            )
            FilterChipPill(
                label = "Not called yet",
                active = BrowseFilter.NotCalledYet in activeFilters,
                onClick = { onToggleFilter(BrowseFilter.NotCalledYet) },
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                is BrowseUiState.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                    // 2026-06-09 #19 — quiet honest notice when READ_CALL_LOG is
                    // denied: names still render, but rows drop the call-time
                    // meta (every "Never called" would be a false claim).
                    if (state.callLogPermissionDenied) {
                        Text(
                            text = "Showing names only. Call times need the call-log permission.",
                            style = OrbitTheme.type.meta,
                            color = OrbitTheme.colors.fgMuted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = OrbitTheme.spacing.x4,
                                    vertical = OrbitTheme.spacing.x2,
                                ),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = OrbitTheme.spacing.x4),
                        contentPadding = PaddingValues(bottom = OrbitTheme.spacing.x6),
                    ) {
                        // Partition contacts into queued (position number) and
                        // non-queued ("Other members" section below, no position number).
                        val (queuedContacts, otherContacts) =
                            state.contacts.partition { state.queuePositions[it.id] != null }

                        items(
                            items = queuedContacts,
                            key = { it.id },
                            contentType = { "browseRow" },
                        ) { contact ->
                            val queuePos = state.queuePositions[contact.id]
                            // UI Contact.id is "c-$entityId" (String); the use cases need Long.
                            val entityId: Long? = contact.id.removePrefix("c-").toLongOrNull()
                            val isSelected = entityId != null && entityId in state.selectedIds
                            val isMultiSelect = state.isMultiSelect
                            // combinedClickable chain identical in both modes;
                            // differentiation lives INSIDE the lambdas. Haptic fires ONLY on
                            // entry — gated by `!isMultiSelect`.
                            //
                            // BROWSE-04: long-press on a single row when NOT
                            // already in multi-select opens an anchored DropdownMenu with
                            // Call / Ignore / Pause / Select. The "Select" item invokes
                            // `onEnterMultiSelect(entityId)` — preserves the multi-select
                            // contract so all existing BrowseViewModelTest tests still pass.
                            // When already in multi-select, long-press is silent (no-op).
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isMultiSelect && isSelected) OrbitTheme.colors.accentTint
                                        else Color.Transparent,
                                    )
                                    .combinedClickable(
                                        onLongClick = {
                                            // LOW polish — surface a snackbar when the row's UI id
                                            // ("c-<long>") fails to parse, so the long-press isn't a
                                            // silent no-op. Multi-select long-press stays inert.
                                            if (!isMultiSelect) {
                                                if (entityId != null) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    menuAnchorContactId = entityId
                                                } else {
                                                    onContactIdParseFail()
                                                }
                                            }
                                        },
                                        onLongClickLabel = "Quick actions",
                                        onClick = {
                                            if (isMultiSelect) {
                                                if (entityId != null) {
                                                    onToggleSelect(entityId)
                                                } else {
                                                    onContactIdParseFail()
                                                }
                                            } else {
                                                onOpenContact(contact.id)
                                            }
                                        },
                                    )
                                    .semantics {
                                        if (isMultiSelect) selected = isSelected
                                    },
                            ) {
                                if (isMultiSelect) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = null,
                                        modifier = Modifier.padding(start = OrbitTheme.spacing.x3),
                                    )
                                }
                                BrowseRow(
                                    contact = contact,
                                    onTap = {},  // inert — parent combinedClickable owns tap
                                    onDial = if (isMultiSelect) ({}) else ({
                                        val phone = contact.phone
                                        if (phone.isNotBlank()) {
                                            context.dialPhoneNumber(phone)
                                        }
                                    }),
                                    modifier = Modifier.weight(1f),
                                    // 2026-06-09 #19 — due dot + paused/ignored status
                                    // ride Ready (not Contact — id-only equality).
                                    due = contact.id in state.dueIds,
                                    statusLabel = when (state.rowStatus[contact.id]) {
                                        BrowseRowStatus.Paused -> "Paused"
                                        BrowseRowStatus.Ignored -> "Ignored"
                                        null -> null
                                    },
                                    showCallMeta = !state.callLogPermissionDenied,
                                    // Queue position prefix; head row in accent.
                                    queuePosition = queuePos,
                                    isHead = queuePos == 1,
                                )

                                // Anchored DropdownMenu — only renders for the row whose
                                // entityId matches the open-menu anchor. The menu lives
                                // inside the row so its anchor offset is correct.
                                if (entityId != null && menuAnchorContactId == entityId) {
                                    DropdownMenu(
                                        expanded = true,
                                        onDismissRequest = { menuAnchorContactId = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Call") },
                                            leadingIcon = {
                                                PhIcon(
                                                    name = "phone-call",
                                                    size = 18.dp,
                                                    tint = OrbitTheme.colors.fg,
                                                )
                                            },
                                            onClick = {
                                                menuAnchorContactId = null
                                                val phone = contact.phone
                                                if (phone.isNotBlank()) {
                                                    context.dialPhoneNumber(phone)
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ignore") },
                                            leadingIcon = {
                                                PhIcon(
                                                    name = "eye-slash",
                                                    size = 18.dp,
                                                    tint = OrbitTheme.colors.fg,
                                                )
                                            },
                                            onClick = {
                                                menuAnchorContactId = null
                                                onSingleRowIgnore(entityId, contact.name)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Pause") },
                                            leadingIcon = {
                                                PhIcon(
                                                    name = "pause-circle",
                                                    size = 18.dp,
                                                    tint = OrbitTheme.colors.fg,
                                                )
                                            },
                                            onClick = {
                                                menuAnchorContactId = null
                                                pauseSheetForContactName = contact.name
                                                pauseSheetForContactId = entityId
                                            },
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Select") },
                                            onClick = {
                                                menuAnchorContactId = null
                                                onEnterMultiSelect(entityId)
                                            },
                                        )
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(OrbitTheme.colors.lineSoft),
                            )
                        }

                        if (otherContacts.isNotEmpty()) {
                            item(key = "other-members-header", contentType = "sectionHeader") {
                                Text(
                                    text = "Other members",
                                    style = OrbitTheme.type.eyebrow,
                                    color = OrbitTheme.colors.fgMuted,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = OrbitTheme.spacing.x5,
                                            vertical = OrbitTheme.spacing.x3,
                                        ),
                                )
                            }
                            items(
                                items = otherContacts,
                                key = { it.id },
                                contentType = { "browseRow" },
                            ) { contact ->
                                // `state.queuePositions[contact.id]` is null here — BrowseRow
                                // renders a blank position column for "Other members" rows.
                                val entityId: Long? = contact.id.removePrefix("c-").toLongOrNull()
                                val isSelected = entityId != null && entityId in state.selectedIds
                                val isMultiSelect = state.isMultiSelect
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isMultiSelect && isSelected) OrbitTheme.colors.accentTint
                                            else Color.Transparent,
                                        )
                                        .combinedClickable(
                                            onLongClick = {
                                                if (!isMultiSelect) {
                                                    if (entityId != null) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        menuAnchorContactId = entityId
                                                    } else {
                                                        onContactIdParseFail()
                                                    }
                                                }
                                            },
                                            onLongClickLabel = "Quick actions",
                                            onClick = {
                                                if (isMultiSelect) {
                                                    if (entityId != null) {
                                                        onToggleSelect(entityId)
                                                    } else {
                                                        onContactIdParseFail()
                                                    }
                                                } else {
                                                    onOpenContact(contact.id)
                                                }
                                            },
                                        )
                                        .semantics {
                                            if (isMultiSelect) selected = isSelected
                                        },
                                ) {
                                    if (isMultiSelect) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = null,
                                            modifier = Modifier.padding(start = OrbitTheme.spacing.x3),
                                        )
                                    }
                                    BrowseRow(
                                        contact = contact,
                                        onTap = {},  // inert — parent combinedClickable owns tap
                                        onDial = if (isMultiSelect) ({}) else ({
                                            val phone = contact.phone
                                            if (phone.isNotBlank()) {
                                                context.dialPhoneNumber(phone)
                                            }
                                        }),
                                        modifier = Modifier.weight(1f),
                                        // 2026-06-09 #19 — due dot + paused/ignored status
                                        due = contact.id in state.dueIds,
                                        statusLabel = when (state.rowStatus[contact.id]) {
                                            BrowseRowStatus.Paused -> "Paused"
                                            BrowseRowStatus.Ignored -> "Ignored"
                                            null -> null
                                        },
                                        showCallMeta = !state.callLogPermissionDenied,
                                        // queuePosition = null (default) — blank column for "Other members"
                                    )
                                    if (entityId != null && menuAnchorContactId == entityId) {
                                        DropdownMenu(
                                            expanded = true,
                                            onDismissRequest = { menuAnchorContactId = null },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Call") },
                                                leadingIcon = {
                                                    PhIcon(
                                                        name = "phone-call",
                                                        size = 18.dp,
                                                        tint = OrbitTheme.colors.fg,
                                                    )
                                                },
                                                onClick = {
                                                    menuAnchorContactId = null
                                                    val phone = contact.phone
                                                    if (phone.isNotBlank()) {
                                                        context.dialPhoneNumber(phone)
                                                    }
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Ignore") },
                                                leadingIcon = {
                                                    PhIcon(
                                                        name = "eye-slash",
                                                        size = 18.dp,
                                                        tint = OrbitTheme.colors.fg,
                                                    )
                                                },
                                                onClick = {
                                                    menuAnchorContactId = null
                                                    onSingleRowIgnore(entityId, contact.name)
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Pause") },
                                                leadingIcon = {
                                                    PhIcon(
                                                        name = "pause-circle",
                                                        size = 18.dp,
                                                        tint = OrbitTheme.colors.fg,
                                                    )
                                                },
                                                onClick = {
                                                    menuAnchorContactId = null
                                                    pauseSheetForContactName = contact.name
                                                    pauseSheetForContactId = entityId
                                                },
                                            )
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text("Select") },
                                                onClick = {
                                                    menuAnchorContactId = null
                                                    onEnterMultiSelect(entityId)
                                                },
                                            )
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(OrbitTheme.colors.lineSoft),
                                )
                            }
                        }
                    }
                }

                // Loading branch render path retired (ADR 0006).
                // The Loading data variant is kept on the sealed type for
                // defense-in-depth; first-install renders Empty directly.
                BrowseUiState.Loading,
                BrowseUiState.Empty,
                -> EmptyShell(
                    heading = "No one here yet.",
                    body = "This list is waiting for people.",
                )

                // 2026-06-09 #19 — the list has people; the chips excluded them.
                // Distinct copy + a way back, instead of the false "No one here yet."
                BrowseUiState.FilteredEmpty -> EmptyShell(
                    heading = "No one matches these filters.",
                    body = "Everyone on this list is hidden by the active filters.",
                    actionLabel = "Clear filters",
                    onAction = onClearFilters,
                )

                is BrowseUiState.NoMatches -> EmptyShell(
                    heading = "Nothing matches \"${state.query}\".",
                    body = "Try a shorter name or clear the search.",
                )

                // 2026-06-09 #19 — reachable now: READ_CALL_LOG denied while a
                // call-history chip is active. The chips can't be answered
                // honestly without the call log.
                BrowseUiState.CallLogDenied -> EmptyShell(
                    heading = "Showing names only.",
                    body = "These filters need the call-log permission. You can turn it on in Settings.",
                    actionLabel = "Clear filters",
                    onAction = onClearFilters,
                )
            }

            // Snackbar host (bottom).
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Filter chip — wraps [OrbitChip] with active/inactive tone visual. Active uses
 * Terracotta (accent tone), inactive uses Stone (neutral). Tap target is
 * floor-clamped at `spacing.tapMin`.
 */
@Composable
private fun FilterChipPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        OrbitChip(
            label = label,
            tone = if (active) ChipTone.Terracotta else ChipTone.Stone,
        )
    }
}

@Composable
private fun EmptyShell(
    heading: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x6),
        ) {
            Text(
                text = heading,
                style = OrbitTheme.type.h2,
                color = OrbitTheme.colors.fg,
                textAlign = TextAlign.Center,
            )
            Box(modifier = Modifier.height(OrbitTheme.spacing.x2))
            Text(
                text = body,
                style = OrbitTheme.type.body,
                color = OrbitTheme.colors.fgMuted,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null) {
                Box(modifier = Modifier.height(OrbitTheme.spacing.x4))
                // Ghost variant — the screen's single terracotta element stays
                // the rows' tap-to-call icon (rules.md §Design 5).
                OrbitButton(
                    text = actionLabel,
                    onClick = onAction,
                    variant = OrbitButtonVariant.Ghost,
                )
            }
        }
    }
}

// Preview fixtures for the stateless BrowseContent.
private val previewContact: Contact = Contact(
    id = "preview-1",
    name = "Avery Quinn",
    phone = "+1 555 0100",
    lastCalledLabel = "11 days ago",
    avgLengthLabel = "14 min",
    pickupRateLabel = "82%",
    totalCalls = 12,
    due = false,
    listIds = listOf("inner-orbit"),
    bestWindowLabel = "Evenings",
    heat = FloatArray(24) { 0f },
    history = emptyList(),
    notes = emptyList(),
    patternNote = "",
)

private val previewState: BrowseUiState = BrowseUiState.Ready(
    contacts = listOf(previewContact),
    searchQuery = "",
    activeFilters = emptySet(),
    callLogPermissionDenied = false,
    dueIds = setOf("preview-1"),
)

@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@PreviewLightDark
@PreviewFontScale
@Composable
private fun BrowseContentPreview() {
    OrbitTheme {
        BrowseContent(
            state = previewState,
            initialQuery = "",
            activeFilters = emptySet(),
            lists = emptyList(),
            listId = "inner-orbit",
            listName = "Inner orbit",
            onSearchChanged = {},
            onToggleFilter = {},
            onClearFilters = {},
            onBack = {},
            onOpenContact = {},
            onAddContacts = {},
            onEnterMultiSelect = {},
            onToggleSelect = {},
            onExitMultiSelect = {},
            onBulkRemove = {},
            onBulkIgnore = {},
            onBulkPause = {},
            onBulkMove = { _, _ -> },
            onBulkCopy = { _, _ -> },
            onSingleRowIgnore = { _, _ -> },
            onSingleRowPause = { _, _, _ -> },
            onUndo = {},
            onContactIdParseFail = {},
            snackbarEvents = MutableSharedFlow<app.orbit.ui.screens.picker.SnackbarEvent>().asSharedFlow(),
        )
    }
}
