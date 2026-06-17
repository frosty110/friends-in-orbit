package app.orbit.ui.screens.home

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.AppViewModel
import app.orbit.data.entity.ListType
import app.orbit.ui.components.CountBadge
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.PostCallBanner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.orbit.ui.screens.lists.DeleteListDialog
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import kotlinx.coroutines.flow.collectLatest

/**
 * Home (mood picker) screen.
 *
 * **Two-layer pattern** (the screen-VM wiring template): Hilt-wired outer +
 * stateless inner. The outer collects ViewModel state, then forwards everything
 * to the stateless [HomeContent].
 *
 * **Token compliance:**
 *   - Heading uses `OrbitTheme.type.h2` (was hardcoded `26.sp`).
 *   - List-tile primary uses `OrbitTheme.type.listTile` (MT-01, was `17.sp`).
 *   - Subtitles use `OrbitTheme.type.meta` / `OrbitTheme.type.micro`.
 *   - Tile padding uses `OrbitTheme.spacing.x5` = 20dp (was `18.dp`).
 *   - Icon-well shape uses `OrbitTheme.shapes.md` = 12dp (MT-04 — was `10.dp`).
 *
 * **Behaviour:**
 *   - HOME-01: tile grid renders real list names from Room.
 *   - HOME-02: due-count pill renders only when `dueCount >= 1` (CountBadge
 *     itself is a no-op for `count <= 0`).
 *   - HOME-04: when no lists exist, Home renders a centered primary-weight
 *     "Create your first list" button under one warm line (no grid), which
 *     routes to Lists Manager with the create-list bottom sheet auto-opened
 *     (Routes.lists(openCreate = true)). Onboarding is not re-entered from
 *     Home — `AppViewModel` owns the first-run start-destination decision;
 *     Home is a manage surface, not a setup surface.
 *   - BROWSE-03 entry point: app-bar magnifying-glass icon → `onOpenSearch`
 *     (NavHost routes to `Routes.GlobalSearch`).
 *   - PRIV-03: list names render as "List" while
 *     `LocalPrivacyCurtain.current == true` (list names stay masked, but with
 *     the right noun).
 */
@Composable
fun HomeScreen(
    onOpenList: (listId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLists: () -> Unit,
    onCreateList: () -> Unit,
    // Long-press quick-actions menu — navigation legs (the NavHost owns routes).
    onAddPeopleToList: (listId: String) -> Unit = {},
    onOpenListSettings: (listId: String) -> Unit = {},
    onOpenContactWithFocus: (contactId: String, focusNote: Boolean) -> Unit = { _, _ -> },
    vm: HomeViewModel = hiltViewModel(),
    appVm: AppViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val postCallPrompt by appVm.postCallPrompt.collectAsStateWithLifecycle()
    val curtain = LocalPrivacyCurtain.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Long-press menu snackbar surface (archive/delete Undo, mute confirmation,
    // mutation failures). collectLatest so the newest action's snackbar
    // supersedes any still-showing one — without it an Undo snackbar (Indefinite
    // by default) would mask a following event (features/home/README.md
    // "swallowed-toast trap"). The `finally` finalizes a deferred delete on
    // dismissal, supersession, or screen-leave; it's a no-op for a delete that
    // was just undone, and for non-delete events. Gated by repeatOnLifecycle so
    // a delete committed on screen-leave still runs through viewModelScope.
    LaunchedEffect(lifecycleOwner, snackbarHostState) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.snackbarEvents.collectLatest { event ->
                try {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        when (event.kind) {
                            HomeSnackbarEvent.Kind.ARCHIVE_UNDO -> event.payloadListId?.let(vm::undoArchive)
                            HomeSnackbarEvent.Kind.DELETE_UNDO -> event.payloadListId?.let(vm::undoDelete)
                            HomeSnackbarEvent.Kind.PLAIN -> Unit
                        }
                    }
                } finally {
                    if (event.kind == HomeSnackbarEvent.Kind.DELETE_UNDO) {
                        event.payloadListId?.let(vm::commitDelete)
                    }
                }
            }
        }
    }

    // NOTE-02 — `LifecycleResumeEffect` re-fires on every resume, so the
    // dialer→app return path always re-derives the post-call prompt.
    // `LaunchedEffect(Unit)` would NOT work here: Home stays composed across
    // the call, so a single-fire effect misses the resume after the user
    // makes the outgoing call from the dialer. The codebase's older
    // `LifecycleEventObserver + DisposableEffect` pattern in SettingsScreen /
    // ContactPickerScreen achieves the same semantics; this is the modern
    // lifecycle-runtime-compose 2.8.7 idiom (per the version catalog).
    // lifecycle-runtime-compose 2.8.7 deprecated the single-arg
    // `LifecycleResumeEffect(lifecycleOwner)` overload — the signature now
    // requires an explicit `key1` so the runtime can decide when to relaunch.
    // Pass `Unit` because Home's resume hook is identity-stable for the
    // lifetime of this composition (the prompt state itself is owned by
    // AppViewModel — `key1` is purely an identity token).
    LifecycleResumeEffect(key1 = Unit, lifecycleOwner = lifecycleOwner) {
        appVm.checkPostCallPrompt()
        onPauseOrDispose { /* prompt state lives in the VM; nothing to clean up */ }
    }

    HomeContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onOpenList = onOpenList,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onOpenLists = onOpenLists,
        onCreateList = onCreateList,
        onAddPeople = { listId -> onAddPeopleToList(listId.toString()) },
        onToggleMute = { listId, enabled -> vm.toggleNotifications(listId, enabled) },
        onListSettings = { listId -> onOpenListSettings(listId.toString()) },
        onArchive = { listId -> vm.archiveList(listId) },
        onDeleteConfirmed = { listId -> vm.requestDelete(listId) },
        postCallPrompt = postCallPrompt,
        curtain = curtain,
        onPostCallAddNote = { prompt ->
            appVm.dismissPostCallPrompt(prompt.callEventId)
            onOpenContactWithFocus(prompt.contactId.toString(), true)
        },
        onPostCallDismiss = { prompt ->
            appVm.dismissPostCallPrompt(prompt.callEventId)
        },
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenList: (listId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLists: () -> Unit,
    onCreateList: () -> Unit,
    // Long-press quick-actions — Long listId so the renderer can bind per tile.
    onAddPeople: (Long) -> Unit = {},
    onToggleMute: (listId: Long, currentlyEnabled: Boolean) -> Unit = { _, _ -> },
    onListSettings: (Long) -> Unit = {},
    onArchive: (Long) -> Unit = {},
    onDeleteConfirmed: (Long) -> Unit = {},
    postCallPrompt: AppViewModel.PostCallPromptState? = null,
    curtain: Boolean = false,
    onPostCallAddNote: (AppViewModel.PostCallPromptState) -> Unit = {},
    onPostCallDismiss: (AppViewModel.PostCallPromptState) -> Unit = {},
) {
    // Which tile's quick-actions menu is open (null = none) — single-open
    // invariant. Plus the pending delete-confirm target; rememberSaveable so a
    // config change mid-dialog doesn't drop the staged action.
    var menuAnchorListId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val tiles: List<ListTileState> = when (state) {
        // Loading = pre-first-database-answer window (slow SQLCipher cold
        // open). Renders as quiet chrome — app bar over background, no grid,
        // no header, never the first-install CTA. ADR 0006 §Skeleton policy
        // forbids skeletons on steady-state navigation, so nothing shimmers.
        HomeUiState.Loading, HomeUiState.Empty -> emptyList()
        is HomeUiState.Ready -> state.lists
    }
    // The heading counts distinct people, not per-list due rows: a contact due
    // on two lists is one person ready, so summing per-tile `dueCount`
    // over-counted. HomeViewModel derives the union count
    // (`Ready.dueContactCount`); tiles keep their per-list badges.
    val due = (state as? HomeUiState.Ready)?.dueContactCount ?: 0
    val isEmpty = state is HomeUiState.Empty
    val isLoading = state is HomeUiState.Loading

    Box(modifier = Modifier.fillMaxSize()) {
      OrbitScreen {
        OrbitAppBar(
            title = "Orbit",
            trailing = {
                Row {
                    OrbitIconButton(
                        icon = "magnifying-glass",
                        onClick = onOpenSearch,
                        contentDescription = "Search",
                    )
                    OrbitIconButton(
                        icon = "list",
                        onClick = onOpenLists,
                        contentDescription = "Lists",
                    )
                    OrbitIconButton(
                        icon = "gear",
                        onClick = onOpenSettings,
                        contentDescription = "Settings",
                    )
                }
            },
        )

        // NOTE-02 — PostCallBanner sits above the eyebrow + heading so it
        // earns the user's first glance after a return from the dialer. Slide
        // + fade enter/exit at OrbitMotion.DurBaseMs (250ms — matches the
        // app-wide motion language).
        AnimatedVisibility(
            visible = postCallPrompt != null,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(OrbitMotion.DurBaseMs),
            ) + fadeIn(animationSpec = tween(OrbitMotion.DurBaseMs)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(OrbitMotion.DurBaseMs),
            ) + fadeOut(animationSpec = tween(OrbitMotion.DurBaseMs)),
        ) {
            postCallPrompt?.let { prompt ->
                PostCallBanner(
                    contactName = prompt.contactName,
                    curtain = curtain,
                    onAddNote = { onPostCallAddNote(prompt) },
                    onDismiss = { onPostCallDismiss(prompt) },
                )
            }
        }

        // Header renders only in Ready. Loading is quiet chrome; Empty leaves
        // the one-line CTA tile to carry the screen (rules.md §Design 8 —
        // empty states are one line, warm, non-performative).
        if (!isLoading && !isEmpty) {
            Column(Modifier.padding(horizontal = OrbitTheme.spacing.x5, vertical = 0.dp)) {
                Text(
                    text = "Today",
                    style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgMuted),
                )
                if (due > 0) {
                    Text(
                        text = if (due == 1) "1 person ready" else "$due people ready",
                        style = OrbitTheme.type.h2.copy(color = OrbitTheme.colors.fg),
                        modifier = Modifier.padding(
                            top = OrbitTheme.spacing.x1,
                            bottom = OrbitTheme.spacing.x2,
                        ),
                    )
                } else {
                    // Nobody due: a warm caught-up line instead of the cold
                    // "0 people ready" (grammar + voice — README.md
                    // "Content fundamentals").
                    Text(
                        text = "All caught up",
                        style = OrbitTheme.type.h2.copy(color = OrbitTheme.colors.fg),
                        modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
                    )
                    Text(
                        text = "Nobody is due right now.",
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                        modifier = Modifier.padding(
                            top = OrbitTheme.spacing.x1,
                            bottom = OrbitTheme.spacing.x2,
                        ),
                    )
                }
            }
        }

        // HOME-04: the genuine first-install state gets a primary-weight CTA,
        // centered, with one warm line above it (features/home/README.md) —
        // creating the first list is the screen's whole job here, so it isn't
        // a footnote-weight row. Routes to Lists Manager with the create-list
        // bottom sheet auto-opened, never back into onboarding. The button is
        // the screen's single terracotta element in this state.
        if (isEmpty) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = OrbitTheme.spacing.x6),
            ) {
                Text(
                    text = "Start with the people you keep meaning to call.",
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x5))
                OrbitButton(text = "Create your first list", onClick = onCreateList)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = OrbitTheme.spacing.x4,
                    end = OrbitTheme.spacing.x4,
                    top = OrbitTheme.spacing.x3,
                    bottom = OrbitTheme.spacing.x6,
                ),
                verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Loading contributes no items — the grid stays a quiet surface
                // until the database answers (never the first-install CTA).
                if (isLoading) {
                    // No items.
                } else {
                    // HOME-01: real list tiles, stable keys for Compose skipping.
                    items(tiles, key = { it.id }) { tile ->
                        ListTile(
                            tile = tile,
                            menuOpen = menuAnchorListId == tile.id,
                            onClick = { onOpenList(tile.id.toString()) },
                            onLongPress = { menuAnchorListId = tile.id },
                            onDismissMenu = { menuAnchorListId = null },
                            onAddPeople = { onAddPeople(tile.id) },
                            onToggleMute = { onToggleMute(tile.id, tile.notificationsEnabled) },
                            onListSettings = { onListSettings(tile.id) },
                            onArchive = { onArchive(tile.id) },
                            onDelete = { pendingDeleteId = tile.id },
                        )
                    }
                    // Trailing "New list" CTA — list-first model (lists are the
                    // unit the user manipulates; people are list members). Mirrors
                    // the dashed new-list pattern in Lists Manager
                    // (features/orbit-lists/README.md:25).
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CreateListTile(label = "New list", onClick = onCreateList)
                    }
                }
            }
        }
      }

      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter),
      )
    }

    // Destructive confirm for the long-press Delete. Sits outside the grid so
    // its layout is independent of scroll state. Confirm stages the deferred
    // delete (onDeleteConfirmed → vm.requestDelete); dismiss just clears.
    pendingDeleteId?.let { id ->
        DeleteListDialog(
            onConfirm = {
                onDeleteConfirmed(id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListTile(
    tile: ListTileState,
    onClick: () -> Unit,
    menuOpen: Boolean = false,
    onLongPress: () -> Unit = {},
    onDismissMenu: () -> Unit = {},
    onAddPeople: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onListSettings: () -> Unit = {},
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val curtain = LocalPrivacyCurtain.current
    // List names stay masked under the curtain (they are user-authored and can
    // reveal relationships — "people who ground me", recovery-context lists;
    // list and contact names auto-anonymize per features/privacy-and-lock/README.md),
    // but the neutral noun for a list is "List", not "Contact".
    val displayName = if (curtain) "List" else tile.name
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 128.dp)
            .orbitCardShadow(shape = OrbitTheme.shapes.lg, isDark = OrbitTheme.colors.isDark)
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.surface)
            // Tap routes to Card View (one-at-a-time loop); long-press opens the
            // manage-this-list quick-actions menu. features/home/README.md.
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "Quick actions",
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .padding(OrbitTheme.spacing.x5),
    ) {
        // Anchored quick-actions menu — manage-this-list actions otherwise two
        // screens deep in Lists Manager. Order locked by the spec; destructive
        // pair sits below a divider.
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
        ) {
            DropdownMenuItem(
                text = { Text("Add people") },
                onClick = { onDismissMenu(); onAddPeople() },
            )
            DropdownMenuItem(
                text = { Text(if (tile.notificationsEnabled) "Mute prompts" else "Unmute prompts") },
                onClick = { onDismissMenu(); onToggleMute() },
            )
            DropdownMenuItem(
                text = { Text("List settings") },
                onClick = { onDismissMenu(); onListSettings() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Archive") },
                onClick = { onDismissMenu(); onArchive() },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = OrbitTheme.colors.danger) },
                onClick = { onDismissMenu(); onDelete() },
            )
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(OrbitTheme.shapes.md)
                        .background(OrbitTheme.colors.accentTint),
                ) {
                    PhIcon(name = "users", size = 18.dp, tint = OrbitTheme.colors.accentPress)
                }
                // LIST-07 — type-distinct visual cue. Smart tiles get an inline
                // "shuffle-angular" auto glyph next to the due-count badge.
                // Glyph stays visible under the privacy curtain (PRIV-03)
                // because type isn't a name. Static tiles render no glyph.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tile.type == ListType.SMART) {
                        PhIcon(
                            name = "shuffle-angular",
                            size = 14.dp,
                            tint = OrbitTheme.colors.accent,
                            modifier = Modifier.semantics {
                                contentDescription = "Smart list"
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    // HOME-02: pill renders only when count >= 1. CountBadge itself
                    // returns early at count <= 0, so the count > 0 guard here is
                    // belt-and-suspenders — keeps the call site explicit
                    // ("count == 0 → no pill").
                    if (tile.dueCount > 0) CountBadge(count = tile.dueCount)
                }
            }
            Column {
                Text(
                    text = displayName,
                    color = OrbitTheme.colors.fg,
                    style = OrbitTheme.type.listTile,
                )
                // Member count replaces the old generic "Your people" — the
                // subtitle now tells you something about the list. A count is
                // not a name, so it stays visible under the privacy curtain
                // (PRIV-03). Null = counts not hydrated yet (cache-first
                // initial frame): render an empty line so the tile doesn't
                // reflow when the count lands a frame later.
                Text(
                    text = when (val count = tile.memberCount) {
                        null -> ""
                        0 -> "No one yet"
                        1 -> "1 person"
                        else -> "$count people"
                    },
                    style = OrbitTheme.type.meta.copy(
                        color = OrbitTheme.colors.fgMuted,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CreateListTile(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(OrbitTheme.shapes.lg)
            .clickable(onClick = onClick)
            .padding(horizontal = OrbitTheme.spacing.x4),
    ) {
        PhIcon(name = "plus", size = 16.dp, tint = OrbitTheme.colors.fgMuted)
        Text(
            text = label,
            style = OrbitTheme.type.meta.copy(
                color = OrbitTheme.colors.fgMuted,
            ),
        )
    }
}

// Preview fixture for the stateless HomeContent. Fixture is a plain data-class
// instance (THEME-04 / THEME-05).
private val previewState: HomeUiState = HomeUiState.Ready(
    lists = listOf(
        ListTileState(id = 1L, name = "Inner orbit", dueCount = 3, type = ListType.STATIC, memberCount = 10),
        ListTileState(id = 2L, name = "Late night", dueCount = 0, type = ListType.SMART, memberCount = 1),
    ),
    hasPermissions = true,
    dueContactCount = 3,
)

// Nobody due — exercises the "All caught up" heading + sub-line.
private val previewCaughtUpState: HomeUiState = HomeUiState.Ready(
    lists = listOf(
        ListTileState(id = 1L, name = "Inner orbit", dueCount = 0, type = ListType.STATIC, memberCount = 10),
        ListTileState(id = 2L, name = "Late night", dueCount = 0, type = ListType.SMART, memberCount = 0),
    ),
    hasPermissions = true,
)

@PreviewLightDark
@PreviewFontScale
@Composable
private fun HomeContentPreview() {
    OrbitTheme {
        HomeContent(
            state = previewState,
            onOpenList = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenLists = {},
            onCreateList = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun HomeContentCaughtUpPreview() {
    OrbitTheme {
        HomeContent(
            state = previewCaughtUpState,
            onOpenList = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenLists = {},
            onCreateList = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun HomeContentEmptyPreview() {
    OrbitTheme {
        HomeContent(
            state = HomeUiState.Empty,
            onOpenList = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenLists = {},
            onCreateList = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun HomeContentLoadingPreview() {
    OrbitTheme {
        HomeContent(
            state = HomeUiState.Loading,
            onOpenList = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenLists = {},
            onCreateList = {},
        )
    }
}
