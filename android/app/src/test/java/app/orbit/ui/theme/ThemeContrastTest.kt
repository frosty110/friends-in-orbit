// android/app/src/test/java/app/orbit/ui/theme/ThemeContrastTest.kt
//
// The accessibility gate for user-selectable themes. A theme can ship only if
// its semantic color pairs clear WCAG AA, and the user-facing accent dial can
// never land on an inaccessible primary. Pure-Kotlin JVM JUnit-4 (no Android),
// same package so it reads the internal ColorMath helpers + LightColors/DarkColors.
//
// Thresholds (WCAG 2.2, the floor the UX rubric's gate G2 holds every theme to):
//   - Any text token on any surface it sits on: >= 4.5:1. That includes button
//     labels on the accent fill (accentFg/accent), the subtle text used for
//     Skip and View details (fgSubtle), green "good time" text (positiveText),
//     and a snackbar's action on its inverse bar.
//   - UI parts that are not text (accent vs surface, the outline that marks an
//     unchecked control): >= 3.0:1.
// Until 2026-10-05 button labels were held only to 3.0, which let Warm's
// white-on-terracotta ship at 3.88:1, and fgSubtle was never checked.
package app.orbit.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {

    private val bodyAA = 4.5f
    private val uiAA = 3.0f

    // Collected, not thrown at the first miss, so one run names every failing
    // pair. Each test ends with [assertNoFailures].
    private val failures = mutableListOf<String>()

    private fun assertContrast(pair: String, fg: Color, bg: Color, min: Float) {
        val ratio = contrastRatio(fg, bg)
        if (ratio < min - 0.01f) failures += "$pair contrast ${"%.2f".format(ratio)} < $min"
    }

    private fun assertNoFailures() {
        assertTrue(failures.joinToString(separator = "\n", prefix = "\n"), failures.isEmpty())
    }

    private fun checkPalette(name: String, c: OrbitColors) {
        // Text tokens on every neutral surface they appear on.
        val surfaces = listOf("bg" to c.bg, "surface" to c.surface, "bgSubtle" to c.bgSubtle)
        for ((surfaceName, surface) in surfaces) {
            assertContrast("$name fg/$surfaceName", c.fg, surface, bodyAA)
            assertContrast("$name fgMuted/$surfaceName", c.fgMuted, surface, bodyAA)
            assertContrast("$name fgSubtle/$surfaceName", c.fgSubtle, surface, bodyAA)
        }
        // Button labels are text: 4.5, at rest and pressed.
        assertContrast("$name accentFg/accent", c.accentFg, c.accent, bodyAA)
        assertContrast("$name accentFg/accentPress", c.accentFg, c.accentPress, bodyAA)
        // Accent used as a text link ("Match theme", list names on Home).
        assertContrast("$name accent/bg", c.accent, c.bg, bodyAA)
        assertContrast("$name accent/surface", c.accent, c.surface, bodyAA)
        // Green status text and its chip.
        assertContrast("$name positiveText/surface", c.positiveText, c.surface, bodyAA)
        assertContrast("$name positiveText/bg", c.positiveText, c.bg, bodyAA)
        // Destructive labels.
        assertContrast("$name danger/surface", c.danger, c.surface, bodyAA)
        // The outline that marks text fields, checkboxes and unchecked switches.
        assertContrast("$name fgSubtle(outline)/surface", c.fgSubtle, c.surface, uiAA)
    }

    /** A snackbar is the other mode's background with the other mode's accent action. */
    private fun checkInverse(name: String, settings: ThemeSettings, deviceHue: Float? = null) {
        for (dark in listOf(false, true)) {
            val inverse = OrbitThemes.resolve(settings, !dark, deviceHue).colors
            val mode = if (dark) "dark" else "light"
            assertContrast("$name $mode snackbar text", inverse.fg, inverse.bg, bodyAA)
            assertContrast("$name $mode snackbar action", inverse.accent, inverse.bg, bodyAA)
        }
    }

    @Test
    fun `every curated theme clears WCAG AA in both modes`() {
        for (def in OrbitThemes.all) {
            checkPalette("${def.id.name} light", def.light)
            checkPalette("${def.id.name} dark", def.dark)
        }
        assertNoFailures()
    }

    @Test
    fun `snackbars keep their text and action legible in every theme`() {
        for (def in OrbitThemes.all) checkInverse(def.id.name, ThemeSettings(themeId = def.id))
        assertNoFailures()
    }

    @Test
    fun `the Wallpaper theme clears AA whatever the wallpaper hue`() {
        var hue = 0
        while (hue < 360) {
            val def = OrbitThemes.def(OrbitThemeId.DEVICE, deviceHue = hue.toFloat())
            checkPalette("Wallpaper hue=$hue light", def.light)
            checkPalette("Wallpaper hue=$hue dark", def.dark)
            checkInverse("Wallpaper hue=$hue", ThemeSettings(themeId = OrbitThemeId.DEVICE), hue.toFloat())
            hue += 15
        }
        // A grey wallpaper has no hue: the theme falls back to Warm's.
        assertTrue(OrbitThemes.def(OrbitThemeId.DEVICE, deviceHue = null).light.accent == OrbitThemes.def(OrbitThemeId.WARM).light.accent ||
            OrbitThemes.defaultHueFor(OrbitThemeId.DEVICE) == OrbitThemes.defaultHueFor(OrbitThemeId.WARM))
        assertNoFailures()
    }

    @Test
    fun `chip tones stay legible (fg on bg) for every theme and mode`() {
        for (def in OrbitThemes.all) {
            for ((label, tones) in listOf("light" to def.lightTones, "dark" to def.darkTones)) {
                val chips = with(tones.chip) {
                    listOf("terracotta" to terracotta, "sage" to sage, "amber" to amber, "brick" to brick, "stone" to stone)
                }
                for ((slot, t) in chips) {
                    assertContrast("${def.id.name} $label chip:$slot", t.fg, t.bg, uiAA)
                }
            }
        }
        assertNoFailures()
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
        assertNoFailures()
    }

    @Test
    fun `accent dial generates a body-AA accent for every hue in dark mode`() {
        var hue = 0
        while (hue < 360) {
            val a = accentForHue(hue.toFloat(), isDark = true)
            assertContrast("dial dark hue=$hue accentFg/accent", a.accentFg, a.accent, bodyAA)
            // The accent must also be distinguishable from the dark surface.
            assertContrast("dial dark hue=$hue accent/surface", a.accent, DarkColors.surface, uiAA)
            hue += 15
        }
        assertNoFailures()
    }

    @Test
    fun `resolve with an accent-hue override stays accessible`() {
        val settings = ThemeSettings(themeId = OrbitThemeId.COOL, accentHue = 280)
        val light = OrbitThemes.resolve(settings, isDark = false)
        val dark = OrbitThemes.resolve(settings, isDark = true)
        assertContrast("override light accentFg/accent", light.colors.accentFg, light.colors.accent, bodyAA)
        assertContrast("override dark accentFg/accent", dark.colors.accentFg, dark.colors.accent, bodyAA)
        assertNoFailures()
    }
}
