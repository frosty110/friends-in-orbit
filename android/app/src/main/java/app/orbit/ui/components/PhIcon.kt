package app.orbit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest

// Phosphor icon renderer. SVGs live in assets/icons/*.svg and are tinted via
// ColorFilter so they pick up `currentColor` equivalent. The Coil loader is
// served by `OrbitApp` — Coil resolves the singleton via
// `Coil.imageLoader(context)` when no `imageLoader = ...` parameter is passed.
@Composable
fun PhIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = OrbitTheme.colors.fg,
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data("file:///android_asset/icons/$name.svg")
            .build(),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

@Composable
fun PhIconBox(
    name: String,
    size: Dp = 24.dp,
    tint: Color = OrbitTheme.colors.fg,
    modifier: Modifier = Modifier,
) {
    Box(modifier) { PhIcon(name = name, size = size, tint = tint) }
}
