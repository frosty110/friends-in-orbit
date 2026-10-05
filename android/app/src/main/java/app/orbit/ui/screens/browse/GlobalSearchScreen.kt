package app.orbit.ui.screens.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.orbit.R
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
import app.orbit.ui.components.ListContextChip
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitListSkeleton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSearchField
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.dialPhoneNumber
import app.orbit.ui.util.formatRelative
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Real GlobalSearch (BROWSE-03). A debounced cross-list search backed by a
 * Hilt-injected VM that combines contacts × lists × call-events × query into
 * a sorted result list.
 *
 * Search semantics:
 *   - Empty query → guidance: "Find someone", by name or number.
 *   - Non-empty query → [ContactSearch.filterRanked] over ALL contacts
 *     (name with diacritic folding + phone digits; word-start matches rank
 *     before mid-word, before phone hits).
 *   - 250ms debounce in the screen-side `snapshotFlow`.
 *   - Result sort: rank band first, then lastCallAt DESC NULLS LAST within
 *     a band (contacts are recency-sorted before the stable ranked filter).
 *   - No matches → "Nothing matches “{query}”" (template matches Browse).
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
 * via [BrowseRow]; list names are masked by [ListContextChip], as everywhere.
 */

/**
 * GlobalSearch UiState: local to this file (no cross-file consumers).
 *
 * BROWSE-06: `Loading` is a typed query whose people have not loaded yet. It
 * used to fall through to the empty query's hint, or to "Nothing matches"
 * against an empty contact set, both false. `Error` is a failed data stream,
 * with Retry.
 */
@Immutable
sealed interface SearchUiState {
    @Immutable data object Empty : SearchUiState
    @Immutable data object Loading : SearchUiState
    @Immutable data object Error : SearchUiState
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

    /** Everything a search needs besides the query. */
    private data class SearchData(
        val contacts: List<ContactEntity>,
        val listNamesByContactId: Map<Long, List<String>>,
        val aggregates: Map<Long, CallAgg>,
    )

    // The newest data, replayed when the flow restarts (WhileSubscribed brings
    // the screen back after 5s), so a returning user sees their results, not a
    // skeleton. Null only before the first load.
    @Volatile private var lastData: SearchData? = null

    private fun dataFlow(): Flow<SearchData?> =
        combine(
            contactRepo.observeAll(),
            activeListNamesByContactId,
            callAggregatesFlow,
        ) { contacts, listNames, aggregates ->
            SearchData(contacts, listNames, aggregates).also { lastData = it }
        }
            .map<SearchData, SearchData?> { it }
            .onStart { emit(lastData) }

    // BROWSE-06: bumped by [onRetry]; flatMapLatest re-subscribes every source.
    private val retryCount = MutableStateFlow(0)

    /** The Error state's Retry. */
    fun onRetry() {
        retryCount.update { it + 1 }
    }

    val uiState: StateFlow<SearchUiState> = retryCount.flatMapLatest {
        combine(searchQuery, dataFlow()) { query, data ->
            val q = query.trim()
            when {
                // The hint is true whatever the data, so it never waits.
                q.isEmpty() -> SearchUiState.Empty
                data == null -> SearchUiState.Loading
                else -> search(q, data)
            }
        }
            // No logging here (rules.md Code 4): the state is the report.
            .catch { emit(SearchUiState.Error) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = if (searchQuery.value.isBlank()) SearchUiState.Empty else SearchUiState.Loading,
    )

    private fun search(q: String, data: SearchData): SearchUiState {
        val contacts = data.contacts
        val listNamesByContactId = data.listNamesByContactId
        val aggregates = data.aggregates

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

        return if (hits.isEmpty()) SearchUiState.NoMatches(q) else SearchUiState.Ready(hits, q)
    }

    /**
     * Helper — overlay a relative-time `lastCalledLabel` on the
     * minimal-safe Contact projection. Delegates to the shared
     * [formatRelative] (`ui/util/RelativeTime.kt`): local calendar-day
     * comparison + honest singulars, matching Browse and the call log.
     */
    private fun Contact.withLastCallLabel(lastCallAt: Instant?, now: Instant): Contact {
        if (lastCallAt == null) return copy(lastCalledLabel = null)
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

    GlobalSearchContent(
        state = state,
        initialQuery = initialQuery,
        onSearchChanged = vm::onSearchChanged,
        onRetry = vm::onRetry,
        onBack = onBack,
        onOpenContact = onOpenContact,
        onAddToLists = onAddToLists,
    )
}

/**
 * Stateless inner extracted so `@PreviewLightDark` +
 * `@PreviewFontScale` (D-06) can render without `hiltViewModel()` /
 * `collectAsStateWithLifecycle()` at preview time.
 *
 * Curtain (PRIV-03): names and photos are masked by [BrowseRow]; list names by
 * [ListContextChip] (they read "List"), like every other list-name surface.
 * Before 2026-10-05 the chips here showed real list names under the curtain.
 */
@OptIn(FlowPreview::class)
@Composable
private fun GlobalSearchContent(
    state: SearchUiState,
    initialQuery: String,
    onSearchChanged: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    onAddToLists: (contactId: String) -> Unit,
    autoFocus: Boolean = true,
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
        if (autoFocus) searchFocusRequester.requestFocus()
    }

    OrbitScreen {
        OrbitAppBar(
            title = stringResource(R.string.browse_search_title),
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = stringResource(R.string.components_action_back),
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
                placeholder = stringResource(R.string.browse_search_hint),
                focusRequester = searchFocusRequester,
            )
        }

        when (val s = state) {
            SearchUiState.Empty -> OrbitScreenMessage(
                icon = "magnifying-glass",
                title = stringResource(R.string.browse_search_empty_title),
                body = stringResource(R.string.browse_search_empty_body),
            )
            // BROWSE-06: a typed query waiting on its people: a skeleton,
            // never a premature "Nothing matches".
            SearchUiState.Loading -> OrbitListSkeleton(rows = 4)
            SearchUiState.Error -> OrbitScreenMessage(
                icon = "warning-circle",
                title = stringResource(R.string.browse_search_error_title),
                body = stringResource(R.string.browse_search_error_body),
                actionLabel = stringResource(R.string.browse_try_again),
                onAction = onRetry,
                actionVariant = OrbitButtonVariant.Primary,
            )
            is SearchUiState.NoMatches -> OrbitScreenMessage(
                title = stringResource(R.string.browse_no_matches_title, s.query),
                body = stringResource(R.string.browse_no_matches_body),
            )
            is SearchUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = OrbitTheme.spacing.x6),
            ) {
                items(
                    items = s.results,
                    key = { it.contact.id },
                    contentType = { "searchHit" },
                ) { hit ->
                    SearchHitRow(
                        hit = hit,
                        onOpen = { onOpenContact(hit.contact.id) },
                        onDial = {
                            if (hit.contact.phone.isNotBlank()) {
                                context.dialPhoneNumber(hit.contact.phone)
                            }
                        },
                        onAddToLists = { onAddToLists(hit.contact.id) },
                    )
                }
            }
        }
    }
}

/**
 * One result: the shared [BrowseRow], then where this person sits in your
 * orbit (#20, UI-SPEC §BROWSE-03): one quiet chip per active list, or "Not on
 * any list", and an "Add to list" control. Stone chips and an ink label keep
 * the accent off this screen (rules.md §Design 5); the chips wrap rather than
 * run off a narrow screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHitRow(
    hit: SearchHit,
    onOpen: () -> Unit,
    onDial: () -> Unit,
    onAddToLists: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BrowseRow(
            contact = hit.contact,
            onTap = onOpen,
            onDial = onDial,
        )
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
                verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
                modifier = Modifier.weight(1f),
            ) {
                if (hit.lists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.browse_search_not_on_a_list),
                        style = OrbitTheme.type.meta,
                        color = OrbitTheme.colors.fgMuted,
                    )
                } else {
                    hit.lists.forEach { listName ->
                        ListContextChip(listName = listName, tone = ChipTone.Stone)
                    }
                }
            }
            // 48dp tap target (rules.md §Design 3); quiet text
            // affordance: no terracotta.
            Box(
                modifier = Modifier
                    .defaultMinSize(
                        minWidth = OrbitTheme.spacing.tapMin,
                        minHeight = OrbitTheme.spacing.tapMin,
                    )
                    .clip(OrbitTheme.shapes.md)
                    .clickable(role = Role.Button, onClick = onAddToLists)
                    .padding(horizontal = OrbitTheme.spacing.x2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.browse_search_add_to_list),
                    style = OrbitTheme.type.button,
                    color = OrbitTheme.colors.fg,
                )
            }
        }
    }
}

// ─── Previews ──────────────────────────────────────────────────────────────────
// One per state, so each renders in the screenshot gallery.

private fun previewHit(id: Long, name: String, lastCalled: UiText?, lists: List<String>) = SearchHit(
    contact = Contact(
        id = "c-$id",
        name = name,
        phone = "+1 555 0100",
        lastCalledLabel = lastCalled,
        avgLengthLabel = null,
        pickupRateLabel = "",
        totalCalls = 0,
        due = false,
        listIds = emptyList(),
        bestWindowLabel = null,
        heat = FloatArray(24) { 0f },
        history = emptyList(),
        notes = emptyList(),
        patternNote = "",
    ),
    lists = lists,
)

@Composable
private fun SearchPreviewHost(state: SearchUiState, query: String = "") {
    OrbitTheme {
        GlobalSearchContent(
            state = state,
            initialQuery = query,
            onSearchChanged = {},
            onRetry = {},
            onBack = {},
            onOpenContact = {},
            onAddToLists = {},
            autoFocus = false,
        )
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun GlobalSearchContentPreview() {
    SearchPreviewHost(SearchUiState.Empty)
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun GlobalSearchResultsPreview() {
    SearchPreviewHost(
        SearchUiState.Ready(
            results = listOf(
                previewHit(1, "Maya Ahmed", UiText.plural(R.plurals.time_ago_days, 3, 3), listOf("Inner orbit", "Late night")),
                previewHit(2, "Maya Brooks", null, emptyList()),
            ),
            query = "maya",
        ),
        query = "maya",
    )
}

@PreviewLightDark
@Composable
private fun GlobalSearchLoadingPreview() {
    SearchPreviewHost(SearchUiState.Loading, query = "maya")
}

@PreviewLightDark
@Composable
private fun GlobalSearchNoMatchesPreview() {
    SearchPreviewHost(SearchUiState.NoMatches("zz"), query = "zz")
}

@PreviewLightDark
@Composable
private fun GlobalSearchErrorPreview() {
    SearchPreviewHost(SearchUiState.Error, query = "maya")
}
