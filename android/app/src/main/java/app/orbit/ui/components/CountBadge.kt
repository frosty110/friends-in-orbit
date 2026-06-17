package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 26.dp)
            .height(26.dp)
            .clip(OrbitTheme.shapes.full)
            .background(OrbitTheme.colors.accent)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = count.toString(),
            style = OrbitTheme.type.badge.copy(
                color = Color.White,
            ),
        )
    }
}
