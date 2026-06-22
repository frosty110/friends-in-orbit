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
    val tones: OrbitTones
        @Composable @ReadOnlyComposable get() = LocalOrbitTones.current
    val type: OrbitTypography
        @Composable @ReadOnlyComposable get() = LocalOrbitTypography.current
    val shapes: OrbitShapes
        @Composable @ReadOnlyComposable get() = LocalOrbitShapes.current
    val spacing: OrbitSpacing
        @Composable @ReadOnlyComposable get() = LocalOrbitSpacing.current
}

/**
 * Root theme. [settings] carries the user's chosen theme / dark mode / accent
 * dial (defaults to Warm + system). [darkTheme] is derived from those settings
 * but kept as an explicit parameter so previews can force a mode with
 * `OrbitTheme(darkTheme = true)`. The final palette + tonal families are
 * resolved through [OrbitThemes] and provided via [LocalOrbitColors] /
 * [LocalOrbitTones]; every screen reads them as `OrbitTheme.colors` /
 * `OrbitTheme.tones`, so swapping the theme never touches a screen.
 */
@Composable
fun OrbitTheme(
    settings: ThemeSettings = ThemeSettings.DEFAULT,
    darkTheme: Boolean = OrbitThemes.effectiveDark(settings, isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    val resolved = OrbitThemes.resolve(settings, darkTheme)
    val colors = resolved.colors

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
        LocalOrbitTones provides resolved.tones,
        LocalOrbitTypography provides OrbitType,
        LocalOrbitShapes provides OrbitShapeSet,
        LocalOrbitSpacing provides OrbitSpacing(),
    ) {
        MaterialTheme(colorScheme = m3, content = content)
    }
}
