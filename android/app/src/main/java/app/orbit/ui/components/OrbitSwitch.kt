package app.orbit.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitMotion
import app.orbit.ui.theme.OrbitTheme

// Warm, quiet pill switch. Thumb slides 200ms ease-out, track cross-fades
// between --line and --accent. Matches SettingsScreen.jsx ToggleRow visual.
@Composable
fun OrbitSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track = if (checked) OrbitTheme.colors.accent else OrbitTheme.colors.line
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 3.dp,
        animationSpec = tween(durationMillis = 200, easing = OrbitMotion.EaseOut),
        label = "switch-thumb",
    )
    val alpha = if (enabled) 1f else 0.45f
    Box(
        modifier = modifier
            .width(44.dp)
            .height(26.dp)
            .clip(OrbitTheme.shapes.full)
            .alpha(alpha)
            .background(track)
            .then(
                if (enabled) {
                    Modifier.clickable { onCheckedChange(!checked) }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset, y = 3.dp)
                .size(20.dp)
                .shadow(elevation = 1.dp, shape = OrbitTheme.shapes.full)
                .clip(OrbitTheme.shapes.full)
                .background(Color.White),
        )
    }
    @Suppress("UNUSED_VARIABLE") val pad = Modifier.padding(0.dp)
}
