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
}
