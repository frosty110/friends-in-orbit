// android/app/src/test/java/app/orbit/ui/theme/ThemeContrastTest.kt
//
// The accessibility gate for user-selectable themes. A theme can ship only if
// its semantic color pairs clear WCAG AA, and the user-facing accent dial can
// never land on an inaccessible primary. Pure-Kotlin JVM JUnit-4 (no Android),
// same package so it reads the internal ColorMath helpers + LightColors/DarkColors.
//
// Thresholds (WCAG 2.1):
//   - Body text (fg/bg, fgMuted/bg): >= 4.5:1
//   - UI components & large text (accent fills, accent vs surface): >= 3.0:1
//     (Orbit's own terracotta is ~3.9:1 white-on-accent, so 3.0 is the curated
//      floor; the generated dial is held to the stricter 4.5 below.)
package app.orbit.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {

    private val bodyAA = 4.5f
    private val uiAA = 3.0f

    private fun assertContrast(pair: String, fg: Color, bg: Color, min: Float) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(
            "$pair contrast ${"%.2f".format(ratio)} < $min",
            ratio >= min - 0.01f,
        )
    }

    private fun checkPalette(name: String, c: OrbitColors) {
        // Body text on both surfaces.
        assertContrast("$name fg/bg", c.fg, c.bg, bodyAA)
        assertContrast("$name fg/surface", c.fg, c.surface, bodyAA)
        assertContrast("$name fgMuted/bg", c.fgMuted, c.bg, bodyAA)
        assertContrast("$name fgMuted/surface", c.fgMuted, c.surface, bodyAA)
        // Accent as a UI element + its own foreground.
        assertContrast("$name accentFg/accent", c.accentFg, c.accent, uiAA)
        assertContrast("$name accent/surface", c.accent, c.surface, uiAA)
        // Danger (used for destructive labels) must read on the surface.
        assertContrast("$name danger/surface", c.danger, c.surface, uiAA)
    }

    @Test
    fun `every curated theme clears WCAG AA in both modes`() {
        for (def in OrbitThemes.all) {
            checkPalette("${def.id.displayName} light", def.light)
            checkPalette("${def.id.displayName} dark", def.dark)
        }
    }

    @Test
    fun `chip tones stay legible (fg on bg) for every theme and mode`() {
        for (def in OrbitThemes.all) {
            for ((label, tones) in listOf("light" to def.lightTones, "dark" to def.darkTones)) {
                val chips = with(tones.chip) {
                    listOf("terracotta" to terracotta, "sage" to sage, "amber" to amber, "brick" to brick, "stone" to stone)
                }
                for ((slot, t) in chips) {
                    assertContrast("${def.id.displayName} $label chip:$slot", t.fg, t.bg, uiAA)
                }
            }
        }
    }

    @Test
    fun `accent dial generates a body-AA accent for every hue in light mode`() {
        var hue = 0
        while (hue < 360) {
            val a = accentForHue(hue.toFloat(), isDark = false)
            // Generator targets white-on-accent >= 4.5; the chosen fg is at least that good.
            assertContrast("dial light hue=$hue accentFg/accent", a.accentFg, a.accent, bodyAA)
            hue += 15
        }
    }

    @Test
    fun `accent dial generates a UI-AA accent for every hue in dark mode`() {
        var hue = 0
        while (hue < 360) {
            val a = accentForHue(hue.toFloat(), isDark = true)
            assertContrast("dial dark hue=$hue accentFg/accent", a.accentFg, a.accent, uiAA)
            // The accent must also be distinguishable from the dark surface.
            assertContrast("dial dark hue=$hue accent/surface", a.accent, DarkColors.surface, uiAA)
            hue += 15
        }
    }

    @Test
    fun `resolve with an accent-hue override stays accessible`() {
        val settings = ThemeSettings(themeId = OrbitThemeId.COOL, accentHue = 280)
        val light = OrbitThemes.resolve(settings, isDark = false)
        val dark = OrbitThemes.resolve(settings, isDark = true)
        assertContrast("override light accentFg/accent", light.colors.accentFg, light.colors.accent, bodyAA)
        assertContrast("override dark accentFg/accent", dark.colors.accentFg, dark.colors.accent, uiAA)
    }
}
