package app.orbit.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.LocalReducedMotion
import app.orbit.ui.theme.OrbitTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Picks a whole number by scrolling a row of values toward the one you want,
 * the value under the centre band being the choice (ADR 0011). It replaced the
 * day sliders, whose linear track put the 2-day default flush against the
 * "1 day" end and made a 30-day aim a 1-pixel target (ADR 0010 saw the same
 * screenshot read as a bug three times).
 *
 * **Horizontal, not the iOS-style vertical drum.** Every place this sits is a
 * vertically scrolling settings page that saves as you change it. A vertical
 * wheel there takes the page's own scroll gesture: a thumb that lands on it to
 * scroll the page spins the value instead, and the change saves. A sideways
 * row never competes with the page.
 *
 * - **Fling and snap.** A fling coasts and settles with a value centred
 *   ([rememberSnapFlingBehavior]); a tap on any value brings it to the centre.
 * - **One write per gesture.** [onValueChange] follows the centre live, for
 *   the sentence above to read along; [onValueCommit] fires once, when the row
 *   comes to rest on a value that differs from the last one committed (the
 *   save-on-change screens write on it). The last commit is tracked here, not
 *   read back from [value], because the saved value only returns after Room
 *   round-trips the write (the old day field committed twice for that reason).
 * - **A haptic tick per value** as it crosses the centre, the way a physical
 *   detent feels (`SegmentFrequentTick`, the platform's tick for a fast run of
 *   detents such as a scroll wheel).
 * - **TalkBack hears one adjustable control**, like a slider: its name
 *   ([label]), its value in words ([valueDescription]), and swipe up or down
 *   to move one value. The values inside are not separate stops.
 * - **Keyboard and D-pad:** left and right (or minus and plus) step it.
 * - **No accent** (rules.md §Design 5): the centred value is ink on the quiet
 *   `bgSubtle` band; the rest are `fgMuted`. The far edges fade out as the row
 *   runs under them, which is decoration on values half out of view.
 * - **48dp tall at least, and as wide as the widest value needs** at the
 *   current font scale, so 200% text never clips (rules.md §Design 2, 3).
 *
 * [caption] puts a quiet word under landmark values ("2 weeks" under 14) so a
 * row of bare numbers still says where the familiar rhythms are.
 *
 * [valueIsSet] false says [value] is only where the row opens, not a stored
 * value (List settings for a list with no rhythm yet, LIST-30). Then choosing
 * it, by a tap or by coming to rest on it, commits it too: otherwise the one
 * value a person could not pick would be the one it opened on.
 */
@Composable
fun OrbitWheelPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    onValueCommit: (Int) -> Unit,
    label: String,
    valueDescription: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
    caption: @Composable (Int) -> String? = { null },
    valueIsSet: Boolean = true,
) {
    require(!range.isEmpty()) { "OrbitWheelPicker needs at least one value" }
    val count = range.last - range.first + 1
    fun indexOf(v: Int) = (v.coerceIn(range) - range.first)

    val state = rememberLazyListState(initialFirstVisibleItemIndex = indexOf(value))
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reducedMotion = LocalReducedMotion.current
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestOnCommit by rememberUpdatedState(onValueCommit)

    val centredIndex by remember(state) { derivedStateOf { state.layoutInfo.centredIndex(state) } }
    val centredValue = range.first + centredIndex.coerceIn(0, count - 1)

    // The value this control last wrote, or last received from outside; none
    // while [value] is only where it opened ([valueIsSet] false).
    var lastCommitted by remember { mutableIntStateOf(if (valueIsSet) value else NOTHING_COMMITTED) }

    // A value arriving from outside (the screen loaded, another surface saved)
    // moves the row there at once: it is not a gesture, so it neither ticks
    // nor commits.
    LaunchedEffect(value, range, valueIsSet) {
        lastCommitted = if (valueIsSet) value else NOTHING_COMMITTED
        if (!state.isScrollInProgress && centredIndex != indexOf(value)) {
            state.scrollToItem(indexOf(value))
        }
    }

    // Live: the sentence above follows the centre, with a tick per value.
    LaunchedEffect(state) {
        snapshotFlowOf { centredIndex }
            .distinctUntilChanged()
            .drop(1)
            .collect { index ->
                if (state.isScrollInProgress) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                latestOnChange(range.first + index)
            }
    }

    // Commit when a drag or fling comes to rest.
    LaunchedEffect(state) {
        snapshotFlowOf { state.isScrollInProgress }
            .distinctUntilChanged()
            .filter { inProgress -> !inProgress }
            .collect {
                val settled = range.first + centredIndex
                if (settled != lastCommitted) {
                    lastCommitted = settled
                    latestOnCommit(settled)
                }
            }
    }

    // Taps, TalkBack and keys land here: scroll there, then commit directly.
    // An instant scroll can start and stop inside one frame, too fast for the
    // at-rest watcher above to see, so these do not rely on it.
    fun select(target: Int) {
        val v = target.coerceIn(range)
        scope.launch {
            if (reducedMotion) state.scrollToItem(indexOf(v)) else state.animateScrollToItem(indexOf(v))
            latestOnChange(v)
            if (v != lastCommitted) {
                lastCommitted = v
                latestOnCommit(v)
            }
        }
    }

    val c = OrbitTheme.colors
    val numberStyle = OrbitTheme.type.title
    val captionStyle = OrbitTheme.type.micro
    val description = valueDescription(centredValue)

    // As wide as the widest value needs at this font scale, never under 56dp.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(range, numberStyle, density) {
        listOf(range.first, range.last).maxOf { measurer.measure(it.toString(), numberStyle).size.width }
    }
    val itemWidth: Dp = maxOf(MinItemWidth, with(density) { widest.toDp() } + OrbitTheme.spacing.x4)

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .focusable(interactionSource = interaction)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.Minus, Key.NumPadSubtract -> { select(centredValue - 1); true }
                    Key.DirectionRight, Key.Plus, Key.NumPadAdd -> { select(centredValue + 1); true }
                    else -> false
                }
            }
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = centredValue.toFloat(),
                    range = range.first.toFloat()..range.last.toFloat(),
                    // One stop per value, so TalkBack's adjust moves exactly one.
                    steps = (count - 2).coerceAtLeast(0),
                )
                setProgress { target ->
                    select(target.roundToInt())
                    true
                }
            },
    ) {
        val sidePadding = ((maxWidth - itemWidth) / 2).coerceAtLeast(0.dp)
        val bandShape = OrbitTheme.shapes.md
        val ringWidth = with(density) { 2.dp.toPx() }

        LazyRow(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = sidePadding),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // The centre band, where the chosen value sits: drawn behind
                // the row at the row's own height (a lazy row has no
                // intrinsic height to size a sibling by), outside the fade
                // below so it stays solid. Quiet, not accent; an ink ring
                // under keyboard focus.
                .drawBehind {
                    val bandWidth = itemWidth.toPx()
                    translate(left = (size.width - bandWidth) / 2f) {
                        val outline = bandShape.createOutline(Size(bandWidth, size.height), layoutDirection, this)
                        drawOutline(outline, color = c.bgSubtle)
                        if (focused) drawOutline(outline, color = c.fg, style = Stroke(width = ringWidth))
                    }
                }
                // Fade the far edges out. The mask colours are an alpha ramp,
                // not visible colours, so they are not theme tokens.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            EdgeFade to Color.Black,
                            1f - EdgeFade to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            items(count = count, key = { index -> range.first + index }) { index ->
                val v = range.first + index
                val isCentred = index == centredIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(itemWidth)
                        .heightIn(min = OrbitTheme.spacing.tapMin)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = { select(v) },
                        )
                        .graphicsLayer {
                            // Values shrink a little as they leave the centre;
                            // tied to the finger, so it is feedback, not motion.
                            val s = state.layoutInfo.itemScale(index)
                            scaleX = s
                            scaleY = s
                        }
                        .padding(vertical = OrbitTheme.spacing.x1),
                ) {
                    Text(
                        text = v.toString(),
                        style = numberStyle,
                        color = if (isCentred) c.fg else c.fgMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    caption(v)?.let { words ->
                        Text(
                            text = words,
                            style = captionStyle,
                            color = c.fgSubtle,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            // Wider than its value is fine: landmarks are far apart.
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                }
            }
        }
    }
}

/** Narrowest a value slot gets, at the default font scale. */
private val MinItemWidth = 56.dp

/** Fraction of the row's width over which each edge fades out. */
private const val EdgeFade = 0.18f

/** Smallest a value gets, far from the centre. */
private const val MinScale = 0.82f

/**
 * The index of the item whose centre is nearest the viewport's centre. Item
 * offsets and the viewport bounds share one coordinate space (the viewport's
 * start is negative by the leading content padding), so the midpoint of the
 * two bounds is the visual centre.
 */
internal fun LazyListLayoutInfo.centredIndex(state: LazyListState): Int {
    val centre = (viewportStartOffset + viewportEndOffset) / 2
    return visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - centre) }?.index
        ?: state.firstVisibleItemIndex
}

/** 1 at the centre, easing down to [MinScale] one and a half slots away. */
private fun LazyListLayoutInfo.itemScale(index: Int): Float {
    val item = visibleItemsInfo.firstOrNull { it.index == index } ?: return MinScale
    val centre = (viewportStartOffset + viewportEndOffset) / 2f
    val distance = abs(item.offset + item.size / 2f - centre) / item.size.coerceAtLeast(1)
    return (1f - (1f - MinScale) * (distance / 1.5f)).coerceIn(MinScale, 1f)
}

/** `snapshotFlow`, named for what it reads here. */
private fun <T> snapshotFlowOf(block: () -> T) = androidx.compose.runtime.snapshotFlow(block)

/** Below any range: nothing committed yet, so the first choice always commits. */
private const val NOTHING_COMMITTED = Int.MIN_VALUE

@Preview(name = "OrbitWheelPicker, light", showBackground = true)
@Composable
private fun OrbitWheelPickerPreviewLight() {
    OrbitTheme(darkTheme = false) {
        OrbitWheelPicker(
            value = 14,
            range = 1..60,
            onValueChange = {},
            onValueCommit = {},
            label = "How often to aim for",
            valueDescription = { "Every $it days" },
            caption = { if (it == 14) "2 weeks" else if (it == 7) "1 week" else null },
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "OrbitWheelPicker, dark", showBackground = true)
@Composable
private fun OrbitWheelPickerPreviewDark() {
    OrbitTheme(darkTheme = true) {
        OrbitWheelPicker(
            value = 2,
            range = 1..60,
            onValueChange = {},
            onValueCommit = {},
            label = "How often to aim for",
            valueDescription = { "Every $it days" },
        )
    }
}
