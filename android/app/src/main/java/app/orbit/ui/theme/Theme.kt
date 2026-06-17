package app.orbit.ui.theme

// SPLASH BOUNDARY (D-09): res/values/colors.xml carries cream/charcoal/terracotta
// and res/values/themes.xml carries Theme.Orbit / Theme.Orbit.Splash. These are
// load-bearing for the pre-Compose splash bootstrap (Android framework reads them
// before Compose initializes). Do NOT consolidate into OrbitColors.

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

object OrbitTheme {
    val colors: OrbitColors
        @Composable @ReadOnlyComposable get() = LocalOrbitColors.current
    val type: OrbitTypography
        @Composable @ReadOnlyComposable get() = LocalOrbitTypography.current
    val shapes: OrbitShapes
        @Composable @ReadOnlyComposable get() = LocalOrbitShapes.current
    val spacing: OrbitSpacing
        @Composable @ReadOnlyComposable get() = LocalOrbitSpacing.current
}

@Composable
fun OrbitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    // Material3 color scheme — we mostly bypass it, but Material components (Switch,
    // TextField, etc.) still read from it, so align the slots that show through.
    val m3 = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentFg,
            background = colors.bg,
            onBackground = colors.fg,
            surface = colors.surface,
            onSurface = colors.fg,
            surfaceVariant = colors.bgSubtle,
            onSurfaceVariant = colors.fgMuted,
            outline = colors.line,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentFg,
            background = colors.bg,
            onBackground = colors.fg,
            surface = colors.surface,
            onSurface = colors.fg,
            surfaceVariant = colors.bgSubtle,
            onSurfaceVariant = colors.fgMuted,
            outline = colors.line,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalOrbitColors provides colors,
        LocalOrbitTypography provides OrbitType,
        LocalOrbitShapes provides OrbitShapeSet,
        LocalOrbitSpacing provides OrbitSpacing(),
    ) {
        MaterialTheme(colorScheme = m3, content = content)
    }
}
