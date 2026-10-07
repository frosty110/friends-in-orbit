package app.orbit.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Orbit's default press feedback, installed as `LocalIndication` by
 * [OrbitTheme], so every `clickable`, `toggleable` and `selectable` gets it.
 *
 * The design brief says "press = fill darkens, no ripple": a quiet overlay in
 * the foreground colour that appears at once on press and fades out over
 * [OrbitMotion.DurFastMs] on release. Hover is fainter. Keyboard and switch
 * focus adds a 2dp outline as well as the overlay, because a tint alone is
 * hard to see on a busy row (WCAG 2.4.7, focus visible).
 *
 * Like ripple, it draws over the node's bounds, so a rounded control clips
 * before its `clickable`, which is the existing pattern across the app.
 */
object OrbitPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)

    internal const val PRESSED_ALPHA = 0.10f
    internal const val HOVERED_ALPHA = 0.04f
    internal const val FOCUSED_ALPHA = 0.08f
}

private class PressNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private val overlay = Animatable(0f)
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            val pressed = mutableListOf<PressInteraction.Press>()
            val hovered = mutableListOf<HoverInteraction.Enter>()
            val focuses = mutableListOf<FocusInteraction.Focus>()
            interactionSource.interactions.collect { interaction: Interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed += interaction
                    is PressInteraction.Release -> pressed -= interaction.press
                    is PressInteraction.Cancel -> pressed -= interaction.press
                    is HoverInteraction.Enter -> hovered += interaction
                    is HoverInteraction.Exit -> hovered -= interaction.enter
                    is FocusInteraction.Focus -> focuses += interaction
                    is FocusInteraction.Unfocus -> focuses -= interaction.focus
                }
                focused = focuses.isNotEmpty()
                val target = when {
                    pressed.isNotEmpty() -> OrbitPressIndication.PRESSED_ALPHA
                    focused -> OrbitPressIndication.FOCUSED_ALPHA
                    hovered.isNotEmpty() -> OrbitPressIndication.HOVERED_ALPHA
                    else -> 0f
                }
                launch {
                    if (target > overlay.value) {
                        // Feedback must land under the finger immediately.
                        overlay.snapTo(target)
                    } else {
                        overlay.animateTo(target, tween(OrbitMotion.DurFastMs, easing = OrbitMotion.EaseOut))
                    }
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val fg = currentValueOf(LocalOrbitColors).fg
        val alpha = overlay.value
        if (alpha > 0f) drawRect(color = fg, alpha = alpha)
        if (focused) {
            val stroke = 2.dp.toPx()
            drawRect(color = fg, style = Stroke(width = stroke))
        }
    }
}
