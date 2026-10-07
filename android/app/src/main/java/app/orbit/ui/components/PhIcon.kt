package app.orbit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

// Phosphor icon renderer. The icons are VectorDrawables generated from
// app/icons-src/phosphor/*.svg by scripts/phosphor_to_vector.py (map in
// PhosphorIcons.kt), so they draw synchronously and stay crisp at any size;
// the 16-unit stroke on a 256 canvas gives the design system's 1.5dp line at
// 24dp. They used to be SVGs decoded through Coil, which made icons appear a
// frame late and left them out of screenshot tests.
//
// Decorative by design: contentDescription is always null. The control that
// owns the icon carries the label (rules.md Design 7).
@Composable
fun PhIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = OrbitTheme.colors.fg,
) {
    // An unknown name is a programming error; fail where it is written rather
    // than shipping a blank square (the "speaker-slash" icon rendered nothing
    // for months that way).
    val res = requireNotNull(PhosphorIcons[name]) { "No Phosphor icon named \"$name\"" }
    Icon(
        painter = painterResource(res),
        contentDescription = null,
        tint = tint,
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
