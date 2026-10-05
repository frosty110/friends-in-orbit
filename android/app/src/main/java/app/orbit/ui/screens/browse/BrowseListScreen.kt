package app.orbit.ui.screens.browse

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import app.orbit.data.Contact
import app.orbit.data.entity.ListEntity
import app.orbit.domain.model.PauseDuration
import app.orbit.ui.components.BrowseRow
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitCheckbox
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitFilterChip
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitListSkeleton
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSearchField
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.screens.contact.sections.PauseSheet
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.dialPhoneNumber
import app.orbit.ui.util.formatDuration
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
 * BROWSE-01: per-list contacts in queue order (VM-side), under "Up next";
 *            members outside the rotation follow under "Everyone else".
 * BROWSE-02: 250ms debounced search + 2 filter chips (chip×chip = UNION per
 *            user decision), drawn with the shared [OrbitFilterChip].
 * BROWSE-04: long-press on a row opens quick actions; "Select" enters
 *            multi-select. Haptic fires ONLY on the long-press.
 * BROWSE-05: trailing phone icon on each row → `dialPhoneNumber` (hidden in
 *            multi-select, where it used to sit inert).
 * BROWSE-06: a skeleton while the list loads and Retry when it fails, never a
 *            false "No one here yet".
 * BULK-05  : trailing "+" in single-select app-bar → BULK-05 picker entry.
 * MOVE-01  : combinedClickable + LocalHapticFeedback on entry.
 * MOVE-02  : AnimatedContent fadeIn/fadeOut(250) cross-fade swap of
 *            OrbitAppBar ↔ MultiSelectActionBar — replacement, not floating.
 * MOVE-03/04: Move/Copy via inline [ListSelectorSheet].
 * MOVE-05  : VM-side onSelectAllVisible / onSelectAllMatching.
 * MOVE-06  : BackHandler(enabled = isMultiSelect) consumes back gesture.
 * MOVE-07  : Snackbar undo backed by [UndoStack].
 * PRIV-03:   app-bar title + row primary names obey `LocalPrivacyCurtain.current`.
 *
 * One accent element (rules.md §Design 5): the due dot, which marks who is
 * ready. Active filters use the cluster-tier tint, selected rows the same, and
 * the queue head's number is ink, not terracotta.
 */
@Composable
fun BrowseListScreen(
    @Suppress("UNUSED_PARAMETER") listId: String, // route arg; VM reads from SavedStateHandle
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    onAddContacts: (listId: String?) -> Unit,
    vm: BrowseViewModel = hiltViewModel()
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
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
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
        onRetry = vm::onRetry,
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
        onSingleRowUnpause = vm::onSingleRowUnpause,
        onUndo = vm::onUndo,
        onContactIdParseFail = vm::onContactIdParseFail,
        snackbarEvents = vm.snackbarEvents
    )
}

@OptIn(FlowPreview::class)
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
    onRetry: () -> Unit,
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
    onSingleRowUnpause: (Long, String) -> Unit,
    onUndo: () -> Unit,
    onContactIdParseFail: () -> Unit,
    snackbarEvents: SharedFlow<SnackbarEvent>
) {
    val curtain = LocalPrivacyCurtain.current
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Snackbar copy is UiText (strings_browse.xml and shared); resolved when shown.
    val context = LocalContext.current

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
                    message = event.message.asString(context),
                    actionLabel = event.actionLabel?.asString(context),
                    duration = SnackbarDuration.Short,
                    withDismissAction = false
                )
                if (r == SnackbarResult.ActionPerformed) onUndo()
            }
        }
    }

    val searchPlaceholder = stringResource(
        if (curtain) R.string.browse_search_placeholder_curtain else R.string.browse_search_placeholder
    )

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
            onDismiss = { showPauseDialog = false }
        )
    }
    if (showMoveSheet || showCopySheet) {
        ListSelectorSheet(
            mode = if (showMoveSheet) Mode.Move else Mode.Copy,
            lists = lists,
            currentListId = sourceListIdLong,
            onPick = { targetListId, targetListName ->
                if (showMoveSheet) {
                    onBulkMove(targetListId, targetListName)
                } else {
                    onBulkCopy(targetListId, targetListName)
                }
                showMoveSheet = false
                showCopySheet = false
            },
            onDismiss = {
                showMoveSheet = false
                showCopySheet = false
            }
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
            }
        )
    }

    // Everything a person row needs from the screen, built once per state.
    val rowActions = BrowseRowActions(
        menuAnchorContactId = menuAnchorContactId,
        onOpenMenu = { menuAnchorContactId = it },
        onDismissMenu = { menuAnchorContactId = null },
        onOpenContact = onOpenContact,
        onToggleSelect = onToggleSelect,
        onEnterMultiSelect = onEnterMultiSelect,
        onPause = { id, name ->
            pauseSheetForContactName = name
            pauseSheetForContactId = id
        },
        onUnpause = onSingleRowUnpause,
        onIgnore = onSingleRowIgnore,
        onContactIdParseFail = onContactIdParseFail
    )

    OrbitScreen {
        // App-bar swap for Browse multi-select integration.
        // Duration via the OrbitMotion token, not a literal.
        AnimatedContent(
            targetState = isMs,
            transitionSpec = {
                fadeIn(tween(OrbitMotion.DurBaseMs)) togetherWith
                    fadeOut(tween(OrbitMotion.DurBaseMs))
            },
            label = "browse-app-bar"
        ) { multiSelectMode ->
            if (multiSelectMode) {
                Box {
                    MultiSelectActionBar(
                        count = selectedIds.size,
                        onExit = onExitMultiSelect,
                        onMove = { showMoveSheet = true },
                        onCopy = { showCopySheet = true },
                        onRemove = onBulkRemove,
                        onOverflow = { menuExpanded = true }
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
                        }
                    )
                }
            } else {
                OrbitAppBar(
                    // 2026-06-09 #19 — show the real list name (the VM had it
                    // all along). Curtain hides the user-authored name (list
                    // names can be sensitive — "people who ground me"); the
                    // generic title also covers the pre-emission blank.
                    title = if (curtain || listName.isBlank()) {
                        stringResource(R.string.browse_title_fallback)
                    } else {
                        listName
                    },
                    leading = {
                        OrbitIconButton(
                            icon = "arrow-left",
                            onClick = onBack,
                            contentDescription = stringResource(R.string.components_action_back)
                        )
                    },
                    trailing = {
                        // Browse renders in queue order; the BULK-05 "+" affordance
                        // is the only trailing action.
                        OrbitIconButton(
                            icon = "plus",
                            onClick = { onAddContacts(listId) },
                            contentDescription = stringResource(R.string.browse_add_people)
                        )
                    }
                )
            }
        }

        // Search field row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x2
                )
        ) {
            OrbitSearchField(
                query = queryText,
                onQueryChange = { queryText = it },
                placeholder = searchPlaceholder
            )
        }

        // Filter chips: chip×chip composition is UNION per user decision.
        // Scrolls sideways rather than wrap a label at 200% font scale.
        Row(
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = OrbitTheme.spacing.x4)
        ) {
            OrbitFilterChip(
                label = stringResource(R.string.browse_filter_called_recently),
                selected = BrowseFilter.CalledRecently in activeFilters,
                onClick = { onToggleFilter(BrowseFilter.CalledRecently) }
            )
            OrbitFilterChip(
                label = stringResource(R.string.browse_filter_not_called_yet),
                selected = BrowseFilter.NotCalledYet in activeFilters,
                onClick = { onToggleFilter(BrowseFilter.NotCalledYet) }
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
                            text = stringResource(R.string.browse_call_log_denied_notice),
                            style = OrbitTheme.type.meta,
                            color = OrbitTheme.colors.fgMuted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = OrbitTheme.spacing.x4,
                                    vertical = OrbitTheme.spacing.x2
                                )
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = OrbitTheme.spacing.x4),
                        contentPadding = PaddingValues(bottom = OrbitTheme.spacing.x6)
                    ) {
                        // Partition contacts into queued (position number) and
                        // non-queued (everyone else, below, no position number).
                        val (queuedContacts, otherContacts) =
                            state.contacts.partition { state.queuePositions[it.id] != null }

                        // BROWSE-01: the numbers mean "the order Orbit will
                        // suggest them" (vision BROWSE-1); the label says so.
                        if (queuedContacts.isNotEmpty()) {
                            item(key = "up-next-header", contentType = "sectionHeader") {
                                BrowseSectionLabel(stringResource(R.string.browse_section_up_next))
                            }
                        }
                        personRows(queuedContacts, state, rowActions)

                        if (otherContacts.isNotEmpty()) {
                            item(key = "other-members-header", contentType = "sectionHeader") {
                                BrowseSectionLabel(
                                    stringResource(
                                        if (queuedContacts.isEmpty()) {
                                            R.string.browse_section_on_this_list
                                        } else {
                                            R.string.browse_section_everyone_else
                                        }
                                    )
                                )
                            }
                            personRows(otherContacts, state, rowActions)
                        }
                    }
                }

                // BROWSE-06: the feed hasn't emitted yet: a quiet skeleton,
                // never "No one here yet" for a list that has people.
                BrowseUiState.Loading -> OrbitListSkeleton()

                BrowseUiState.Error -> OrbitScreenMessage(
                    icon = "warning-circle",
                    title = stringResource(R.string.browse_error_title),
                    body = stringResource(R.string.browse_error_body),
                    actionLabel = stringResource(R.string.browse_try_again),
                    onAction = onRetry,
                    actionVariant = OrbitButtonVariant.Primary
                )

                BrowseUiState.Empty -> OrbitScreenMessage(
                    icon = "users",
                    title = stringResource(R.string.browse_empty_title),
                    body = stringResource(R.string.browse_empty_body),
                    actionLabel = stringResource(R.string.browse_add_people),
                    onAction = { onAddContacts(listId) }
                )

                // 2026-06-09 #19 — the list has people; the chips excluded them.
                // Distinct copy + a way back, instead of the false "No one here yet."
                BrowseUiState.FilteredEmpty -> OrbitScreenMessage(
                    title = stringResource(R.string.browse_filtered_empty_title),
                    body = stringResource(R.string.browse_filtered_empty_body),
                    actionLabel = stringResource(R.string.browse_clear_filters),
                    onAction = onClearFilters
                )

                is BrowseUiState.NoMatches -> OrbitScreenMessage(
                    icon = "magnifying-glass",
                    title = stringResource(R.string.browse_no_matches_title, state.query),
                    body = stringResource(R.string.browse_no_matches_body),
                    actionLabel = stringResource(R.string.browse_clear_search),
                    onAction = { queryText = "" }
                )

                // 2026-06-09 #19 — reachable now: READ_CALL_LOG denied while a
                // call-history chip is active. The chips can't be answered
                // honestly without the call log.
                BrowseUiState.CallLogDenied -> OrbitScreenMessage(
                    icon = "phone-slash",
                    title = stringResource(R.string.browse_filters_need_calls_title),
                    body = stringResource(R.string.browse_filters_need_calls_body),
                    actionLabel = stringResource(R.string.browse_clear_filters),
                    onAction = onClearFilters
                )
            }

            // Snackbar host (bottom).
            OrbitSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun BrowseSectionLabel(text: String) {
    SectionLabel(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = OrbitTheme.spacing.x5,
                vertical = OrbitTheme.spacing.x3
            )
    )
}

/** What a person row needs from the screen; one instance per composition. */
private class BrowseRowActions(
    val menuAnchorContactId: Long?,
    val onOpenMenu: (Long) -> Unit,
    val onDismissMenu: () -> Unit,
    val onOpenContact: (String) -> Unit,
    val onToggleSelect: (Long) -> Unit,
    val onEnterMultiSelect: (Long) -> Unit,
    val onPause: (Long, String) -> Unit,
    val onUnpause: (Long, String) -> Unit,
    val onIgnore: (Long, String) -> Unit,
    val onContactIdParseFail: () -> Unit
)

/**
 * The person rows of one section. Both sections ("Up next" and everyone
 * else) used to carry their own copy of this block; one copy means the
 * selection semantics below cannot drift apart again.
 */
private fun LazyListScope.personRows(
    contacts: List<Contact>,
    state: BrowseUiState.Ready,
    actions: BrowseRowActions
) {
    items(
        items = contacts,
        key = { it.id },
        contentType = { "browseRow" }
    ) { contact ->
        BrowsePersonRow(contact = contact, state = state, actions = actions)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(OrbitTheme.colors.lineSoft)
        )
    }
}

/**
 * One person in Browse.
 *
 * The combinedClickable chain is identical in both modes; the differences
 * live inside the lambdas. Outside multi-select, tap opens the contact and
 * long-press opens the quick actions (BROWSE-04), with a haptic. In
 * multi-select, tap toggles the row, long-press does nothing, and the row is a
 * checkbox for TalkBack ("Alex, checkbox, checked"), drawn with
 * [OrbitCheckbox]. The dial button is hidden there instead of sitting inert.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowsePersonRow(
    contact: Contact,
    state: BrowseUiState.Ready,
    actions: BrowseRowActions
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val queuePos = state.queuePositions[contact.id]
    // UI Contact.id is "c-$entityId" (String); the use cases need Long.
    val entityId: Long? = contact.id.removePrefix("c-").toLongOrNull()
    val isSelected = entityId != null && entityId in state.selectedIds
    val isMultiSelect = state.isMultiSelect
    val dial = {
        val phone = contact.phone
        if (phone.isNotBlank()) context.dialPhoneNumber(phone)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isMultiSelect && isSelected) OrbitTheme.colors.accentTint else Color.Transparent
            )
            .combinedClickable(
                onLongClick = {
                    // LOW polish: surface a snackbar when the row's UI id
                    // ("c-<long>") fails to parse, so the long-press isn't a
                    // silent no-op. Multi-select long-press stays inert.
                    if (!isMultiSelect) {
                        if (entityId != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.onOpenMenu(entityId)
                        } else {
                            actions.onContactIdParseFail()
                        }
                    }
                },
                onLongClickLabel = if (isMultiSelect) null else stringResource(R.string.browse_row_quick_actions),
                onClickLabel = if (isMultiSelect) null else stringResource(R.string.browse_row_open_details),
                role = if (isMultiSelect) Role.Checkbox else null,
                onClick = {
                    if (isMultiSelect) {
                        if (entityId != null) {
                            actions.onToggleSelect(entityId)
                        } else {
                            actions.onContactIdParseFail()
                        }
                    } else {
                        actions.onOpenContact(contact.id)
                    }
                }
            )
            .semantics {
                if (isMultiSelect) toggleableState = ToggleableState(isSelected)
            }
    ) {
        if (isMultiSelect) {
            OrbitCheckbox(
                checked = isSelected,
                modifier = Modifier.padding(start = OrbitTheme.spacing.x3)
            )
        }
        BrowseRow(
            contact = contact,
            onTap = null, // the parent combinedClickable owns tap + long-press
            onDial = dial,
            showDial = !isMultiSelect,
            modifier = Modifier.weight(1f),
            // 2026-06-09 #19: due dot + paused/ignored status
            // ride Ready (not Contact: id-only equality).
            due = contact.id in state.dueIds,
            statusLabel = when (state.rowStatus[contact.id]) {
                BrowseRowStatus.Paused -> stringResource(R.string.browse_row_paused)
                BrowseRowStatus.Ignored -> stringResource(R.string.browse_row_ignored)
                null -> null
            },
            showCallMeta = !state.callLogPermissionDenied,
            queuePosition = queuePos,
            isHead = queuePos == 1
        )

        // Anchored DropdownMenu: only renders for the row whose entityId
        // matches the open-menu anchor. The menu lives inside the row so its
        // anchor offset is correct.
        if (entityId != null && actions.menuAnchorContactId == entityId) {
            BrowseRowActionMenu(
                isPaused = state.rowStatus[contact.id] == BrowseRowStatus.Paused,
                onDismiss = actions.onDismissMenu,
                onCall = dial,
                onSelect = { actions.onEnterMultiSelect(entityId) },
                onPause = { actions.onPause(entityId, contact.name) },
                onUnpause = { actions.onUnpause(entityId, contact.name) },
                onIgnore = { actions.onIgnore(entityId, contact.name) }
            )
        }
    }
}

/**
 * Long-press row menu, shared by the "queue" and "Other members" sections so
 * both offer the same actions in the same order.
 *
 * Order follows the shared [OrbitDropdownMenu] contract — Call (the point of
 * the screen) first, then Select and Pause, and Ignore last in danger, since
 * it takes the person out of Orbit's rotation entirely.
 */
@Composable
private fun BrowseRowActionMenu(
    isPaused: Boolean,
    onDismiss: () -> Unit,
    onCall: () -> Unit,
    onSelect: () -> Unit,
    onPause: () -> Unit,
    onUnpause: () -> Unit,
    onIgnore: () -> Unit
) {
    OrbitDropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        actions = browseRowMenuActions(
            LocalContext.current.resources,
            isPaused,
            onCall,
            onSelect,
            onPause,
            onUnpause,
            onIgnore
        )
    )
}

/**
 * The long-press actions for one Browse row. A paused row offers Unpause in
 * the pause slot: before, the only way back from a pause was its own Undo
 * snackbar, so an indefinite pause was permanent. `internal` so the order and
 * the swap are unit-tested (BrowseRowMenuTest). Takes [Resources] because
 * [OrbitMenuAction] carries resolved text (the `listRowMenuActions` precedent).
 */
internal fun browseRowMenuActions(
    resources: Resources,
    isPaused: Boolean,
    onCall: () -> Unit,
    onSelect: () -> Unit,
    onPause: () -> Unit,
    onUnpause: () -> Unit,
    onIgnore: () -> Unit
): List<OrbitMenuAction> = listOf(
    OrbitMenuAction(label = resources.getString(R.string.browse_menu_call), onClick = onCall, icon = "phone-call"),
    OrbitMenuAction(label = resources.getString(R.string.browse_menu_select), onClick = onSelect),
    if (isPaused) {
        OrbitMenuAction(label = resources.getString(R.string.browse_menu_unpause), onClick = onUnpause, icon = "play")
    } else {
        OrbitMenuAction(label = resources.getString(R.string.browse_menu_pause), onClick = onPause, icon = "pause-circle")
    },
    OrbitMenuAction(
        label = resources.getString(R.string.browse_menu_ignore),
        onClick = onIgnore,
        icon = "eye-slash",
        tone = OrbitMenuTone.Destructive
    )
)

// ─── Previews ──────────────────────────────────────────────────────────────────
// One per state, so each renders in the screenshot gallery.

private fun previewContact(id: Long, name: String, lastCalled: UiText?): Contact = Contact(
    id = "c-$id",
    name = name,
    phone = "+1 555 0100",
    lastCalledLabel = lastCalled,
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

private val previewState: BrowseUiState = BrowseUiState.Ready(
    contacts = listOf(
        previewContact(1, "Avery Quinn", UiText.plural(R.plurals.time_ago_days, 11, 11)),
        previewContact(2, "Sam Patel", UiText.plural(R.plurals.time_ago_weeks, 3, 3)),
        previewContact(3, "Jordan Lee", null),
        previewContact(4, "Priya Anand", UiText.plural(R.plurals.time_ago_months, 2, 2))
    ),
    searchQuery = "",
    activeFilters = emptySet(),
    callLogPermissionDenied = false,
    dueIds = setOf("c-1", "c-2"),
    rowStatus = mapOf("c-4" to BrowseRowStatus.Paused),
    queuePositions = mapOf("c-1" to 1, "c-2" to 2, "c-3" to 3)
)

@Composable
private fun BrowsePreviewHost(
    state: BrowseUiState,
    activeFilters: Set<BrowseFilter> = emptySet()
) {
    OrbitTheme {
        BrowseContent(
            state = state,
            initialQuery = "",
            activeFilters = activeFilters,
            lists = emptyList(),
            listId = "1",
            listName = "Inner orbit",
            onSearchChanged = {},
            onToggleFilter = {},
            onClearFilters = {},
            onRetry = {},
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
            onSingleRowUnpause = { _, _ -> },
            onUndo = {},
            onContactIdParseFail = {},
            snackbarEvents = MutableSharedFlow<SnackbarEvent>().asSharedFlow()
        )
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun BrowseContentPreview() {
    BrowsePreviewHost(previewState)
}

@PreviewLightDark
@Composable
private fun BrowseMultiSelectPreview() {
    BrowsePreviewHost(
        (previewState as BrowseUiState.Ready).copy(isMultiSelect = true, selectedIds = setOf(1L, 3L))
    )
}

@PreviewLightDark
@Composable
private fun BrowseLoadingPreview() {
    BrowsePreviewHost(BrowseUiState.Loading)
}

@PreviewLightDark
@Composable
private fun BrowseEmptyPreview() {
    BrowsePreviewHost(BrowseUiState.Empty)
}

@PreviewLightDark
@Composable
private fun BrowseFilteredEmptyPreview() {
    BrowsePreviewHost(BrowseUiState.FilteredEmpty, activeFilters = setOf(BrowseFilter.NotCalledYet))
}

@PreviewLightDark
@Composable
private fun BrowseErrorPreview() {
    BrowsePreviewHost(BrowseUiState.Error)
}
