package app.orbit.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry
import app.orbit.ui.theme.OrbitMotion

/**
 * Screen-change motion for the whole graph (rules.md Design 4).
 *
 * Forward: the new screen slides a short way in from the end edge and fades
 * in over [OrbitMotion.DurSlowMs]; the old one drifts toward the start and
 * fades out over [OrbitMotion.DurBaseMs]. Back reverses the direction, so
 * where you are in the app has a direction. The slide is an eighth of the
 * width, a hint rather than a full push: Orbit is calm, not a carousel.
 *
 * Navigation's default was a 700ms crossfade with no direction, twice the
 * house limit. With predictive back enabled in the manifest, Navigation seeks
 * the pop transitions under the user's finger.
 *
 * [reducedMotion] (Settings > Accessibility > Remove animations) swaps every
 * transition for an instant cut (WCAG 2.3.3).
 */
internal class OrbitNavMotion(private val reducedMotion: Boolean) {

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reducedMotion) {
            EnterTransition.None
        } else {
            slideIntoContainer(SlideDirection.Start, tween(OrbitMotion.DurSlowMs, easing = OrbitMotion.EaseOut)) { it / SLIDE_FRACTION } +
                fadeIn(tween(OrbitMotion.DurBaseMs, easing = OrbitMotion.EaseOut))
        }
    }

    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reducedMotion) {
            ExitTransition.None
        } else {
            slideOutOfContainer(SlideDirection.Start, tween(OrbitMotion.DurBaseMs, easing = OrbitMotion.EaseInOut)) { it / SLIDE_FRACTION } +
                fadeOut(tween(OrbitMotion.DurFastMs))
        }
    }

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reducedMotion) {
            EnterTransition.None
        } else {
            slideIntoContainer(SlideDirection.End, tween(OrbitMotion.DurSlowMs, easing = OrbitMotion.EaseOut)) { it / SLIDE_FRACTION } +
                fadeIn(tween(OrbitMotion.DurBaseMs, easing = OrbitMotion.EaseOut))
        }
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reducedMotion) {
            ExitTransition.None
        } else {
            slideOutOfContainer(SlideDirection.End, tween(OrbitMotion.DurBaseMs, easing = OrbitMotion.EaseInOut)) { it / SLIDE_FRACTION } +
                fadeOut(tween(OrbitMotion.DurFastMs))
        }
    }

    private companion object {
        const val SLIDE_FRACTION = 8
    }
}
