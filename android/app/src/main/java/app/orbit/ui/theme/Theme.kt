package app.orbit.ui.theme

// SPLASH BOUNDARY (D-09): res/values/colors.xml carries cream/charcoal/terracotta
// and res/values/themes.xml carries Theme.Orbit / Theme.Orbit.Splash. These are
// load-bearing for the pre-Compose splash bootstrap (Android framework reads them
// before Compose initializes). Do NOT consolidate into OrbitColors.

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitTheme(
    settings: ThemeSettings = ThemeSettings.DEFAULT,
    darkTheme: Boolean = OrbitThemes.effectiveDark(settings, isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Only the Wallpaper theme reads the wallpaper; a wallpaper change
    // recreates the activity, so remembering per theme choice is enough.
    val deviceHue = remember(context, settings.themeId) {
        if (settings.themeId == OrbitThemeId.DEVICE) deviceAccentHue(context) else null
    }
    val resolved = remember(settings, darkTheme, deviceHue) { OrbitThemes.resolve(settings, darkTheme, deviceHue) }
    val colors = resolved.colors
    // Snackbars and other inverse surfaces flip to the opposite mode, so their
    // action colour comes from the same theme in that mode: the light accent
    // on a dark (ink) bar, which is how it clears 4.5:1.
    val inverse = remember(settings, darkTheme, deviceHue) {
        OrbitThemes.resolve(settings, !darkTheme, deviceHue).colors
    }
    val reducedMotion = remember(context) { isReducedMotion(context) }

    CompositionLocalProvider(
        LocalOrbitColors provides colors,
        LocalOrbitTones provides resolved.tones,
        LocalOrbitTypography provides OrbitType,
        LocalOrbitShapes provides OrbitShapeSet,
        LocalOrbitSpacing provides OrbitSpacing(),
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = materialColors(colors, inverse, darkTheme),
            typography = MaterialTypography,
            shapes = MaterialShapes,
        ) {
            // Inside MaterialTheme, which installs ripple as the default
            // indication: Orbit presses darken quietly instead (design brief:
            // "press = fill darkens, no ripple"). Material components that
            // still draw a ripple get the same quiet strength and colour.
            CompositionLocalProvider(
                LocalIndication provides OrbitPressIndication,
                LocalRippleConfiguration provides RippleConfiguration(
                    color = colors.fg,
                    rippleAlpha = RippleAlpha(
                        draggedAlpha = 0.08f,
                        focusedAlpha = 0.10f,
                        hoveredAlpha = 0.04f,
                        pressedAlpha = 0.08f,
                    ),
                ),
                content = content,
            )
        }
    }
}

/**
 * Every Material 3 colour slot mapped to an Orbit token, so stock components
 * (menus, snackbars, dialogs, the time picker, checkboxes, chips, text fields)
 * look like Orbit rather than default Material lavender. Before 2026-10-05 only
 * ten slots were mapped and the rest leaked through.
 */
private fun materialColors(c: OrbitColors, inverse: OrbitColors, dark: Boolean): ColorScheme {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = c.accent,
        onPrimary = c.accentFg,
        primaryContainer = c.accentTint,
        onPrimaryContainer = c.fg,
        inversePrimary = inverse.accent,
        secondary = c.fgSoft,
        onSecondary = c.bg,
        secondaryContainer = c.accentTint,
        onSecondaryContainer = c.fg,
        tertiary = c.positiveText,
        onTertiary = c.bg,
        tertiaryContainer = c.positiveTint,
        onTertiaryContainer = c.fg,
        background = c.bg,
        onBackground = c.fg,
        surface = c.surface,
        onSurface = c.fg,
        surfaceVariant = c.bgSubtle,
        onSurfaceVariant = c.fgMuted,
        // No tonal tint on elevated surfaces: Orbit's surfaces are flat cream
        // and paper, and elevation is shown with shadow.
        surfaceTint = c.surface,
        inverseSurface = inverse.bg,
        inverseOnSurface = inverse.fg,
        error = c.danger,
        onError = c.accentFg,
        errorContainer = c.urgentTint,
        onErrorContainer = c.fg,
        // Outlines mark controls (text fields, unchecked checkboxes), so they
        // must reach 3:1; dividers use the softer variant.
        outline = c.fgSubtle,
        outlineVariant = c.line,
        scrim = Color.Black,
        surfaceBright = c.surface,
        surfaceDim = c.bgSubtle,
        surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.bg,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.bgSubtle,
    )
}

/** Material's type scale, set in Inter with Orbit's roles where they overlap. */
private val MaterialTypography: Typography = Typography().let { base ->
    fun TextStyle.inter() = copy(fontFamily = OrbitFont)
    base.copy(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = OrbitType.title,
        titleLarge = OrbitType.title,
        titleMedium = OrbitType.h3,
        titleSmall = base.titleSmall.inter(),
        bodyLarge = OrbitType.body,
        bodyMedium = OrbitType.meta,
        bodySmall = OrbitType.micro,
        labelLarge = OrbitType.button,
        labelMedium = OrbitType.badge,
        labelSmall = OrbitType.micro,
    )
}

/** Material's shape scale, set to Orbit's radii (menus 8dp, dialogs 24dp). */
private val MaterialShapes: Shapes = Shapes(
    extraSmall = OrbitShapeSet.sm,
    small = OrbitShapeSet.sm,
    medium = OrbitShapeSet.md,
    large = OrbitShapeSet.lg,
    extraLarge = OrbitShapeSet.xl,
)

/**
 * True when the user has turned animations off (Settings > Accessibility >
 * Remove animations sets the animator duration scale to 0). Read by the
 * navigation transitions and any decorative motion, so WCAG 2.3.3 (animation
 * from interactions can be disabled) holds.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

private fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
