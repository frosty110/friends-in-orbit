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
//   - Text on the Home card's tinted surfaces (OrbitTones.ListTone: the band
//     and the wash): the list name (nameFg), fg, fgMuted and fgSubtle on the
//     band, fg and fgSubtle on the wash, >= 4.5:1, for every curated theme,
//     every Wallpaper hue and every accent-dial hue (home-5). Until 2026-10-06
//     only the neutral surfaces were checked and a 72% alpha member count on
//     the tinted band fell under 4.5:1 unnoticed until someone looked.
// Until 2026-10-05 button labels were held only to 3.0, which let Warm's
// white-on-terracotta ship at 3.88:1, and fgSubtle was never checked.
package app.orbit.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {

    private val bodyAA = 4.5f
    private val enhanced = 7.0f
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
        // Text tokens on every neutral surface they appear on. surfaceAlt is
        // the lightest dark surface (GraphiteDeep) and the one accentListTone
        // holds the Home card's band to, so it is certified here on purpose.
        val surfaces = listOf("bg" to c.bg, "surface" to c.surface, "bgSubtle" to c.bgSubtle, "surfaceAlt" to c.surfaceAlt)
        for ((surfaceName, surface) in surfaces) {
            assertContrast("$name fg/$surfaceName", c.fg, surface, bodyAA)
            assertContrast("$name fgMuted/$surfaceName", c.fgMuted, surface, bodyAA)
            assertContrast("$name fgSubtle/$surfaceName", c.fgSubtle, surface, bodyAA)
        }
        // Primary text meets WCAG AAA 1.4.6 (7:1), the enhanced-contrast
        // criterion the UX rubric adopts for reading (decision 1).
        assertContrast("$name fg/bg (AAA)", c.fg, c.bg, enhanced)
        assertContrast("$name fg/surface (AAA)", c.fg, c.surface, enhanced)
        // Button labels are text: 4.5, at rest and pressed.
        assertContrast("$name accentFg/accent", c.accentFg, c.accent, bodyAA)
        assertContrast("$name accentFg/accentPress", c.accentFg, c.accentPress, bodyAA)
        // Accent used as text ("Match theme", a snackbar's action). Home's
        // list names are nameFg on the card's band, checked in checkTones.
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

    /**
     * The Home list card's two surfaces (OrbitTones.ListTone): the accent
     * tinted band that carries the list name, the eyebrow, the why line and
     * the member count, and the wash under the rhythm strip's legend and the
     * today letter. The band of card A is the theme's accentTint, which the
     * Wallpaper theme and the accent dial generate from any hue, so these are
     * checked on the resolved theme for every hue, not on the authored
     * palettes alone. Card B's band and wash are neutral surfaces and pass
     * trivially; checking them costs nothing and catches a future tinted B.
     */
    private fun checkTones(name: String, resolved: ResolvedTheme) {
        val c = resolved.colors
        resolved.tones.listTones.forEachIndexed { index, tone ->
            val card = "$name card${'A' + index}"
            assertContrast("$card nameFg/band", tone.nameFg, tone.band, bodyAA)
            assertContrast("$card fg/band", c.fg, tone.band, bodyAA)
            assertContrast("$card fgMuted/band", c.fgMuted, tone.band, bodyAA)
            assertContrast("$card fgSubtle/band", c.fgSubtle, tone.band, bodyAA)
            assertContrast("$card fg/wash", c.fg, tone.wash, bodyAA)
            assertContrast("$card fgMuted/wash", c.fgMuted, tone.wash, bodyAA)
            assertContrast("$card fgSubtle/wash", c.fgSubtle, tone.wash, bodyAA)
        }
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
            checkTones("${def.id.name} light", OrbitThemes.resolve(ThemeSettings(themeId = def.id), isDark = false))
            checkTones("${def.id.name} dark", OrbitThemes.resolve(ThemeSettings(themeId = def.id), isDark = true))
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
            val wallpaper = ThemeSettings(themeId = OrbitThemeId.DEVICE)
            checkTones("Wallpaper hue=$hue light", OrbitThemes.resolve(wallpaper, isDark = false, deviceHue = hue.toFloat()))
            checkTones("Wallpaper hue=$hue dark", OrbitThemes.resolve(wallpaper, isDark = true, deviceHue = hue.toFloat()))
            hue += 15
        }
        // A grey wallpaper has no hue: the theme falls back to Warm's.
        assertTrue(OrbitThemes.def(OrbitThemeId.DEVICE, deviceHue = null).light.accent == OrbitThemes.def(OrbitThemeId.WARM).light.accent ||
            OrbitThemes.defaultHueFor(OrbitThemeId.DEVICE) == OrbitThemes.defaultHueFor(OrbitThemeId.WARM))
        assertNoFailures()
    }

    // HOME-8: the rhythm bars' direction rims. Each is a non-text mark, so it
    // must clear 3:1 against the card it sits on (the surface and every list
    // wash) and against the near-black ring that insulates it from the fill.
    // The two rims must also differ in lightness, not only hue: that is what
    // still separates them for a colour-blind reader once red-green collapses.
    // 1.5:1 is the floor the 2026-10-07 pair was chosen above (1.6 light, 2.4
    // dark); the violet/blue pair it replaced was the one the owner could not
    // read, and a same-lightness pink/teal would fail here.
    private fun checkDirection(name: String, c: OrbitColors, tones: OrbitTones) {
        val cards = listOf("surface" to c.surface) +
            tones.listTones.mapIndexed { i, t -> "wash$i" to t.wash }
        for ((dir, rim) in listOf("outgoing" to c.directionOutgoing, "incoming" to c.directionIncoming)) {
            for ((cardName, card) in cards) assertContrast("$name $dir rim/$cardName", rim, card, uiAA)
            assertContrast("$name $dir rim/separator", rim, c.directionSeparator, uiAA)
        }
        assertContrast("$name outgoing/incoming lightness", c.directionOutgoing, c.directionIncoming, 1.5f)
    }

    @Test
    fun `direction rims stand off the card, their ring and each other in every theme`() {
        for (def in OrbitThemes.all) {
            for (isDark in listOf(false, true)) {
                val resolved = OrbitThemes.resolve(ThemeSettings(themeId = def.id), isDark = isDark)
                checkDirection("${def.id.name} ${if (isDark) "dark" else "light"}", resolved.colors, resolved.tones)
            }
        }
        var hue = 0
        while (hue < 360) {
            for (isDark in listOf(false, true)) {
                val dial = OrbitThemes.resolve(ThemeSettings(themeId = OrbitThemeId.WARM, accentHue = hue), isDark = isDark)
                checkDirection("accent hue=$hue ${if (isDark) "dark" else "light"}", dial.colors, dial.tones)
            }
            hue += 15
        }
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

    // The dial path rebuilds card A inside OrbitThemes.resolve (not in
    // deriveOrbitTones), so the dial tests on accentForHue alone would miss a
    // band the generated tint cannot carry text on. Every curated theme, every
    // hue, both modes.
    @Test
    fun `the accent dial keeps the Home card's text legible for every theme and hue`() {
        for (def in OrbitThemes.all) {
            var hue = 0
            while (hue < 360) {
                val settings = ThemeSettings(themeId = def.id, accentHue = hue)
                checkTones("${def.id.name} dial hue=$hue light", OrbitThemes.resolve(settings, isDark = false))
                checkTones("${def.id.name} dial hue=$hue dark", OrbitThemes.resolve(settings, isDark = true))
                hue += 15
            }
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
