// android/app/src/main/java/app/orbit/ui/theme/WidgetColors.kt
//
// Widget color delivery — derives a single ColorProviders from OrbitColors so
// app and widget share one source of truth (D-01).
//
// SPLASH BOUNDARY (D-09): res/values/colors.xml carries cream/charcoal/terracotta
// for the pre-Compose splash. Do NOT consolidate those into OrbitColors — the
// Android framework reads them before Compose exists. This file is widget-only.
//
// M3 SLOT NOTE (D-10): OrbitColors has bespoke slots (fgMuted, warningTint,
// accentTint, dangerSoft, infoTint) with no M3 analog. We map only the 10
// slots widgets actually consume via glance-material3's two-arg factory. If a
// future widget needs a non-M3 slot, switch to the direct ColorProviders
// constructor and hand-provide all 31 slots — do NOT bend OrbitColors fields
// into the M3 ColorScheme shape.
package app.orbit.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as ColorProvidersFromM3

private fun OrbitColors.toM3Scheme() = if (isDark) {
    darkColorScheme(
        primary          = accent,
        onPrimary        = accentFg,
        background       = bg,
        onBackground     = fg,
        surface          = surface,
        onSurface        = fg,
        surfaceVariant   = bgSubtle,
        onSurfaceVariant = fgMuted,
        outline          = line,
        error            = danger,
    )
} else {
    lightColorScheme(
        primary          = accent,
        onPrimary        = accentFg,
        background       = bg,
        onBackground     = fg,
        surface          = surface,
        onSurface        = fg,
        surfaceVariant   = bgSubtle,
        onSurfaceVariant = fgMuted,
        outline          = line,
        error            = danger,
    )
}

// Light/dark M3 schemes produced from OrbitColors. Exposed `internal` so
// WidgetColorsTest (same module + package) can assert the derivation slot-by-slot
// without standing up a Context-backed Glance composition. ColorProviders itself
// (the public widget API) only exposes per-slot ColorProviders that resolve their
// light-or-dark value at composition time via Context — that resolution is not
// JVM-testable, so the schemes-under-the-wrapper are the right derivation seam.
internal val WidgetLightScheme = LightColors.toM3Scheme()
internal val WidgetDarkScheme  = DarkColors.toM3Scheme()

// The single ColorProviders instance passed to GlanceTheme at the widget root
// (the widget calls `GlanceTheme(colors = OrbitWidgetColorProviders) { ... }` from
// `provideGlance`). LightColors/DarkColors are `internal` in Color.kt; this file
// shares the `app.orbit.ui.theme` package, so the access is intentional.
//
// LAZY (D-11): glance-material3's two-arg ColorProviders factory
// internally calls `adjustColorToneForWidgetBackground` which routes through
// `android.graphics.Color.red()` (an Android-framework method, not a pure JVM
// one). Eager top-level initialisation would crash WidgetColorsTest on the JVM
// with `Method red in android.graphics.Color not mocked`. `lazy {}` defers
// construction until the first widget composition, where Android is real. The
// test path never touches OrbitWidgetColorProviders directly — it asserts
// against WidgetLightScheme/WidgetDarkScheme, which are pure data.
val OrbitWidgetColorProviders: ColorProviders by lazy {
    ColorProvidersFromM3(
        WidgetLightScheme,
        WidgetDarkScheme,
    )
}

/**
 * THEMING 2026-06-22 — build widget ColorProviders for the user's chosen theme,
 * accent dial, and light/dark mode, so a placed widget matches the in-app
 * appearance. Honors the mode override: SYSTEM keeps light=light / dark=dark
 * (Glance picks per system); LIGHT / DARK force both schemes to the chosen mode.
 *
 * Runtime-only (called from provideGlance) — D-11: the two-arg M3 factory
 * touches android.graphics.Color, which is not mocked on the JVM, so the test
 * path uses the static [OrbitWidgetColorProviders] above instead.
 */
fun orbitWidgetColorProviders(settings: ThemeSettings): ColorProviders {
    val lightColors = OrbitThemes.resolve(
        settings,
        isDark = settings.darkMode == OrbitDarkMode.DARK,
    ).colors
    val darkColors = OrbitThemes.resolve(
        settings,
        isDark = settings.darkMode != OrbitDarkMode.LIGHT,
    ).colors
    return ColorProvidersFromM3(lightColors.toM3Scheme(), darkColors.toM3Scheme())
}
