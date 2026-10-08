package app.orbit.ui.screens.week

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.PhIcon
import app.orbit.ui.screens.home.DirectionLegend
import app.orbit.ui.screens.home.RhythmCall
import app.orbit.ui.screens.home.RhythmDay
import app.orbit.ui.screens.home.RhythmDaySheet
import app.orbit.ui.screens.home.directionColor
import app.orbit.ui.screens.home.directionMark
import app.orbit.ui.theme.LocalReducedMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.TimeStyle
import app.orbit.ui.util.asString
import app.orbit.ui.util.formatClockTime
import app.orbit.ui.util.formatDayHeader
import app.orbit.ui.util.formatDuration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * HOME-13: the Week screen. One list's calls as a week of seven day columns
 * on a time axis that runs down the page from midnight to midnight, each call
 * a block at its start time, as tall as it lasted, filled with the person's
 * colour and wearing the strip's direction mark. The owner asked for "a
 * bigger chart of who I was calling on specific days ... almost like a daily
 * calendar for that week", with AM and PM, that scrolls back
 * (vision/flows/owner-review-2026-10-07.md, decision 2).
 *
 * Reached from Home's strip ("See your week") and its day sheet ("See the
 * whole week"); it always opens on this week, the strip's seven days.
 */
@Composable
fun WeekScreen(
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    vm: WeekViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    // HOME-12's precedent: the screen can stay open across midnight, so
    // today is re-read on every resume and "This week" moves on with Home.
    LifecycleResumeEffect(key1 = Unit) {
        vm.onResumed()
        onPauseOrDispose { /* nothing to release; the state lives in the VM */ }
    }
    WeekContent(
        state = state,
        onBack = onBack,
        onRetry = vm::onRetry,
        onOpenContact = { contactId -> onOpenContact(contactId.toString()) },
    )
}

/**
 * Stateless Week screen (THEME-04). [use24Hour] is the phone's clock setting
 * for the axis; [initialPage] is which week it opens on (0 = this week), for
 * previews and tests, since the screen itself always opens on this week.
 */
@Composable
internal fun WeekContent(
    state: WeekUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
    onOpenContact: (contactId: Long) -> Unit = {},
    use24Hour: Boolean = TimeStyle.is24Hour,
    initialPage: Int = 0,
) {
    val curtain = LocalPrivacyCurtain.current
    // The list's name is the title; under the curtain, "List" (PRIV-03).
    val title = when (state) {
        is WeekUiState.Ready ->
            if (curtain) stringResource(R.string.components_curtain_list) else state.listName
        else -> ""
    }
    OrbitScreen {
        OrbitAppBar(
            title = title,
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = stringResource(R.string.components_action_back),
                )
            },
        )
        when (state) {
            // Quiet chrome: the app bar alone until the first read answers,
            // never a false "No calls this week."
            WeekUiState.Loading -> Unit
            // The shared error body; Go back alone when there is nothing a
            // retry could fix (Browse's precedent, rules.md Code 3).
            is WeekUiState.Error -> OrbitScreenMessage(
                icon = "warning-circle",
                title = stringResource(R.string.home_week_error_title),
                body = stringResource(R.string.components_error_body),
                actionLabel = stringResource(
                    if (state.canRetry) R.string.components_error_retry else R.string.components_action_go_back,
                ),
                onAction = if (state.canRetry) onRetry else onBack,
                actionVariant = OrbitButtonVariant.Primary,
            )
            is WeekUiState.Ready -> WeekReady(
                state = state,
                curtain = curtain,
                use24Hour = use24Hour,
                initialPage = initialPage,
                onOpenContact = onOpenContact,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.WeekReady(
    state: WeekUiState.Ready,
    curtain: Boolean,
    use24Hour: Boolean,
    initialPage: Int,
    onOpenContact: (contactId: Long) -> Unit,
) {
    val weeks = state.weeks
    // Which week is in view is the pager's, and only the pager's (rules.md
    // Code 7): the arrows, "This week" and a swipe all move it, and the
    // heading reads it. Older weeks lie to the left, so a swipe to the right
    // goes back in time, the way the owner asked to "scroll back".
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, weeks.lastIndex)) { weeks.size }
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalReducedMotion.current
    val page = pager.currentPage.coerceIn(0, weeks.lastIndex)
    val week = weeks[page]
    val goTo: (Int) -> Unit = { target ->
        scope.launch {
            // With animations off the step is instant (rules.md Design 8).
            if (reducedMotion) pager.scrollToPage(target) else pager.animateScrollToPage(target)
        }
    }
    // The day whose sheet is open, as an epoch day so it survives rotation.
    // One owner; every week's columns write it through onOpenDay.
    var openDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val side = sideMargin()

    val resources = LocalContext.current.resources
    val heading = if (page == 0) {
        stringResource(R.string.home_week_this_week)
    } else {
        remember(week.firstDay, state.today) { weekRangeLabel(resources, week.firstDay, week.lastDay, state.today) }
    }
    WeekHeader(
        heading = heading,
        canPrevious = page < weeks.lastIndex,
        canNext = page > 0,
        onPrevious = { goTo(page + 1) },
        onNext = { goTo(page - 1) },
    )
    // The legend under the header, and "This week" to come back when the
    // pager is elsewhere. The row keeps the button's height while it is
    // gone, so stepping off this week does not shift the chart.
    FlowRow(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .padding(horizontal = side),
    ) {
        Box(Modifier.heightIn(min = OrbitTheme.spacing.tapMin), contentAlignment = Alignment.CenterStart) {
            DirectionLegend()
        }
        if (page != 0) {
            OrbitButton(
                text = stringResource(R.string.home_week_this_week),
                onClick = { goTo(0) },
                variant = OrbitButtonVariant.Secondary,
            )
        }
    }
    HorizontalPager(
        state = pager,
        reverseLayout = true,
        key = { index -> weeks[index].firstDay.toEpochDay() },
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) { index ->
        WeekChart(
            week = weeks[index],
            today = state.today,
            curtain = curtain,
            use24Hour = use24Hour,
            side = side,
            onOpenDay = { date -> openDay = date.toEpochDay() },
            onOpenContact = onOpenContact,
        )
    }

    // The day sheet the strip opens, for a day of this screen. No "See the
    // whole week" here: this is the whole week.
    openDay?.let { epochDay ->
        val date = LocalDate.ofEpochDay(epochDay)
        val calls = weeks.firstNotNullOfOrNull { w ->
            val i = (epochDay - w.firstDay.toEpochDay()).toInt()
            if (i in w.days.indices) w.days[i].calls else null
        }
        if (calls.isNullOrEmpty()) {
            // The calls changed under the open sheet (a member left, the day
            // rolled out of range): close it, in an effect, never as a write
            // during composition (Home's precedent).
            LaunchedEffect(epochDay) { openDay = null }
        } else {
            RhythmDaySheet(
                dayLabel = formatDayHeader(date, state.today).asString(),
                calls = calls,
                curtain = curtain,
                onOpenContact = { contactId ->
                    openDay = null
                    onOpenContact(contactId)
                },
                onDismiss = { openDay = null },
            )
        }
    }
}

/**
 * The week's dates between the two arrows. The heading is a live region, so
 * TalkBack says the new week when an arrow moves it.
 */
@Composable
private fun WeekHeader(
    heading: String,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    // Older weeks are to the start side; in a right-to-left language the
    // pager mirrors, and so do the arrows.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x2),
    ) {
        WeekArrow(
            icon = if (rtl) "caret-right" else "caret-left",
            label = stringResource(R.string.home_week_previous),
            enabled = canPrevious,
            onClick = onPrevious,
        )
        Text(
            text = heading,
            style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg, fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
        )
        WeekArrow(
            icon = if (rtl) "caret-left" else "caret-right",
            label = stringResource(R.string.home_week_next),
            enabled = canNext,
            onClick = onNext,
        )
    }
}

/**
 * A 48dp arrow that can be off: next on this week, previous on the earliest.
 * Shaped like [OrbitIconButton], which has no disabled state; a disabled
 * arrow says so to TalkBack and is skipped by the audit, as a disabled
 * control is exempt from contrast.
 */
@Composable
private fun WeekArrow(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.md)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        PhIcon(
            name = icon,
            size = 22.dp,
            // OrbitButton's disabled alpha, so off reads the same app-wide.
            tint = if (enabled) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted.copy(alpha = 0.4f),
        )
    }
}

/**
 * One week: the day heads, pinned, over the scrolling chart.
 *
 * No time gutter beside the columns. Seven columns and a gutter wide enough
 * for "12pm" leave about 45dp a day on a 360dp phone, under the 48dp floor
 * (rules.md Design 3), and less at 200% text. So the columns take the full
 * width, Home's 12dp margins below 380dp included (48dp a day at 360dp, the
 * strip's own arithmetic), and the hour labels sit on the grid at the start
 * edge, under the blocks.
 */
@Composable
private fun WeekChart(
    week: WeekPage,
    today: LocalDate,
    curtain: Boolean,
    use24Hour: Boolean,
    side: Dp,
    onOpenDay: (LocalDate) -> Unit,
    onOpenContact: (contactId: Long) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = side)) {
            week.days.forEachIndexed { i, day ->
                val date = week.firstDay.plusDays(i.toLong())
                DayHead(
                    date = date,
                    day = day,
                    today = today,
                    curtain = curtain,
                    onClick = { onOpenDay(date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            // Opens on the week's earliest call, a little above it so the
            // block is not flush under the day heads; 8am for a quiet week.
            val startMinute = ((week.earliestMinute ?: QUIET_WEEK_START_MINUTE) - SCROLL_MARGIN_MINUTES)
                .coerceAtLeast(0)
            val startPx = with(LocalDensity.current) { (HOUR_HEIGHT * (startMinute / 60f)).roundToPx() }
            val scroll = rememberScrollState(initial = startPx)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = side)
                        .height(HOUR_HEIGHT * HOURS_IN_DAY),
                ) {
                    HourGrid(use24Hour = use24Hour, modifier = Modifier.matchParentSize())
                    Row(Modifier.fillMaxSize()) {
                        week.days.forEachIndexed { i, day ->
                            val date = week.firstDay.plusDays(i.toLong())
                            DayColumn(
                                day = day,
                                curtain = curtain,
                                onOpenDay = { onOpenDay(date) },
                                onOpenContact = onOpenContact,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(OrbitTheme.spacing.x6))
            }
            if (week.isEmpty) {
                // Over the empty grid, not in place of it: the week still
                // reads as a week (HOME-13).
                Text(
                    text = stringResource(R.string.home_week_empty),
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = OrbitTheme.spacing.x6, start = side, end = side)
                        .clip(OrbitTheme.shapes.md)
                        .background(OrbitTheme.colors.surface)
                        .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x2),
                )
            }
        }
    }
}

/**
 * A day's head: its letter and date, and the day's one TalkBack node (HOME-13):
 * "Wednesday 30 September, 2 calls: You called Sarah Chen at 6:40pm for
 * 14 min. ..." ([weekDayDescription]), or "Friday 2 October, No calls". The
 * node is the head, not the column under it, so it is always on screen
 * whatever the chart's scroll; the column and its blocks are pointer
 * shortcuts and stay out of TalkBack's way. A day with calls opens its sheet,
 * whose rows open each person, which is how TalkBack reaches a block's page.
 *
 * Today is set apart as on the strip, by weight and ink, never the accent
 * (rules.md Design 5).
 */
@Composable
private fun DayHead(
    date: LocalDate,
    day: RhythmDay,
    today: LocalDate,
    curtain: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val spokenDay = formatDayHeader(date, today).asString()
    val description = remember(spokenDay, day, curtain) {
        weekDayDescription(context, spokenDay, day.calls, curtain)
    }
    val seeDay = stringResource(R.string.home_rhythm_see_day)
    val isToday = date == today
    val ink = if (isToday) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted
    val weight = if (isToday) FontWeight.SemiBold else FontWeight.Normal
    val letter = remember(date) { date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
    val number = remember(date) { date.format(DateTimeFormatter.ofPattern("d", Locale.getDefault())) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.sm)
            .then(
                if (day.calls.isNotEmpty()) {
                    Modifier.clickable(onClickLabel = seeDay, onClick = onClick)
                } else {
                    Modifier
                },
            )
            // One node per day, named by the day: the merged letter and
            // number are drawn, the description is what TalkBack says.
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = OrbitTheme.spacing.x1),
    ) {
        Text(text = letter, style = OrbitTheme.type.micro.copy(color = ink, fontWeight = weight))
        Text(text = number, style = OrbitTheme.type.meta.copy(color = ink, fontWeight = weight))
    }
}

/**
 * The hour lines, every hour from the top edge (midnight) to the bottom
 * (midnight), the three-hourly ones a step stronger, the day boundaries as
 * faint verticals; and the labels every three hours, each sitting just above
 * its line at the start edge ([hourLabels]). Decorative to TalkBack: the day
 * heads say every time.
 */
@Composable
private fun HourGrid(use24Hour: Boolean, modifier: Modifier = Modifier) {
    val soft = OrbitTheme.colors.lineSoft
    val strong = OrbitTheme.colors.line
    val labels = remember(use24Hour) { hourLabels(use24Hour) }
    val labelStyle = OrbitTheme.type.timelineAxis.copy(color = OrbitTheme.colors.fgSubtle)
    val labelInset = OrbitTheme.spacing.x1
    val labelGap = OrbitTheme.spacing.hair
    Box(
        modifier.drawBehind {
            val stroke = GRID_STROKE.toPx()
            val hour = size.height / HOURS_IN_DAY
            for (h in 0..HOURS_IN_DAY) {
                val y = (h * hour).coerceIn(stroke / 2, size.height - stroke / 2)
                drawLine(if (h % 3 == 0) strong else soft, Offset(0f, y), Offset(size.width, y), stroke)
            }
            val dayWidth = size.width / DAYS_PER_PAGE
            for (d in 1 until DAYS_PER_PAGE) {
                val x = d * dayWidth
                drawLine(soft, Offset(x, 0f), Offset(x, size.height), stroke)
            }
        },
    ) {
        Layout(
            content = { labels.forEach { (_, text) -> Text(text = text, style = labelStyle, maxLines = 1) } },
            modifier = Modifier
                .matchParentSize()
                .clearAndSetSemantics { },
        ) { measurables, constraints ->
            val hourPx = constraints.maxHeight.toFloat() / HOURS_IN_DAY
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { i, label ->
                    val lineY = (labels[i].first * hourPx).roundToInt()
                    label.placeRelative(labelInset.roundToPx(), lineY - label.height - labelGap.roundToPx())
                }
            }
        }
    }
}

/**
 * One day's column of blocks ([placeDay]). Tapping the column away from a
 * block opens the day's sheet; tapping a block opens that person. Both are
 * pointer shortcuts with their semantics cleared, because the day's head is
 * its one TalkBack node and already says every call (HOME-13). Cleared
 * before `clickable`, so the click's own semantics go too (an outer clearing
 * modifier drops what the inner ones set).
 */
@Composable
private fun DayColumn(
    day: RhythmDay,
    curtain: Boolean,
    onOpenDay: () -> Unit,
    onOpenContact: (contactId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tapMin = OrbitTheme.spacing.tapMin
    // 48dp in minutes at this scale (72), and the strip's 14dp bar floor (21).
    val minTouch = ceil(tapMin / HOUR_HEIGHT * 60f).toInt()
    val minBlock = ceil(BLOCK_MIN / HOUR_HEIGHT * 60f).toInt()
    val placed = remember(day, minTouch, minBlock) { placeDay(day.calls, minBlock, minTouch) }
    val gap = OrbitTheme.spacing.hair
    BoxWithConstraints(
        modifier.then(
            if (day.calls.isEmpty()) {
                Modifier
            } else {
                Modifier
                    .clearAndSetSemantics { }
                    .clickable(onClick = onOpenDay)
            },
        ),
    ) {
        val columnWidth = maxWidth
        placed.forEach { p ->
            val laneWidth = (columnWidth - gap * (p.lanes - 1)) / p.lanes
            CallBlock(
                placed = p,
                curtain = curtain,
                onClick = { onOpenContact(p.call.contactId) },
                modifier = Modifier
                    .offset(x = (laneWidth + gap) * p.lane, y = HOUR_HEIGHT * (p.touchTop / 60f))
                    .size(width = laneWidth, height = HOUR_HEIGHT * ((p.touchBottom - p.touchTop) / 60f)),
            )
        }
    }
}

/**
 * One call: its touch target ([modifier], at least 48dp tall) holding the
 * drawn block, which is only as tall as the call (above the floor). Fill is
 * the person, rim is the direction, the strip's own [directionMark]. A block
 * long enough shows the first name, on a surface chip so it reads in every
 * theme: text straight on a person's colour fell to about 4:1 for some hues.
 * Never under the curtain (PRIV-03).
 */
@Composable
private fun CallBlock(
    placed: PlacedCall,
    curtain: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val call = placed.call
    Box(
        modifier
            .clearAndSetSemantics { testTag = weekCallTag(call.callEventId) }
            .clip(OrbitTheme.shapes.sm)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .offset(y = HOUR_HEIGHT * ((placed.top - placed.touchTop) / 60f))
                .padding(horizontal = OrbitTheme.spacing.hair)
                .fillMaxWidth()
                .height(HOUR_HEIGHT * ((placed.bottom - placed.top) / 60f))
                .directionMark(
                    rim = directionColor(call.direction),
                    separator = OrbitTheme.colors.directionSeparator,
                    corner = BLOCK_CORNER,
                )
                .background(OrbitTheme.tones.rhythmBarForId(call.contactId)),
        ) {
            val name = call.contactName?.takeUnless { curtain }
            if (name != null) BlockName(firstName = name.trim().substringBefore(' ').ifBlank { name })
        }
    }
}

/** The first name on a chip, placed only when the whole chip fits the block. */
@Composable
private fun BlockName(firstName: String) {
    val inset = OrbitTheme.spacing.hair
    Layout(
        content = {
            Text(
                text = firstName,
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fg),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(OrbitTheme.shapes.sm)
                    .background(OrbitTheme.colors.surface)
                    .padding(horizontal = OrbitTheme.spacing.x1),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val chip = measurables.single().measure(Constraints())
        val top = inset.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (chip.width <= constraints.maxWidth && chip.height + top * 2 <= constraints.maxHeight) {
                chip.placeRelative((constraints.maxWidth - chip.width) / 2, top)
            }
        }
    }
}

@Composable
private fun sideMargin(): Dp =
    if (LocalConfiguration.current.screenWidthDp < 380) OrbitTheme.spacing.x3 else OrbitTheme.spacing.x4

/** A block's touch target in tests: its semantics are cleared, so it is found by tag. Not copy. */
internal fun weekCallTag(callEventId: Long): String = "week-call-$callEventId"

// About 40dp an hour, as the owner's "tall enough to read" was sized: a
// 14-minute call is about 9dp, an hour 40dp, the day 960dp.
private val HOUR_HEIGHT: Dp = 40.dp
private const val HOURS_IN_DAY = 24
private const val DAYS_PER_PAGE = 7

// The strip's bar floor (RHYTHM_BAR_MIN): 3dp of rim and 1.5dp of ring top
// and bottom leave 5dp of the person's colour.
private val BLOCK_MIN: Dp = 14.dp
private val BLOCK_CORNER: Dp = 6.dp
private val GRID_STROKE: Dp = 1.dp
private const val QUIET_WEEK_START_MINUTE = 8 * 60
private const val SCROLL_MARGIN_MINUTES = 30

// ---- Previews (THEME-05: one per state) ----

private val previewToday: LocalDate = LocalDate.of(2026, 10, 7)

private fun previewCall(
    id: Long,
    contactId: Long,
    name: String,
    hour: Int,
    minute: Int,
    minutes: Int,
    direction: CallDirection,
    use24Hour: Boolean,
) = RhythmCall(
    callEventId = id,
    contactId = contactId,
    contactName = name,
    photoUri = null,
    durationSeconds = minutes * 60,
    direction = direction,
    durationLabel = formatDuration(minutes * 60),
    timeLabel = formatClockTime(LocalTime.of(hour, minute), use24Hour),
    minuteOfDay = hour * 60 + minute,
)

private fun previewBusyWeek(use24Hour: Boolean): WeekPage {
    fun c(id: Long, who: Long, name: String, h: Int, m: Int, len: Int, dir: CallDirection) =
        previewCall(id, who, name, h, m, len, dir, use24Hour)
    val out = CallDirection.OUTGOING
    val inc = CallDirection.INCOMING
    return WeekPage(
        firstDay = previewToday.minusDays(6),
        days = listOf(
            RhythmDay(listOf(c(1, 1, "Kai Mensah", 8, 15, 14, out))),
            RhythmDay(emptyList()),
            RhythmDay(listOf(c(2, 2, "Mara Ellis", 13, 15, 9, inc), c(3, 3, "Sam Okafor", 18, 40, 52, out))),
            // Two short calls close together: side by side, each a full target.
            RhythmDay(listOf(c(4, 1, "Kai Mensah", 12, 0, 3, out), c(5, 2, "Mara Ellis", 12, 20, 25, inc))),
            RhythmDay(listOf(c(6, 3, "Sam Okafor", 21, 30, 41, inc))),
            RhythmDay(emptyList()),
            RhythmDay(listOf(c(7, 1, "Kai Mensah", 9, 5, 26, out), c(8, 2, "Mara Ellis", 9, 20, 64, inc))),
        ),
    )
}

private fun previewState(use24Hour: Boolean = false, empty: Boolean = false) = WeekUiState.Ready(
    listName = "Inner orbit",
    today = previewToday,
    weeks = listOf(
        if (empty) {
            WeekPage(previewToday.minusDays(6), List(7) { RhythmDay(emptyList()) })
        } else {
            previewBusyWeek(use24Hour)
        },
        WeekPage(previewToday.minusDays(13), List(7) { RhythmDay(emptyList()) }),
    ),
)

@PreviewLightDark
@Composable
private fun WeekContentPreview() {
    OrbitTheme { WeekContent(state = previewState(), onBack = {}, use24Hour = false) }
}

@Preview(name = "200%", fontScale = 2f)
@Preview(name = "200% dark", fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WeekContentLargeTextPreview() {
    OrbitTheme { WeekContent(state = previewState(), onBack = {}, use24Hour = false) }
}

@PreviewLightDark
@Composable
private fun WeekContent24HourPreview() {
    OrbitTheme { WeekContent(state = previewState(use24Hour = true), onBack = {}, use24Hour = true) }
}

// An earlier week: the heading is its dates, and "This week" comes back.
@PreviewLightDark
@Composable
private fun WeekContentEarlierWeekPreview() {
    OrbitTheme { WeekContent(state = previewState(), onBack = {}, use24Hour = false, initialPage = 1) }
}

@PreviewLightDark
@Composable
private fun WeekContentEmptyPreview() {
    OrbitTheme { WeekContent(state = previewState(empty = true), onBack = {}, use24Hour = false) }
}

// PRIV-03: no name on a block, "List" in the app bar, "someone" to TalkBack.
@PreviewLightDark
@Composable
private fun WeekContentCurtainPreview() {
    CompositionLocalProvider(LocalPrivacyCurtain provides true) {
        OrbitTheme { WeekContent(state = previewState(), onBack = {}, use24Hour = false) }
    }
}

@PreviewLightDark
@Composable
private fun WeekContentLoadingPreview() {
    OrbitTheme { WeekContent(state = WeekUiState.Loading, onBack = {}) }
}

@PreviewLightDark
@Composable
private fun WeekContentErrorPreview() {
    OrbitTheme { WeekContent(state = WeekUiState.Error(canRetry = true), onBack = {}) }
}

// A list id that never parsed, or a list that is gone: Go back alone.
@PreviewLightDark
@Composable
private fun WeekContentGoBackPreview() {
    OrbitTheme { WeekContent(state = WeekUiState.Error(canRetry = false), onBack = {}) }
}
