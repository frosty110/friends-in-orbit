package app.orbit.ui.screens.calllog

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.dialPhoneNumber
import coil.compose.AsyncImage

/**
 * Chronological in-app call log, per the call-history spec (README §Behavior):
 *
 *   - **Sticky calendar-day headers** — "Today", "Yesterday", then
 *     "Wednesday 3 June"-style day groups (LOCAL calendar days, grouped by
 *     the VM). Header styling mirrors the picker's [SectionHeader] idiom.
 *   - **Wall-clock time per row** — "4:30pm" in the trailing column, beside
 *     the direction icon; duration stays in the subtitle. The day itself is
 *     carried by the section header.
 *   - **Direction filter row** — All / Incoming / Outgoing quiet chips
 *     (picker FilterChipsRow idiom: accentTint selected container, 48dp tap
 *     floor). MANUAL "Logged" events stay visible under All and Outgoing.
 *   - **Long-press quick actions** — "Call again" (`ACTION_DIAL` via
 *     [dialPhoneNumber]) and "Open contact" (existing nav callback).
 *     "Add note" is intentionally absent: tapping the row already routes to
 *     ContactDetail focused on this call, where the inline "Add note to this
 *     call" affordance lives — a third menu item would duplicate the tap.
 *   - **Honest pagination footer** — "Show n more" where n is the real next
 *     increment (`min(remaining, PAGE_SIZE)`); hidden once everything is
 *     rendered.
 *
 * Tapping a row routes to ContactDetail with `scrollToCallEventId` set;
 * ContactDetail then scrolls to the matching CallHistoryRow and renders the
 * inline "Add note to this call" Primary button below — closing LOG-03.
 *
 * IGNORE-09 (greyed-state contract):
 *   - avatar 50% opacity
 *   - name in fgSubtle (vs fg)
 *   - " (ignored)" suffix on display name
 *   - subtitle text in fgSubtle (vs fgMuted)
 *   - row remains tappable (opens ContactDetail; greyed != inert)
 *
 * Privacy curtain (PRIV-03 carry-forward): when [LocalPrivacyCurtain] is true
 * the display name renders as the literal "Contact" — same convention as
 * BrowseListScreen/CardViewScreen.
 */
@Composable
fun CallLogScreen(
    onBack: () -> Unit,
    onOpenContact: (contactId: Long, callEventId: Long) -> Unit,
    vm: CallLogViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    CallLogContent(
        state = state,
        onBack = onBack,
        onOpenContact = onOpenContact,
        onCallAgain = { phone -> context.dialPhoneNumber(phone) },
        onFilterChange = vm::onFilterChange,
        onShowMore = vm::onShowMore,
    )
}

/**
 * Stateless inner extracted so `@PreviewLightDark` +
 * `@PreviewFontScale` (D-06) can render without `hiltViewModel()` at preview
 * time.
 */
@Composable
private fun CallLogContent(
    state: CallLogUiState,
    onBack: () -> Unit,
    onOpenContact: (contactId: Long, callEventId: Long) -> Unit,
    onCallAgain: (phone: String) -> Unit,
    onFilterChange: (CallLogDirectionFilter) -> Unit,
    onShowMore: () -> Unit,
) {
    OrbitScreen {
        OrbitAppBar(
            title = "Call history",
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = "Back",
                )
            },
        )
        when (val s = state) {
            // Loading used to render nothing (blank flash under
            // the app bar while the correlated set loads).
            CallLogUiState.Loading -> LoadingSkeleton()
            CallLogUiState.Empty -> EmptyState()
            is CallLogUiState.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                DirectionFilterRow(
                    selected = s.filter,
                    onSelect = onFilterChange,
                )
                if (s.sections.isEmpty()) {
                    FilteredEmptyState(filter = s.filter)
                } else {
                    ReadyList(
                        sections = s.sections,
                        remainingCount = s.remainingCount,
                        onOpenContact = onOpenContact,
                        onCallAgain = onCallAgain,
                        onShowMore = onShowMore,
                    )
                }
            }
        }
    }
}

/**
 * All / Incoming / Outgoing quiet chips. Matches the picker's
 * FilterChipsRow idiom: accentTint selected container, fg label, 48dp tap
 * floor. Single-select; tapping the active chip is a no-op (VM guards).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectionFilterRow(
    selected: CallLogDirectionFilter,
    onSelect: (CallLogDirectionFilter) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4),
    ) {
        CallLogDirectionFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OrbitTheme.colors.accentTint,
                    selectedLabelColor = OrbitTheme.colors.fg,
                ),
                modifier = Modifier.defaultMinSize(minHeight = OrbitTheme.spacing.tapMin),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhIcon(
            name = "phone",
            size = 32.dp,
            tint = OrbitTheme.colors.fgMuted,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
        Text(
            text = "No calls yet",
            style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        Text(
            text = "Calls to people on your lists will show up here.",
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

/**
 * Quiet one-liner when the direction filter matches nothing.
 * The filter row above stays visible so the user can step back to All.
 */
@Composable
private fun FilteredEmptyState(filter: CallLogDirectionFilter) {
    val line = when (filter) {
        CallLogDirectionFilter.INCOMING -> "No incoming calls yet."
        CallLogDirectionFilter.OUTGOING -> "No outgoing calls yet."
        CallLogDirectionFilter.ALL -> "No calls yet."
    }
    Text(
        text = line,
        style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x8),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadyList(
    sections: List<CallLogDaySection>,
    remainingCount: Int,
    onOpenContact: (Long, Long) -> Unit,
    onCallAgain: (String) -> Unit,
    onShowMore: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = OrbitTheme.spacing.x2),
    ) {
        sections.forEach { section ->
            stickyHeader(key = "day-${section.epochDay}", contentType = "day-header") {
                DayHeader(label = section.label)
            }
            items(
                items = section.rows,
                key = { it.callEventId },
                contentType = { "call-event" },
            ) { row ->
                CallLogRowComposable(
                    row = row,
                    onOpen = { onOpenContact(row.contactId, row.callEventId) },
                    onCallAgain = { onCallAgain(row.phone) },
                )
            }
        }
        if (remainingCount > 0) {
            // Honest overflow affordance. The label shows the
            // real size of the next increment and the footer disappears when
            // nothing is left. Sentence case, no period, no exclamation per
            // the project voice rules. 48dp min height for a11y; Role.Button
            // semantics for screen readers; fgMuted matches subtitle hierarchy.
            val step = remainingCount.coerceAtMost(CallLogViewModel.PAGE_SIZE)
            item(key = "show-more-footer", contentType = "footer") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(onClick = onShowMore)
                        .semantics { role = Role.Button }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Show $step more",
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                    )
                }
            }
        }
    }
}

/**
 * Quiet Loading placeholder, shaped like the Ready layout
 * (day-header-width bar, then avatar-circle + text-line rows) so the list
 * doesn't jump when sections land. Muted bars only, no copy.
 */
@Composable
private fun LoadingSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = OrbitTheme.spacing.x2),
    ) {
        Box(
            modifier = Modifier
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x1,
                )
                .size(width = 72.dp, height = 14.dp)
                .clip(OrbitTheme.shapes.sm)
                .background(OrbitTheme.colors.bgSubtle),
        )
        repeat(6) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 64.dp)
                    .padding(
                        horizontal = OrbitTheme.spacing.x4,
                        vertical = OrbitTheme.spacing.x2,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(OrbitTheme.colors.bgSubtle),
                )
                Spacer(Modifier.width(OrbitTheme.spacing.x3))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(width = 140.dp, height = 16.dp)
                            .clip(OrbitTheme.shapes.sm)
                            .background(OrbitTheme.colors.bgSubtle),
                    )
                    Spacer(Modifier.height(OrbitTheme.spacing.x1))
                    Box(
                        modifier = Modifier
                            .size(width = 96.dp, height = 12.dp)
                            .clip(OrbitTheme.shapes.sm)
                            .background(OrbitTheme.colors.bgSubtle),
                    )
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun CallLogLoadingSkeletonPreview() {
    OrbitTheme {
        LoadingSkeleton()
    }
}

/**
 * Sticky calendar-day header. Mirrors the picker's
 * SectionHeader idiom: eyebrow type, fgMuted, opaque bg so rows slide
 * beneath it while pinned.
 */
@Composable
private fun DayHeader(label: String) {
    Text(
        text = label,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallLogRowComposable(
    row: CallLogRow,
    onOpen: () -> Unit,
    onCallAgain: () -> Unit,
) {
    val curtain = LocalPrivacyCurtain.current
    val baseName = if (curtain) "Contact" else row.name
    val nameWithSuffix = if (row.isIgnored) "$baseName (ignored)" else baseName
    val nameColor = if (row.isIgnored) OrbitTheme.colors.fgSubtle else OrbitTheme.colors.fg
    val subtitleColor = if (row.isIgnored) OrbitTheme.colors.fgSubtle else OrbitTheme.colors.fgMuted
    val avatarAlpha = if (row.isIgnored) 0.5f else 1.0f

    // Long-press quick actions anchored to the row.
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .combinedClickable(
                    onClick = onOpen,
                    onClickLabel = "Open contact",
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = "Show quick actions",
                )
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.alpha(avatarAlpha)) {
                // The curtain masks the avatar inputs exactly as
                // it masks the text: no photo, and initials derived from the
                // same masked "Contact" literal (BrowseRow idiom). Real
                // initials/photos under a masked name would leak who this is.
                CallLogAvatar(
                    photoUri = if (curtain) null else row.photoUri,
                    name = baseName,
                    size = 44.dp,
                )
            }
            Spacer(Modifier.width(OrbitTheme.spacing.x3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nameWithSuffix,
                    style = OrbitTheme.type.body.copy(color = nameColor),
                )
                val subtitle = buildList {
                    if (row.listContext.isNotBlank()) add(row.listContext)
                    // Blank for manual events (user-logged connections) — their
                    // subtitle reads "Logged" via directionWord instead.
                    if (row.durationLabel.isNotBlank()) add(row.durationLabel)
                    add(row.directionWord)
                }.joinToString(" · ")
                Text(
                    text = subtitle,
                    style = OrbitTheme.type.meta.copy(color = subtitleColor),
                )
            }
            Spacer(Modifier.width(OrbitTheme.spacing.x2))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                PhIcon(
                    name = row.directionIconName,
                    size = 18.dp,
                    tint = subtitleColor,
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x1))
                Text(
                    text = row.timeLabel,
                    style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Call again") },
                onClick = {
                    menuOpen = false
                    onCallAgain()
                },
            )
            DropdownMenuItem(
                text = { Text("Open contact") },
                onClick = {
                    menuOpen = false
                    onOpen()
                },
            )
        }
    }
}

/**
 * 44dp Avatar with optional Coil-backed photo. Mirrors ContactDetailScreen's
 * ContactPhoto pattern but inlined here so CallLogScreen has zero new
 * cross-screen component dependencies.
 */
@Composable
private fun CallLogAvatar(photoUri: String?, name: String, size: androidx.compose.ui.unit.Dp) {
    if (photoUri.isNullOrBlank()) {
        Avatar(name = name, size = size)
    } else {
        AsyncImage(
            model = photoUri,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(OrbitTheme.colors.bgSubtle),
        )
    }
}

// Preview fixture for the stateless CallLogContent.
// Day-sectioned shape with wall-clock labels.
private val previewState: CallLogUiState = CallLogUiState.Ready(
    sections = listOf(
        CallLogDaySection(
            epochDay = 20_500L,
            label = "Today",
            rows = listOf(
                // Manual event — user-logged connection (CallSource.MANUAL).
                CallLogRow(
                    callEventId = 3L,
                    contactId = 3L,
                    name = "Sam Okafor",
                    phone = "+15550003",
                    photoUri = null,
                    listContext = "from Late night",
                    durationLabel = "",
                    directionWord = "Logged",
                    directionIconName = "check-circle",
                    timeLabel = "9:12am",
                    isIgnored = false,
                ),
            ),
        ),
        CallLogDaySection(
            epochDay = 20_499L,
            label = "Yesterday",
            rows = listOf(
                CallLogRow(
                    callEventId = 2L,
                    contactId = 2L,
                    name = "Jordan Lee",
                    phone = "+15550002",
                    photoUri = null,
                    listContext = "",
                    durationLabel = "3 min",
                    directionWord = "Incoming",
                    directionIconName = "phone-incoming",
                    timeLabel = "8:05pm",
                    isIgnored = false,
                ),
            ),
        ),
        CallLogDaySection(
            epochDay = 20_497L,
            label = "Wednesday 3 June",
            rows = listOf(
                CallLogRow(
                    callEventId = 1L,
                    contactId = 1L,
                    name = "Avery Quinn",
                    phone = "+15550001",
                    photoUri = null,
                    listContext = "from Inner orbit",
                    durationLabel = "14 min",
                    directionWord = "Outgoing",
                    directionIconName = "phone-outgoing",
                    timeLabel = "4:30pm",
                    isIgnored = false,
                ),
            ),
        ),
    ),
    filter = CallLogDirectionFilter.ALL,
    remainingCount = 37,
)

@PreviewLightDark
@PreviewFontScale
@Composable
private fun CallLogContentPreview() {
    OrbitTheme {
        CallLogContent(
            state = previewState,
            onBack = {},
            onOpenContact = { _, _ -> },
            onCallAgain = {},
            onFilterChange = {},
            onShowMore = {},
        )
    }
}
