package app.orbit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.LocalReducedMotion
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Orbit mark: you at the centre, two quiet rings, and a few people on
 * them in the warm personality tones. Drawn in code from theme tokens, so it
 * follows every theme and mode, and it is the brand moment Welcome lacked
 * (it was text on cream; UX rubric D12).
 *
 * On first show the people drift a short way along their rings into place,
 * once, over [OrbitMotion.DurSlowMs] with a small stagger. Never looping
 * (rules.md §Design 8), and static when the system's animations are off.
 * Decorative, so hidden from TalkBack.
 */
@Composable
fun OrbitMark(modifier: Modifier = Modifier, size: Dp = 168.dp) {
    val colors = OrbitTheme.colors
    val tones = OrbitTheme.tones.avatarPalettes
    val reduced = LocalReducedMotion.current
    val settle = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (!reduced) settle.animateTo(1f, tween(OrbitMotion.DurSlowMs * 2, easing = OrbitMotion.EaseOut))
    }

    Canvas(modifier = modifier.size(size).clearAndSetSemantics {}) {
        val c = center
        val outer = this.size.minDimension / 2f - 10.dp.toPx()
        val inner = outer * 0.58f
        val ring = Stroke(width = 1.5.dp.toPx())
        drawCircle(color = colors.line, radius = outer, center = c, style = ring)
        drawCircle(color = colors.line, radius = inner, center = c, style = ring)

        // You, at the centre.
        drawCircle(color = colors.fg, radius = 9.dp.toPx(), center = c)

        // People on the rings: (radius, final angle in degrees, dot radius, tone).
        val people = listOf(
            Triple(inner, -35f, 7.dp.toPx()),
            Triple(outer, 50f, 9.dp.toPx()),
            Triple(outer, 200f, 7.dp.toPx()),
            Triple(inner, 150f, 6.dp.toPx()),
        )
        val t = settle.value
        people.forEachIndexed { i, (r, angle, dot) ->
            // Staggered: later people start a little later and travel a
            // little less, so the group settles rather than spins.
            val local = ((t - i * 0.08f) / (1f - i * 0.08f)).coerceIn(0f, 1f)
            val a = Math.toRadians((angle - (1f - local) * (40f - i * 6f)).toDouble())
            val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            val (bg, fg) = tones[i % tones.size]
            drawCircle(color = bg, radius = dot + 3.dp.toPx(), center = p, alpha = local)
            drawCircle(color = fg, radius = dot * 0.55f, center = p, alpha = local)
        }
    }
}
