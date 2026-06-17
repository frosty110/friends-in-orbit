package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Lists Manager screen.
 *
 * Two-layer composable:
 *   - outer ([ListsManagerScreen]) wires Hilt + lifecycle-aware collection +
 *     SnackbarHost + the snackbar-triggered VM dispatches (archive/restore
 *     produce toast feedback per the copywriting contract).
 *   - inner ([ListsManagerContent]) is stateless — all event callbacks flow in,
 *     all state flows in, rendering only.
 *
 * Layout per the Lists Manager layout spec:
 *   AppBar + reorderable LazyColumn over `state.active` + ArchivedSectionHeader
 *   + (when expanded) `state.archived` rows + footer hint, plus an
 *   ExtendedFloatingActionButton anchored bottom-right.
 *
 * Archive filter lives in `HomeViewModel`, not here. This screen is the only
 * surface that ever displays archived rows.
 *
 * Every `ReorderableItem` content carries `Modifier.animateItem()` — the older
 * deprecated placement-animation API is not used.
 */
/**
 * BULK-05 wiring: each active list row carries a trailing "Add contacts" "+"
 * affordance whose tap routes via [onAddContacts] → `Routes.pickContacts(listId)`.
 * The NavHost is the only caller that knows about routes, so this screen just
 * surfaces the callback and ListRow renders the icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsManagerScreen(
    onBack: () -> Unit,
    onOpenList: (listId: String) -> Unit,
    onAddContacts: (listId: String) -> Unit = {},   // BULK-05 — entry to ContactPickerScreen
    // When true, the create-list bottom sheet is expanded on first composition.
    // Used by Home's "Create your first list" / "New list" CTAs (Routes.lists(openCreate = true))
    // so a single tap from Home lands the user directly in the creation form.
    // Subsequent rotations preserve whatever the user did from there via rememberSaveable.
    openCreateOnLaunch: Boolean = false,
    vm: ListsManagerViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // H4 fix — surface VM mutation failures via the same SnackbarHostState.
    // The collector mirrors the ContactPickerScreen / ContactDetailScreen
    // pattern; `actionLabel` is now nullable so failure-only events display
    // without a forced action button.
    //
    // LOW polish (Group 5) — archive Undo lifecycle: the previous screen-side
    // `scope.launch { showSnackbar(...) }` died on screen leave, so the Undo
    // work was lost. We now ride VM-emitted events that carry the listId via
    // `SnackbarEvent.actionPayload`; tapping Undo dispatches back into the VM
    // (which is bound to viewModelScope, surviving navigation animations).
    //
    // repeatOnLifecycle gates the collect by STARTED. Snackbar emissions while
    // the screen is backgrounded drop on the floor (replay = 0).
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.snackbarEvents.collect { event ->
                val result = snackbarHostState.showSnackbar(
                    message = event.message,
                    actionLabel = event.actionLabel,
                )
                if (result == SnackbarResult.ActionPerformed && event.actionPayload != null) {
                    vm.onUndoArchive(event.actionPayload)
                }
            }
        }
    }

    // 2026-06-09 #26 — create used to show "List created." and strand the user
    // here. Now the VM emits the new id and we route straight into the new
    // list's configuration screen; arriving there IS the confirmation.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.createdListEvents.collect { newListId ->
                onOpenList(newListId.toString())
            }
        }
    }

    // The launch-then-flip pattern lives here, not in CreateListBottomSheet
    // itself. Direct
    // `showSheet = false` is a known visual-jank bug; we hide via the sheet
    // animation first, then flip the parent flag in invokeOnCompletion.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSheet by rememberSaveable { mutableStateOf(openCreateOnLaunch) }

    ListsManagerContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenList = onOpenList,
        onAddContacts = onAddContacts,
        onCreate = { showSheet = true },
        onMove = vm::moveList,
        // LOW polish (Group 5) — VM owns the snackbar event + Undo dispatch
        // (see archiveList + onUndoArchive). Screen no longer races
        // navigation-on-snackbar.
        onArchive = vm::archiveList,
        onDelete = vm::deleteList,
        // F-11 — quick rename surfaced from the manager overflow menu;
        // delegates to ListRepository.updateName via vm::renameList without
        // forcing a drill into List Configuration.
        onRename = vm::renameList,
        onRestore = { id ->
            vm.unarchiveList(id)
            scope.launch {
                snackbarHostState.showSnackbar("List restored.")
            }
        },
        onToggleArchived = vm::toggleArchivedExpanded,
    )

    if (showSheet) {
        CreateListBottomSheet(
            sheetState = sheetState,
            onCreate = { template, name ->
                // 2026-06-09 #26 — no "List created." snackbar: the VM's
                // createdListEvents collector above navigates to the new
                // list's config screen instead of stranding the user here.
                vm.createList(template, name)
                scope.launch { sheetState.hide() }
                    .invokeOnCompletion {
                        if (!sheetState.isVisible) showSheet = false
                    }
            },
            onDismiss = {
                scope.launch { sheetState.hide() }
                    .invokeOnCompletion {
                        if (!sheetState.isVisible) showSheet = false
                    }
            },
        )
    }
}

@Composable
private fun ListsManagerContent(
    state: ListsManagerUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenList: (listId: String) -> Unit,
    onAddContacts: (listId: String) -> Unit,
    onCreate: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onArchive: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onRestore: (Long) -> Unit,
    onToggleArchived: () -> Unit,
) {
    OrbitScreen {
        OrbitAppBar(
            title = "Lists",
            leading = { OrbitIconButton("arrow-left", onBack, contentDescription = "Back") },
            trailing = { OrbitIconButton("plus", onCreate, contentDescription = "New list") },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                is ListsManagerUiState.Loading -> {
                    // Compose-level "still hydrating" state. Nothing to draw — the
                    // Lifecycle aware collection delivers the next emission within ms.
                }
                is ListsManagerUiState.Empty -> {
                    EmptyState(onCreate = onCreate)
                }
                is ListsManagerUiState.Ready -> {
                    ReadyContent(
                        state = state,
                        onOpenList = onOpenList,
                        onAddContacts = onAddContacts,
                        onMove = onMove,
                        onArchive = onArchive,
                        onDelete = onDelete,
                        onRename = onRename,
                        onRestore = onRestore,
                        onToggleArchived = onToggleArchived,
                    )
                }
            }

            // FAB anchored bottom-end; UI-SPEC §"Component Inventory" — terracotta accent.
            ExtendedFloatingActionButton(
                onClick = onCreate,
                containerColor = OrbitTheme.colors.accent,
                contentColor = OrbitTheme.colors.accentFg,
                shape = OrbitTheme.shapes.full,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = OrbitTheme.spacing.x1),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(OrbitTheme.spacing.x4),
            ) {
                PhIcon(name = "plus", size = OrbitTheme.spacing.x5 - OrbitTheme.spacing.x1, tint = OrbitTheme.colors.accentFg)
                Spacer(Modifier.fillMaxWidth(0f))
                Text(
                    text = " New list",
                    style = OrbitTheme.type.button.copy(color = OrbitTheme.colors.accentFg),
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun ReadyContent(
    state: ListsManagerUiState.Ready,
    onOpenList: (listId: String) -> Unit,
    onAddContacts: (listId: String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onArchive: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onRestore: (Long) -> Unit,
    onToggleArchived: () -> Unit,
) {
    // D-25 — pending delete target. Tap on the trash icon stages an id; the
    // DeleteListDialog reads it to decide whether to render and clears it
    // on confirm or dismiss. rememberSaveable so a config change mid-dialog
    // does not silently drop the pending action.
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    // F-11 — pending rename target. The active-row overflow menu's "Rename"
    // entry stages (id, currentName) here; the RenameListDialog reads them
    // to decide whether to render and clears them on save or dismiss.
    // rememberSaveable so a config change mid-dialog does not silently drop
    // the pending action.
    var pendingRenameId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingRenameName by rememberSaveable { mutableStateOf("") }
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // The header / archived rows carry composite key prefixes ("archived-header",
        // "footer", or "archived:<id>") — only active-row drags reach this body
        // because ReorderableItem wraps active items only. The "active-card-spacer-top"
        // item occupies LazyColumn index 0, so absolute indices are offset by 1
        // relative to state.active.
        onMove(from.index - 1, to.index - 1)
    }
    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = OrbitTheme.spacing.x4),
        contentPadding = PaddingValues(top = OrbitTheme.spacing.x1, bottom = OrbitTheme.spacing.x10),
    ) {
        if (state.active.isNotEmpty()) {
            item(key = "active-card-spacer-top") {
                Spacer(Modifier.height(OrbitTheme.spacing.x1))
            }
            items(state.active, key = { it.id }) { tile ->
                ReorderableItem(reorderState, key = tile.id) { isDragging ->
                    val active = state.active
                    val idx = active.indexOfFirst { it.id == tile.id }
                    Box(
                        modifier = Modifier
                            .orbitCardShadow(OrbitTheme.shapes.lg, OrbitTheme.colors.isDark)
                            .clip(OrbitTheme.shapes.lg)
                            .background(OrbitTheme.colors.surface),
                    ) {
                        ListRow(
                            tile = tile,
                            isDragging = isDragging,
                            modifier = Modifier.animateItem(),
                            dragHandleModifier = Modifier.draggableHandle(),
                            onClick = { onOpenList(tile.id.toString()) },
                            // F-11 — surface a quick-rename AlertDialog instead
                            // of routing to List Configuration. The dialog reads
                            // (pendingRenameId, pendingRenameName) below.
                            onRename = {
                                pendingRenameId = tile.id
                                pendingRenameName = tile.name
                            },
                            onArchive = { onArchive(tile.id) },
                            onConfigure = { onOpenList(tile.id.toString()) },
                            onMoveUp = {
                                if (idx > 0) onMove(idx, idx - 1)
                            },
                            onMoveDown = {
                                if (idx in 0 until active.lastIndex) onMove(idx, idx + 1)
                            },
                            onAddContacts = { onAddContacts(tile.id.toString()) },
                        )
                    }
                }
            }
        }

        // Archived (N) collapsible header.
        item(key = "archived-header") {
            ArchivedSectionHeader(
                count = state.archived.size,
                expanded = state.archivedExpanded,
                onToggle = onToggleArchived,
                modifier = Modifier.animateItem(),
            )
        }

        if (state.archivedExpanded && state.archived.isNotEmpty()) {
            items(state.archived, key = { "archived:${it.id}" }) { tile ->
                Box(
                    modifier = Modifier
                        .padding(top = OrbitTheme.spacing.x1)
                        .orbitCardShadow(OrbitTheme.shapes.lg, OrbitTheme.colors.isDark)
                        .clip(OrbitTheme.shapes.lg)
                        .background(OrbitTheme.colors.surfaceAlt),
                ) {
                    ArchivedListRow(
                        tile = tile,
                        modifier = Modifier.animateItem(),
                        onRestore = { onRestore(tile.id) },
                        onDelete = { pendingDeleteId = tile.id },
                        onConfigure = { onOpenList(tile.id.toString()) },
                    )
                }
            }
        }

        // Footer hint — verbatim copy locked by the copywriting contract.
        item(key = "footer") {
            Text(
                text = "Drag to reorder. Lists higher up show first on home.",
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgSubtle),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = OrbitTheme.spacing.x5, start = OrbitTheme.spacing.x5, end = OrbitTheme.spacing.x5),
            )
        }
    }

    // D-25 — destructive confirmation dialog for hard-deleting an archived
    // list. Sits outside the LazyColumn so its layout is independent of list
    // scroll state. Confirm dispatches via `onDelete` (→ vm::deleteList) and
    // clears the pending id; dismiss simply clears.
    pendingDeleteId?.let { id ->
        DeleteListDialog(
            onConfirm = {
                onDelete(id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
        )
    }

    // F-11 — quick-rename dialog. Sits outside the LazyColumn so its layout is
    // independent of list scroll state. Save dispatches via `onRename`
    // (→ vm::renameList → ListRepository.updateName); dismiss simply clears.
    // The dialog itself blocks empty/blank commits, and the VM double-guards.
    pendingRenameId?.let { id ->
        RenameListDialog(
            currentName = pendingRenameName,
            onSave = { newName ->
                onRename(id, newName)
                pendingRenameId = null
                pendingRenameName = ""
            },
            onDismiss = {
                pendingRenameId = null
                pendingRenameName = ""
            },
        )
    }
}

@Composable
private fun ArchivedSectionHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = OrbitTheme.spacing.x4)
            .clickable(onClick = onToggle)
            .padding(vertical = OrbitTheme.spacing.x2),
    ) {
        PhIcon(
            name = if (expanded) "caret-down" else "caret-right",
            size = OrbitTheme.spacing.x4,
            tint = OrbitTheme.colors.fgMuted,
        )
        Text(
            text = "Archived ($count)",
            style = OrbitTheme.type.h2.copy(color = OrbitTheme.colors.fg),
        )
    }
}

@Composable
private fun EmptyState(onCreate: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(OrbitTheme.spacing.x6),
    ) {
        Spacer(Modifier.height(OrbitTheme.spacing.x8))
        Text(
            text = "No lists yet",
            style = OrbitTheme.type.h2.copy(color = OrbitTheme.colors.fg),
        )
        Text(
            text = "Add a list to start grouping the people you want to stay in touch with.",
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            textAlign = TextAlign.Center,
        )
        OrbitButton(
            text = "New list",
            onClick = onCreate,
            leadingIcon = "plus",
        )
    }
}

// Preview fixture for the stateless ListsManagerContent
// (THEME-04 / THEME-05 — D-06). Empty state renders the CTA without needing
// a populated list fixture.
private val previewState: ListsManagerUiState = ListsManagerUiState.Empty

@PreviewLightDark
@PreviewFontScale
@Composable
private fun ListsManagerContentPreview() {
    OrbitTheme {
        ListsManagerContent(
            state = previewState,
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onOpenList = {},
            onAddContacts = {},
            onCreate = {},
            onMove = { _, _ -> },
            onArchive = {},
            onDelete = {},
            onRename = { _, _ -> },
            onRestore = {},
            onToggleArchived = {},
        )
    }
}
