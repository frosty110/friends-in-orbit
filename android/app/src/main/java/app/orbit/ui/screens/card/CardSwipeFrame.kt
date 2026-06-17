package app.orbit.ui.screens.card

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/**
 * Card View's swipe container — three-anchor `anchoredDraggable`, derived from
 * the generic SwipeableCard component (deleted 2026-06-09) but with the
 * contract the card loop actually needs:
 *
 *   - **No snap-back flash.** After a committed swipe the card HOLDS at the
 *     off-screen anchor instead of teleporting the old contact back to center
 *     while the DB round-trip is in flight. When the next emission lands the
 *     frame re-centers: instantly (the caller's crossfade covers the face
 *     change) when the surfaced contact changed, animated via
 *     [OrbitMotion.springCardCommit] when the same contact legitimately
 *     returns (e.g. a single-member list).
 *   - **Programmatic swipes.** [CardSwipeFrameState.requestSwipeLeft] /
 *     [CardSwipeFrameState.requestSwipeRight] animate to the anchor through
 *     the same settle path as a drag, so button- and text-initiated actions
 *     get identical motion + haptic + exactly-once callback semantics.
 *   - **Fresh callbacks.** `onSwipeLeft` / `onSwipeRight` are read through
 *     [rememberUpdatedState] inside the long-lived settle collector, so a
 *     commit always dispatches against the currently surfaced contact.
 *   - **Stuck-card guard.** If a commit produces no emission at all (e.g. a
 *     mutation race), the frame animates back to center after a short hold
 *     rather than leaving a hole where the card was.
 *
 * Shared invariants carried over from SwipeableCard: `state.offset` is read
 * only in `graphicsLayer` / the ghost-overlay run-block (never composition),
 * anchors register via `onSizeChanged`, haptic fires exactly once per commit
 * off `snapshotFlow { settledValue }`, and the a11y custom actions mirror the
 * two gestures.
 */
@Stable
internal class CardSwipeFrameState {
    internal val requests = MutableSharedFlow<CardAnchor>(extraBufferCapacity = 1)

    /** Animate the card to the left (defer) anchor — same path as a drag. */
    fun requestSwipeLeft() {
        requests.tryEmit(CardAnchor.Left)
    }

    /** Animate the card to the right (sooner) anchor — same path as a drag. */
    fun requestSwipeRight() {
        requests.tryEmit(CardAnchor.Right)
    }
}

internal enum class CardAnchor { Left, Center, Right }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CardSwipeFrame(
    contactKey: Long,
    emissionKey: Any?,
    frameState: CardSwipeFrameState,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    modifier: Modifier = Modifier,
    ghostOverlay: @Composable BoxScope.(offsetFraction: Float) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)

    val state = remember {
        AnchoredDraggableState(
            initialValue = CardAnchor.Center,
            positionalThreshold = { totalDistance -> totalDistance * 0.3f },
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            snapAnimationSpec = OrbitMotion.springCardCommit,
            decayAnimationSpec = exponentialDecay(),
        )
    }

    // Commit handler — haptic + exactly-once callback. Deliberately NO
    // snapTo(Center) here: the card holds off-screen until the next emission.
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }
            .drop(1)
            .collect { newValue ->
                when (newValue) {
                    CardAnchor.Left -> {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        currentOnSwipeLeft()
                    }
                    CardAnchor.Right -> {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        currentOnSwipeRight()
                    }
                    CardAnchor.Center -> Unit
                }
            }
    }

    // Emission-driven re-center. Runs on every new emission (and contact
    // change); a no-op while the card already rests at center.
    var lastContactKey by remember { mutableStateOf(contactKey) }
    LaunchedEffect(contactKey, emissionKey) {
        val contactChanged = contactKey != lastContactKey
        lastContactKey = contactKey
        if (state.settledValue != CardAnchor.Center) {
            if (contactChanged) {
                // New face — return instantly; the caller's crossfade owns
                // the visible transition so the old card never flashes back.
                state.snapTo(CardAnchor.Center)
            } else {
                // Same contact re-surfaced (single-member list, undo, race) —
                // bring the card back with the standard commit motion.
                state.animateTo(CardAnchor.Center)
            }
        }
    }

    // Stuck-card guard — a commit whose mutation produced no emission would
    // otherwise leave the frame empty forever. collectLatest cancels the
    // window whenever the settle value changes (including our own re-center).
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collectLatest { settled ->
            if (settled != CardAnchor.Center) {
                delay(RECENTER_GUARD_MS)
                if (state.settledValue != CardAnchor.Center) {
                    state.animateTo(CardAnchor.Center)
                }
            }
        }
    }

    // Button / text-affordance swipes — only from rest, so a held-off-screen
    // card can't double-fire while its mutation is in flight.
    LaunchedEffect(state, frameState) {
        frameState.requests.collect { anchor ->
            if (state.settledValue == CardAnchor.Center && anchor != CardAnchor.Center) {
                state.animateTo(anchor)
            }
        }
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .onSizeChanged { size ->
                    state.updateAnchors(
                        DraggableAnchors {
                            CardAnchor.Left at -size.width.toFloat()
                            CardAnchor.Center at 0f
                            CardAnchor.Right at size.width.toFloat()
                        }
                    )
                }
                .graphicsLayer {
                    val offset = state.offset
                    if (!offset.isNaN()) {
                        translationX = offset
                        val width = size.width.takeIf { it > 0 } ?: 1f
                        rotationZ = ((offset / width) * 8f).coerceIn(-8f, 8f)
                        alpha = (1f - (abs(offset) / width).coerceAtMost(0.3f))
                    }
                }
                .anchoredDraggable(state, Orientation.Horizontal)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(label = "Surface sooner") {
                            currentOnSwipeRight(); true
                        },
                        CustomAccessibilityAction(label = "Defer") {
                            currentOnSwipeLeft(); true
                        },
                    )
                },
            content = content,
        )
        // Ghost-hint overlay slot — sits OUTSIDE the rotated/translated card
        // so chips stay anchored to the screen edges. Non-composition offset
        // read (primitive Float local), same as SwipeableCard.
        val offsetFraction = run {
            val raw = state.offset
            val safeRaw = if (raw.isNaN()) 0f else raw
            val anchorPx = state.anchors.positionOf(CardAnchor.Right)
            val divisor = if (anchorPx.isFinite() && anchorPx > 0f) anchorPx else 1f
            (safeRaw / divisor).coerceIn(-1f, 1f)
        }
        ghostOverlay(offsetFraction)
    }
}

/**
 * Hold window before a committed-but-unanswered swipe brings the card back.
 * Comfortably longer than a Room round-trip; short enough that a genuinely
 * failed mutation doesn't strand an empty frame.
 */
private const val RECENTER_GUARD_MS: Long = 900L

@PreviewLightDark
@Composable
private fun CardSwipeFramePreview() {
    OrbitTheme {
        CardSwipeFrame(
            contactKey = 1L,
            emissionKey = Unit,
            frameState = remember { CardSwipeFrameState() },
            onSwipeLeft = {},
            onSwipeRight = {},
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OrbitTheme.colors.surface),
            )
        }
    }
}
