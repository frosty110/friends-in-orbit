package app.orbit.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.AppViewModel
import app.orbit.data.entity.ListType
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.PostCallBanner
import app.orbit.ui.screens.lists.DeleteListDialog
import app.orbit.ui.theme.OrbitListTones
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitRhythmTones
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import coil.compose.SubcomposeAsyncImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest

/**
 * Home (mood picker) screen — the always-on recommender (vision/00-home).
 *
 * **Two-layer pattern**: Hilt-wired outer + stateless inner. The outer collects
 * ViewModel state, then forwards everything to the stateless [HomeContent].
 *
 * **Redesign (HOME-3/5/6/7):**
 *   - HOME-6: no "due / N ready / caught up" framing. The header is a calm date,
 *     never a count or a completion state.
 *   - HOME-5: full-width, single-column tonal cards — a tinted header band over a
 *     lighter graph wash, both shades of the list's own [OrbitListTones] colour.
 *   - HOME-3: each card shows "Next up" — the head of that list's queue (reused
 *     from `SurfaceNextUseCase` via `HomeFeed.enrichment`) — with a warm recency
 *     line. The whole card taps through to Card View; there is no call button.
 *   - HOME-7: a 7-day rhythm strip per card, bars scaled relative to the list's
 *     own busiest day (125% headroom), coloured per person.
 *   - PRIV-03: list and contact names mask under the privacy curtain.
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
    // supersedes any still-showing one (features/home/README.md
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
    // dialer→app return path always re-derives the post-call prompt. (See git
    // history for why `LaunchedEffect(Unit)` is insufficient here.)
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
        // Loading = pre-first-database-answer window (slow SQLCipher cold open).
        // Renders as quiet chrome — app bar over background, no list, never the
        // first-install CTA (ADR 0006 §Skeleton policy).
        HomeUiState.Loading, HomeUiState.Empty -> emptyList()
        is HomeUiState.Ready -> state.lists
    }
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

        // NOTE-02 — PostCallBanner sits above the header so it earns the user's
        // first glance after a return from the dialer.
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

        // HOME-6 — calm date orientation only. No count, no "caught up": Home is
        // an always-on recommender, not an inbox. Header shows only in Ready;
        // Loading is quiet chrome, Empty carries the first-install CTA.
        if (!isLoading && !isEmpty) {
            val dateLabel = remember {
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
            }
            Column(Modifier.padding(horizontal = OrbitTheme.spacing.x5, vertical = 0.dp)) {
                Text(
                    text = "Today",
                    style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgMuted),
                )
                Text(
                    text = dateLabel,
                    style = OrbitTheme.type.h2.copy(color = OrbitTheme.colors.fg),
                    modifier = Modifier.padding(
                        top = OrbitTheme.spacing.x1,
                        bottom = OrbitTheme.spacing.x2,
                    ),
                )
            }
        }

        // HOME-04: the genuine first-install state gets a primary-weight CTA,
        // centered, with one warm line above it. Routes to Lists Manager with
        // the create-list bottom sheet auto-opened.
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
            // HOME-5 — single column of full-width tonal cards.
            LazyColumn(
                contentPadding = PaddingValues(
                    start = OrbitTheme.spacing.x4,
                    end = OrbitTheme.spacing.x4,
                    top = OrbitTheme.spacing.x3,
                    bottom = OrbitTheme.spacing.x6,
                ),
                verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Loading contributes no items — the list stays a quiet surface
                // until the database answers (never the first-install CTA).
                if (!isLoading) {
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
                    item { CreateListTile(label = "New list", onClick = onCreateList) }
                    item { ReflectionFooter() }
                }
            }
        }
      }

      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter),
      )
    }

    // Destructive confirm for the long-press Delete. Sits outside the list so
    // its layout is independent of scroll state.
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
    val isDark = OrbitTheme.colors.isDark
    val tone = OrbitListTones.forKey(tile.id, isDark)
    // List names stay masked under the curtain (user-authored, relationship-
    // revealing); the neutral noun for a list is "List".
    val displayName = if (curtain) "List" else tile.name
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .orbitCardShadow(shape = OrbitTheme.shapes.lg, isDark = isDark)
            .clip(OrbitTheme.shapes.lg)
            .background(tone.wash)
            // Tap routes to Card View (the whole tile is one target — no call
            // button); long-press opens the manage-this-list quick-actions menu.
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "Quick actions",
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            ),
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
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

        Column(Modifier.fillMaxWidth()) {
            // Zone 1 — tinted header band: list name + Next up, in one row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tone.band)
                    .padding(OrbitTheme.spacing.x4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(118.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            style = OrbitTheme.type.listTile.copy(color = tone.nameFg),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // LIST-07 — smart-list type cue. Type isn't a name, so it
                        // stays visible under the privacy curtain.
                        if (tile.type == ListType.SMART) {
                            Spacer(Modifier.width(4.dp))
                            PhIcon(name = "shuffle-angular", size = 13.dp, tint = tone.nameFg)
                        }
                    }
                    Text(
                        text = memberLabel(tile.memberCount),
                        style = OrbitTheme.type.meta.copy(color = tone.nameFg.copy(alpha = 0.72f)),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Spacer(Modifier.width(OrbitTheme.spacing.x3))
                NextUpRow(nextUp = tile.nextUp, curtain = curtain, modifier = Modifier.weight(1f))
            }
            // Zone 2 — lighter wash under the 7-day rhythm.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tone.wash)
                    .padding(
                        start = OrbitTheme.spacing.x4,
                        end = OrbitTheme.spacing.x4,
                        top = OrbitTheme.spacing.x3,
                        bottom = OrbitTheme.spacing.x4,
                    ),
            ) {
                RhythmStrip(rhythm = tile.rhythm)
            }
        }
    }
}

/** HOME-3 — the recommendation half of the header band. */
@Composable
private fun NextUpRow(nextUp: NextUp?, curtain: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (nextUp == null) {
            // Rare: list has members but nobody surfaceable right now.
            Text(
                text = "No one up next",
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.weight(1f),
            )
            return@Row
        }
        // Contact names mask under the curtain (PRIV-03).
        val name = if (curtain) "Someone" else nextUp.name
        NextUpAvatar(photoUri = if (curtain) null else nextUp.photoUri, name = name, size = 44.dp)
        Spacer(Modifier.width(OrbitTheme.spacing.x3))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Next up",
                style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgSubtle),
            )
            Text(
                text = name,
                style = OrbitTheme.type.listTile.copy(color = OrbitTheme.colors.fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = nextUp.why,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(OrbitTheme.spacing.x2))
        PhIcon(name = "caret-right", size = 20.dp, tint = OrbitTheme.colors.fgSubtle)
    }
}

/**
 * Photo-with-initials-fallback avatar for the Next-up person. Follows the
 * `CallLogAvatar` / `ContactPhoto` convention but uses [SubcomposeAsyncImage] so
 * a blank/error load falls back to real initials rather than an empty crop.
 */
@Composable
private fun NextUpAvatar(photoUri: String?, name: String, size: Dp) {
    if (photoUri.isNullOrBlank()) {
        Avatar(name = name, size = size)
        return
    }
    SubcomposeAsyncImage(
        model = photoUri,
        contentDescription = null,
        loading = { Avatar(name = name, size = size) },
        error = { Avatar(name = name, size = size) },
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(OrbitTheme.colors.bgSubtle),
    )
}

/**
 * HOME-7 — 7-day rhythm strip. Bars are RELATIVE to this list's own busiest day
 * (axis = 125% of it), so short-chat lists and long-call lists each read fully.
 * One bar segment per qualifying call, coloured per person; quiet days show a
 * faint dot. Reflection, not a dashboard: no numbers, no targets.
 */
@Composable
private fun RhythmStrip(rhythm: List<RhythmDay>) {
    val labels = remember {
        val today = LocalDate.now()
        (0..6).map { offset ->
            today.minusDays((6 - offset).toLong())
                .dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
        }
    }
    val totals = rhythm.map { day -> day.calls.sumOf { it.durationSeconds } }
    val scaleMax = ((totals.maxOrNull() ?: 0).coerceAtLeast(1)) * RHYTHM_HEADROOM

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "Last 7 days",
            style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgSubtle),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            rhythm.forEachIndexed { idx, day ->
                DayColumn(
                    day = day,
                    label = labels.getOrElse(idx) { "" },
                    isToday = idx == rhythm.lastIndex,
                    scaleMax = scaleMax,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: RhythmDay,
    label: String,
    isToday: Boolean,
    scaleMax: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier.height(RHYTHM_BAR_AREA),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (day.calls.isEmpty()) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(OrbitTheme.colors.line),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    day.calls.forEach { call ->
                        val frac = (call.durationSeconds / scaleMax).coerceIn(0f, 1f)
                        val h = (frac * RHYTHM_BAR_AREA.value).coerceAtLeast(6f).dp
                        Box(
                            Modifier
                                .height(h)
                                .width(RHYTHM_BAR_WIDTH)
                                .clip(RoundedCornerShape(6.dp))
                                .background(OrbitRhythmTones.barForId(call.contactId)),
                        )
                    }
                }
            }
        }
        Text(
            text = label,
            style = OrbitTheme.type.micro.copy(
                color = if (isToday) OrbitTheme.colors.accent else OrbitTheme.colors.fgSubtle,
                fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

private fun memberLabel(count: Int?): String = when (count) {
    null -> ""
    0 -> "No one yet"
    1 -> "1 person"
    else -> "$count people"
}

// Reflection footer — the app's one piece of wisdom, surfaced once at the foot
// of Home. Body size (16sp), never smaller. Two short sentences.
@Composable
private fun ReflectionFooter() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = OrbitTheme.spacing.x5,
                vertical = OrbitTheme.spacing.x5,
            ),
    ) {
        Text(
            text = "The call that makes your day can make someone else's. " +
                "You deserve to be the one who reaches out.",
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            textAlign = TextAlign.Center,
        )
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
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
        )
    }
}

private val RHYTHM_BAR_AREA: Dp = 48.dp
private val RHYTHM_BAR_WIDTH: Dp = 26.dp
private const val RHYTHM_HEADROOM: Float = 1.25f

// ---- Previews ----

private fun previewRhythm(seed: Int): List<RhythmDay> = listOf(
    RhythmDay(listOf(RhythmCall(1L, 14 * 60))),
    RhythmDay(emptyList()),
    RhythmDay(listOf(RhythmCall(2L, 26 * 60), RhythmCall(1L, 6 * 60))),
    RhythmDay(emptyList()),
    RhythmDay(listOf(RhythmCall(3L, (41 - seed) * 60))),
    RhythmDay(listOf(RhythmCall(2L, 9 * 60))),
    RhythmDay(listOf(RhythmCall(1L, 4 * 60))),
)

private val previewState: HomeUiState = HomeUiState.Ready(
    lists = listOf(
        ListTileState(
            id = 1L, name = "Inner orbit", dueCount = 3, type = ListType.STATIC, memberCount = 12,
            nextUp = NextUp(1L, "Kai", null, "3 weeks since you last spoke"),
            rhythm = previewRhythm(0),
        ),
        ListTileState(
            id = 2L, name = "Late night", dueCount = 0, type = ListType.SMART, memberCount = 5,
            nextUp = NextUp(2L, "Mara", null, "you haven't spoken yet"),
            rhythm = previewRhythm(8),
        ),
    ),
    hasPermissions = true,
    dueContactCount = 3,
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
