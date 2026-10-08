package app.orbit.ui.screens.browse

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
import app.orbit.data.entity.ListType
import app.orbit.domain.model.PauseDuration
import app.orbit.ui.components.BrowseRow
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitCheckbox
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitFilterChip
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitInlineNotice
import app.orbit.ui.components.OrbitListSkeleton
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSearchField
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.components.PauseDurationSheet
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.dialPhoneNumber
import app.orbit.ui.util.formatDuration
import app.orbit.ui.util.formatSpan
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Browse outer composable with multi-select gesture-and-bar substrate.
 *
 * Two-layer Hilt pattern preserved:
 *   - outer reads `vm.uiState`, `vm.searchQuery`, `vm.activeFilters`, `vm.lists`
 *     and forwards callbacks to the inner stateless [BrowseContent].
 *   - inner owns the local debounced TextField buffer (via `snapshotFlow` +
 *     `debounce(250)`) and forwards committed query strings to `vm::onSearchChanged`.
 *
 * BROWSE-07: one sequence under "Next up": everyone in the order the card
 *            brings them up, numbered, each with when ("Up now", "Thursday");
 *            then "Everyone else" (people the rule cannot place), "Paused"
 *            (with until when) and "Ignored". Groups keep their order through
 *            search and the filters.
 * BROWSE-08: a drag handle on each sequence row (not while selecting), with
 *            TalkBack's "Move up" and "Move down"; a drop is one write with
 *            Undo, and one quiet line under the sequence says it is not
 *            permanent.
 * BROWSE-09: opened from the card, the card's person is marked "On your
 *            card" and scrolled into view.
 * BROWSE-02: 250ms debounced search + 2 filter chips (chip×chip = UNION per
 *            user decision), drawn with the shared [OrbitFilterChip].
 * BROWSE-04: long-press on a row opens quick actions; "Select" enters
 *            multi-select. Haptic fires ONLY on the long-press.
 * BROWSE-05: trailing phone icon on each row → `dialPhoneNumber` (hidden in
 *            multi-select, where it used to sit inert).
 * BROWSE-06: a skeleton while the list loads and Retry when it fails, never a
 *            false "No one here yet". A list id that never parsed has nothing
 *            to retry, so that Error offers Go back alone.
 * BULK-05  : trailing "+" in the app bar → the Add people picker. Not on a
 *            smart list, whose rows the sync writes (ListRow.kt's "+" hides
 *            for the same reason); the Empty state's "Add people" likewise.
 * MOVE-01  : a row's long-press "Select" (combinedClickable + haptic) enters
 *            with that row; the app bar's Select button enters with nothing
 *            selected (vision BROWSE-2), so the power is not gesture-only.
 * MOVE-02  : AnimatedContent fadeIn/fadeOut(250) cross-fade swap of
 *            OrbitAppBar ↔ MultiSelectActionBar — replacement, not floating.
 * MOVE-03/04: Move/Copy via inline [ListSelectorSheet]; regular lists only.
 *            Remove and Move are not offered while browsing a smart list.
 * MOVE-05  : "Select all" in the selection overflow → `onSelectAllMatching`
 *            with the ids of `Ready.contacts`, the searched and filtered set.
 * MOVE-06  : BackHandler(enabled = isMultiSelect) consumes back gesture.
 * MOVE-07  : Snackbar undo backed by [UndoStack]; the bar is disabled while a
 *            write is in flight, so a second tap cannot replace the Undo. A
 *            newer snackbar replaces the one on screen, and each Undo hands
 *            back its own change's token, so it reverts only that change on
 *            this screen. The app-level picker snackbar shares the one-deep
 *            stack with no token (see BrowseViewModel.onUndo), a known gap.
 * PRIV-03:   app-bar title + row primary names obey `LocalPrivacyCurtain.current`;
 *            [OrbitSearchField] masks what is typed.
 *
 * Call log access off: the notice above the rows and the filter state both
 * offer "Open settings" (Orbit's Settings hosts the grant), as Card view and
 * Call history do; until 2026-10-06 Browse named Settings and gave no path.
 *
 * One accent element (rules.md §Design 5): the due dot, which marks who is
 * ready. Active filters use the cluster-tier tint, selected rows the same, the
 * queue head's number is ink, not terracotta, and "On your card" is a Stone
 * chip.
 */
@Composable
fun BrowseListScreen(
    @Suppress("UNUSED_PARAMETER") listId: String, // route arg; VM reads from SavedStateHandle
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    // Non-null: the route arg is required, so the nav host never has to
    // invent a list id (browse-19).
    onAddContacts: (listId: String) -> Unit,
    // Defaulted so the nav host can wire it in its own change.
    onOpenSettings: () -> Unit = {},
    vm: BrowseViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val initialQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val activeFilters by vm.activeFilters.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val listName by vm.listName.collectAsStateWithLifecycle()
    val listType by vm.listType.collectAsStateWithLifecycle()

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
        listType = listType,
        onSearchChanged = vm::onSearchChanged,
        onToggleFilter = vm::onToggleFilter,
        onClearFilters = vm::onClearFilters,
        onRetry = vm::onRetry,
        onBack = onBack,
        onOpenContact = onOpenContact,
        onAddContacts = onAddContacts,
        onOpenSettings = onOpenSettings,
        onEnterMultiSelect = { id -> vm.onEnterMultiSelect(id) },
        onSelectPeople = { vm.onEnterMultiSelect() },
        onSelectAll = vm::onSelectAllMatching,
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
        onReorder = vm::onReorder,
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
    listType: ListType?,
    onSearchChanged: (String) -> Unit,
    onToggleFilter: (BrowseFilter) -> Unit,
    onClearFilters: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    onAddContacts: (listId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onEnterMultiSelect: (Long) -> Unit,
    onSelectPeople: () -> Unit,
    onSelectAll: (Set<Long>) -> Unit,
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
    onUndo: (token: Long) -> Unit,
    onContactIdParseFail: () -> Unit,
    onReorder: (contactId: Long, placeAfter: Long?, name: String) -> Unit,
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

    // BROWSE-08: the drag's held order. Every drop ends in a snackbar ("Moved
    // Kai earlier", or "Couldn't save your change"), so the collector below
    // releases it (see SequenceDrag).
    val drag = remember { SequenceDrag() }

    // Snackbar event collector. One snackbar at a time, newest wins (MOVE-07,
    // BROWSE-08; Card view's collector is the precedent): collectLatest
    // cancels the older showSnackbar, which dismisses it, so the Undo on
    // screen always belongs to the change it names, and its tap hands back
    // that change's token (`actionPayload`), which the ViewModel checks.
    // Until 2026-10-08 a plain collect queued snackbars behind each other
    // while the undo stack held only the newest change, so Undo on "Moved Kai
    // earlier" reverted the drop made after it (browse-1).
    // Gated by STARTED so the snackbar does not fire on a
    // backgrounded screen (SnackbarEvent SharedFlow has replay = 0).
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snackbarEvents.collectLatest { event ->
                snackbarHostState.currentSnackbarData?.dismiss()
                drag.release()
                val r = snackbarHostState.showSnackbar(
                    message = event.message.asString(context),
                    actionLabel = event.actionLabel?.asString(context),
                    duration = SnackbarDuration.Short,
                    withDismissAction = false
                )
                if (r == SnackbarResult.ActionPerformed) event.actionPayload?.let(onUndo)
            }
        }
    }

    val searchPlaceholder = stringResource(
        if (curtain) R.string.browse_search_placeholder_curtain else R.string.browse_search_placeholder
    )

    val isMs = (state as? BrowseUiState.Ready)?.isMultiSelect ?: false
    val selectedIds: Set<Long> = (state as? BrowseUiState.Ready)?.selectedIds ?: emptySet()
    val isCommitting = (state as? BrowseUiState.Ready)?.isCommitting ?: false
    val sourceListIdLong: Long? = listId.toLongOrNull()
    // A smart list's rows are written by SmartListMembershipSync, not by the
    // user (menus-1, browse-1; the ListRow.kt "+" precedent): no "+", no
    // "Add people", no Remove, no Move. Unknown (null, before the list row
    // emits) is treated as "not yet": the picker would otherwise open for a
    // list that may turn out to be smart.
    val isSmartList = listType == ListType.SMART
    val canAddPeople = listType == ListType.STATIC

    // BackHandler — MOVE-06. Enabled only in multi-select to avoid double-consuming back.
    BackHandler(enabled = isMs) { onExitMultiSelect() }

    // Move/Copy/Pause inline triggers (DECISION — see ListSelectorSheet KDoc).
    var showPauseSheet by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showCopySheet by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    // Single-row long-press DropdownMenu state.
    // `menuAnchorContactId` tracks WHICH row's menu is open (null = none);
    // `pauseSheetForContactId` opens the PauseDurationSheet for the chosen
    // single-row Pause action. `pauseSheetForContactName` carries the display
    // name for the snackbar copy ("Paused {Name} for 1 week"). Both clear on
    // dismiss. Multi-select preempts these (long-press is a no-op when
    // `isMultiSelect` is true).
    var menuAnchorContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    // BROWSE-09: whether the list has already jumped to the card's person.
    var scrolledToCard by rememberSaveable { mutableStateOf(false) }
    var pauseSheetForContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pauseSheetForContactName by rememberSaveable { mutableStateOf("") }

    // Pause all: the one shared "Pause for how long?" sheet (menus-4). Until
    // 2026-10-06 this path had its own AlertDialog whose third option read
    // "Indefinitely" while the single-row sheet said "Until you unpause".
    // The sheet calls onSelect, then onDismiss once its hide animation ends.
    if (showPauseSheet) {
        PauseDurationSheet(
            onSelect = onBulkPause,
            onDismiss = { showPauseSheet = false }
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

    // Single-row Pause: the same shared sheet as Contact detail and Pause all.
    pauseSheetForContactId?.let { cid ->
        PauseDurationSheet(
            onSelect = { duration -> onSingleRowPause(cid, pauseSheetForContactName, duration) },
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
                        onOverflow = { menuExpanded = true },
                        enabled = !isCommitting,
                        showMove = !isSmartList,
                        showRemove = !isSmartList
                    )
                    // OrbitDropdownMenu dismisses itself before each action runs.
                    MultiSelectOverflowMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onSelectAll = {
                            // MOVE-05: Ready.contacts is already the searched and
                            // filtered set, so this covers unrendered rows too.
                            val matching = (state as? BrowseUiState.Ready)?.contacts.orEmpty()
                                .mapNotNull { it.id.removePrefix("c-").toLongOrNull() }
                                .toSet()
                            onSelectAll(matching)
                        },
                        onPauseAll = { showPauseSheet = true },
                        onIgnoreAll = onBulkIgnore,
                        hasSelection = selectedIds.isNotEmpty(),
                        enabled = !isCommitting
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Vision BROWSE-2: a visible way into multi-select,
                            // so Move, Copy, Remove, Pause all and Ignore all are
                            // not gated behind a long-press nothing advertises.
                            // Only when there are rows to select.
                            if (state is BrowseUiState.Ready) {
                                OrbitIconButton(
                                    icon = "check-circle",
                                    onClick = onSelectPeople,
                                    contentDescription = stringResource(R.string.browse_select_people)
                                )
                            }
                            // BULK-05 "+": regular lists only (see canAddPeople).
                            if (canAddPeople) {
                                OrbitIconButton(
                                    icon = "plus",
                                    onClick = { onAddContacts(listId) },
                                    contentDescription = stringResource(R.string.browse_add_people)
                                )
                            }
                        }
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
                label = stringResource(R.string.browse_filter_recently_called),
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
                    // meta (every "Never called" would be a false claim). The
                    // shared strip carries the fix (calllog-13, browse-5): Orbit's
                    // Settings hosts the grant.
                    if (state.callLogPermissionDenied) {
                        OrbitInlineNotice(
                            text = stringResource(R.string.browse_call_log_denied_notice),
                            actionLabel = stringResource(R.string.components_action_open_settings),
                            onAction = onOpenSettings
                        )
                    }
                    BrowseReadyList(
                        state = state,
                        actions = rowActions,
                        onReorder = onReorder,
                        drag = drag,
                        scrolledToCard = scrolledToCard,
                        onScrolledToCard = { scrolledToCard = true }
                    )
                }

                // BROWSE-06: the feed hasn't emitted yet: a quiet skeleton,
                // never "No one here yet" for a list that has people.
                BrowseUiState.Loading -> OrbitListSkeleton()

                // The shared error body and Retry (strings_components.xml): one
                // error family across the app (strings-12). When nothing can be
                // retried (the route's list id never parsed, so the VM has no
                // feed to re-subscribe) the one action is Go back, which the
                // screen's own onBack already wires; a Try again that did
                // nothing was the accent here until 2026-10-06 (rules.md
                // Code 3).
                is BrowseUiState.Error -> if (state.canRetry) {
                    OrbitScreenMessage(
                        icon = "warning-circle",
                        title = stringResource(R.string.browse_error_title),
                        body = stringResource(R.string.components_error_body),
                        actionLabel = stringResource(R.string.components_error_retry),
                        onAction = onRetry,
                        actionVariant = OrbitButtonVariant.Primary
                    )
                } else {
                    OrbitScreenMessage(
                        icon = "warning-circle",
                        title = stringResource(R.string.browse_error_title),
                        body = stringResource(R.string.components_error_body),
                        actionLabel = stringResource(R.string.components_action_go_back),
                        onAction = onBack,
                        actionVariant = OrbitButtonVariant.Primary
                    )
                }

                // "Add people" only where people can be added: a smart list's
                // rows come from its rule, so the body says so and offers nothing.
                BrowseUiState.Empty -> OrbitScreenMessage(
                    icon = "users",
                    title = stringResource(R.string.browse_empty_title),
                    body = stringResource(
                        if (isSmartList) R.string.browse_empty_body_smart else R.string.browse_empty_body
                    ),
                    actionLabel = if (canAddPeople) stringResource(R.string.browse_add_people) else null,
                    onAction = if (canAddPeople) ({ onAddContacts(listId) }) else null
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
                // honestly without the call log. Two honest ways forward: grant
                // access (the body names Settings, so the Primary action goes
                // there; browse-5) or clear the chips.
                BrowseUiState.CallLogDenied -> OrbitScreenMessage(
                    icon = "phone-slash",
                    title = stringResource(R.string.browse_filters_need_calls_title),
                    body = stringResource(R.string.browse_filters_need_calls_body),
                    actionLabel = stringResource(R.string.components_action_open_settings),
                    onAction = onOpenSettings,
                    actionVariant = OrbitButtonVariant.Primary,
                    secondaryLabel = stringResource(R.string.browse_clear_filters),
                    onSecondary = onClearFilters
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
 * One entry of the Ready list, in drawing order. Built once per state by
 * [browseItems] so the LazyColumn, the drag's keys and BROWSE-09's scroll
 * target all read the same positions.
 */
private sealed interface BrowseItem {
    val key: String

    class Header(override val key: String, @StringRes val text: Int) : BrowseItem

    /** [inSequence]: a row of the order the card follows, the only rows that move (BROWSE-08). */
    class Person(val contact: Contact, val inSequence: Boolean) : BrowseItem {
        override val key: String get() = contact.id
    }

    data object Footnote : BrowseItem {
        override val key: String = "sequence-footnote"
    }
}

/**
 * BROWSE-07: the Ready list's groups, in order. "Next up" over the sequence
 * (with [sequence], possibly the order under the finger mid-drag), then the
 * quiet line that dragging is not permanent when rows can be dragged, then
 * "Everyone else" (or "On this list" when nobody is in the sequence),
 * "Paused" and "Ignored". Each group keeps the ViewModel's order, so a search
 * or a filter narrows a group without reordering it.
 */
private fun browseItems(state: BrowseUiState.Ready, sequence: List<Contact>, showFootnote: Boolean): List<BrowseItem> {
    val inSequence = sequence.map { it.id }.toSet()
    val outside = state.contacts.filter { it.id !in inSequence && state.queuePositions[it.id] == null }
    val paused = outside.filter { state.rowStatus[it.id] == BrowseRowStatus.Paused }
    val ignored = outside.filter { state.rowStatus[it.id] == BrowseRowStatus.Ignored }
    val others = outside.filter { state.rowStatus[it.id] == null }
    return buildList {
        if (sequence.isNotEmpty()) {
            add(BrowseItem.Header("up-next-header", R.string.browse_section_next_up))
            sequence.forEach { add(BrowseItem.Person(it, inSequence = true)) }
            if (showFootnote) add(BrowseItem.Footnote)
        }
        if (others.isNotEmpty()) {
            add(
                BrowseItem.Header(
                    "other-members-header",
                    if (sequence.isEmpty()) R.string.browse_section_on_this_list else R.string.browse_section_everyone_else
                )
            )
            others.forEach { add(BrowseItem.Person(it, inSequence = false)) }
        }
        if (paused.isNotEmpty()) {
            add(BrowseItem.Header("paused-header", R.string.browse_section_paused))
            paused.forEach { add(BrowseItem.Person(it, inSequence = false)) }
        }
        if (ignored.isNotEmpty()) {
            add(BrowseItem.Header("ignored-header", R.string.browse_section_ignored))
            ignored.forEach { add(BrowseItem.Person(it, inSequence = false)) }
        }
    }
}

/**
 * BROWSE-08: the order under the finger, by row id, from the start of a drag
 * until the drop has been answered. The drag follows the Lists screen
 * (sh.calvin.reorderable, a handle per row), with one difference: Lists writes
 * on every swap, while here a drop is one write and one snackbar with Undo, so
 * the order is held here until then. Gesture state with one owner, the screen
 * (rules.md Code 7): [start] and the library's moves set it, [stop] ends the
 * gesture, and [release] lets go once the drop is answered, which is either
 * the ViewModel's state showing it or the drop's snackbar arriving. The
 * snackbar matters: a write that fails at once can show and withdraw the
 * ViewModel's overlay inside one frame, so the state the screen sees never
 * changes, and without it the dropped order would stay on screen after
 * "Couldn't save your change".
 */
@Stable
private class SequenceDrag {
    var order by mutableStateOf<List<String>?>(null)
    private var active by mutableStateOf(false)

    fun start(current: List<String>) {
        active = true
        order = current
    }

    /** Ends the gesture and returns the order it dropped. */
    fun stop(): List<String>? {
        active = false
        return order
    }

    /** Lets go of the held order, unless a new drag is under way. */
    fun release() {
        if (!active) order = null
    }
}

/**
 * The Ready list: the sequence and its groups (BROWSE-07), the card's person
 * marked and scrolled to (BROWSE-09), and drag to reorder (BROWSE-08, see
 * [SequenceDrag]). Rows move only in the sequence and not while selecting,
 * where a tap selects.
 */
@Composable
private fun BrowseReadyList(
    state: BrowseUiState.Ready,
    actions: BrowseRowActions,
    onReorder: (contactId: Long, placeAfter: Long?, name: String) -> Unit,
    drag: SequenceDrag,
    scrolledToCard: Boolean,
    onScrolledToCard: () -> Unit
) {
    val curtain = LocalPrivacyCurtain.current
    val lazyListState = rememberLazyListState()
    val stateSequence = state.contacts.filter { state.queuePositions[it.id] != null }
    val canReorder = !state.isMultiSelect && stateSequence.size > 1

    val stateOrder = stateSequence.map { it.id }
    // The state shows the drop (the ViewModel's overlay, or the write's result).
    LaunchedEffect(stateOrder) { drag.release() }

    val sequence = drag.order?.let { order ->
        val byId = stateSequence.associateBy { it.id }
        order.mapNotNull { byId[it] } + stateSequence.filter { it.id !in order }
    } ?: stateSequence
    val items = browseItems(state, sequence, showFootnote = canReorder)

    // BROWSE-09: once, when the card's person first shows, bring their row into
    // view with the row above it (or the heading) for context. A jump, not an
    // animation: it is where the screen opens. The flag is the screen's
    // (saved there), so neither a rotation nor a search that empties and
    // refills this list scrolls again under the user's thumb.
    LaunchedEffect(state.onYourCardId) {
        val target = state.onYourCardId ?: return@LaunchedEffect
        if (scrolledToCard) return@LaunchedEffect
        val index = items.indexOfFirst { it.key == target }
        if (index >= 0) {
            lazyListState.scrollToItem((index - 1).coerceAtLeast(0))
            onScrolledToCard()
        }
    }

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val order = drag.order ?: return@rememberReorderableLazyListState
        val fromIndex = order.indexOf(from.key as? String)
        val toIndex = order.indexOf(to.key as? String)
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState
        drag.order = order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    // Resolved here: the drag callbacks and semantics blocks are not composable.
    val moveUpLabel = stringResource(R.string.browse_row_move_up)
    val moveDownLabel = stringResource(R.string.browse_row_move_down)

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = OrbitTheme.spacing.x4),
        contentPadding = PaddingValues(bottom = OrbitTheme.spacing.x6)
    ) {
        items(
            items = items,
            key = { it.key },
            contentType = { item ->
                when (item) {
                    is BrowseItem.Header -> "sectionHeader"
                    is BrowseItem.Person -> "browseRow"
                    BrowseItem.Footnote -> "footnote"
                }
            }
        ) { item ->
            when (item) {
                is BrowseItem.Header -> BrowseSectionLabel(stringResource(item.text))
                BrowseItem.Footnote -> BrowseSequenceFootnote()
                is BrowseItem.Person -> {
                    val contact = item.contact
                    val entityId = contact.id.removePrefix("c-").toLongOrNull()
                    if (item.inSequence && canReorder && entityId != null) {
                        ReorderableItem(reorderState, key = contact.id) { isDragging ->
                            // PRIV-03: "Reorder" alone under the curtain, never the name.
                            val handleLabel = if (curtain) {
                                stringResource(R.string.browse_row_reorder_unnamed)
                            } else {
                                stringResource(R.string.browse_row_reorder, contact.name)
                            }
                            val index = sequence.indexOfFirst { it.id == contact.id }
                            val idAt = { i: Int -> sequence.getOrNull(i)?.id?.removePrefix("c-")?.toLongOrNull() }
                            val reorder = RowReorder(
                                handle = Modifier.draggableHandle(
                                    onDragStarted = { drag.start(sequence.map { it.id }) },
                                    onDragStopped = {
                                        val order = drag.stop()
                                        if (order == null || order == stateOrder) {
                                            drag.release()
                                        } else {
                                            val at = order.indexOf(contact.id)
                                            val above = order.getOrNull(at - 1)?.removePrefix("c-")?.toLongOrNull()
                                            onReorder(entityId, above, contact.name)
                                        }
                                    }
                                ),
                                handleLabel = handleLabel,
                                moveUpLabel = moveUpLabel,
                                moveDownLabel = moveDownLabel,
                                // Up: follow the row two above (or go first);
                                // down: follow the row below.
                                onMoveUp = if (index > 0) ({ onReorder(entityId, idAt(index - 2), contact.name) }) else null,
                                onMoveDown = if (index in 0 until sequence.lastIndex) {
                                    { onReorder(entityId, idAt(index + 1), contact.name) }
                                } else {
                                    null
                                },
                                isDragging = isDragging
                            )
                            PersonRowWithDivider(contact, state, actions, leadingCell = true, reorder = reorder)
                        }
                    } else {
                        // Every row keeps the draggable rows' leading cell while
                        // any row can move, so the avatars line up.
                        PersonRowWithDivider(contact, state, actions, leadingCell = canReorder, reorder = null)
                    }
                }
            }
        }
    }
}

/** What a sequence row needs to move (BROWSE-08); null on rows that cannot. */
private class RowReorder(
    val handle: Modifier,
    val handleLabel: String,
    val moveUpLabel: String,
    val moveDownLabel: String,
    val onMoveUp: (() -> Unit)?,
    val onMoveDown: (() -> Unit)?,
    val isDragging: Boolean
)

@Composable
private fun PersonRowWithDivider(
    contact: Contact,
    state: BrowseUiState.Ready,
    actions: BrowseRowActions,
    leadingCell: Boolean,
    reorder: RowReorder?
) {
    Column(
        modifier = Modifier.background(
            // The row being dragged lifts off the list onto the surface colour.
            if (reorder?.isDragging == true) OrbitTheme.colors.surface else Color.Transparent
        )
    ) {
        BrowsePersonRow(
            contact = contact,
            state = state,
            actions = actions,
            leadingCell = leadingCell,
            reorder = reorder
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(OrbitTheme.colors.lineSoft)
        )
    }
}

/** BROWSE-08: the owner's "not permanent", once, under the sequence. */
@Composable
private fun BrowseSequenceFootnote() {
    Text(
        text = stringResource(R.string.browse_sequence_footnote),
        style = OrbitTheme.type.meta,
        color = OrbitTheme.colors.fgMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x5, vertical = OrbitTheme.spacing.x3)
    )
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
    actions: BrowseRowActions,
    leadingCell: Boolean,
    reorder: RowReorder?
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
            isHead = queuePos == 1,
            // BROWSE-07: "Up now", "Thursday"; on a paused row, "Until 12 Oct".
            whenLabel = (state.whenLabels[contact.id] ?: state.untilLabels[contact.id])?.asString(),
            // BROWSE-09: the person the card is showing.
            marker = if (contact.id == state.onYourCardId) stringResource(R.string.browse_row_on_your_card) else null,
            leadingCell = leadingCell,
            dragHandle = reorder?.handle,
            dragHandleLabel = reorder?.handleLabel,
            // BROWSE-08: TalkBack moves a row with "Move up" and "Move down"
            // (the handle is a gesture). In the row's own action list, after
            // "Call": set on this Row instead, they replaced "Call {name}" in
            // the merged node (BrowseSequenceContentTest caught it).
            extraActions = if (reorder == null) {
                emptyList()
            } else {
                listOfNotNull(
                    reorder.onMoveUp?.let { up ->
                        CustomAccessibilityAction(reorder.moveUpLabel) {
                            up()
                            true
                        }
                    },
                    reorder.onMoveDown?.let { down ->
                        CustomAccessibilityAction(reorder.moveDownLabel) {
                            down()
                            true
                        }
                    }
                )
            }
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
    // Icons are all-or-none within a menu (OrbitMenu.kt); Select had none
    // and read as a gap (menus-12).
    OrbitMenuAction(label = resources.getString(R.string.browse_menu_select), onClick = onSelect, icon = "check-circle"),
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

// BROWSE-07: the sequence (two up now, one tomorrow, one on a weekday, one in
// two weeks), then a paused and an ignored person; opened from the card, so
// its person is marked (BROWSE-09).
private val previewState: BrowseUiState = BrowseUiState.Ready(
    contacts = listOf(
        previewContact(1, "Avery Quinn", UiText.plural(R.plurals.time_ago_days, 11, 11)),
        previewContact(2, "Sam Patel", UiText.plural(R.plurals.time_ago_weeks, 3, 3)),
        previewContact(3, "Jordan Lee", null),
        previewContact(5, "Mei Tanaka", UiText.plural(R.plurals.time_ago_weeks, 1, 1)),
        previewContact(6, "Leo Brandt", UiText.plural(R.plurals.time_ago_days, 4, 4)),
        previewContact(4, "Priya Anand", UiText.plural(R.plurals.time_ago_months, 2, 2)),
        previewContact(7, "Kai Moreno", null)
    ),
    searchQuery = "",
    activeFilters = emptySet(),
    callLogPermissionDenied = false,
    dueIds = setOf("c-1", "c-2"),
    rowStatus = mapOf("c-4" to BrowseRowStatus.Paused, "c-7" to BrowseRowStatus.Ignored),
    queuePositions = mapOf("c-1" to 1, "c-2" to 2, "c-3" to 3, "c-5" to 4, "c-6" to 5),
    whenLabels = mapOf(
        "c-1" to UiText.res(R.string.browse_when_up_now),
        "c-2" to UiText.res(R.string.browse_when_up_now),
        "c-3" to UiText.res(R.string.browse_when_tomorrow),
        "c-5" to UiText.res(R.string.browse_when_on_day, "Thursday"),
        "c-6" to UiText.res(R.string.browse_when_in_span, formatSpan(14))
    ),
    untilLabels = mapOf("c-4" to UiText.res(R.string.browse_row_until, "12 Oct")),
    onYourCardId = "c-1"
)

// internal (not private) so BrowseErrorShellTest can render a state's shell
// on the JVM through the same host the gallery uses; BrowseListScreen itself
// takes a Hilt ViewModel and cannot be composed there.
@Composable
internal fun BrowsePreviewHost(
    state: BrowseUiState,
    activeFilters: Set<BrowseFilter> = emptySet(),
    listType: ListType = ListType.STATIC,
    onReorder: (contactId: Long, placeAfter: Long?, name: String) -> Unit = { _, _, _ -> },
    snackbarEvents: SharedFlow<SnackbarEvent> = MutableSharedFlow<SnackbarEvent>().asSharedFlow(),
    onUndo: (token: Long) -> Unit = {}
) {
    OrbitTheme {
        BrowseContent(
            state = state,
            initialQuery = "",
            activeFilters = activeFilters,
            lists = emptyList(),
            listId = "1",
            listName = "Inner orbit",
            listType = listType,
            onSearchChanged = {},
            onToggleFilter = {},
            onClearFilters = {},
            onRetry = {},
            onBack = {},
            onOpenContact = {},
            onAddContacts = {},
            onOpenSettings = {},
            onEnterMultiSelect = {},
            onSelectPeople = {},
            onSelectAll = {},
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
            onUndo = onUndo,
            onContactIdParseFail = {},
            onReorder = onReorder,
            snackbarEvents = snackbarEvents
        )
    }
}

/** BROWSE-07/08/09: the sequence with when, its handles and footnote, the groups, the card's person. */
@PreviewLightDark
@PreviewFontScale
@Composable
private fun BrowseContentPreview() {
    BrowsePreviewHost(previewState)
}

/**
 * BROWSE-09: the card's person is not first (the deck moved between the
 * card's read and Browse's), so the mark sits where the order puts them.
 */
@PreviewLightDark
@Composable
private fun BrowseCardPersonMovedPreview() {
    BrowsePreviewHost((previewState as BrowseUiState.Ready).copy(onYourCardId = "c-3"))
}

/** Opened from "Browse this list" (nobody on the card): no row is marked. */
@PreviewLightDark
@Composable
private fun BrowseNoCardPersonPreview() {
    BrowsePreviewHost((previewState as BrowseUiState.Ready).copy(onYourCardId = null))
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
    BrowsePreviewHost(BrowseUiState.Error())
}

/** A list id that never parsed (a bad deep link): nothing to retry, so Go back alone. */
@PreviewLightDark
@Composable
private fun BrowseBadLinkPreview() {
    BrowsePreviewHost(BrowseUiState.Error(canRetry = false))
}

@PreviewLightDark
@Composable
private fun BrowseNoMatchesPreview() {
    BrowsePreviewHost(BrowseUiState.NoMatches("zz"))
}

/** A call filter on, call log access off: the hard gate with both ways forward. */
@PreviewLightDark
@Composable
private fun BrowseCallLogDeniedPreview() {
    BrowsePreviewHost(BrowseUiState.CallLogDenied, activeFilters = setOf(BrowseFilter.CalledRecently))
}

/** Call log access off with no filter: the rows, without call times, under the notice. */
@PreviewLightDark
@Composable
private fun BrowseCallLogNoticePreview() {
    BrowsePreviewHost((previewState as BrowseUiState.Ready).copy(callLogPermissionDenied = true))
}

/** Browsing a smart list: no "+", and the selection bar offers Copy only. */
@PreviewLightDark
@Composable
private fun BrowseSmartListPreview() {
    BrowsePreviewHost(
        (previewState as BrowseUiState.Ready).copy(isMultiSelect = true, selectedIds = setOf(1L)),
        listType = ListType.SMART
    )
}

/** A smart list whose rule matches nobody: no "Add people" to offer. */
@PreviewLightDark
@Composable
private fun BrowseSmartListEmptyPreview() {
    BrowsePreviewHost(BrowseUiState.Empty, listType = ListType.SMART)
}
