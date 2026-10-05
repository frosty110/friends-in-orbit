package app.orbit.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme

/**
 * Warm, quiet pill switch.
 *
 * Two ways to use it:
 *  - **Inside a row that is the toggle** (the usual case, e.g. `ToggleRow`):
 *    pass `onCheckedChange = null`. The row carries `toggleable(role =
 *    Role.Switch)`, so TalkBack hears one control with the row's label and an
 *    on/off state, and the whole row is the touch target. The switch only draws.
 *  - **Standalone:** pass a callback. The switch is then its own `Role.Switch`
 *    with a 48dp touch target around the 44x26 track.
 *
 * Unchecked, the track is outlined and the thumb is subtle text colour, so the
 * control and its state read at 3:1 or better (WCAG 1.4.11); the old pale
 * borderless track was about 1.3:1 on white. Checked, the thumb takes the
 * accent's own foreground, so it stays visible in every theme (Mono dark's
 * light accent made a white thumb vanish).
 */
@Composable
fun OrbitSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = OrbitTheme.colors
    val track by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.bgSubtle,
        animationSpec = tween(OrbitMotion.DurFastMs, easing = OrbitMotion.EaseOut),
        label = "switch-track",
    )
    val thumbColor = if (checked) colors.accentFg else colors.fgSubtle
    val thumbSize = if (checked) 20.dp else 16.dp
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 5.dp,
        animationSpec = tween(OrbitMotion.DurFastMs, easing = OrbitMotion.EaseOut),
        label = "switch-thumb",
    )
    val toggle = if (onCheckedChange != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
    } else {
        Modifier
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier.then(toggle)) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(OrbitTheme.shapes.full)
                .background(track)
                .then(
                    if (checked) Modifier else Modifier.border(1.5.dp, colors.fgSubtle, OrbitTheme.shapes.full),
                ),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .clip(OrbitTheme.shapes.full)
                    .background(thumbColor),
            )
        }
    }
}
