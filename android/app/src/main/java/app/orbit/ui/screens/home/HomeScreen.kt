package app.orbit.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.ListType
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.PostCallBanner
import app.orbit.ui.screens.lists.DeleteListDialog
import app.orbit.ui.theme.LocalReducedMotion
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.dialPhoneNumber
import app.orbit.ui.util.formatDayHeader
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

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
 *     lighter graph wash, both shades of the list's own [OrbitTones] colour.
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
    val context = LocalContext.current

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
                        message = event.message.asString(context),
                        actionLabel = event.actionLabel?.asString(context),
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
        onRetry = vm::onRetry,
        snackbarHostState = snackbarHostState,
        onCallNextUp = { phone -> context.dialPhoneNumber(phone) },
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
        // HOME-8 — a rhythm-day sheet row taps through to the person. No note
        // focus: this is "who was that", not a post-call prompt.
        onOpenContact = { contactId -> onOpenContactWithFocus(contactId.toString(), false) },
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
    onRetry: () -> Unit = {},
    onCallNextUp: (phone: String) -> Unit = {},
    // Long-press quick-actions — Long listId so the renderer can bind per tile.
    onAddPeople: (Long) -> Unit = {},
    onToggleMute: (listId: Long, currentlyEnabled: Boolean) -> Unit = { _, _ -> },
    onListSettings: (Long) -> Unit = {},
    onArchive: (Long) -> Unit = {},
    onDeleteConfirmed: (Long) -> Unit = {},
    onOpenContact: (contactId: Long) -> Unit = {},
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
        HomeUiState.Loading, HomeUiState.Empty, HomeUiState.Error -> emptyList()
        is HomeUiState.Ready -> state.lists
    }
    val isEmpty = state is HomeUiState.Empty
    val isLoading = state is HomeUiState.Loading
    val isError = state is HomeUiState.Error
    val reducedMotion = LocalReducedMotion.current

    Box(modifier = Modifier.fillMaxSize()) {
      OrbitScreen {
        OrbitAppBar(
            title = stringResource(R.string.app_name),
            trailing = {
                Row {
                    OrbitIconButton(
                        icon = "magnifying-glass",
                        onClick = onOpenSearch,
                        contentDescription = stringResource(R.string.home_action_search),
                    )
                    // A bulleted list, as in the design: the plain hamburger
                    // also meant "list options" on Card view (rubric D2).
                    OrbitIconButton(
                        icon = "list-bullets",
                        onClick = onOpenLists,
                        contentDescription = stringResource(R.string.home_action_lists),
                    )
                    OrbitIconButton(
                        icon = "gear",
                        onClick = onOpenSettings,
                        contentDescription = stringResource(R.string.home_action_settings),
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
        if (!isLoading && !isEmpty && !isError) {
            val datePattern = stringResource(R.string.home_date_pattern)
            val dateLabel = remember(datePattern) {
                LocalDate.now().format(DateTimeFormatter.ofPattern(datePattern, Locale.getDefault()))
            }
            Column(Modifier.padding(horizontal = OrbitTheme.spacing.x5)) {
                Text(
                    text = stringResource(R.string.home_date_eyebrow),
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
        if (isError) {
            // HOME-10: say what happened and offer Try again.
            OrbitScreenMessage(
                icon = "warning-circle",
                title = stringResource(R.string.home_error_title),
                body = stringResource(R.string.components_error_body),
                actionLabel = stringResource(R.string.components_error_retry),
                onAction = onRetry,
            )
        } else if (isEmpty) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = OrbitTheme.spacing.x6),
            ) {
                Text(
                    text = stringResource(R.string.home_empty_body),
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x5))
                OrbitButton(text = stringResource(R.string.home_empty_cta), onClick = onCreateList)
            }
        } else {
            // HOME-5 — single column of full-width tonal cards.
            // 12dp side margins below 380dp (16dp otherwise), so seven
            // rhythm days get 48dp each on a 360dp phone.
            val sideMargin = if (LocalConfiguration.current.screenWidthDp < 380) OrbitTheme.spacing.x3 else OrbitTheme.spacing.x4
            LazyColumn(
                contentPadding = PaddingValues(
                    start = sideMargin,
                    end = sideMargin,
                    top = OrbitTheme.spacing.x3,
                    bottom = OrbitTheme.spacing.x6,
                ),
                verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Loading contributes no items — the list stays a quiet surface
                // until the database answers (never the first-install CTA).
                if (!isLoading) {
                    itemsIndexed(tiles, key = { _, tile -> tile.id }) { index, tile ->
                        // Cards slide and fade when a list is archived, deleted,
                        // restored or reordered, instead of popping (rubric D5);
                        // still when the system's animations are off.
                        Box(if (reducedMotion) Modifier else Modifier.animateItem()) {
                        ListTile(
                            tile = tile,
                            toneIndex = index,
                            menuOpen = menuAnchorListId == tile.id,
                            onClick = { onOpenList(tile.id.toString()) },
                            onLongPress = { menuAnchorListId = tile.id },
                            onDismissMenu = { menuAnchorListId = null },
                            onAddPeople = { onAddPeople(tile.id) },
                            onToggleMute = { onToggleMute(tile.id, tile.notificationsEnabled) },
                            onListSettings = { onListSettings(tile.id) },
                            onArchive = { onArchive(tile.id) },
                            onDelete = { pendingDeleteId = tile.id },
                            onOpenContact = onOpenContact,
                            onCallNextUp = onCallNextUp,
                        )
                        }
                    }
                    item { CreateListTile(label = stringResource(R.string.home_new_list), onClick = onCreateList) }
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
    toneIndex: Int,
    onClick: () -> Unit,
    menuOpen: Boolean = false,
    onLongPress: () -> Unit = {},
    onDismissMenu: () -> Unit = {},
    onAddPeople: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onListSettings: () -> Unit = {},
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
    onOpenContact: (contactId: Long) -> Unit = {},
    onCallNextUp: (phone: String) -> Unit = {},
) {
    val curtain = LocalPrivacyCurtain.current
    val isDark = OrbitTheme.colors.isDark
    // Alternate A/B by row position (not list id) so adjacent cards separate.
    val tone = OrbitTheme.tones.listTone(toneIndex.toLong())
    // List names stay masked under the curtain (user-authored, relationship-
    // revealing); the neutral noun for a list is "List".
    val displayName = if (curtain) stringResource(R.string.components_curtain_list) else tile.name
    val haptic = LocalHapticFeedback.current
    // HOME-8 — which rhythm day the user tapped open (index into tile.rhythm,
    // 0 = six days ago). rememberSaveable so a rotation mid-sheet doesn't drop
    // it. Held per tile: each card's strip opens its own day.
    var openDayIndex by rememberSaveable(tile.id) { mutableStateOf<Int?>(null) }

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
                onClickLabel = stringResource(R.string.home_tile_open_list),
                onLongClickLabel = stringResource(R.string.home_tile_quick_actions),
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            ),
    ) {
        // Menu order + destructive tinting follow the shared contract in
        // [OrbitDropdownMenu]: everyday actions first, archive/delete last in
        // danger. Archive used to render in plain fg here — it is reversible,
        // but it still takes the list off home, so it reads as destructive.
        OrbitDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            actions = listOf(
                OrbitMenuAction(label = stringResource(R.string.home_menu_add_people), onClick = onAddPeople),
                OrbitMenuAction(label = stringResource(R.string.home_menu_list_settings), onClick = onListSettings),
                OrbitMenuAction(
                    // Glossary (voice.md): these notifications are "nudges".
                    label = stringResource(
                        if (tile.notificationsEnabled) R.string.home_menu_pause_nudges else R.string.home_menu_resume_nudges,
                    ),
                    onClick = onToggleMute,
                ),
                OrbitMenuAction(
                    label = stringResource(R.string.components_action_archive),
                    onClick = onArchive,
                    tone = OrbitMenuTone.Destructive,
                ),
                OrbitMenuAction(
                    label = stringResource(R.string.components_action_delete),
                    onClick = onDelete,
                    tone = OrbitMenuTone.Destructive,
                ),
            ),
        )

        Column(Modifier.fillMaxWidth()) {
            // Zone 1: tinted header band, list name beside Next up. At large
            // font scales the two stack instead: side by side, the name sat in
            // a fixed 118dp column and clipped at 200% (rubric gate G3).
            val largeText = LocalDensity.current.fontScale > 1.3f
            val nameBlock: @Composable (Modifier) -> Unit = { blockModifier ->
                Column(blockModifier) {
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
                            Spacer(Modifier.width(OrbitTheme.spacing.x1))
                            PhIcon(name = "shuffle-angular", size = 13.dp, tint = tone.nameFg)
                        }
                    }
                    // Full strength, not faded: a 72% alpha member count fell
                    // under 4.5:1 on the tinted band.
                    Text(
                        text = memberLabel(tile.memberCount),
                        style = OrbitTheme.type.meta.copy(color = tone.nameFg),
                        modifier = Modifier.padding(top = OrbitTheme.spacing.x1),
                    )
                }
            }
            val nextUpRow: @Composable (Modifier) -> Unit = { rowModifier ->
                NextUpRow(
                    nextUp = tile.nextUp,
                    curtain = curtain,
                    onCall = onCallNextUp,
                    modifier = rowModifier,
                )
            }
            // Also stacked on a narrow card: on a 360dp phone, side by side
            // left "Next up" about 54dp for its text, so "3 weeks since you
            // last spoke" truncated to "3 week..." (found rendering at w360dp).
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = largeText || maxWidth < NARROW_CARD
                if (stacked) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tone.band)
                            .padding(OrbitTheme.spacing.x4),
                        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                    ) {
                        nameBlock(Modifier.fillMaxWidth())
                        nextUpRow(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tone.band)
                            .padding(OrbitTheme.spacing.x4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        nameBlock(Modifier.width(118.dp))
                        Spacer(Modifier.width(OrbitTheme.spacing.x3))
                        nextUpRow(Modifier.weight(1f))
                    }
                }
            }
            // Zone 2 — lighter wash under the 7-day rhythm.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tone.wash)
                    .padding(
                        top = OrbitTheme.spacing.x3,
                        bottom = OrbitTheme.spacing.x4,
                    ),
            ) {
                // The day columns span the card's full width (the header row
                // keeps its inset), so each day is a wider target: 42dp per
                // day on a 360dp phone was under the 48dp floor.
                RhythmStrip(
                    rhythm = tile.rhythm,
                    onDayClick = { index -> openDayIndex = index },
                    headerPadding = OrbitTheme.spacing.x4,
                )
            }
        }
    }

    // HOME-8 — the tapped day's calls. Sits outside the card's Box so the
    // sheet's scrim is not clipped by the card shape.
    openDayIndex?.let { index ->
        val day = tile.rhythm.getOrNull(index)
        if (day == null || day.calls.isEmpty()) {
            // The rhythm re-emitted while the sheet was open (a call landed, a
            // member left) and this day no longer has anything to show. Close
            // it — in an effect, never as a write during composition.
            LaunchedEffect(index) { openDayIndex = null }
        } else {
            RhythmDaySheet(
                dayLabel = rhythmDayLabel(index, tile.rhythm.size),
                calls = day.calls,
                curtain = curtain,
                onOpenContact = { contactId ->
                    openDayIndex = null
                    onOpenContact(contactId)
                },
                onDismiss = { openDayIndex = null },
            )
        }
    }
}

/**
 * "Today" / "Yesterday" / "Wednesday 3 June" for rhythm index [index], where
 * the last index is today. Derived from the same trailing-7-day window the
 * strip's weekday letters use, so the sheet title and the tapped column can
 * never disagree about which day they mean.
 */
@Composable
private fun rhythmDayLabel(index: Int, size: Int): String = remember(index, size) {
    val today = LocalDate.now()
    formatDayHeader(today.minusDays((size - 1 - index).toLong()), today)
}

/** HOME-3 — the recommendation half of the header band. */
@Composable
private fun NextUpRow(
    nextUp: NextUp?,
    curtain: Boolean,
    onCall: (phone: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (nextUp == null) {
            // Rare: list has members but nobody surfaceable right now.
            // HOME-6: calm, never "caught up" or "no one due".
            Text(
                text = stringResource(R.string.home_next_up_quiet),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.weight(1f),
            )
            return@Row
        }
        // Contact names mask under the curtain (PRIV-03). The avatar keeps the
        // full name (initials / photo); the line shows just the first name — the
        // warm "one name" feel (HOME-3).
        val someone = stringResource(R.string.components_curtain_someone)
        val avatarName = if (curtain) someone else nextUp.name
        val firstName = if (curtain) someone else nextUp.name.substringBefore(' ').ifBlank { nextUp.name }
        Avatar(name = avatarName, size = 44.dp, photoUri = if (curtain) null else nextUp.photoUri)
        Spacer(Modifier.width(OrbitTheme.spacing.x3))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_next_up_eyebrow),
                style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgSubtle),
            )
            Text(
                text = firstName,
                style = OrbitTheme.type.listTile.copy(color = OrbitTheme.colors.fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = nextUp.why.asString(),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(OrbitTheme.spacing.x2))
        // HOME-9: one deliberate tap from Home to a call. A labelled, muted
        // phone button (rules.md §Design 6 allows a quiet dial per person row);
        // the rest of the card still opens the list's deck. It replaces the
        // chevron, which only repeated "this card is tappable".
        val phone = nextUp.phone
        if (phone != null) {
            OrbitIconButton(
                icon = "phone-call",
                onClick = { onCall(phone) },
                tint = OrbitTheme.colors.fgMuted,
                contentDescription = stringResource(R.string.home_next_up_call, firstName),
            )
        } else {
            PhIcon(name = "caret-right", size = 20.dp, tint = OrbitTheme.colors.fgSubtle)
        }
    }
}


/**
 * HOME-7 — 7-day rhythm strip. Bars are RELATIVE to this list's own busiest day
 * (axis = 125% of it), so short-chat lists and long-call lists each read fully.
 * One bar segment per qualifying call, coloured per person; quiet days show a
 * faint dot. Reflection, not a dashboard: no numbers, no targets.
 *
 * HOME-8 layers two things on top without changing that reading:
 *   - a **direction rim** on each bar (cool violet = you called, cool blue =
 *     they called). Fill stays the person, rim is the direction — two channels,
 *     never confusable, so "how much am I reaching out vs being reached" is
 *     answerable at a glance.
 *   - a **tap target per day**, which opens [RhythmDaySheet] with that day's
 *     calls: who, which way, how long, when.
 */
@Composable
private fun RhythmStrip(
    rhythm: List<RhythmDay>,
    onDayClick: (index: Int) -> Unit,
    headerPadding: Dp = 0.dp,
) {
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = headerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_rhythm_eyebrow),
                style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgSubtle),
                modifier = Modifier.weight(1f),
            )
            // The rim colours are meaningless without a key, and the sheet is
            // one tap too far to serve as the only explanation. Kept to two
            // words so it survives large font scales on a narrow card.
            DirectionLegend()
        }
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
                    onClick = { onDayClick(idx) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Two-swatch key for the direction rims. Swatches mirror the bar mark exactly:
 *  neutral fill, coloured rim — so the legend teaches the encoding, not a
 *  second one. */
@Composable
private fun DirectionLegend() {
    val legendDescription = stringResource(R.string.home_rhythm_legend_a11y)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = legendDescription
        },
    ) {
        LegendSwatch(label = stringResource(R.string.home_rhythm_legend_you), rim = OrbitTheme.colors.directionOutgoing)
        LegendSwatch(label = stringResource(R.string.home_rhythm_legend_them), rim = OrbitTheme.colors.directionIncoming)
    }
}

@Composable
private fun LegendSwatch(label: String, rim: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
    ) {
        Box(
            Modifier
                .size(width = 12.dp, height = 10.dp)
                .clip(RHYTHM_BAR_SHAPE)
                .background(OrbitTheme.colors.fgSubtle.copy(alpha = 0.22f))
                .border(RHYTHM_RIM, rim, RHYTHM_BAR_SHAPE),
        )
        Text(
            text = label,
            style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
        )
    }
}

@Composable
private fun DayColumn(
    day: RhythmDay,
    label: String,
    isToday: Boolean,
    scaleMax: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A quiet day has nothing to open, so it stays inert rather than presenting
    // a tap target that leads to an empty sheet.
    val tappable = day.calls.isNotEmpty()
    // Resolved in composition: the semantics block below is not composable.
    val a11yLabel = dayA11yLabel(label, day.calls)
    val seeDayLabel = stringResource(R.string.home_rhythm_see_day)
    Column(
        modifier = modifier
            .clip(OrbitTheme.shapes.sm)
            .then(
                if (tappable) {
                    Modifier
                        .clickable(onClickLabel = seeDayLabel, onClick = onClick)
                        // mergeDescendants so the column announces as one target
                        // ("Monday, 2 calls…") instead of the bare weekday letter
                        // the child Text would otherwise contribute.
                        .semantics(mergeDescendants = true) {
                            contentDescription = a11yLabel
                        }
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
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
                // Per-bar floor. The rim wants 10dp (a 6dp bar minus a 2dp rim
                // top and bottom leaves a 2dp sliver of person-colour, and the
                // fill stops reading) — but a busy day has to stay inside the
                // 48dp strip, so the floor yields to an even split of whatever
                // height the gaps leave over.
                val n = day.calls.size
                val budget = RHYTHM_BAR_AREA.value - RHYTHM_BAR_GAP.value * (n - 1)
                val minBar = (budget / n).coerceIn(4f, RHYTHM_BAR_MIN.value)
                Column(verticalArrangement = Arrangement.spacedBy(RHYTHM_BAR_GAP)) {
                    day.calls.forEach { call ->
                        val frac = (call.durationSeconds / scaleMax).coerceIn(0f, 1f)
                        val h = (frac * RHYTHM_BAR_AREA.value).coerceAtLeast(minBar).dp
                        Box(
                            Modifier
                                .height(h)
                                .width(RHYTHM_BAR_WIDTH)
                                .clip(RHYTHM_BAR_SHAPE)
                                .background(OrbitTheme.tones.rhythmBarForId(call.contactId))
                                .border(RHYTHM_RIM, directionColor(call.direction), RHYTHM_BAR_SHAPE),
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

/**
 * Screen-reader label for a day column. The rim colours carry the direction
 * split visually; this is the same information in words, since a colour rim is
 * invisible to TalkBack.
 */
@Composable
private fun dayA11yLabel(dayLabel: String, calls: List<RhythmCall>): String =
    stringResource(
        R.string.home_rhythm_day_a11y,
        dayLabel,
        pluralStringResource(R.plurals.home_rhythm_day_calls, calls.size, calls.size),
        directionSummary(calls).asString(),
    )

@Composable
private fun memberLabel(count: Int?): String = when (count) {
    null -> ""
    0 -> stringResource(R.string.home_member_count_none)
    else -> pluralStringResource(R.plurals.home_member_count, count, count)
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
            text = stringResource(R.string.home_reflection),
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CreateListTile(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2, Alignment.CenterHorizontally),
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

// HOME-8 — the direction rim. 2dp is the smallest width that still holds a
// legible hue at this bar size; the shape is shared with the legend swatch so
// the key and the mark are literally the same object.
private val RHYTHM_BAR_SHAPE = RoundedCornerShape(6.dp)
private val RHYTHM_RIM: Dp = 2.dp
private val RHYTHM_BAR_GAP: Dp = 3.dp
private val RHYTHM_BAR_MIN: Dp = 10.dp

// ---- Previews ----

private fun previewCall(
    id: Long,
    contactId: Long,
    minutes: Int,
    direction: CallDirection,
): RhythmCall = RhythmCall(
    callEventId = id,
    contactId = contactId,
    contactName = when (contactId) {
        1L -> "Kai Mensah"
        2L -> "Mara Ellis"
        else -> "Sam Okafor"
    },
    photoUri = null,
    durationSeconds = minutes * 60,
    direction = direction,
    durationLabel = "$minutes min",
    timeLabel = "4:30pm",
)

private fun previewRhythm(seed: Int): List<RhythmDay> = listOf(
    RhythmDay(listOf(previewCall(1L, 1L, 14, CallDirection.OUTGOING))),
    RhythmDay(emptyList()),
    RhythmDay(
        listOf(
            previewCall(2L, 2L, 26, CallDirection.INCOMING),
            previewCall(3L, 1L, 6, CallDirection.OUTGOING),
        ),
    ),
    RhythmDay(emptyList()),
    RhythmDay(listOf(previewCall(4L, 3L, 41 - seed, CallDirection.INCOMING))),
    RhythmDay(listOf(previewCall(5L, 2L, 9, CallDirection.OUTGOING))),
    RhythmDay(listOf(previewCall(6L, 1L, 4, CallDirection.OUTGOING))),
)

private val previewState: HomeUiState = HomeUiState.Ready(
    lists = listOf(
        ListTileState(
            id = 1L, name = "Inner orbit", dueCount = 3, type = ListType.STATIC, memberCount = 12,
            nextUp = NextUp(1L, "Kai", null, UiText.res(R.string.home_why_span, "3 weeks"), phone = "+1 555 0100"),
            rhythm = previewRhythm(0),
        ),
        ListTileState(
            id = 2L, name = "Late night", dueCount = 0, type = ListType.SMART, memberCount = 5,
            nextUp = NextUp(2L, "Mara", null, UiText.res(R.string.home_why_never), phone = "+1 555 0101"),
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

// Gate G3: a 40-character person and list name, at 100% and 200% text.
@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun HomeContentLongNamesPreview() {
    OrbitTheme {
        HomeContent(
            state = HomeUiState.Ready(
                lists = listOf(
                    ListTileState(
                        id = 1L, name = "Old friends from the climbing gym crew", dueCount = 12, type = ListType.STATIC, memberCount = 48,
                        nextUp = NextUp(
                            1L, "Bartholomew Montgomery-Featherstonehaugh", null,
                            UiText.res(R.string.home_why_span, "3 weeks"), phone = "+1 555 0100",
                        ),
                        rhythm = previewRhythm(0),
                    ),
                ),
                hasPermissions = true,
                dueContactCount = 12,
            ),
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

@PreviewLightDark
@Composable
private fun HomeContentErrorPreview() {
    OrbitTheme {
        HomeContent(
            state = HomeUiState.Error,
            onOpenList = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenLists = {},
            onCreateList = {},
        )
    }
}

/** Below this card width the list header stacks name over Next up. */
private val NARROW_CARD = 360.dp
