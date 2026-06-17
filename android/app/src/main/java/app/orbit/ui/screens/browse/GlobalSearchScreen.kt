package app.orbit.ui.screens.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.orbit.data.ChipTone
import app.orbit.data.Contact
import app.orbit.data.entity.ContactEntity
import app.orbit.data.mappers.toUiContact
import app.orbit.data.repository.CallAgg
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.domain.clock.Clock
import app.orbit.domain.search.ContactSearch
import app.orbit.ui.components.BrowseRow
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitChip
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitSearchField
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.dialPhoneNumber
import app.orbit.ui.util.formatRelative
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Real GlobalSearch (BROWSE-03). A debounced cross-list search backed by a
 * Hilt-injected VM that combines contacts × lists × call-events × query into
 * a sorted result list.
 *
 * Search semantics:
 *   - Empty query → guidance copy "Search people by name."
 *   - Non-empty query → [ContactSearch.filterRanked] over ALL contacts
 *     (name with diacritic folding + phone digits; word-start matches rank
 *     before mid-word, before phone hits).
 *   - 250ms debounce in the screen-side `snapshotFlow`.
 *   - Result sort: rank band first, then lastCallAt DESC NULLS LAST within
 *     a band (contacts are recency-sorted before the stable ranked filter).
 *   - No matches → "Nothing matches \"{query}\"." (template matches Browse).
 *
 * Membership context ("is Maya in my orbit?"): each hit carries the
 * names of the active (non-archived) lists the contact belongs to, derived
 * by combining `ListRepository.observeMembersOfList` across
 * `observeActive()`. Rows render one quiet chip per list, or "Not on any
 * list", plus an "Add to list" affordance that routes to the existing list
 * picker (Routes.PickLists). Picker commits surface on the app-level
 * snackbar host, so navigation alone completes the loop.
 *
 * Curtain (PRIV-03): names read `LocalPrivacyCurtain.current` transitively
 * via [BrowseRow]; list-name chips are membership labels, not contact PII.
 */

/** GlobalSearch UiState — local to this file (no cross-file consumers). */
@Immutable
sealed interface SearchUiState {
    @Immutable data object Empty : SearchUiState
    @Immutable
    data class Ready(
        val results: List<SearchHit>,
        val query: String,
    ) : SearchUiState
    @Immutable data class NoMatches(val query: String) : SearchUiState
}

/** A single search result — Contact + the list names that contact belongs to. */
@Immutable
data class SearchHit(
    val contact: Contact,
    val lists: List<String>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val callEventRepo: CallEventRepository,
    private val clock: Clock,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val searchQuery: StateFlow<String> =
        savedStateHandle.getStateFlow(SEARCH_QUERY_KEY, "")

    // Zone for the shared relative-time formatter (CallLogViewModel
    // convention: read once, not per row).
    private val zone: ZoneId = ZoneId.systemDefault()

    fun onSearchChanged(q: String) {
        savedStateHandle[SEARCH_QUERY_KEY] = q
    }

    // Push the per-contact lastAt down into SQL via
    // `observeAggregatesForContacts(ids)`, keyed off the contact-id set the
    // search VM already streams. Replaces the legacy `callEventRepo.observeAll()`
    // shape that pulled the entire `call_events` table on every emission.
    private val callAggregatesFlow = contactRepo.observeAll()
        .map { contacts -> contacts.map(ContactEntity::id) }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap<Long, CallAgg>())
            else callEventRepo.observeAggregatesForContacts(ids)
        }

    // #20 — per-contact active-list names, built from EXISTING repository
    // observers only (no new DAO surface): one observeMembersOfList stream
    // per active list, combined into Map<contactId, List<listName>>. List
    // count is small (personal app), so N parallel streams is cheap.
    // Archived lists are excluded deliberately — an archived list is not
    // "in your orbit".
    private val activeListNamesByContactId: Flow<Map<Long, List<String>>> =
        listRepo.observeActive().flatMapLatest { lists ->
            if (lists.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    lists.map { list ->
                        listRepo.observeMembersOfList(list.id)
                            .map { members -> list.name to members.map { it.contactId } }
                    },
                ) { perList ->
                    // observeActive() is sortOrder ASC, so chip order follows
                    // the user's own list ordering.
                    buildMap<Long, List<String>> {
                        perList.forEach { (listName, contactIds) ->
                            contactIds.forEach { contactId ->
                                put(contactId, get(contactId).orEmpty() + listName)
                            }
                        }
                    }
                }
            }
        }

    val uiState: StateFlow<SearchUiState> = combine(
        contactRepo.observeAll(),
        activeListNamesByContactId,
        callAggregatesFlow,
        searchQuery,
    ) { contacts: List<ContactEntity>, listNamesByContactId, aggregates: Map<Long, CallAgg>, query ->
        val q = query.trim()
        if (q.isEmpty()) return@combine SearchUiState.Empty

        // #16 — shared matcher across ALL contacts (BROWSE-03 distinction from
        // BROWSE-02's per-list scoping). Recency-sort BEFORE the ranked filter:
        // filterRanked's sort is stable, so lastCallAt DESC NULLS LAST survives
        // within each rank band while word-start hits still lead overall.
        val recencyOrdered = contacts.sortedWith(
            compareByDescending(nullsLast<Instant>()) { aggregates[it.id]?.lastAt }
        )
        val matched = ContactSearch.filterRanked(
            items = recencyOrdered,
            query = q,
            name = { it.displayName },
            phone = { it.normalizedPhone },
        )

        val now = clock.now()
        val hits = matched.map { entity ->
            SearchHit(
                contact = entity.toUiContact()
                    .withLastCallLabel(aggregates[entity.id]?.lastAt, now),
                lists = listNamesByContactId[entity.id].orEmpty(),
            )
        }

        if (hits.isEmpty()) SearchUiState.NoMatches(q) else SearchUiState.Ready(hits, q)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SearchUiState.Empty,
    )

    /**
     * Helper — overlay a relative-time `lastCalledLabel` on the
     * minimal-safe Contact projection. Delegates to the shared
     * [formatRelative] (`ui/util/RelativeTime.kt`): local calendar-day
     * comparison + honest singulars, matching Browse and the call log.
     */
    private fun Contact.withLastCallLabel(lastCallAt: Instant?, now: Instant): Contact {
        if (lastCallAt == null) return copy(lastCalledLabel = "")
        return copy(lastCalledLabel = formatRelative(lastCallAt, now, zone))
    }

    companion object {
        internal const val SEARCH_QUERY_KEY = "globalSearchQuery"
    }
}

@OptIn(FlowPreview::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    onAddToLists: (contactId: String) -> Unit,
    vm: GlobalSearchViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val initialQuery by vm.searchQuery.collectAsStateWithLifecycle()
    // Curtain is observed transitively via BrowseRow; reading it here registers
    // this composable as a consumer for completeness (PRIV-03 audit).
    @Suppress("UNUSED_VARIABLE") val curtain = LocalPrivacyCurtain.current

    GlobalSearchContent(
        state = state,
        initialQuery = initialQuery,
        onSearchChanged = vm::onSearchChanged,
        onBack = onBack,
        onOpenContact = onOpenContact,
        onAddToLists = onAddToLists,
    )
}

/**
 * Stateless inner extracted so `@PreviewLightDark` +
 * `@PreviewFontScale` (D-06) can render without `hiltViewModel()` /
 * `collectAsStateWithLifecycle()` at preview time.
 */
@OptIn(FlowPreview::class)
@Composable
private fun GlobalSearchContent(
    state: SearchUiState,
    initialQuery: String,
    onSearchChanged: (String) -> Unit,
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    onAddToLists: (contactId: String) -> Unit,
) {
    val context = LocalContext.current

    var queryText by rememberSaveable { mutableStateOf(initialQuery) }

    LaunchedEffect(Unit) {
        snapshotFlow { queryText }
            .debounce(250)
            .distinctUntilChanged()
            .collect { onSearchChanged(it) }
    }

    // The screen exists to type into; focus the field on entry
    // so the keyboard is already up. One-shot (Unit key): rotation restores
    // focus naturally, and a user who deliberately dismissed the keyboard
    // isn't fought on recomposition.
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocus()
    }

    OrbitScreen {
        OrbitAppBar(
            title = "Search",
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = "Back",
                )
            },
        )

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
                placeholder = "Search people",
                focusRequester = searchFocusRequester,
            )
        }

        when (val s = state) {
            SearchUiState.Empty -> EmptyHint(text = "Search people by name.")
            is SearchUiState.NoMatches -> EmptyHint(text = "Nothing matches \"${s.query}\".")
            is SearchUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = OrbitTheme.spacing.x6),
            ) {
                items(
                    items = s.results,
                    key = { it.contact.id },
                    contentType = { "searchHit" },
                ) { hit ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BrowseRow(
                            contact = hit.contact,
                            onTap = { onOpenContact(hit.contact.id) },
                            onDial = {
                                if (hit.contact.phone.isNotBlank()) {
                                    context.dialPhoneNumber(hit.contact.phone)
                                }
                            },
                        )
                        // Membership context (#20 — UI-SPEC §BROWSE-03): one
                        // quiet chip per active list, or "Not on any list",
                        // plus an "Add to list" affordance routing to the
                        // existing list picker. Stone tone keeps the screen's
                        // single terracotta element the dial icon (rules.md
                        // §Design 5).
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = OrbitTheme.spacing.x5,
                                    end = OrbitTheme.spacing.x4,
                                    bottom = OrbitTheme.spacing.x2,
                                ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(
                                    OrbitTheme.spacing.x2,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                if (hit.lists.isEmpty()) {
                                    Text(
                                        text = "Not on any list",
                                        style = OrbitTheme.type.micro,
                                        color = OrbitTheme.colors.fgMuted,
                                    )
                                } else {
                                    hit.lists.forEach { listName ->
                                        OrbitChip(
                                            label = listName,
                                            tone = ChipTone.Stone,
                                        )
                                    }
                                }
                            }
                            // 48dp tap target (rules.md §Design 3); quiet text
                            // affordance — no terracotta.
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(
                                        minWidth = OrbitTheme.spacing.tapMin,
                                        minHeight = OrbitTheme.spacing.tapMin,
                                    )
                                    .clickable(
                                        onClick = { onAddToLists(hit.contact.id) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Add to list",
                                    style = OrbitTheme.type.meta,
                                    color = OrbitTheme.colors.fg,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(OrbitTheme.spacing.x8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fgMuted,
        )
    }
}

// Preview fixture for the stateless GlobalSearchContent.
// Empty state is the sensible default — renders the "Search people by name."
// hint without needing a Contact fixture (THEME-04 / THEME-05 — D-06).
private val previewState: SearchUiState = SearchUiState.Empty

@PreviewLightDark
@PreviewFontScale
@Composable
private fun GlobalSearchContentPreview() {
    OrbitTheme {
        GlobalSearchContent(
            state = previewState,
            initialQuery = "",
            onSearchChanged = {},
            onBack = {},
            onOpenContact = {},
            onAddToLists = {},
        )
    }
}
