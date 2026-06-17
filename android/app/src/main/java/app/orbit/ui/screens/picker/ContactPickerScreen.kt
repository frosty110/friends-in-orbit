package app.orbit.ui.screens.picker

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.PhIcon
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.domain.search.ContactSearch
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitSearchField
import app.orbit.ui.theme.OrbitTheme
import java.time.Instant
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
 *   - LoadingPermission   → silent (system dialog covers)
 *   - PermissionRationale → [RationaleCard]
 *   - PermissionDenied    → [PermissionDeniedEmpty]
 *   - EmptyDevice         → [EmptyDeviceContacts]
 *   - Ready / Committing  → [ReadyContent]: search + chips + select-all + list
 *
 * Pitfalls mitigated:
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
@Composable
fun ContactPickerScreen(
    onBack: () -> Unit,
    onCommit: () -> Unit,
    onSkip: (() -> Unit)? = null,
    vm: ContactPickerViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission launcher — granted boolean callback into the VM.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
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
        onSearchChanged = vm::onSearchChanged,
        onToggleFilter = vm::onToggleFilter,
        onSetSort = vm::setSortBy,
        onToggleSelect = vm::onToggleSelect,
        onSelectAllMatching = vm::onSelectAllMatching,
        onClearSelection = vm::onClearSelection,
        onShowIgnoredToggle = vm::onShowIgnoredToggle,
        onIgnore = { contact -> vm.onIgnore(contact.contactId, contact.displayName) },
        onUnignore = { contact -> vm.onUnignore(contact.contactId, contact.displayName) },
        onCommit = {
            vm.onCommit()
            onCommit()
        },
        onSkip = onSkip,
        onPermissionGrant = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
        onOpenSettings = {
            val intent = buildOpenAppSettingsIntent(context.packageName)
            context.startActivity(intent)
        },
    )
}

/**
 * App-bar title per [PickerMode]. Move/Copy carry the live selection count with
 * an honest singular ("Move 1 contact", never "Move 1 contacts"). Pure +
 * internal so the JVM unit test can pin it.
 */
internal fun pickerModeTitle(mode: PickerMode, selectionCount: Int): String {
    val noun = if (selectionCount == 1) "contact" else "contacts"
    return when (mode) {
        PickerMode.Add -> "Add contacts"
        PickerMode.Move -> "Move $selectionCount $noun"
        PickerMode.Copy -> "Copy $selectionCount $noun"
    }
}

@Composable
private fun ContactPickerContent(
    state: ContactPickerUiState,
    onBack: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onToggleFilter: (PickerFilter) -> Unit,
    onSetSort: (PickerSort) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSelectAllMatching: (Set<Long>) -> Unit,
    onClearSelection: () -> Unit,
    onShowIgnoredToggle: (Boolean) -> Unit,
    onIgnore: (PickerContact) -> Unit,
    onUnignore: (PickerContact) -> Unit,
    onCommit: () -> Unit,
    onSkip: (() -> Unit)?,
    onPermissionGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    OrbitScreen {
        // App-bar title varies with mode and singularizes honestly
        // ("Move 1 contact").
        val title: String = pickerModeTitle(state.mode, state.selectionCount)
        OrbitAppBar(
            title = title,
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = "Back",
                )
            },
            trailing = if (state.searchQuery.isNotBlank()) {
                {
                    OrbitIconButton(
                        icon = "x",
                        onClick = { onSearchChanged("") },
                        contentDescription = "Clear search",
                    )
                }
            } else null,
        )

        // imePadding on the root content surface so BatchCounter and search
        // field never disappear behind the soft keyboard.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            when (state.phase) {
                ContactPickerUiState.Phase.LoadingPermission -> {
                    // Silent — system dialog covers the surface.
                }
                ContactPickerUiState.Phase.PermissionRationale ->
                    RationaleCard(onGrant = onPermissionGrant, onDismiss = onBack)
                ContactPickerUiState.Phase.PermissionDenied ->
                    PermissionDeniedEmpty(onOpenSettings = onOpenSettings)
                ContactPickerUiState.Phase.EmptyDevice ->
                    EmptyDeviceContacts()
                ContactPickerUiState.Phase.NotFound ->
                    NotFoundEmpty()
                ContactPickerUiState.Phase.Ready,
                ContactPickerUiState.Phase.Committing -> ReadyContent(
                    state = state,
                    onSearchChanged = onSearchChanged,
                    onToggleFilter = onToggleFilter,
                    onSetSort = onSetSort,
                    onToggleSelect = onToggleSelect,
                    onSelectAllMatching = onSelectAllMatching,
                    onClearSelection = onClearSelection,
                    onShowIgnoredToggle = onShowIgnoredToggle,
                    onIgnore = onIgnore,
                    onUnignore = onUnignore,
                    onCommit = onCommit,
                    onSkip = onSkip,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadyContent(
    state: ContactPickerUiState,
    onSearchChanged: (String) -> Unit,
    onToggleFilter: (PickerFilter) -> Unit,
    onSetSort: (PickerSort) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSelectAllMatching: (Set<Long>) -> Unit,
    onClearSelection: () -> Unit,
    onShowIgnoredToggle: (Boolean) -> Unit,
    onIgnore: (PickerContact) -> Unit,
    onUnignore: (PickerContact) -> Unit,
    onCommit: () -> Unit,
    onSkip: (() -> Unit)?,
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

    Column(modifier = Modifier.fillMaxSize()) {
        OrbitSearchField(
            query = state.searchQuery,
            onQueryChange = onSearchChanged,
            // The shared matcher also searches phone digits.
            placeholder = "Search name or number",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x2),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x1,
                ),
        ) {
            SortControl(
                sortBy = state.sortBy,
                onSetSort = onSetSort,
            )
            Spacer(modifier = Modifier.weight(1f))
            // Quiet, reversible entry for the wired showIgnored toggle. Only
            // meaningful once something is actually ignored.
            if (state.ignoredCount > 0 || state.showIgnored) {
                ShowIgnoredControl(
                    showIgnored = state.showIgnored,
                    onToggle = onShowIgnoredToggle,
                )
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
            modifier = Modifier.padding(vertical = OrbitTheme.spacing.x2),
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
                    vertical = OrbitTheme.spacing.x1,
                ),
            )
        } else if (state.selectAllCapExceeded) {
            Text(
                text = "Over ${ContactPickerUiState.SELECT_ALL_MAX} matches. " +
                    "Narrow the search to select them all.",
                style = OrbitTheme.type.meta,
                color = OrbitTheme.colors.fgMuted,
                modifier = Modifier.padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x1,
                ),
            )
        }

        // Alphabetical section index. Only meaningful when the rows are in name
        // order and not re-banded by search rank.
        val showSections = state.sortBy == PickerSort.ByName && state.searchQuery.isBlank()
        val sections: List<PickerSection> = remember(state.filteredContacts, showSections) {
            if (showSections) buildPickerSections(state.filteredContacts) else emptyList()
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
                modifier = Modifier.animateItem(),
            )
        }

        // List area takes the remaining height; the bottom bar docks below it
        // (not a floating overlay, so the last row stays reachable).
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.allContacts.isEmpty() -> EmptyDeviceContacts()
                state.filteredContacts.isEmpty() -> EmptyState(
                    heading = if (state.searchQuery.isNotBlank()) {
                        "Nothing matches \"${state.searchQuery}\""
                    } else {
                        "Nothing matches these filters"
                    },
                    body = if (state.searchQuery.isNotBlank()) {
                        "Try a shorter name or remove a filter."
                    } else {
                        "Try removing a chip or widening your thresholds in Settings."
                    },
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = OrbitTheme.spacing.x2),
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
                                key = { it.contactId },
                            ) { contact -> this.pickerRow(contact) }
                        }
                    } else {
                        items(
                            items = state.filteredContacts,
                            key = { it.contactId },
                        ) { contact -> this.pickerRow(contact) }
                    }
                }
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
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }

        // Docked bottom bar. BatchCounter self-hides at zero selection; the
        // onboarding "Skip for now" footer fills the slot when nothing is
        // selected so the flow never dead-ends.
        if (state.selectionCount > 0) {
            Box(modifier = Modifier.onSizeChanged { barHeightPx = it.height }) {
                BatchCounter(
                    selectionCount = state.selectionCount,
                    targetListName = state.targetListName,
                    mode = state.mode,
                    isCommitting = state.phase == ContactPickerUiState.Phase.Committing,
                    onClear = onClearSelection,
                    onCommit = onCommit,
                )
            }
        } else if (onSkip != null) {
            OrbitButton(
                text = "Skip for now",
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(OrbitTheme.spacing.x4),
            )
        }
    }
}

/**
 * Sort control for the picker. A compact pill that opens a [DropdownMenu] with
 * the available sort modes. Default is alphabetical.
 */
@Composable
private fun SortControl(
    sortBy: PickerSort,
    onSetSort: (PickerSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // Ordered list of the user-facing sort options.
    val options: List<Pair<PickerSort, String>> = listOf(
        PickerSort.ByName to "Alphabetical",
        PickerSort.ByMostCalled to "Most called",
        // "Recently called" — absorbs the intent of the removed "Called recently"
        // filter (surface recent callers by ordering, not by hiding others).
        PickerSort.ByRecency to "Recently called",
        PickerSort.ByRecentlySaved to "Recently saved",
    )
    val currentLabel = when (sortBy) {
        PickerSort.ByName -> "Alphabetical"
        PickerSort.ByMostCalled -> "Most called"
        PickerSort.ByRecentlySaved -> "Recently saved"
        PickerSort.ByRecency -> "Recently called"
    }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(OrbitTheme.shapes.full)
                .clickable { expanded = true }
                .padding(
                    horizontal = OrbitTheme.spacing.x3,
                    vertical = OrbitTheme.spacing.x2,
                ),
        ) {
            PhIcon(
                name = "sliders-horizontal",
                size = 16.dp,
                tint = OrbitTheme.colors.fgMuted,
            )
            Spacer(Modifier.width(OrbitTheme.spacing.x2))
            Text(
                text = "Sort: $currentLabel",
                style = OrbitTheme.type.meta,
                color = OrbitTheme.colors.fg,
            )
            Spacer(Modifier.width(OrbitTheme.spacing.x1))
            PhIcon(
                name = "caret-down",
                size = 12.dp,
                tint = OrbitTheme.colors.fgMuted,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (sort, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = if (sort == sortBy) {
                        { PhIcon(name = "check", size = 16.dp, tint = OrbitTheme.colors.accent) }
                    } else null,
                    onClick = {
                        onSetSort(sort)
                        expanded = false
                    },
                )
            }
        }
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
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            // rules.md Design 3 — 48dp tap floor, even for quiet controls.
            .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.full)
            .clickable { onToggle(!showIgnored) }
            .padding(
                horizontal = OrbitTheme.spacing.x3,
                vertical = OrbitTheme.spacing.x2,
            ),
    ) {
        PhIcon(
            name = if (showIgnored) "eye" else "eye-slash",
            size = 16.dp,
            tint = OrbitTheme.colors.fgMuted,
        )
        Spacer(Modifier.width(OrbitTheme.spacing.x2))
        Text(
            text = if (showIgnored) "Hide ignored" else "Show ignored",
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fg,
        )
    }
}

/**
 * One alphabetical section of the filtered list, plus the LazyColumn item index
 * of its sticky header so the [AlphabetRail] can `scrollToItem` straight to it.
 */
@Immutable
private data class PickerSection(
    val letter: String,
    val contacts: List<PickerContact>,
    val headerItemIndex: Int,
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
            headerItemIndex = itemIndex,
        )
        itemIndex += 1 + members.size
        section
    }
}

/**
 * Sticky alphabetical header. Opaque [OrbitTheme.colors.bg] (the OrbitScreen
 * surface) so rows scrolling beneath never bleed through.
 */
@Composable
private fun SectionHeader(letter: String) {
    Text(
        text = letter,
        style = OrbitTheme.type.eyebrow,
        color = OrbitTheme.colors.fgMuted,
        modifier = Modifier
            .fillMaxWidth()
            .background(OrbitTheme.colors.bg)
            .padding(
                horizontal = OrbitTheme.spacing.x4,
                vertical = OrbitTheme.spacing.x1,
            ),
    )
}

@Composable
private fun EmptyState(heading: String, body: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x6),
        ) {
            Text(
                text = heading,
                style = OrbitTheme.type.h3,
                color = OrbitTheme.colors.fg,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = OrbitTheme.type.body,
                color = OrbitTheme.colors.fgMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * C6 — terminal empty state when the `targetListId` nav arg is missing or
 * unparseable. Same shape as [PermissionDeniedEmpty] (centred text, no
 * actions). Keeps copy terse and sentence-case per voice rules.
 */
@Composable
private fun NotFoundEmpty() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "List not found",
            style = OrbitTheme.type.h3,
            color = OrbitTheme.colors.fg,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x6),
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
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onCommit = {},
            onSkip = null,
            onPermissionGrant = {},
            onOpenSettings = {},
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
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onCommit = {},
            onSkip = null,
            onPermissionGrant = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "ContactPicker — Rationale light", showBackground = true)
@Composable
private fun ContactPickerRationalePreviewLight() {
    OrbitTheme(darkTheme = false) {
        ContactPickerContent(
            state = previewReadyState().copy(
                phase = ContactPickerUiState.Phase.PermissionRationale,
            ),
            onBack = {},
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onCommit = {},
            onSkip = null,
            onPermissionGrant = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "ContactPicker — Denied light", showBackground = true)
@Composable
private fun ContactPickerDeniedPreviewLight() {
    OrbitTheme(darkTheme = false) {
        ContactPickerContent(
            state = previewReadyState().copy(
                phase = ContactPickerUiState.Phase.PermissionDenied,
            ),
            onBack = {},
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onCommit = {},
            onSkip = null,
            onPermissionGrant = {},
            onOpenSettings = {},
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
            isLongGap = false,
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
            isLongGap = false,
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
            isLongGap = true,
        ),
    )
    return ContactPickerUiState(
        phase = ContactPickerUiState.Phase.Ready,
        mode = PickerMode.Add,
        targetListName = "Inner orbit",
        searchQuery = "",
        activeFilters = if (darkSelectionDemo) setOf(PickerFilter.LongGap) else emptySet(),
        showIgnored = false,
        allContacts = sample,
        selectedIds = if (darkSelectionDemo) setOf(1L, 2L) else setOf(1L),
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
            onSearchChanged = {},
            onToggleFilter = {},
            onSetSort = {},
            onToggleSelect = {},
            onSelectAllMatching = {},
            onClearSelection = {},
            onShowIgnoredToggle = {},
            onIgnore = {},
            onUnignore = {},
            onCommit = {},
            onSkip = null,
            onPermissionGrant = {},
            onOpenSettings = {},
        )
    }
}
