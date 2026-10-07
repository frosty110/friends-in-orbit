package app.orbit.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * LIST-29: the tints of New list's three rhythm templates, most frequent
 * first ("Inner orbit", about weekly; "Family", every couple of weeks;
 * "Drifted", about monthly). One ramp from the theme's accent tint toward
 * its surface, so the more often a list brings people up, the warmer its
 * tile reads. The owner asked for the boxes to be "colour coded slightly";
 * the tint is decoration, and each tile's subtitle still says its rhythm in
 * words.
 *
 * Derived here, in the theme layer, from tokens alone (rules.md §Design 1),
 * the way the Home card's band and the heat ramp are ([deriveOrbitTones]),
 * so it follows the curated themes, the Wallpaper theme and the accent dial
 * in both modes without a colour of its own.
 *
 * The tiles carry their name in `fg` and their subtitle and icon in
 * `fgMuted`, so the warmest stop is the accent tint stepped toward the
 * surface until both clear 4.5:1 on it (rules.md §Design 4). The raw tint
 * does not always: Warm's light tint carries `fgMuted` at about 4.1:1. The
 * other two stops lie between that one and the surface, both of which carry
 * the text; ThemeContrastTest checks every stop itself, in every theme,
 * mode, Wallpaper hue and dial hue, since a mix of two legible colours is
 * not legible by arithmetic alone.
 */
internal fun rhythmTemplateTints(colors: OrbitColors): List<Color> {
    var t = 0f
    var warmest = colors.accentTint
    while (!carriesTileText(warmest, colors) && t < 1f) {
        t = (t + TINT_STEP).coerceAtMost(1f)
        warmest = lerp(colors.accentTint, colors.surface, t)
    }
    return RAMP.map { toward -> lerp(warmest, colors.surface, toward) }
}

private fun carriesTileText(tint: Color, colors: OrbitColors): Boolean =
    contrastRatio(colors.fg, tint) >= TILE_TEXT_MIN && contrastRatio(colors.fgMuted, tint) >= TILE_TEXT_MIN

/**
 * How far each stop sits from the warmest toward the surface: the first is
 * the warmest itself, the last still visibly tinted beside the neutral
 * "Start from blank" tile, which is the plain surface.
 */
private val RAMP = listOf(0f, 0.35f, 0.65f)

/** How far each step of the search moves toward the surface; 20 steps span the whole way. */
private const val TINT_STEP = 0.05f

/** WCAG AA for text, the floor rules.md §Design 4 holds every text token to. */
private const val TILE_TEXT_MIN = 4.5f
