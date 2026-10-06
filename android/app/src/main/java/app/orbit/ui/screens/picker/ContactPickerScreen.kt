package app.orbit.ui.screens.picker

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.domain.search.ContactSearch
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitListSkeleton
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSearchField
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.openPhoneContact
import java.time.Instant
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Picker screen (PICK-01..08, BULK-01/02).
 *
 * Two-layer composable matching [app.orbit.ui.screens.lists.ListsManagerScreen]:
 *   - Outer [ContactPickerScreen] owns Hilt resolution, permission launcher,
 *     and lifecycle-aware permission refresh.
 *   - Inner [ContactPickerContent] is stateless — all callbacks flow in.
 *
 * State-driven branching:
 *   - LoadingPermission   → [OrbitListSkeleton] (a quiet skeleton, never a blank page)
 *   - PermissionRationale → [RationaleCard]
 *   - PermissionDenied    → [PermissionDeniedEmpty]
 *   - EmptyDevice         → [EmptyDeviceContacts], only via the phase (the
 *                           address-book read came back empty)
 *   - NotFound            → the list or person is gone, with Go back
 *   - Error (PICK-09)     → the shared message with Retry
 *   - Ready / Committing  → [ReadyContent]: search + chips + select-all + list,
 *                           or one honest empty state per
 *                           [ContactPickerUiState.emptyReason]
 *
 * Pitfalls mitigated:
 *   - Search box that eats keystrokes: the typed text is screen-local state
 *     owned by [ContactPickerContent] — ABOVE the phase `when`, so it survives
 *     a phase flip, with exactly one writer (the field and its own clear-X). It is debounced on the way DOWN to the VM and never read back
 *     up. See the long note at its declaration; regression coverage lives in
 *     `androidTest/.../OrbitSearchFieldTest`.
 *   - IME overlap: root Box carries `Modifier.imePadding()`.
 *   - Select-all over unrendered rows: the screen passes
 *     `state.filteredContacts.map { it.contactId }.toSet()` to
 *     `viewModel.onSelectAllMatching(...)` — IDs are domain-derived, not from
 *     LazyColumn nodes.
 *   - LazyColumn `items(...)` carries `key = { it.contactId }` per rules.md.
 *
 * Picker-commit lifecycle — this screen hosts NO snackbar. On commit the picker
 * pops via the caller's `onCommit` lambda, so the commit result ("Added N to
 * X · Undo" / "Couldn't save that") is published on [PickerCommitBus] and shown
 * by the app-level [PickerCommitSnackbarHost] mounted in `OrbitNavHost`, on
 * whatever screen the pop lands on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun ContactPickerScreen(
    onBack: () -> Unit,
    onCommit: () -> Unit,
    // Unused since 2026-10-06 and slated for removal once OrbitNavHost drops
    // the argument: onboarding's first-list step owns Skip and its gating, so
    // the picker draws no "Skip for now" footer. (The one call site always
    // passed null, so the footer never rendered anyway.)
    onSkip: (() -> Unit)? = null,
    vm: ContactPickerViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission launcher — granted boolean callback into the VM.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onPermissionResult(granted) }

    // Lifecycle-aware permission refresh — same pattern as
    // OnboardingPermissionsScreen: re-read on ON_RESUME so flips via
    // Settings → Apps reflect here.
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshPermission()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    ContactPickerContent(
        state = state,
        onBack = onBack,
        onRetry = vm::onRetry,
        onSearchChanged = vm::onSearchChanged,
        onToggleFilter = vm::onToggleFilter,
        onSetSort = vm::setSortBy,
        onToggleSelect = vm::onToggleSelect,
        onSelectAllMatching = vm::onSelectAllMatching,
        onClearSelection = vm::onClearSelection,
        onShowIgnoredToggle = vm::onShowIgnoredToggle,
        onIgnore = { contact -> vm.onIgnore(contact.contactId, contact.displayName) },
        onUnignore = { contact -> vm.onUnignore(contact.contactId, contact.displayName) },
        // "Who is this?" — hand the row off to the phone's own contacts app,
        // which is where the call and message history for that number lives.
        // Orbit never reads message content (no READ_SMS), so the system
        // contact card is the honest answer to an unrecognised number.
        onOpenInPhone = { contact ->
            contact.phoneContactId?.let { context.openPhoneContact(it) }
        },
        onCommit = {
            vm.onCommit()
            onCommit()
        },
        onPermissionGrant = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
        onOpenSettings = {
            val intent = buildOpenAppSettingsIntent(context.packageName)
            context.startActivity(intent)
        }
    )
}

/**
 * App-bar title per [PickerMode]. Move/Copy carry the live selection count with
 * an honest singular ("Move 1 person", never "Move 1 people"), from
 * `<plurals>` (strings_picker.xml). Pure + internal so the unit test can pin
 * it. The app says "people", not "contacts", for the people in Orbit (the
 * title read "Add contacts" until 2026-10-05).
 */
internal fun pickerModeTitle(mode: PickerMode, selectionCount: Int): UiText = when (mode) {
    PickerMode.Add -> UiText.res(R.string.picker_title_add)
    PickerMode.Move -> UiText.plural(R.plurals.picker_title_move, selectionCount, selectionCount)
    PickerMode.Copy -> UiText.plural(R.plurals.picker_title_copy, selectionCount, selectionCount)
    // CONTACT-07: one pick, so no count.
    PickerMode.Relink -> UiText.res(R.string.picker_title_relink)
}

@OptIn(FlowPreview::class)
@Composable
private fun ContactPickerContent(
    state: ContactPickerUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onToggleFilter: (PickerFilter) -> Unit,
    onSetSort: (PickerSort) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSelectAllMatching: (Set<Long>) -> Unit,
    onClearSelection: () -> Unit,
    onShowIgnoredToggle: (Boolean) -> Unit,
    onIgnore: (PickerContact) -> Unit,
    onUnignore: (PickerContact) -> Unit,
    onOpenInPhone: (PickerContact) -> Unit,
    onCommit: () -> Unit,
    onPermissionGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // ── Search box state — ONE source of truth for the typed text.
    //
    // Two invariants, both of which have bitten this screen before:
    //
    //  1. The field must NOT bind to the VM's debounced query. This screen
    //     recomposes constantly off the live Room flows, so a recomposition
    //     mid-debounce re-applies the stale value and reverts the keystroke —
    //     to the user, typing does nothing at all.
    //  2. The state must live ABOVE the `when (state.phase)` branch, not inside
    //     [ReadyContent]. Held inside, it is torn down and re-initialised from
    //     `state.searchQuery` every time the phase leaves the Ready/Committing
    //     branch (a permission re-read on ON_RESUME re-writes the phase on every
    //     foreground), which silently empties the box mid-search. Its clear-X
    //     (inside the field) writes here too; a clear that only wrote to the
    //     VM once left the typed text stranded in a box it could no longer
    //     clear.
    //
    // `snapshotFlow { … }.debounce(…)` in a single `LaunchedEffect(Unit)` is the
    // same shape [app.orbit.ui.screens.browse.GlobalSearchScreen] and
    // [app.orbit.ui.screens.browse.BrowseListScreen] use; the VM does not
    // re-debounce, so the field never round-trips a stale value.
    var searchInput by rememberSaveable { mutableStateOf(state.searchQuery) }
    LaunchedEffect(Unit) {
        snapshotFlow { searchInput }
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .collect { onSearchChanged(it) }
    }

    OrbitScreen {
        // App-bar title varies with mode and singularizes honestly
        // ("Move 1 person").
        val title: String = pickerModeTitle(state.mode, state.selectionCount).asString()
        OrbitAppBar(
            title = title,
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = stringResource(R.string.components_action_back)
                )
            },
            // No clear-X here: the search field carries its own, gated on the
            // field's text. Two "Clear search" controls for one field was one
            // job with two entry points (rubric D2).
        )

        // Keeping the soft keyboard from covering the BatchCounter and the
        // search field is [OrbitScreen]'s job now — it pads by
        // `systemBars.union(ime)`, and `windowInsetsPadding` CONSUMES what it
        // applies, so this `imePadding()` sees a zero IME inset and is a no-op
        // rather than a double pad. Kept as a belt-and-braces guard for the day
        // this surface is hosted somewhere other than OrbitScreen; delete it if
        // that never happens.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            when (state.phase) {
                ContactPickerUiState.Phase.LoadingPermission ->
                    // The first emission waits for the address-book read and
                    // the Room joins; a large address book showed an app bar
                    // over nothing for that long (no system dialog is up yet:
                    // the launcher only fires from the Grant button). The
                    // shared skeleton, never a blank or a false empty page.
                    OrbitListSkeleton()
                ContactPickerUiState.Phase.PermissionRationale ->
                    RationaleCard(onGrant = onPermissionGrant, onDismiss = onBack)
                ContactPickerUiState.Phase.PermissionDenied ->
                    PermissionDeniedEmpty(onOpenSettings = onOpenSettings)
                ContactPickerUiState.Phase.EmptyDevice ->
                    EmptyDeviceContacts()
                ContactPickerUiState.Phase.NotFound ->
                    NotFoundEmpty(mode = state.mode, onBack = onBack)
                ContactPickerUiState.Phase.Error -> OrbitScreenMessage(
                    icon = "warning-circle",
                    title = stringResource(R.string.picker_error_title),
                    // The shared error words (rubric D6): what is true, that
                    // nothing is lost, and Try again.
                    body = stringResource(R.string.components_error_body),
                    actionLabel = stringResource(R.string.components_error_retry),
                    onAction = onRetry,
                    actionVariant = OrbitButtonVariant.Primary
                )
                ContactPickerUiState.Phase.Ready,
                ContactPickerUiState.Phase.Committing -> ReadyContent(
                    state = state,
                    searchInput = searchInput,
                    onSearchInputChange = { searchInput = it },
                    onToggleFilter = onToggleFilter,
                    onSetSort = onSetSort,
                    onToggleSelect = onToggleSelect,
                    onSelectAllMatching = onSelectAllMatching,
                    onClearSelection = onClearSelection,
                    onShowIgnoredToggle = onShowIgnoredToggle,
                    onIgnore = onIgnore,
                    onUnignore = onUnignore,
                    onOpenInPhone = onOpenInPhone,
                    onCommit = onCommit,
                    onBack = onBack
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadyContent(
    state: ContactPickerUiState,
    // The raw, un-debounced search text. Owned by [ContactPickerContent] so it
    // outlives this branch of the phase `when` and is reachable by the app bar's
    // clear-X — see the invariants documented at its declaration.
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    onToggleFilter: (PickerFilter) -> Unit,
    onSetSort: (PickerSort) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSelectAllMatching: (Set<Long>) -> Unit,
    onClearSelection: () -> Unit,
    onShowIgnoredToggle: (Boolean) -> Unit,
    onIgnore: (PickerContact) -> Unit,
    onUnignore: (PickerContact) -> Unit,
    onOpenInPhone: (PickerContact) -> Unit,
    onCommit: () -> Unit,
    // The way out of an empty state that has nothing to add.
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Measured height of the docked BatchCounter, and a one-shot flag set when a
    // selection tap is about to make the bar appear while the list is scrolled
    // to the bottom. In that case the appearing bar steals the bottom of the
    // viewport and would hide the row the user just selected — so we push the
    // list up by the bar's height to keep that row in view.
    var barHeightPx by remember { mutableStateOf(0) }
    var pushUpOnBar by remember { mutableStateOf(false) }

    LaunchedEffect(state.selectionCount > 0, barHeightPx) {
        if (state.selectionCount > 0 && barHeightPx > 0 && pushUpOnBar) {
            listState.animateScrollBy(barHeightPx.toFloat())
            pushUpOnBar = false
        }
    }

    // Re-narrowing the visible set (filters, sort, or search) should reveal the
    // new top matches — a stale scroll offset from the previous set hides them.
    LaunchedEffect(state.activeFilters, state.sortBy, state.searchQuery) {
        listState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OrbitSearchField(
            query = searchInput,
            onQueryChange = onSearchInputChange,
            // The shared matcher also searches phone digits.
            placeholder = stringResource(R.string.picker_search_hint),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x2)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x1
                )
        ) {
            SortControl(
                sortBy = state.sortBy,
                onSetSort = onSetSort
            )
            Spacer(modifier = Modifier.weight(1f))
            // Quiet, reversible entry for the wired showIgnored toggle. Only
            // meaningful once something is actually ignored.
            if (state.ignoredCount > 0 || state.showIgnored) {
                ShowIgnoredControl(
                    showIgnored = state.showIgnored,
                    onToggle = onShowIgnoredToggle
                )
            }
        }

        // A disabled FilterChip can't explain itself. Call-history filters
        // ("long gap", "commonly/rarely called") read count 0 — and stay grey —
        // until a call-log sync lands, which is invisible without a word.
        val hasCallHistory = remember(state.allContacts) {
            state.allContacts.any { it.lastCallAt != null }
        }
        val filterHintNeedsCalls = stringResource(R.string.picker_filters_need_calls)
        val filterHintGreyed = stringResource(R.string.picker_filters_greyed)
        val filterDisabledHint: String? = run {
            val callDependent = listOf(
                PickerFilter.CommonlyCalled,
                PickerFilter.RarelyCalled,
                PickerFilter.LongGap
            )
            val anyGreyed = callDependent.any {
                it !in state.activeFilters && it.countFor(state) == 0
            }
            when {
                !anyGreyed -> null
                !hasCallHistory -> filterHintNeedsCalls
                else -> filterHintGreyed
            }
        }

        FilterChipsRow(
            activeFilters = state.activeFilters,
            onToggle = onToggleFilter,
            countFor = { filter -> filter.countFor(state) },
            availableLists = state.availableLists,
            onSelectInList = { id, _ -> onToggleFilter(PickerFilter.InList(id)) },
            onClearInList = {
                // Remove ALL InList filters from activeFilters (idempotent —
                // toggling an existing filter removes it via the VM's
                // _activeFilters set arithmetic).
                state.activeFilters
                    .filterIsInstance<PickerFilter.InList>()
                    .forEach { onToggleFilter(it) }
            },
            disabledHint = filterDisabledHint,
            modifier = Modifier.padding(vertical = OrbitTheme.spacing.x2)
        )

        // Select-all surfaces whenever search OR filters narrow the list; over
        // the cap it degrades to a quiet note instead.
        if (state.canSelectAllMatching) {
            val matchingIds = state.filteredContacts.map { it.contactId }.toSet()
            SelectAllMatchingChip(
                matchingCount = matchingIds.size,
                onClick = { onSelectAllMatching(matchingIds) },
                modifier = Modifier.padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x1
                )
            )
        } else if (state.selectAllCapExceeded) {
            Text(
                text = pluralStringResource(
                    R.plurals.picker_select_all_capped,
                    ContactPickerUiState.SELECT_ALL_MAX,
                    ContactPickerUiState.SELECT_ALL_MAX,
                ),
                style = OrbitTheme.type.meta,
                color = OrbitTheme.colors.fgMuted,
                modifier = Modifier.padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x1
                )
            )
        }

        // Alphabetical section index. Only meaningful when the rows are in name
        // order and not re-banded by search rank.
        val showSections = state.sortBy == PickerSort.ByName && state.searchQuery.isBlank()
        val sections: List<PickerSection> = remember(state.filteredContacts, showSections) {
            if (showSections) buildPickerSections(state.filteredContacts) else emptyList()
        }

        // Section currently at the top of the viewport → the rail highlights it
        // at rest, so the letter in view is always emphasized as the user scrolls.
        val activeSectionIndex by remember(sections) {
            derivedStateOf {
                if (sections.isEmpty()) {
                    -1
                } else {
                    val first = listState.firstVisibleItemIndex
                    sections.indexOfLast { it.headerItemIndex <= first }.coerceAtLeast(0)
                }
            }
        }

        // Shared row slot for the sectioned and flat branches below.
        val pickerRow: @Composable LazyItemScope.(PickerContact) -> Unit = { contact ->
            PickerContactRow(
                contact = contact,
                isSelected = contact.contactId in state.selectedIds,
                onToggle = { id ->
                    // If this tap is what makes the batch bar appear
                    // (0 → 1 selection) and the list is at the bottom,
                    // arm the push-up so the just-selected bottom row
                    // isn't hidden by the docked bar.
                    if (state.selectionCount == 0 &&
                        id !in state.selectedIds &&
                        !listState.canScrollForward
                    ) {
                        pushUpOnBar = true
                    }
                    onToggleSelect(id)
                },
                onIgnore = onIgnore,
                onUnignore = onUnignore,
                // Only offer "Open in Contacts" for rows we can actually
                // resolve to a device contact — a call-log-only row has no
                // phoneContactId and the intent would dead-end.
                onOpenInPhone = if (contact.phoneContactId != null) onOpenInPhone else null,
                modifier = Modifier.animateItem()
            )
        }

        // List area takes the remaining height; the bottom bar docks below it
        // (not a floating overlay, so the last row stays reachable).
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // "No contacts on this device" belongs to the EmptyDevice phase
            // alone (the address-book read came back empty). Until 2026-10-06
            // an empty candidate set took the same shortcut here, so a user
            // whose whole address book was already on the list was told the
            // phone had no contacts; each reason now has its own true words.
            when (val reason = state.emptyReason) {
                null -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = OrbitTheme.spacing.x2)
                ) {
                    if (sections.isNotEmpty()) {
                        sections.forEachIndexed { sectionIndex, section ->
                            // Key carries the run index — folded letters can
                            // produce a second run of the same letter under
                            // NOCASE collation ("Åke" sorts after "Zoe" but
                            // folds to A), and sticky-header keys must be unique.
                            stickyHeader(key = "header-$sectionIndex-${section.letter}") {
                                SectionHeader(letter = section.letter)
                            }
                            items(
                                items = section.contacts,
                                key = { it.contactId }
                            ) { contact -> this.pickerRow(contact) }
                        }
                    } else {
                        items(
                            items = state.filteredContacts,
                            key = { it.contactId }
                        ) { contact -> this.pickerRow(contact) }
                    }
                }
                else -> PickerEmptyMessage(
                    reason = reason,
                    state = state,
                    onBack = onBack,
                    onShowIgnored = { onShowIgnoredToggle(true) }
                )
            }

            // Fast-scroll rail; only with 2+ sections to jump between. Letters
            // mirror the section headers, top-aligned with the list's letter
            // geography.
            if (sections.size > 1) {
                AlphabetRail(
                    letters = sections.map { it.letter },
                    onLetterSelected = { index ->
                        scope.launch {
                            listState.scrollToItem(sections[index].headerItemIndex)
                        }
                    },
                    activeIndex = activeSectionIndex,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        // Docked bottom bar (PICK-06); BatchCounter self-hides at zero
        // selection. Nothing fills the slot for an empty selection: Back is
        // the way out, and onboarding's first-list step owns its own Skip.
        if (state.selectionCount > 0) {
            Box(modifier = Modifier.onSizeChanged { barHeightPx = it.height }) {
                BatchCounter(
                    selectionCount = state.selectionCount,
                    targetListName = state.targetListName,
                    mode = state.mode,
                    isCommitting = state.phase == ContactPickerUiState.Phase.Committing,
                    onClear = onClearSelection,
                    onCommit = onCommit
                )
            }
        }
    }
}

/**
 * Sort control for the picker: a quiet pill that opens the shared
 * [OrbitDropdownMenu] with the sort modes, the current one ticked. Default is
 * alphabetical.
 *
 * 2026-10-05: the pill was about 36dp tall, under the 48dp floor
 * (rules.md §Design 3), and opened a stock Material menu whose tick was in the
 * accent; it now has the floor, a button role with a spoken purpose, and the
 * Orbit menu with an ink tick (the screen's accent is the commit button).
 */
@Composable
private fun SortControl(
    sortBy: PickerSort,
    onSetSort: (PickerSort) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Ordered list of the user-facing sort options.
    val options: List<Pair<PickerSort, String>> = listOf(
        PickerSort.ByName to stringResource(R.string.picker_sort_alphabetical),
        PickerSort.ByMostCalled to stringResource(R.string.picker_sort_most_called),
        // "Recently called" — absorbs the intent of the removed "Called recently"
        // filter (surface recent callers by ordering, not by hiding others).
        PickerSort.ByRecency to stringResource(R.string.picker_sort_recently_called),
        PickerSort.ByRecentlySaved to stringResource(R.string.picker_sort_recently_added)
    )
    val currentLabel = options.first { it.first == sortBy }.second

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
                .clip(OrbitTheme.shapes.full)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.picker_sort_change)
                ) { expanded = true }
                .padding(
                    horizontal = OrbitTheme.spacing.x3,
                    vertical = OrbitTheme.spacing.x2
                )
        ) {
            PhIcon(
                name = "sliders-horizontal",
                size = 16.dp,
                tint = OrbitTheme.colors.fgMuted
            )
            Spacer(Modifier.width(OrbitTheme.spacing.x2))
            Text(
                text = stringResource(R.string.picker_sort_label, currentLabel),
                style = OrbitTheme.type.meta,
                color = OrbitTheme.colors.fg
            )
            Spacer(Modifier.width(OrbitTheme.spacing.x1))
            PhIcon(
                name = "caret-down",
                size = 12.dp,
                tint = OrbitTheme.colors.fgMuted
            )
        }
        OrbitDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            actions = options.map { (sort, label) ->
                OrbitMenuAction(
                    label = label,
                    onClick = { onSetSort(sort) },
                    selected = sort == sortBy
                )
            }
        )
    }
}

/**
 * Quiet pill toggling [ContactPickerUiState.showIgnored]. Same visual weight as
 * [SortControl] (icon + meta text, no accent): revealing ignored contacts is a
 * maintenance task, not a primary action.
 */
@Composable
private fun ShowIgnoredControl(
    showIgnored: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            // rules.md Design 3 — 48dp tap floor, even for quiet controls.
            .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.full)
            .clickable(role = Role.Button) { onToggle(!showIgnored) }
            .padding(
                horizontal = OrbitTheme.spacing.x3,
                vertical = OrbitTheme.spacing.x2
            )
    ) {
        PhIcon(
            name = if (showIgnored) "eye" else "eye-slash",
            size = 16.dp,
            tint = OrbitTheme.colors.fgMuted
        )
        Spacer(Modifier.width(OrbitTheme.spacing.x2))
        Text(
            text = stringResource(if (showIgnored) R.string.picker_hide_ignored else R.string.picker_show_ignored),
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fg
        )
    }
}

/** Debounce before committing a search keystroke to the VM (see the field). */
private const val SEARCH_DEBOUNCE_MS = 150L

/**
 * One alphabetical section of the filtered list, plus the LazyColumn item index
 * of its sticky header so the [AlphabetRail] can `scrollToItem` straight to it.
 */
@Immutable
private data class PickerSection(
    val letter: String,
    val contacts: List<PickerContact>,
    val headerItemIndex: Int
)

/**
 * Groups consecutive runs of the same (diacritic-folded) first letter.
 * Consecutive-run grouping (not a map) keeps section order identical to row
 * order, whatever the DAO's NOCASE collation did with non-ASCII names.
 * Non-letter initials bucket under "#".
 */
private fun buildPickerSections(contacts: List<PickerContact>): List<PickerSection> {
    if (contacts.isEmpty()) return emptyList()
    val runs = mutableListOf<Pair<String, MutableList<PickerContact>>>()
    for (contact in contacts) {
        val first = ContactSearch.fold(contact.displayName)
            .firstOrNull { !it.isWhitespace() }
        val letter = if (first != null && first in 'a'..'z') {
            first.uppercaseChar().toString()
        } else {
            "#"
        }
        val last = runs.lastOrNull()
        if (last != null && last.first == letter) {
            last.second.add(contact)
        } else {
            runs.add(letter to mutableListOf(contact))
        }
    }
    var itemIndex = 0
    return runs.map { (letter, members) ->
        val section = PickerSection(
            letter = letter,
            contacts = members,
            headerItemIndex = itemIndex
        )
        itemIndex += 1 + members.size
        section
    }
}

/**
 * Sticky alphabetical header: the shared [SectionLabel] (a heading for
 * TalkBack) on an opaque [OrbitTheme.colors.bg] (the OrbitScreen surface) so
 * rows scrolling beneath never bleed through.
 */
@Composable
private fun SectionHeader(letter: String) {
    SectionLabel(
        text = letter,
        modifier = Modifier
            .fillMaxWidth()
            .background(OrbitTheme.colors.bg)
            .padding(
                horizontal = OrbitTheme.spacing.x4,
                vertical = OrbitTheme.spacing.x1
            )
    )
}

/**
 * Terminal state when the route's list or person is not there: the id was
 * malformed, or the row is gone. The shared message with Go back as its one
 * action (rubric G4: every empty state offers the next step; it had none and
 * told the user to "go back" in words). A Re-link route carries a person, so
 * it uses the words Contact detail uses for a missing person (voice.md:
 * people, not contacts; it said "Contact not found" until 2026-10-06).
 */
@Composable
private fun NotFoundEmpty(mode: PickerMode, onBack: () -> Unit) {
    val relink = mode == PickerMode.Relink
    OrbitScreenMessage(
        title = stringResource(if (relink) R.string.picker_person_not_found else R.string.picker_list_not_found),
        body = stringResource(if (relink) R.string.picker_person_not_found_body else R.string.picker_list_not_found_body),
        actionLabel = stringResource(R.string.picker_go_back),
        onAction = onBack,
        // The only thing to do on the screen, so it takes the accent.
        actionVariant = OrbitButtonVariant.Primary
    )
}

/**
 * One honest message per [ContactPickerUiState.EmptyReason], each with its
 * next step (rubric G4). Secondary actions only: the commit button owns the
 * screen's accent, and here nothing is selected.
 */
@Composable
private fun PickerEmptyMessage(
    reason: ContactPickerUiState.EmptyReason,
    state: ContactPickerUiState,
    onBack: () -> Unit,
    onShowIgnored: () -> Unit
) {
    when (reason) {
        ContactPickerUiState.EmptyReason.EveryoneOnList -> {
            // PRIV-03: the list's name reads "List" under the curtain, as it
            // does on the commit bar.
            val listName = if (LocalPrivacyCurtain.current) {
                stringResource(R.string.components_curtain_list)
            } else {
                state.targetListName
            }
            OrbitScreenMessage(
                icon = "users",
                title = stringResource(R.string.picker_everyone_on_list_title, listName),
                actionLabel = stringResource(R.string.picker_go_back),
                onAction = onBack
            )
        }
        ContactPickerUiState.EmptyReason.NoRelinkTargets -> OrbitScreenMessage(
            icon = "users",
            title = stringResource(R.string.picker_relink_none_title),
            actionLabel = stringResource(R.string.picker_go_back),
            onAction = onBack
        )
        ContactPickerUiState.EmptyReason.EveryoneIgnored -> OrbitScreenMessage(
            icon = "eye-slash",
            title = stringResource(R.string.picker_everyone_ignored_title),
            actionLabel = stringResource(R.string.picker_show_ignored),
            onAction = onShowIgnored
        )
        // Plain words: "chip" and "thresholds" were developer vocabulary on a
        // user-facing line (rubric D7).
        ContactPickerUiState.EmptyReason.NoSearchMatches -> OrbitScreenMessage(
            icon = "magnifying-glass",
            title = stringResource(R.string.picker_no_matches_query_title, state.searchQuery),
            body = stringResource(R.string.picker_no_matches_query_body)
        )
        ContactPickerUiState.EmptyReason.NoFilterMatches -> OrbitScreenMessage(
            icon = "magnifying-glass",
            title = stringResource(R.string.picker_no_matches_filters_title),
            body = stringResource(R.string.picker_no_matches_filters_body)
        )
    }
}

// ─── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "ContactPicker — Ready light", showBackground = true)
@Composable
private fun ContactPickerReadyPreviewLight() {
    OrbitTheme(darkTheme = false) {
        ContactPickerContent(
            state = previewReadyState(darkSelectionDemo = false),
            onBack = {},
            onRetry = {},
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onOpenInPhone = {},
            onCommit = {},
            onPermissionGrant = {},
            onOpenSettings = {}
        )
    }
}

@Preview(name = "ContactPicker — Ready dark", showBackground = true)
@Composable
private fun ContactPickerReadyPreviewDark() {
    OrbitTheme(darkTheme = true) {
        ContactPickerContent(
            state = previewReadyState(darkSelectionDemo = true),
            onBack = {},
            onRetry = {},
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onOpenInPhone = {},
            onCommit = {},
            onPermissionGrant = {},
            onOpenSettings = {}
        )
    }
}

@Preview(name = "ContactPicker — Rationale light", showBackground = true)
@Composable
private fun ContactPickerRationalePreviewLight() {
    OrbitTheme(darkTheme = false) {
        ContactPickerContent(
            state = previewReadyState().copy(
                phase = ContactPickerUiState.Phase.PermissionRationale
            ),
            onBack = {},
            onRetry = {},
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onOpenInPhone = {},
            onCommit = {},
            onPermissionGrant = {},
            onOpenSettings = {}
        )
    }
}

@Preview(name = "ContactPicker — Denied light", showBackground = true)
@Composable
private fun ContactPickerDeniedPreviewLight() {
    OrbitTheme(darkTheme = false) {
        ContactPickerContent(
            state = previewReadyState().copy(
                phase = ContactPickerUiState.Phase.PermissionDenied
            ),
            onBack = {},
            onRetry = {},
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onOpenInPhone = {},
            onCommit = {},
            onPermissionGrant = {},
            onOpenSettings = {}
        )
    }
}

private fun previewReadyState(darkSelectionDemo: Boolean = false): ContactPickerUiState {
    val now = Instant.now()
    val sample = listOf(
        PickerContact(
            contactId = 1L,
            displayName = "Sarah Levin",
            phone = "+15555550101",
            photoUri = null,
            isIgnored = false,
            callCount = 4,
            lastCallAt = now.minusSeconds(3 * 24 * 3600),
            firstSeenByAppAt = now.minusSeconds(60L * 24 * 3600),
            listIds = setOf(1L),
            listNames = listOf("Inner orbit"),
            isCommonlyCalled = true,
            isRarelyCalled = false,
            isRecentlyAdded = false,
            isLongGap = false
        ),
        PickerContact(
            contactId = 2L,
            displayName = "Marcus Reid",
            phone = "+15555550102",
            photoUri = null,
            isIgnored = false,
            callCount = 0,
            lastCallAt = null,
            firstSeenByAppAt = now.minusSeconds(7L * 24 * 3600),
            listIds = emptySet(),
            listNames = emptyList(),
            isCommonlyCalled = false,
            isRarelyCalled = false,
            isRecentlyAdded = true,
            isLongGap = false
        ),
        PickerContact(
            contactId = 3L,
            displayName = "Priya Anand",
            phone = "+15555550103",
            photoUri = null,
            isIgnored = false,
            callCount = 1,
            lastCallAt = now.minusSeconds(120L * 24 * 3600),
            firstSeenByAppAt = now.minusSeconds(200L * 24 * 3600),
            listIds = emptySet(),
            listNames = emptyList(),
            isCommonlyCalled = false,
            isRarelyCalled = true,
            isRecentlyAdded = false,
            isLongGap = true
        )
    )
    return ContactPickerUiState(
        phase = ContactPickerUiState.Phase.Ready,
        mode = PickerMode.Add,
        targetListName = "Inner orbit",
        searchQuery = "",
        activeFilters = if (darkSelectionDemo) setOf(PickerFilter.LongGap) else emptySet(),
        showIgnored = false,
        allContacts = sample,
        selectedIds = if (darkSelectionDemo) setOf(1L, 2L) else setOf(1L)
    )
}

/** One host for the state previews, so each state renders in the gallery and its audits. */
@Composable
private fun ContactPickerPreviewHost(state: ContactPickerUiState) {
    OrbitTheme {
        ContactPickerContent(
            state = state,
            onBack = {},
            onRetry = {},
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onOpenInPhone = {},
            onCommit = {},
            onPermissionGrant = {},
            onOpenSettings = {}
        )
    }
}

@PreviewLightDark
@Composable
private fun ContactPickerLoadingPreview() {
    ContactPickerPreviewHost(previewReadyState().copy(phase = ContactPickerUiState.Phase.LoadingPermission))
}

@PreviewLightDark
@Composable
private fun ContactPickerErrorPreview() {
    ContactPickerPreviewHost(previewReadyState().copy(phase = ContactPickerUiState.Phase.Error))
}

@PreviewLightDark
@Composable
private fun ContactPickerListNotFoundPreview() {
    ContactPickerPreviewHost(previewReadyState().copy(phase = ContactPickerUiState.Phase.NotFound))
}

@PreviewLightDark
@Composable
private fun ContactPickerPersonNotFoundPreview() {
    ContactPickerPreviewHost(
        previewReadyState().copy(phase = ContactPickerUiState.Phase.NotFound, mode = PickerMode.Relink)
    )
}

@PreviewLightDark
@Composable
private fun ContactPickerNoSearchMatchesPreview() {
    ContactPickerPreviewHost(previewReadyState().copy(searchQuery = "zq", selectedIds = emptySet()))
}

@PreviewLightDark
@Composable
private fun ContactPickerNoFilterMatchesPreview() {
    // Nobody in the fixture is starred.
    ContactPickerPreviewHost(
        previewReadyState().copy(activeFilters = setOf(PickerFilter.Starred), selectedIds = emptySet())
    )
}

// Static: the bar is disabled while the write is in flight, nothing animates.
@PreviewLightDark
@Composable
private fun ContactPickerCommittingPreview() {
    ContactPickerPreviewHost(previewReadyState().copy(phase = ContactPickerUiState.Phase.Committing))
}

@PreviewLightDark
@Composable
private fun ContactPickerEveryoneOnListPreview() {
    ContactPickerPreviewHost(previewReadyState().copy(allContacts = emptyList(), selectedIds = emptySet()))
}

@PreviewLightDark
@Composable
private fun ContactPickerEveryoneIgnoredPreview() {
    val ready = previewReadyState()
    ContactPickerPreviewHost(
        ready.copy(allContacts = ready.allContacts.map { it.copy(isIgnored = true) }, selectedIds = emptySet())
    )
}

@PreviewLightDark
@Composable
private fun ContactPickerRelinkPreview() {
    val ready = previewReadyState()
    ContactPickerPreviewHost(
        ready.copy(
            mode = PickerMode.Relink,
            targetListName = "Sarah Levin",
            allContacts = ready.allContacts.map { it.copy(phoneContactId = it.contactId) },
            selectedIds = setOf(2L)
        )
    )
}

@PreviewLightDark
@Composable
private fun ContactPickerNoRelinkTargetsPreview() {
    ContactPickerPreviewHost(
        previewReadyState().copy(
            mode = PickerMode.Relink,
            targetListName = "Sarah Levin",
            allContacts = emptyList(),
            selectedIds = emptySet()
        )
    )
}

// Combined preview for the stateless ContactPickerContent (THEME-04 /
// THEME-05). Reuses the existing previewReadyState fixture.
@PreviewLightDark
@PreviewFontScale
@Composable
private fun ContactPickerContentPreview() {
    OrbitTheme {
        ContactPickerContent(
            state = previewReadyState(),
            onBack = {},
            onRetry = {},
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onOpenInPhone = {},
            onCommit = {},
            onPermissionGrant = {},
            onOpenSettings = {}
        )
    }
}
