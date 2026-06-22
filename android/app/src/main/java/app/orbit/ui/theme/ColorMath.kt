// android/app/src/main/java/app/orbit/ui/theme/ColorMath.kt
//
// Pure color math shared by the theme registry, the accent dial, and the
// WCAG contrast test. No Compose-runtime dependency — every function here is a
// plain transform on `androidx.compose.ui.graphics.Color`, so it is fully
// JVM-unit-testable (ContrastTest asserts against these directly).
//
// THEMING (2026-06-22): introduced for user-selectable color themes. The
// accent-dial path MUST route every generated accent through
// [accentForHue] so a user can never land on an inaccessible primary — the
// generator lowers lightness until white-on-accent clears WCAG AA.
package app.orbit.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.1 relative luminance of an sRGB color (0..1). Compose `Color`
 * channels are already linear-ish sRGB floats in [0,1]; we apply the standard
 * gamma expansion per channel.
 */
internal fun Color.relativeLuminance(): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

/**
 * WCAG contrast ratio between two colors, in [1, 21]. Order-independent.
 * AA body text needs >= 4.5; AA large text / UI components need >= 3.0.
 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    val hi = max(la, lb)
    val lo = min(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/** Pick whichever of the two foregrounds reads more legibly on [bg]. */
internal fun bestForeground(bg: Color, light: Color = Color.White, dark: Color = Color(0xFF211E1C)): Color =
    if (contrastRatio(bg, light) >= contrastRatio(bg, dark)) light else dark

/**
 * HSL -> Color. [h] in degrees [0,360), [s] and [l] in [0,1].
 * Standard CSS HSL conversion.
 */
internal fun hsl(h: Float, s: Float, l: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
    )
}

/** Color -> hue degrees [0,360). Used to seed the dial from a theme's accent. */
internal fun Color.hueDegrees(): Float {
    val r = red
    val g = green
    val b = blue
    val maxc = max(r, max(g, b))
    val minc = min(r, min(g, b))
    val delta = maxc - minc
    if (delta < 1e-5f) return 0f
    val h = when (maxc) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return ((h % 360f) + 360f) % 360f
}

/**
 * A complete accent family for one mode. Mirrors the five accent slots in
 * [OrbitColors] so the resolver can splice it in wholesale.
 */
internal data class AccentSet(
    val accent: Color,
    val accentHover: Color,
    val accentPress: Color,
    val accentTint: Color,
    val accentFg: Color,
)

/**
 * Generate an accessible accent family from a single hue. This is the guard
 * that makes the user-facing accent dial unbreakable: we fix a tasteful,
 * low-saturation S/L (Orbit is never fully saturated — see README "Visual
 * foundations") then lower lightness until white-on-accent clears AA. The tint
 * is a soft, high-lightness wash for selected/pill backgrounds.
 *
 * Light mode: mid-tone accent, white foreground. Dark mode: lifted accent so
 * it reads on charcoal; foreground chosen by contrast (white or warm ink).
 */
internal fun accentForHue(hue: Float, isDark: Boolean): AccentSet {
    val saturation = if (isDark) 0.52f else 0.48f
    var lightness = if (isDark) 0.60f else 0.46f

    // Lower lightness until white text clears AA on the accent fill. White is
    // the brand accent-fg; darkening the fill monotonically raises contrast.
    var accent = hsl(hue, saturation, lightness)
    var guard = 0
    while (contrastRatio(accent, Color.White) < 4.5f && lightness > 0.18f && guard < 40) {
        lightness -= 0.02f
        accent = hsl(hue, saturation, lightness)
        guard++
    }

    val accentFg = bestForeground(accent)
    val press = hsl(hue, saturation, (lightness - 0.10f).coerceAtLeast(0.10f))
    val hover = hsl(hue, saturation, (lightness - 0.05f).coerceAtLeast(0.12f))
    val tint = if (isDark) hsl(hue, 0.38f, 0.22f) else hsl(hue, 0.34f, 0.88f)

    return AccentSet(
        accent = accent,
        accentHover = hover,
        accentPress = press,
        accentTint = tint,
        accentFg = accentFg,
    )
}
