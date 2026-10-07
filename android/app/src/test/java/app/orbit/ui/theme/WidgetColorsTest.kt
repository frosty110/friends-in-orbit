// android/app/src/test/java/app/orbit/ui/theme/WidgetColorsTest.kt
//
// Pure-Kotlin JVM JUnit-4 test (no Robolectric, no @RunWith). Verifies the
// OrbitColors -> ColorScheme derivation in WidgetColors.kt. Lives in the same
// package as WidgetColors.kt so it can read internal LightColors/DarkColors and
// the internal WidgetLightScheme/WidgetDarkScheme exposed for testability.
//
// DERIVATION SEAM (Rule 1 deviation from PLAN's interfaces block):
// `androidx.glance.color.ColorProviders` does NOT carry `.light: ColorScheme`
// and `.dark: ColorScheme` fields — that was an incorrect claim in the plan's
// interfaces block. ColorProviders only exposes per-slot `ColorProvider`s that
// resolve to a light-or-dark Color at composition time via Context. We therefore
// assert against the internal `WidgetLightScheme` / `WidgetDarkScheme` schemes
// produced by `OrbitColors.toM3Scheme()` — those are the actual derivation seam
// and are pure JVM data (no Android dep), satisfying D-11 (JVM-only test).
package app.orbit.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetColorsTest {

    @Test
    fun `light primary maps to LightColors accent`() {
        assertEquals(LightColors.accent, WidgetLightScheme.primary)
    }

    @Test
    fun `dark surface maps to DarkColors surface`() {
        assertEquals(DarkColors.surface, WidgetDarkScheme.surface)
    }

    @Test
    fun `light error maps to LightColors danger`() {
        assertEquals(LightColors.danger, WidgetLightScheme.error)
    }

    // WIDGET-11: a widget avatar's day and night colours are the ones the app
    // gives the same name in light and dark, under the same mode override.
    // Cool is used because its avatar tones differ by mode (Warm's do not).

    @Test
    fun `avatar tones follow the system mode by default`() {
        val settings = ThemeSettings(themeId = OrbitThemeId.COOL, darkMode = OrbitDarkMode.SYSTEM)
        val colors = orbitWidgetAvatarTones(settings).forName("Kai Nakamura")
        val day = OrbitThemes.resolve(settings, isDark = false).tones.avatarPalette("Kai Nakamura")
        val night = OrbitThemes.resolve(settings, isDark = true).tones.avatarPalette("Kai Nakamura")
        assertEquals(day.first, colors.backgroundDay)
        assertEquals(day.second, colors.foregroundDay)
        assertEquals(night.first, colors.backgroundNight)
        assertEquals(night.second, colors.foregroundNight)
    }

    @Test
    fun `avatar tones stay light when the app is set to light`() {
        val settings = ThemeSettings(themeId = OrbitThemeId.COOL, darkMode = OrbitDarkMode.LIGHT)
        val colors = orbitWidgetAvatarTones(settings).forName("Kai Nakamura")
        assertEquals(colors.backgroundDay, colors.backgroundNight)
        assertEquals(colors.foregroundDay, colors.foregroundNight)
    }

    @Test
    fun `avatar tones stay dark when the app is set to dark`() {
        val settings = ThemeSettings(themeId = OrbitThemeId.COOL, darkMode = OrbitDarkMode.DARK)
        val colors = orbitWidgetAvatarTones(settings).forName("Kai Nakamura")
        val night = OrbitThemes.resolve(settings, isDark = true).tones.avatarPalette("Kai Nakamura")
        assertEquals(night.first, colors.backgroundDay)
        assertEquals(night.first, colors.backgroundNight)
    }
}
