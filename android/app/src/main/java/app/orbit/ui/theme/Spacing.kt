package app.orbit.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 4dp base grid. Names mirror --space-N in CSS.
@Immutable
data class OrbitSpacing(
    val x1: Dp = 4.dp,
    val x2: Dp = 8.dp,
    val x3: Dp = 12.dp,
    val x4: Dp = 16.dp,
    val x5: Dp = 20.dp,
    val x6: Dp = 24.dp,
    val x7: Dp = 32.dp,
    val x8: Dp = 40.dp,
    val x9: Dp = 56.dp,
    val x10: Dp = 72.dp,
    val tapMin: Dp = 48.dp,
)

internal val LocalOrbitSpacing = staticCompositionLocalOf { OrbitSpacing() }
