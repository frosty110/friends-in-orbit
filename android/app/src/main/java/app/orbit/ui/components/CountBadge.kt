package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            // Min, not fixed: the count grows with the font scale instead of
            // clipping at 200%.
            .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp)
            .clip(OrbitTheme.shapes.full)
            .background(OrbitTheme.colors.accent)
            .padding(horizontal = OrbitTheme.spacing.x2),
    ) {
        Text(
            text = count.toString(),
            // The accent's own foreground: white on the light accent, ink on
            // the lifted dark accent (white on Mono dark's accent was 1.35:1).
            style = OrbitTheme.type.badge.copy(
                color = OrbitTheme.colors.accentFg,
            ),
        )
    }
}
