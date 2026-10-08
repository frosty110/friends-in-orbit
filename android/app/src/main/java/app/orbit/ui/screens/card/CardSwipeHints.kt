package app.orbit.ui.screens.card

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.LocalReducedMotion
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.asString
import kotlinx.coroutines.delay

/*
 * CARD-09: hints that teach the swipe (owner review 2026-10-07, decision 4).
 *
 * "Show users what those two symbols mean ... arrows that say what the
 * swiping does for that person ... it fades away and then maybe fades back
 * in ... a little reminder thing." When the card sits untouched, "Later ·
 * Thursday" with an arrow pointing left and "Sooner · Tomorrow" with an arrow
 * pointing right fade in at the card's top corners, hold, and fade out;
 * they come back a few times while nothing happens, and stop for good once
 * the swipe is learnt.
 *
 * Why this is not the idle motion rules.md Design 8 forbids ("no motion on
 * idle surfaces", CORE-09, and the June vision's "a card that stirs when you
 * stall reads as anxious"): the card itself never moves, only two quiet
 * labels change opacity, at most [SwipeHintTiming.MAX_PER_PERSON] times for
 * each person shown and never again after the user has made
 * `SWIPE_HINT_MOVES_TO_LEARN` moves (CardViewViewModel), with nothing that
 * loops forever. It is an exception the owner decided, recorded with CARD-09
 * in features/card-view/README.md. With animations off they appear and go
 * without fading.
 *
 * Decoration for sighted touch users only: the overlay is cleared from the
 * semantics tree, because TalkBack users already have the labelled Later and
 * Sooner buttons and the card face's custom actions. It spends no accent (the
 * Call button is the card's one, rules.md Design 5).
 */

/** CARD-09's timing, in one place. */
internal object SwipeHintTiming {
    /** How long the card must sit untouched before the hints first show. */
    const val IDLE_MS: Long = 4_000L

    /** How long the hints stay at full strength once faded in. */
    const val HOLD_MS: Long = 2_500L

    /** From one appearance to the next while nothing happens. */
    const val EVERY_MS: Long = 12_000L

    /** At most this many appearances for each person the card shows. */
    const val MAX_PER_PERSON: Int = 3

    /** The fade in and out: `motion.durSlow`, the calm end of rules.md Design 8's range. */
    const val FADE_MS: Int = OrbitMotion.DurSlowMs
}

/**
 * When the hints show, for one person on the card. Plain state with a
 * suspending clock ([runIdleClock]) so the timing is tested on virtual time
 * without a frame clock; the overlay turns [visible] into opacity.
 *
 * One owner (rules.md Code 7): [rememberSwipeHints] remembers one per person,
 * keyed by the contact id, and is the only caller of [runIdleClock] and
 * [hideAtOnce].
 */
@Stable
internal class SwipeHintsState(timesShown: Int = 0) {

    /** What the hints say; null until they have shown once. */
    var hints: CardMoveHints? by mutableStateOf(null)
        private set

    /** Whether the hints should be showing now. */
    var visible: Boolean by mutableStateOf(false)
        private set

    /** True when the last hide was a touch: the hints go at once, with no fade. */
    var hiddenAtOnce: Boolean by mutableStateOf(false)
        private set

    /** Appearances so far for this person, kept across rotation ([Saver]). */
    var timesShown: Int by mutableIntStateOf(timesShown)
        private set

    /** Any touch on the card: the hints go at once. The caller then restarts [runIdleClock]. */
    fun hideAtOnce() {
        hiddenAtOnce = true
        visible = false
    }

    /**
     * The idle clock: waits [SwipeHintTiming.IDLE_MS], then shows the hints,
     * holds them, fades them and comes back every [SwipeHintTiming.EVERY_MS]
     * until this person has seen them [SwipeHintTiming.MAX_PER_PERSON] times.
     * Cancelling it (a touch, the screen pausing) restarts the idle clock on
     * the next run.
     *
     * [fetch] says what to show, asked fresh before each appearance so the
     * "when" is the one the move would get at that moment; null means no
     * hints (the swipe is learnt), and the clock stops.
     */
    suspend fun runIdleClock(fetch: suspend () -> CardMoveHints?) {
        delay(SwipeHintTiming.IDLE_MS)
        while (timesShown < SwipeHintTiming.MAX_PER_PERSON) {
            val next = fetch()
            if (next == null) {
                // Learnt (or unreadable): nothing more for this person, so a
                // later touch does not ask again.
                timesShown = SwipeHintTiming.MAX_PER_PERSON
                return
            }
            hints = next
            hiddenAtOnce = false
            visible = true
            timesShown += 1
            delay(SwipeHintTiming.FADE_MS + SwipeHintTiming.HOLD_MS)
            visible = false
            if (timesShown >= SwipeHintTiming.MAX_PER_PERSON) return
            delay(SwipeHintTiming.EVERY_MS - SwipeHintTiming.FADE_MS - SwipeHintTiming.HOLD_MS)
        }
    }

    companion object {
        /** Keeps the count across rotation, so turning the phone does not buy three more. */
        val Saver: Saver<SwipeHintsState, Int> = Saver(
            save = { it.timesShown },
            restore = { SwipeHintsState(timesShown = it) },
        )
    }
}

/**
 * The idle clock for the person on the card. Returns the [SwipeHintsState]
 * the overlay draws, and a modifier for the card's whole area that hears
 * every touch (on the initial pass, consuming nothing, so the drag and the
 * buttons get it as before): a press hides the hints at once and holds the
 * clock, and lifting the last finger starts it again from zero.
 *
 * The clock runs only while [active] (the caller passes false while the Log
 * a connection sheet or the list menu is over the card: the menu's button
 * and popup are outside this modifier, so no touch here would hold it) and
 * while the screen is resumed, so hints never spend their three appearances
 * while the dialer is in front. Going inactive hides them at once; coming
 * back starts the idle wait from zero.
 */
@Composable
internal fun rememberSwipeHints(
    contactId: Long,
    active: Boolean,
    fetch: suspend (contactId: Long) -> CardMoveHints?,
): Pair<SwipeHintsState, Modifier> {
    val state = rememberSaveable(contactId, saver = SwipeHintsState.Saver) { SwipeHintsState() }
    val currentFetch by rememberUpdatedState(fetch)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var touching by remember(contactId) { mutableStateOf(false) }
    var touchEpoch by remember(contactId) { mutableIntStateOf(0) }

    LaunchedEffect(state, touchEpoch, active && resumed && !touching) {
        if (!active || !resumed || touching) {
            state.hideAtOnce()
            return@LaunchedEffect
        }
        state.runIdleClock { currentFetch(contactId) }
    }

    val touchModifier = Modifier.pointerInput(contactId) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Press -> {
                        state.hideAtOnce()
                        touching = true
                    }
                    PointerEventType.Release -> if (event.changes.none { it.pressed }) {
                        touching = false
                        touchEpoch += 1
                    }
                    else -> Unit
                }
            }
        }
    }
    return state to touchModifier
}

/**
 * The two hints, drawn in [CardSwipeFrame]'s ghost slot so they sit still
 * over the card's top corners while the card never moves. [pinned] shows
 * them at full strength with no clock (the previews, so the gallery and its
 * audits see them).
 */
@Composable
internal fun BoxScope.SwipeHintsOverlay(state: SwipeHintsState?, pinned: CardMoveHints?) {
    val hints = pinned ?: state?.hints ?: return
    val reducedMotion = LocalReducedMotion.current
    val alpha = remember { Animatable(if (pinned != null) 1f else 0f) }
    if (pinned == null && state != null) {
        LaunchedEffect(state.visible, state.hiddenAtOnce, reducedMotion) {
            val target = if (state.visible) 1f else 0f
            when (val fade = hintFade(state.visible, state.hiddenAtOnce, reducedMotion)) {
                null -> alpha.snapTo(target)
                else -> alpha.animateTo(target, fade)
            }
        }
    }
    val later = hints.later.asString()
    val sooner = hints.sooner.asString()
    // Along the card's top edge, over the list chip and the avatar: the one
    // band of the face that never carries the person's name or why now. Found
    // by rendering: centred, the pills sat on the name at 200% text, and at
    // the bottom they did in landscape, where the short face ends at the name.
    HintPair(
        gap = OrbitTheme.spacing.x2,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(start = OrbitTheme.spacing.x2, end = OrbitTheme.spacing.x2, top = OrbitTheme.spacing.x3)
            // Opacity read in the draw phase: the fades never recompose.
            .graphicsLayer { this.alpha = alpha.value }
            // Decoration: TalkBack has the labelled buttons and the face's
            // actions, so nothing here reaches the semantics tree.
            .clearAndSetSemantics { },
        later = { HintPill(icon = "arrow-left", label = later, iconFirst = true) },
        sooner = { HintPill(icon = "arrow-right", label = sooner, iconFirst = false) },
    )
}

/**
 * Later at the start edge and Sooner at the end edge, on one line when both
 * fit at their full width, otherwise Later on the first line and Sooner on
 * the next. Each may take the whole width, so a word never breaks letter by
 * letter: two half-width boxes split "Thursday" and "Tomorrow" mid-word at
 * 200% text in landscape.
 */
@Composable
private fun HintPair(
    gap: Dp,
    modifier: Modifier,
    later: @Composable () -> Unit,
    sooner: @Composable () -> Unit,
) {
    Layout(contents = listOf(later, sooner), modifier = modifier) { (laterParts, soonerParts), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val laterPill = laterParts.first().measure(loose)
        val soonerPill = soonerParts.first().measure(loose)
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val oneLine = laterPill.width + gapPx + soonerPill.width <= width
        val height = if (oneLine) {
            maxOf(laterPill.height, soonerPill.height)
        } else {
            laterPill.height + gapPx + soonerPill.height
        }
        layout(width, height) {
            laterPill.placeRelative(0, 0)
            soonerPill.placeRelative(width - soonerPill.width, if (oneLine) 0 else laterPill.height + gapPx)
        }
    }
}

/**
 * How the hints' opacity changes: a fade in (`easeOut`, an entrance) or out
 * (`easeInOut`) over [SwipeHintTiming.FADE_MS], or null for a change in the
 * same frame: always with animations off (rules.md Design 8, WCAG 2.3.3),
 * and for a hide a touch caused, which must not linger under the finger.
 */
internal fun hintFade(show: Boolean, hiddenAtOnce: Boolean, reducedMotion: Boolean): AnimationSpec<Float>? =
    when {
        reducedMotion -> null
        show -> tween(SwipeHintTiming.FADE_MS, easing = OrbitMotion.EaseOut)
        hiddenAtOnce -> null
        else -> tween(SwipeHintTiming.FADE_MS, easing = OrbitMotion.EaseInOut)
    }

/**
 * One hint: an arrow and its words in a quiet pill, the neutral look of the
 * Later and Sooner buttons beneath (`bgSubtle` with a `line` edge, words in
 * `fg`). [HintPair] places the two.
 */
@Composable
private fun HintPill(icon: String, label: String, iconFirst: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
        modifier = Modifier
            .clip(OrbitTheme.shapes.full)
            .background(OrbitTheme.colors.bgSubtle)
            .border(1.dp, OrbitTheme.colors.line, OrbitTheme.shapes.full)
            .padding(horizontal = OrbitTheme.spacing.x3, vertical = OrbitTheme.spacing.x2),
    ) {
        if (iconFirst) PhIcon(name = icon, size = 16.dp, tint = OrbitTheme.colors.fgMuted)
        Text(text = label, style = OrbitTheme.type.meta, color = OrbitTheme.colors.fg)
        if (!iconFirst) PhIcon(name = icon, size = 16.dp, tint = OrbitTheme.colors.fgMuted)
    }
}
