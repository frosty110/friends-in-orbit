package app.orbit.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

// Durations in ms — --dur-fast/base/slow.
object OrbitMotion {
    const val DurFastMs = 150
    const val DurBaseMs = 250
    const val DurSlowMs = 350

    // CSS cubic-bezier(0.22, 1, 0.36, 1) — entrances.
    val EaseOut: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    // CSS cubic-bezier(0.65, 0, 0.35, 1) — layout shifts.
    val EaseInOut: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    // CSS cubic-bezier(0.34, 1.26, 0.64, 1) — swipe commit, gentle spring.
    val EaseSpring: Easing = CubicBezierEasing(0.34f, 1.26f, 0.64f, 1f)

    /**
     * MT-07 — the single spring used by CardSwipeFrame's snap animation and any
     * direct animateTo call on the card. Tuned to land between DurBaseMs (250ms)
     * and DurSlowMs (350ms) with <=5% overshoot per CORE-09 and rules.md
     * Design 1. Drop to DampingRatioNoBouncy only after motion scrub confirms
     * overshoot exceeds 5%.
     */
    val springCardCommit: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
