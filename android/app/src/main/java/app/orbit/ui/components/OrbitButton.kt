package app.orbit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

enum class OrbitButtonVariant { Primary, Secondary, Ghost, Destructive }

// 48dp minimum per PRD accessibility baseline. 12dp radius per shape tokens.
// Press = fill darkens ~8-10% via press overlay (no ripple, per design brief).
@Composable
fun OrbitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: OrbitButtonVariant = OrbitButtonVariant.Primary,
    leadingIcon: String? = null,
    height: Dp = 48.dp,
    enabled: Boolean = true,
) {
    val c = OrbitTheme.colors
    val (bg, fg, border) = when (variant) {
        OrbitButtonVariant.Primary     -> Triple(c.accent,   c.accentFg, null)
        OrbitButtonVariant.Secondary   -> Triple(c.bgSubtle, c.fg,       null)
        OrbitButtonVariant.Ghost       -> Triple(Color.Transparent, c.fgMuted, null)
        OrbitButtonVariant.Destructive -> Triple(Color.Transparent, c.danger, c.line)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressTint = if (pressed) {
        if (variant == OrbitButtonVariant.Primary) c.accentPress else c.bgSubtle
    } else bg
    val alpha = if (enabled) 1f else 0.4f
    // Never re-alpha a transparent container: Color.Transparent is black at
    // alpha 0, so `.copy(alpha = 1f)` turns Ghost/Destructive fills into a
    // solid black slab (2026-06-09 onboarding walkthrough, V1).
    val container = if (pressTint == Color.Transparent) pressTint else pressTint.copy(alpha = alpha)

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            // Min height only — a hard .height() pin clipped two-line labels
            // at 200% font scale (2026-06-09 a11y sweep). Buttons grow with
            // their text; at default scale nothing changes.
            .defaultMinSize(minHeight = height)
            .clip(OrbitTheme.shapes.md)
            .background(container)
            .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), OrbitTheme.shapes.md) else Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
    ) {
        if (leadingIcon != null) PhIcon(name = leadingIcon, size = 18.dp, tint = fg)
        Text(text = text, style = OrbitTheme.type.button.copy(color = fg.copy(alpha = alpha)))
    }
}

// 48x48 touch target — ghost icon button for app bar leading/trailing slots.
@Composable
fun OrbitIconButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = OrbitTheme.colors.fg,
    contentDescription: String? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(OrbitTheme.shapes.md)
            .clickable(onClick = onClick)
            .then(
                if (contentDescription != null)
                    Modifier.semantics { this.contentDescription = contentDescription }
                else Modifier
            ),
    ) {
        PhIcon(name = icon, size = 22.dp, tint = tint)
    }
}

