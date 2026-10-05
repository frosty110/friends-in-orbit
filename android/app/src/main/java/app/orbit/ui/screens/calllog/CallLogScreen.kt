package app.orbit.ui.screens.calllog

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitFilterChip
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitListSkeleton
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.PhIcon
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import app.orbit.ui.util.dialPhoneNumber
import app.orbit.ui.util.formatDuration

/**
 * Chronological in-app call log, per the call-history spec (README §Behavior):
 *
 *   - **Everyone, or one person (LOG-04).** Opened from Settings it shows
 *     everyone's calls under "Call history". Opened from Contact detail's
 *     "View all calls" it shows that person's calls under "Calls with Sam",
 *     and back returns to them. Rows then lead with what happened ("You
 *     called", "Sam called") instead of repeating the same name and face on
 *     every row.
 *   - **Sticky calendar-day headers** — "Today", "Yesterday", then
 *     "Wednesday 3 June"-style day groups (LOCAL calendar days, grouped by
 *     the VM), as [SectionLabel] headings so TalkBack can jump between days.
 *   - **Wall-clock time per row** — "4:30pm" in the trailing column, beside
 *     the direction icon; duration stays in the subtitle. The day itself is
 *     carried by the section header.
 *   - **Direction filter row**: All / Incoming / Outgoing as
 *     [OrbitFilterChip]s with radio semantics, in a scrolling row so a label
 *     is never broken mid-word at 200% font scale ("Outgoin g" before).
 *     MANUAL "Logged" events stay visible under All and Outgoing.
 *   - **Long-press quick actions** — "Call again" (`ACTION_DIAL` via
 *     [dialPhoneNumber]) and "Open contact" (existing nav callback).
 *     "Add note" is intentionally absent: tapping the row already routes to
 *     ContactDetail focused on this call, where the inline "Add note to this
 *     call" affordance lives — a third menu item would duplicate the tap.
 *   - **Honest pagination footer** — "Show n more" where n is the real next
 *     increment (`min(remaining, PAGE_SIZE)`); hidden once everything is
 *     rendered.
 *   - **Honest states (LOG-05).** A skeleton while loading; "No calls yet"
 *     only when Orbit can read the call log; "Orbit can't see your calls"
 *     with "Open settings" when it can't (Card view's precedent: Orbit's
 *     Settings owns the grant and the resync it needs); "Couldn't load your
 *     calls" with "Try again" when a data stream fails.
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
 * the display name (rows and title) renders as the literal "Contact", the
 * avatar drops its photo, and the "from {list}" context is left out, since
 * list names are masked everywhere else too (ListContextChip).
 *
 * Copy lives in strings_calllog.xml (UX rubric 3.4); day headings and
 * durations arrive as [app.orbit.ui.util.UiText] from the VM.
 */
@Composable
fun CallLogScreen(
    onBack: () -> Unit,
    onOpenContact: (contactId: Long, callEventId: Long) -> Unit,
    onOpenSettings: () -> Unit = {},
    vm: CallLogViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // LOG-05: READ_CALL_LOG is re-read on every resume (ARCH-04; the Browse
    // and Card view precedent), so coming back from Settings with access
    // granted replaces the denied state without a restart.
    LifecycleResumeEffect(Unit) {
        vm.onCallLogPermissionChanged(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG,
            ) != PackageManager.PERMISSION_GRANTED,
        )
        onPauseOrDispose { }
    }
    CallLogContent(
        state = state,
        onBack = onBack,
        onOpenContact = onOpenContact,
        onCallAgain = { phone -> context.dialPhoneNumber(phone) },
        onFilterChange = vm::onFilterChange,
        onShowMore = vm::onShowMore,
        onOpenSettings = onOpenSettings,
        onRetry = vm::onRetry,
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
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    val curtain = LocalPrivacyCurtain.current
    val person = state.scope as? CallLogScope.Person
    // What the person is called on this screen. Blank until their row loads.
    val personName = when {
        person == null || person.name.isBlank() -> ""
        curtain -> stringResource(R.string.components_curtain_contact)
        else -> person.name
    }
    OrbitScreen {
        OrbitAppBar(
            title = when {
                person == null -> stringResource(R.string.calllog_title)
                personName.isBlank() -> ""
                else -> stringResource(R.string.calllog_title_person, personName)
            },
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = stringResource(R.string.components_action_back),
                )
            },
        )
        when (state) {
            // A calm placeholder shaped like the list, never "No calls yet":
            // the state stays here until the permission is known too.
            is CallLogUiState.Loading -> OrbitListSkeleton(showSectionLabel = true)
            is CallLogUiState.Empty -> OrbitScreenMessage(
                icon = "phone",
                title = if (person != null && personName.isNotBlank()) {
                    stringResource(R.string.calllog_empty_title_person, firstName(personName))
                } else {
                    stringResource(R.string.calllog_empty_title)
                },
                body = if (person != null && personName.isNotBlank()) {
                    stringResource(R.string.calllog_empty_body_person, firstName(personName))
                } else {
                    stringResource(R.string.calllog_empty_body)
                },
            )
            is CallLogUiState.PermissionDenied -> OrbitScreenMessage(
                icon = "phone-slash",
                title = stringResource(R.string.calllog_denied_title),
                body = stringResource(R.string.calllog_denied_body),
                actionLabel = stringResource(R.string.calllog_open_settings),
                onAction = onOpenSettings,
                // The only thing to do on this screen, so it takes the accent.
                actionVariant = OrbitButtonVariant.Primary,
            )
            is CallLogUiState.Error -> OrbitScreenMessage(
                icon = "warning-circle",
                title = stringResource(R.string.calllog_error_title),
                body = stringResource(R.string.calllog_error_body),
                actionLabel = stringResource(R.string.calllog_try_again),
                onAction = onRetry,
                actionVariant = OrbitButtonVariant.Primary,
            )
            is CallLogUiState.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                if (state.callLogDenied) {
                    CallLogDeniedNotice(onOpenSettings = onOpenSettings)
                }
                DirectionFilterRow(
                    selected = state.filter,
                    onSelect = onFilterChange,
                )
                if (state.sections.isEmpty()) {
                    FilteredEmptyState(filter = state.filter)
                } else {
                    ReadyList(
                        sections = state.sections,
                        remainingCount = state.remainingCount,
                        personName = if (person != null) personName else null,
                        onOpenContact = onOpenContact,
                        onCallAgain = onCallAgain,
                        onShowMore = onShowMore,
                    )
                }
            }
        }
    }
}

private fun firstName(name: String): String = name.substringBefore(' ').ifBlank { name }

/**
 * Shown above the rows when Orbit has history but can no longer read the call
 * log. The rows are still true; what is missing is anything newer. Inline and
 * dismiss-free, like Card view's notice: an honest state stays visible until
 * it is fixed.
 */
@Composable
private fun CallLogDeniedNotice(onOpenSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x1)
            .clip(OrbitTheme.shapes.md)
            .background(OrbitTheme.colors.bgSubtle)
            .padding(start = OrbitTheme.spacing.x3),
    ) {
        Text(
            text = stringResource(R.string.calllog_denied_notice),
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = OrbitTheme.spacing.x2),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
                .clip(OrbitTheme.shapes.md)
                .clickable(role = Role.Button, onClick = onOpenSettings)
                .padding(horizontal = OrbitTheme.spacing.x3),
        ) {
            Text(
                text = stringResource(R.string.calllog_open_settings),
                style = OrbitTheme.type.button,
                color = OrbitTheme.colors.fg,
            )
        }
    }
}

/**
 * All / Incoming / Outgoing, one of which is always chosen: radio semantics in
 * a selectable group, so TalkBack says "Incoming, radio button, 2 of 3".
 * Tapping the active chip is a no-op (VM guards). Scrolls sideways instead of
 * wrapping a label at large font sizes.
 */
@Composable
private fun DirectionFilterRow(
    selected: CallLogDirectionFilter,
    onSelect: (CallLogDirectionFilter) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .padding(horizontal = OrbitTheme.spacing.x4),
    ) {
        CallLogDirectionFilter.entries.forEach { filter ->
            OrbitFilterChip(
                label = stringResource(filter.label),
                selected = filter == selected,
                onClick = { onSelect(filter) },
                role = Role.RadioButton,
            )
        }
    }
}

/**
 * Quiet one-liner when the direction filter matches nothing.
 * The filter row above stays visible so the user can step back to All.
 */
@Composable
private fun FilteredEmptyState(filter: CallLogDirectionFilter) {
    val line = stringResource(
        when (filter) {
            CallLogDirectionFilter.INCOMING -> R.string.calllog_filtered_empty_incoming
            CallLogDirectionFilter.OUTGOING -> R.string.calllog_filtered_empty_outgoing
            CallLogDirectionFilter.ALL -> R.string.calllog_filtered_empty_all
        },
    )
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
    // Non-null in the one-person log (LOG-04): rows then lead with the event.
    personName: String?,
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
                DayHeader(label = section.label.asString())
            }
            items(
                items = section.rows,
                key = { it.callEventId },
                contentType = { "call-event" },
            ) { row ->
                CallLogRowComposable(
                    row = row,
                    personName = personName,
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
                        .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
                        .clickable(role = Role.Button, onClick = onShowMore)
                        .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x3),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.calllog_show_more, step, step),
                        style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                    )
                }
            }
        }
    }
}

/**
 * Sticky calendar-day header: a [SectionLabel] (heading semantics) on an
 * opaque background so rows slide beneath it while it is pinned.
 */
@Composable
private fun DayHeader(label: String) {
    SectionLabel(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .background(OrbitTheme.colors.bg)
            .padding(
                horizontal = OrbitTheme.spacing.x4,
                vertical = OrbitTheme.spacing.x1,
            ),
    )
}

/** How a row is titled in the one-person log, where every row is the same person. */
@Composable
private fun CallLogKind.personTitle(firstName: String): String = when (this) {
    CallLogKind.Outgoing -> stringResource(R.string.calllog_person_you_called)
    CallLogKind.Incoming -> stringResource(R.string.calllog_person_they_called, firstName)
    CallLogKind.Logged -> stringResource(R.string.calllog_person_logged)
    CallLogKind.Attempted -> stringResource(R.string.calllog_person_attempted)
}

/** The direction word in everyone's log: "Outgoing", "Incoming", "Logged", "Attempted". */
@StringRes
private fun CallLogKind.directionWord(): Int = when (this) {
    CallLogKind.Outgoing -> R.string.calllog_direction_outgoing
    CallLogKind.Incoming -> R.string.calllog_direction_incoming
    CallLogKind.Logged -> R.string.calllog_direction_logged
    CallLogKind.Attempted -> R.string.calllog_direction_attempted
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallLogRowComposable(
    row: CallLogRow,
    personName: String?,
    onOpen: () -> Unit,
    onCallAgain: () -> Unit,
) {
    val curtain = LocalPrivacyCurtain.current
    val baseName = if (curtain) stringResource(R.string.components_curtain_contact) else row.name
    val nameWithSuffix = if (row.isIgnored) stringResource(R.string.calllog_name_ignored, baseName) else baseName
    val openDetailsLabel = stringResource(R.string.calllog_open_details)
    val nameColor = if (row.isIgnored) OrbitTheme.colors.fgSubtle else OrbitTheme.colors.fg
    val subtitleColor = if (row.isIgnored) OrbitTheme.colors.fgSubtle else OrbitTheme.colors.fgMuted
    val avatarAlpha = if (row.isIgnored) 0.5f else 1.0f
    val onePerson = personName != null

    // Long-press quick actions anchored to the row.
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OrbitTheme.spacing.tapMin)
                .combinedClickable(
                    onClick = onOpen,
                    onClickLabel = openDetailsLabel,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(R.string.calllog_show_quick_actions),
                )
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.alpha(avatarAlpha)) {
                if (onePerson) {
                    // Same person on every row: the leading slot shows what
                    // happened, in the avatar's footprint so rows line up.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(RowAvatarSize)
                            .clip(CircleShape)
                            .background(OrbitTheme.colors.bgSubtle),
                    ) {
                        PhIcon(name = row.directionIconName, size = 20.dp, tint = OrbitTheme.colors.fgMuted)
                    }
                } else {
                    // The curtain masks the avatar inputs exactly as
                    // it masks the text: no photo, and initials derived from the
                    // same masked "Contact" literal (BrowseRow idiom). Real
                    // initials/photos under a masked name would leak who this is.
                    Avatar(
                        name = baseName,
                        size = RowAvatarSize,
                        photoUri = if (curtain) null else row.photoUri,
                    )
                }
            }
            Spacer(Modifier.width(OrbitTheme.spacing.x3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (onePerson) row.kind.personTitle(firstName(personName.orEmpty())) else nameWithSuffix,
                    style = OrbitTheme.type.body.copy(color = nameColor),
                )
                val fromList = if (row.listName.isNotBlank()) {
                    stringResource(R.string.calllog_from_list, row.listName)
                } else {
                    null
                }
                val duration = row.durationLabel?.asString()
                val directionWord = stringResource(row.kind.directionWord())
                val subtitle = buildList {
                    // List names are masked under the curtain (ListContextChip),
                    // and in the one-person log every row would repeat it.
                    if (!onePerson && !curtain && fromList != null) add(fromList)
                    // None for manual events (user-logged connections): their
                    // subtitle reads "Logged" via the direction word instead.
                    if (duration != null) add(duration)
                    if (!onePerson) add(directionWord)
                }.joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = OrbitTheme.type.meta.copy(color = subtitleColor),
                    )
                }
            }
            Spacer(Modifier.width(OrbitTheme.spacing.x2))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!onePerson) {
                    PhIcon(
                        name = row.directionIconName,
                        size = 18.dp,
                        tint = subtitleColor,
                    )
                    Spacer(Modifier.size(OrbitTheme.spacing.x1))
                }
                Text(
                    text = row.timeLabel,
                    style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
                )
            }
        }
        OrbitDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            actions = listOf(
                OrbitMenuAction(label = stringResource(R.string.calllog_call_again), onClick = onCallAgain),
                OrbitMenuAction(label = openDetailsLabel, onClick = onOpen),
            ),
        )
    }
}

/** Row avatar, shared with Browse and Search rows (no spacing token is 44dp). */
private val RowAvatarSize = 44.dp

// ─── Previews ──────────────────────────────────────────────────────────────────
// Day-sectioned fixtures with wall-clock labels, one per state, so every state
// renders in the screenshot gallery.

private fun previewRow(
    id: Long,
    name: String,
    listName: String,
    durationMinutes: Int?,
    kind: CallLogKind,
    timeLabel: String,
): CallLogRow = CallLogRow(
    callEventId = id,
    contactId = id,
    name = name,
    phone = "+1555000$id",
    photoUri = null,
    listName = listName,
    durationLabel = durationMinutes?.let { formatDuration(it * 60) },
    directionIconName = when (kind) {
        CallLogKind.Outgoing -> "phone-outgoing"
        CallLogKind.Incoming -> "phone-incoming"
        CallLogKind.Logged -> "check-circle"
        CallLogKind.Attempted -> "phone-slash"
    },
    timeLabel = timeLabel,
    isIgnored = false,
    kind = kind,
)

/** "Wednesday 3 June", built the way formatDayHeader builds it. */
private val PREVIEW_WEDNESDAY: UiText = UiText.res(R.string.time_day_named, "Wednesday", 3, "June")

private val previewSections: List<CallLogDaySection> = listOf(
    CallLogDaySection(
        epochDay = 20_500L,
        label = UiText.res(R.string.time_day_today),
        rows = listOf(previewRow(3L, "Sam Okafor", "Late night", null, CallLogKind.Logged, "9:12am")),
    ),
    CallLogDaySection(
        epochDay = 20_499L,
        label = UiText.res(R.string.time_day_yesterday),
        rows = listOf(previewRow(2L, "Jordan Lee", "", 3, CallLogKind.Incoming, "8:05pm")),
    ),
    CallLogDaySection(
        epochDay = 20_497L,
        label = PREVIEW_WEDNESDAY,
        rows = listOf(previewRow(1L, "Avery Quinn", "Inner orbit", 14, CallLogKind.Outgoing, "4:30pm")),
    ),
)

private val previewState: CallLogUiState = CallLogUiState.Ready(
    sections = previewSections,
    filter = CallLogDirectionFilter.ALL,
    remainingCount = 37,
)

private val previewPerson = CallLogScope.Person(contactId = 1L, name = "Avery Quinn")

private val previewPersonState: CallLogUiState = CallLogUiState.Ready(
    sections = listOf(
        CallLogDaySection(
            epochDay = 20_500L,
            label = UiText.res(R.string.time_day_today),
            rows = listOf(
                previewRow(5L, "Avery Quinn", "Inner orbit", 22, CallLogKind.Incoming, "9:40am"),
                previewRow(4L, "Avery Quinn", "Inner orbit", null, CallLogKind.Attempted, "8:02am"),
            ),
        ),
        CallLogDaySection(
            epochDay = 20_497L,
            label = PREVIEW_WEDNESDAY,
            rows = listOf(
                previewRow(1L, "Avery Quinn", "Inner orbit", 14, CallLogKind.Outgoing, "4:30pm"),
                previewRow(6L, "Avery Quinn", "Inner orbit", null, CallLogKind.Logged, "11:15am"),
            ),
        ),
    ),
    scope = previewPerson,
)

@Composable
private fun CallLogPreviewHost(state: CallLogUiState) {
    OrbitTheme {
        CallLogContent(
            state = state,
            onBack = {},
            onOpenContact = { _, _ -> },
            onCallAgain = {},
            onFilterChange = {},
            onShowMore = {},
            onOpenSettings = {},
            onRetry = {},
        )
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun CallLogContentPreview() {
    CallLogPreviewHost(previewState)
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun CallLogPersonPreview() {
    CallLogPreviewHost(previewPersonState)
}

@PreviewLightDark
@Composable
private fun CallLogLoadingPreview() {
    CallLogPreviewHost(CallLogUiState.Loading())
}

@PreviewLightDark
@Composable
private fun CallLogEmptyPreview() {
    CallLogPreviewHost(CallLogUiState.Empty(scope = previewPerson))
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun CallLogPermissionDeniedPreview() {
    CallLogPreviewHost(CallLogUiState.PermissionDenied())
}

@PreviewLightDark
@Composable
private fun CallLogDeniedWithHistoryPreview() {
    CallLogPreviewHost(
        CallLogUiState.Ready(sections = previewSections, callLogDenied = true),
    )
}

@PreviewLightDark
@Composable
private fun CallLogErrorPreview() {
    CallLogPreviewHost(CallLogUiState.Error())
}
