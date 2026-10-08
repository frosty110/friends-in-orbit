package app.orbit.ui.screens.home

import android.content.res.Resources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
import app.orbit.ui.components.NoteWaiting
import app.orbit.ui.components.NotesWaitingStack
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.components.PhIcon
import app.orbit.ui.screens.lists.DeleteListDialog
import app.orbit.ui.theme.LocalReducedMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.dialPhoneNumber
import app.orbit.ui.util.formatDayHeader
import app.orbit.ui.util.formatDuration
import app.orbit.ui.util.formatAgo
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Home screen: the always-on recommender (vision/00-home).
 *
 * **Two-layer pattern**: Hilt-wired outer + stateless inner. The outer collects
 * ViewModel state, then forwards everything to the stateless [HomeContent].
 *
 * **Redesign (HOME-3/5/6/7/9):**
 *   - HOME-6: no "due / N ready / caught up" framing. The header is a calm date,
 *     never a count or a completion state.
 *   - HOME-5: full-width, single-column tonal cards — a tinted header band over a
 *     lighter graph wash, both shades of the list's own [OrbitTones] colour.
 *   - HOME-3: each card shows "Next up" — the head of that list's queue (reused
 *     from `SurfaceNextUseCase` via `HomeFeed.enrichment`) — with a warm recency
 *     line. The card taps through to Card View; the row's one other target is
 *     HOME-9's quiet, labelled call button ("Call Kai"), which opens the dialer.
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
    // HOME-14: "Add a note" on a call waiting for one opens the note page (NOTE-04).
    onOpenPostCallNote: (contactId: String, callEventId: Long) -> Unit = { _, _ -> },
    // HOME-13: the strip's "See your week" and the day sheet's "See the whole
    // week" open the list's Week screen.
    onOpenWeek: (listId: String) -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
    appVm: AppViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val notesWaiting by appVm.notesWaiting.collectAsStateWithLifecycle()
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

    // HOME-14: what a dismissal says, on the same host. The newest message
    // replaces whatever is showing, as above; the Undo's ids ride the event.
    LaunchedEffect(lifecycleOwner, snackbarHostState) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            appVm.notesWaitingEvents.collectLatest { event ->
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = event.message.asString(context),
                    actionLabel = if (event.undoCallEventIds.isEmpty()) {
                        null
                    } else {
                        context.getString(R.string.components_action_undo)
                    },
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    appVm.undoDismissNotesWaiting(event.undoCallEventIds)
                }
            }
        }
    }

    // HOME-12: the day Home is showing, derived once per resume. Home is the
    // root destination and stays composed across the dialer round-trip and
    // across midnight; a `LocalDate.now()` read once at composition left the
    // header on yesterday's date and the strip's letters a day behind the
    // feed's buckets. One value, one writer (rules.md Code 7), passed down to
    // the header, the strip and the day sheet so they can never disagree.
    var today by remember { mutableStateOf(LocalDate.now()) }

    // `LifecycleResumeEffect` re-fires on every resume, including the return
    // from the dialer (Home stays composed across the call, so a
    // `LaunchedEffect(Unit)` would miss it). It refreshes `today` and tells
    // the feed (HOME-12), so the letters and the buckets move to the new day
    // together, and moves the window of calls waiting for a note to now
    // (HOME-14), so a call more than a day old leaves the stack.
    LifecycleResumeEffect(key1 = Unit, lifecycleOwner = lifecycleOwner) {
        today = LocalDate.now()
        vm.onResumed()
        appVm.onHomeResumed()
        onPauseOrDispose { /* the stack's state lives in the VM; nothing to clean up */ }
    }

    HomeContent(
        state = state,
        today = today,
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
        onOpenWeek = { listId -> onOpenWeek(listId.toString()) },
        // HOME-14: the calls waiting for a note. "Add a note" opens the
        // note page and leaves the call waiting until a note is saved
        // (NOTE-05); only Dismiss closes it.
        notesWaiting = notesWaiting,
        onAddNoteForCall = { call -> onOpenPostCallNote(call.contactId.toString(), call.callEventId) },
        onDismissWaiting = { callEventIds -> appVm.dismissNotesWaiting(callEventIds) },
    )
}

/**
 * Stateless Home. `internal` so HomeContentTest can compose it with fixtures
 * and a fixed [today]; previews and the screen pass their own.
 */
@Composable
internal fun HomeContent(
    state: HomeUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    // HOME-12: the date the header, the strip's letters and the day sheet
    // mean by "today". The screen refreshes it on resume; a preview or a test
    // fixes it.
    today: LocalDate = remember { LocalDate.now() },
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
    // HOME-13: a card's Week screen, from its strip or its day sheet.
    onOpenWeek: (listId: Long) -> Unit = {},
    // HOME-14: the calls waiting for a note, newest first, and what their
    // buttons do. Dismiss passes one id; "Dismiss all" passes every one shown.
    notesWaiting: List<NoteWaiting> = emptyList(),
    onAddNoteForCall: (NoteWaiting) -> Unit = {},
    onDismissWaiting: (callEventIds: List<Long>) -> Unit = {},
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
    // HOME-14: whether the pile of waiting calls is open. One owner, here,
    // above the stack's own branches (rules.md Code 7), so it survives the
    // pile shrinking to one card and growing again.
    var notesWaitingOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // The stack is the list's first item. An item inserted above the first
    // visible one leaves the list where it was, so a stack that arrives after
    // the cards would sit above the top of the screen; bring it into view
    // when the user was at the top anyway.
    val hasWaiting = notesWaiting.isNotEmpty()
    LaunchedEffect(hasWaiting) {
        if (hasWaiting && listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset == 0) {
            if (reducedMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
        }
    }

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

        // HOME-6 — calm date orientation only. No count, no "caught up": Home is
        // an always-on recommender, not an inbox. Header shows only in Ready;
        // Loading is quiet chrome, Empty carries the first-install CTA.
        if (!isLoading && !isEmpty && !isError) {
            val datePattern = stringResource(R.string.home_date_pattern)
            // Keyed on `today` (HOME-12): keyed on the pattern alone, the
            // header still said yesterday after a midnight in the background.
            val dateLabel = remember(datePattern, today) {
                today.format(DateTimeFormatter.ofPattern(datePattern, Locale.getDefault()))
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

        // HOME-11: the genuine first-install state gets a primary-weight CTA,
        // centered, with one warm line above it. Opens New list (LIST-28),
        // whose Create returns here. Only when no list exists:
        // Loading never renders it (ADR 0006), so it cannot flash at a user
        // who has lists.
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
                state = listState,
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
                    // HOME-14: the calls waiting for a note lead the list, so
                    // they are the first thing seen on return from a call and
                    // scroll with the cards however tall an open pile grows.
                    // They fade in and out like the cards (and not at all with
                    // the system's animations off).
                    if (hasWaiting) {
                        item(key = NOTES_WAITING_KEY) {
                            Box(if (reducedMotion) Modifier else Modifier.animateItem()) {
                                NotesWaitingStack(
                                    calls = notesWaiting,
                                    open = notesWaitingOpen,
                                    onOpenChange = { notesWaitingOpen = it },
                                    onAddNote = onAddNoteForCall,
                                    onDismiss = { call -> onDismissWaiting(listOf(call.callEventId)) },
                                    onDismissAll = { onDismissWaiting(notesWaiting.map { it.callEventId }) },
                                )
                            }
                        }
                    }
                    itemsIndexed(tiles, key = { _, tile -> tile.id }) { index, tile ->
                        // Cards slide and fade when a list is archived, deleted,
                        // restored or reordered, instead of popping (rubric D5);
                        // still when the system's animations are off.
                        Box(if (reducedMotion) Modifier else Modifier.animateItem()) {
                        ListTile(
                            tile = tile,
                            toneIndex = index,
                            today = today,
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
                            onOpenWeek = { onOpenWeek(tile.id) },
                        )
                        }
                    }
                    item { CreateListTile(label = stringResource(R.string.home_new_list), onClick = onCreateList) }
                    item { ReflectionFooter() }
                }
            }
        }
      }

      OrbitSnackbarHost(
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
    today: LocalDate,
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
    onOpenWeek: () -> Unit = {},
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
            // Tap routes to Card View; long-press opens the manage-this-list
            // quick-actions menu. The card carries two smaller targets of its
            // own, HOME-9's call button and HOME-8's day columns, each with
            // its own label.
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
        // Built by [homeTileMenuActions] so HomeTileMenuTest pins the order.
        OrbitDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            actions = homeTileMenuActions(
                resources = LocalContext.current.resources,
                listName = displayName,
                notificationsEnabled = tile.notificationsEnabled,
                type = tile.type,
                onAddPeople = onAddPeople,
                onListSettings = onListSettings,
                onToggleNudges = onToggleMute,
                onArchive = onArchive,
                onDelete = onDelete,
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
                        // stays visible under the privacy curtain. PhIcon is
                        // decorative by design, so the meaning is said here:
                        // Lists Manager exposes the same fact as a "Smart list"
                        // chip, and TalkBack had no way to tell the two list
                        // kinds apart on Home (WCAG 1.1.1).
                        if (tile.type == ListType.SMART) {
                            val smartLabel = stringResource(R.string.lists_row_smart_chip)
                            Spacer(Modifier.width(OrbitTheme.spacing.x1))
                            PhIcon(
                                name = "shuffle-angular",
                                size = 13.dp,
                                tint = tone.nameFg,
                                modifier = Modifier.semantics { contentDescription = smartLabel },
                            )
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
                        nameBlock(Modifier.width(NAME_COLUMN_WIDTH))
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
                    today = today,
                    onDayClick = { index -> openDayIndex = index },
                    onSeeWeek = onOpenWeek,
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
                dayLabel = rhythmDayLabel(index, tile.rhythm.size, today),
                calls = day.calls,
                curtain = curtain,
                onOpenContact = { contactId ->
                    openDayIndex = null
                    onOpenContact(contactId)
                },
                onDismiss = { openDayIndex = null },
                // HOME-13: every day the strip shows is in this week, so the
                // Week screen opens on it.
                onSeeWeek = {
                    openDayIndex = null
                    onOpenWeek()
                },
            )
        }
    }
}

/**
 * "Today" / "Yesterday" / "Wednesday 3 June" for rhythm index [index], where
 * the last index is [today]. Derived from the same trailing-7-day window the
 * strip's weekday letters and spoken labels use, so the sheet title, the
 * tapped column and what TalkBack said about it can never disagree about
 * which day they mean.
 */
@Composable
private fun rhythmDayLabel(index: Int, size: Int, today: LocalDate): String = remember(index, size, today) {
    formatDayHeader(today.minusDays((size - 1 - index).toLong()), today)
}.asString()

/**
 * The card's long-press menu, as data so the ordering contract is
 * unit-testable (`HomeTileMenuTest`), the shape `listRowMenuActions` and
 * `browseRowMenuActions` share. Labels come from [resources] because
 * [OrbitMenuAction] carries resolved text. [listName] is the name as shown,
 * so under the privacy curtain it is already "List" (PRIV-03).
 *
 * "Add people" is left out (not disabled) for a smart list: its members are
 * what its rule matches (features/orbit-lists), and anyone added by hand was
 * removed by the next reconcile with no message, the silent fallback
 * rules.md Code 3 forbids. Lists Manager hides its "+" for the same reason
 * (`ListRow.kt`), so the two surfaces offer the same actions.
 *
 * Archive carries the same supporting line as the Lists row ("Hides {list}
 * from home. You can restore it."): the same action read as a warned,
 * explained step there and a bare word here.
 */
internal fun homeTileMenuActions(
    resources: Resources,
    listName: String,
    notificationsEnabled: Boolean,
    type: ListType,
    onAddPeople: () -> Unit,
    onListSettings: () -> Unit,
    onToggleNudges: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
): List<OrbitMenuAction> = buildList {
    if (type != ListType.SMART) {
        add(OrbitMenuAction(label = resources.getString(R.string.home_menu_add_people), onClick = onAddPeople))
    }
    add(OrbitMenuAction(label = resources.getString(R.string.home_menu_list_settings), onClick = onListSettings))
    add(
        OrbitMenuAction(
            // Glossary (voice.md): these notifications are "nudges", and a
            // list's are paused and resumed; the list itself is not paused.
            label = resources.getString(
                if (notificationsEnabled) R.string.home_menu_pause_nudges else R.string.home_menu_resume_nudges,
            ),
            onClick = onToggleNudges,
        ),
    )
    add(
        OrbitMenuAction(
            label = resources.getString(R.string.components_action_archive),
            onClick = onArchive,
            tone = OrbitMenuTone.Destructive,
            supporting = resources.getString(R.string.components_menu_archive_supporting, listName),
        ),
    )
    add(
        OrbitMenuAction(
            label = resources.getString(R.string.components_action_delete),
            onClick = onDelete,
            tone = OrbitMenuTone.Destructive,
        ),
    )
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
 *   - a **direction rim** on each bar (pink = you called, teal = they
 *     called), cut off from the fill by a near-black ring ([directionMark]).
 *     Fill stays the person, rim is the direction: two channels, never
 *     confusable, so "how much am I reaching out vs being reached" is
 *     answerable at a glance.
 *   - a **tap target per day**, which opens [RhythmDaySheet] with that day's
 *     calls: who, which way, how long, when.
 *
 * HOME-13 makes the header line the way into the Week screen: "See your
 * week" with a chevron, the legend still beside it ([onSeeWeek]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RhythmStrip(
    rhythm: List<RhythmDay>,
    today: LocalDate,
    onDayClick: (index: Int) -> Unit,
    onSeeWeek: () -> Unit,
    headerPadding: Dp = 0.dp,
) {
    // The drawn glyph is the one-letter weekday ("S M T W T F S"). Keyed on
    // `today` (HOME-12): remembered once, the letters sat a day behind the
    // feed's buckets after a midnight in the background.
    val glyphs = remember(today) {
        (0..6).map { offset ->
            today.minusDays((6 - offset).toLong())
                .dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
        }
    }
    // What TalkBack says for the day is a different string from the glyph:
    // "T" is Tuesday or Thursday and "S" either weekend day, so a column that
    // announced its letter said nothing. The spoken day comes from the same
    // formatDayHeader call the day sheet's title uses ("Today", "Yesterday",
    // "Wednesday 3 June"), so the column and the sheet it opens agree.
    val spokenLabels = (0..6).map { offset ->
        rhythmDayLabel(index = offset, size = 7, today = today)
    }
    val totals = rhythm.map { day -> day.calls.sumOf { it.durationSeconds } }
    val scaleMax = ((totals.maxOrNull() ?: 0).coerceAtLeast(1)) * RHYTHM_HEADROOM

    Column(Modifier.fillMaxWidth()) {
        // A flow, not a row: at large text the legend moves under the button
        // rather than squeezing "See your week" onto two lines.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = headerPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // HOME-13: the line that said "Last 7 days" is the button to the
            // whole week (the owner asked for a bigger chart behind "See this
            // day"). Its own 48dp target, inside the card's: the card's tap
            // still opens the deck everywhere else. The end padding is the
            // least gap to the legend on one line, and nothing once it wraps.
            SeeWeekLink(
                text = stringResource(R.string.home_rhythm_see_week),
                onClick = onSeeWeek,
                modifier = Modifier.padding(end = OrbitTheme.spacing.x2),
            )
            // The rim colours are meaningless without a key, and the sheet is
            // one tap too far to serve as the only explanation. Kept to two
            // words so it survives large font scales on a narrow card.
            DirectionLegend()
        }
        // The button's 48dp already leaves air above the bars.
        Spacer(Modifier.height(OrbitTheme.spacing.x1))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            rhythm.forEachIndexed { idx, day ->
                DayColumn(
                    day = day,
                    glyph = glyphs.getOrElse(idx) { "" },
                    spokenLabel = spokenLabels.getOrElse(idx) { "" },
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
 *  coloured rim, black ring, neutral fill. The legend teaches the encoding,
 *  not a second one. Internal so the Week screen (HOME-13) shows this same
 *  key, not a second drawing of it. */
@Composable
internal fun DirectionLegend() {
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
        // The neutral fill is the old 22% subtle wash, composited onto the
        // surface first: the mark paints its black ring under the content, so
        // a translucent fill would come out near-black.
        Box(
            Modifier
                .size(width = 16.dp, height = RHYTHM_BAR_MIN)
                .directionMark(rim = rim, separator = OrbitTheme.colors.directionSeparator, corner = RHYTHM_BAR_CORNER)
                .background(OrbitTheme.colors.fgSubtle.copy(alpha = 0.22f).compositeOver(OrbitTheme.colors.surface)),
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
    glyph: String,
    spokenLabel: String,
    isToday: Boolean,
    scaleMax: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A quiet day has nothing to open, so it stays inert rather than presenting
    // a tap target that leads to an empty sheet.
    val tappable = day.calls.isNotEmpty()
    // Resolved in composition: the semantics blocks below are not composable.
    val a11yLabel = dayA11yLabel(spokenLabel, day.calls)
    val quietLabel = stringResource(
        R.string.home_rhythm_day_quiet_a11y,
        spokenLabel,
        stringResource(R.string.home_direction_none),
    )
    val seeDayLabel = stringResource(R.string.home_rhythm_see_day)
    Column(
        modifier = modifier
            .clip(OrbitTheme.shapes.sm)
            .then(
                if (tappable) {
                    Modifier
                        .clickable(onClickLabel = seeDayLabel, onClick = onClick)
                        // mergeDescendants so the column announces as one target
                        // ("Monday 5 October, 2 calls…") instead of the bare
                        // weekday letter the child Text would otherwise contribute.
                        .semantics(mergeDescendants = true) {
                            contentDescription = a11yLabel
                        }
                } else {
                    // Still one node per day, so TalkBack hears seven days and
                    // a quiet one is a fact ("Friday 2 October, No calls"), not
                    // a lone letter. mergeDescendants, not clearAndSetSemantics:
                    // only a merging node stays a node of its own under the
                    // card's combinedClickable; a clearing one was folded into
                    // the card's announcement with every other quiet day. The
                    // glyph merges in as text, and a description is what
                    // TalkBack speaks when a node has one.
                    Modifier.semantics(mergeDescendants = true) { contentDescription = quietLabel }
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
                // Per-bar floor. The mark wants 14dp: 3dp of rim and 1.5dp of
                // ring top and bottom leave 5dp of person colour, about the
                // least that still reads as a hue. But a busy day has to stay
                // inside the 48dp strip, so the floor yields to an even split
                // of whatever height the gaps leave over, and below the floor
                // the rim and ring shrink with the bar so the fill never
                // vanishes (chrome).
                val n = day.calls.size
                val budget = RHYTHM_BAR_AREA.value - RHYTHM_BAR_GAP.value * (n - 1)
                val minBar = (budget / n).coerceIn(4f, RHYTHM_BAR_MIN.value)
                Column(verticalArrangement = Arrangement.spacedBy(RHYTHM_BAR_GAP)) {
                    day.calls.forEach { call ->
                        val frac = (call.durationSeconds / scaleMax).coerceIn(0f, 1f)
                        val h = (frac * RHYTHM_BAR_AREA.value).coerceAtLeast(minBar).dp
                        val chrome = (h / RHYTHM_BAR_MIN).coerceAtMost(1f)
                        Box(
                            Modifier
                                .height(h)
                                .width(RHYTHM_BAR_WIDTH)
                                .directionMark(
                                    rim = directionColor(call.direction),
                                    separator = OrbitTheme.colors.directionSeparator,
                                    corner = RHYTHM_BAR_CORNER,
                                    rimWidth = DIRECTION_RIM * chrome,
                                    separatorWidth = DIRECTION_SEPARATOR * chrome,
                                )
                                .background(OrbitTheme.tones.rhythmBarForId(call.contactId)),
                        )
                    }
                }
            }
        }
        // Today is set apart by weight and ink, not the accent: in the accent
        // (as the prototype draws it) a Home with N lists spent the screen's
        // one accent N times on letters nobody taps (rules.md Design 5).
        Text(
            text = glyph,
            style = OrbitTheme.type.micro.copy(
                color = if (isToday) OrbitTheme.colors.fg else OrbitTheme.colors.fgSubtle,
                fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

/**
 * Screen-reader label for a day column with calls. The rim colours carry the
 * direction split visually; this is the same information in words, since a
 * colour rim is invisible to TalkBack. [dayLabel] is the spoken day, never
 * the drawn letter.
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
            // A floor, not a fixed height, so the row grows when its text
            // wraps at 200% (rules.md Design 2); the token, not a literal.
            .heightIn(min = OrbitTheme.spacing.tapMin)
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

// The name block's column when it sits beside Next up: the prototype's
// `flex: 0 0 118px` (vision/00-home/prototype). Wide enough for a two-line
// list name at 100%, and the layout stacks instead above 130% text or on a
// card narrower than NARROW_CARD, so it never has to grow.
private val NAME_COLUMN_WIDTH: Dp = 118.dp

// HOME-8: the bar's outer corner, shared with the legend swatch so the key and
// the mark are literally the same object. The rim and ring widths live with
// [directionMark] because the day sheet's avatar rings use them too.
private val RHYTHM_BAR_CORNER: Dp = 6.dp
private val RHYTHM_BAR_GAP: Dp = 3.dp
private val RHYTHM_BAR_MIN: Dp = 14.dp

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
    durationLabel = formatDuration(minutes * 60),
    timeLabel = "4:30pm",
    minuteOfDay = 16 * 60 + 30,
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
            nextUp = NextUp(
                1L, "Kai", null,
                UiText.res(R.string.home_why_ago, formatAgo(21)), phone = "+1 555 0100",
            ),
            rhythm = previewRhythm(0),
        ),
        ListTileState(
            id = 2L, name = "Late night", dueCount = 0, type = ListType.SMART, memberCount = 5,
            nextUp = NextUp(2L, "Mara", null, UiText.res(R.string.home_why_never), phone = "+1 555 0101"),
            rhythm = previewRhythm(8),
        ),
    ),
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
                            UiText.res(R.string.home_why_ago, formatAgo(21)), phone = "+1 555 0100",
                        ),
                        rhythm = previewRhythm(0),
                    ),
                ),
            ),
            onOpenList = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenLists = {},
            onCreateList = {},
        )
    }
}

private fun previewWaiting(id: Long, name: String, direction: CallDirection, minutes: Int, hoursAgo: Int) = NoteWaiting(
    callEventId = id,
    contactId = id,
    name = name,
    photoUri = null,
    direction = direction,
    meta = UiText.res(
        R.string.components_notes_waiting_meta,
        formatDuration(minutes * 60),
        UiText.plural(R.plurals.time_ago_hours, hoursAgo, hoursAgo),
    ),
)

// HOME-14: three calls waiting for a note, as the closed pile over the cards.
@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun HomeContentNotesWaitingPreview() {
    OrbitTheme {
        HomeContent(
            state = previewState,
            onOpenList = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenLists = {},
            onCreateList = {},
            notesWaiting = listOf(
                previewWaiting(11L, "Kai Mensah", CallDirection.OUTGOING, minutes = 14, hoursAgo = 2),
                previewWaiting(12L, "Mara Ellis", CallDirection.INCOMING, minutes = 26, hoursAgo = 5),
                previewWaiting(13L, "Sam Okafor", CallDirection.OUTGOING, minutes = 41, hoursAgo = 20),
            ),
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

/** HOME-14: the stack's key in Home's list; tile keys are list ids (Longs), so it cannot collide. */
private const val NOTES_WAITING_KEY = "notes-waiting"

/** Below this card width the list header stacks name over Next up. */
private val NARROW_CARD = 360.dp
