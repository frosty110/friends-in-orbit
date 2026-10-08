// android/app/src/main/java/app/orbit/ui/theme/Tones.kt
//
// Tonal families — avatar palettes, the 7-day rhythm bars, list-chip tones, the
// Home card A/B treatments, and the Card View heat ramp.
//
// THEMING (2026-06-22): these were five hardcoded warm `object`s
// (OrbitChipTones / OrbitAvatarTones / OrbitHeatRamp / OrbitListTones /
// OrbitRhythmTones). They are now a single per-theme, per-mode [OrbitTones]
// instance provided through [LocalOrbitTones] and read as `OrbitTheme.tones`.
// Each curated theme derives its tones from a small set of personality hues +
// its accent (see [deriveOrbitTones] and ThemeRegistry.kt), so the avatars,
// rhythm bars, and Home cards stay coherent with the chosen accent instead of
// being locked to terracotta. Mirrors the `OrbitTheme.colors` pattern exactly.
//
// SPLASH BOUNDARY (D-09): res/values/colors.xml carries cream/charcoal/terracotta
// for the pre-Compose splash. Do NOT consolidate those here — the Android
// framework reads them before Compose exists.
package app.orbit.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The full set of tonal treatments for one theme in one mode (light or dark).
 *
 * Authored per theme as a list of five [ToneTriple] "personality hues"; the
 * avatar palettes, rhythm bars, list chips, and heat ramp all derive from those
 * plus the theme's accent. See [deriveOrbitTones].
 */
@Immutable
data class OrbitTones(
    val chip: ChipTones,
    /** (bg, fg) avatar background/initials pairs, indexed by a name hash. */
    val avatarPalettes: List<Pair<Color, Color>>,
    /** Saturated per-person rhythm-strip bar colors. */
    val rhythmBars: List<Color>,
    /** 7-stop warmth ramp, low -> high density, for the Card View HeatStrip. */
    val heatRamp: List<Color>,
    /** Alternating Home card treatments (A accent-tinted, B neutral). */
    val listTones: List<ListTone>,
) {
    @Immutable
    data class ToneTriple(val bg: Color, val fg: Color, val dot: Color)

    /** The five named chip slots. Names are historical (data.ChipTone); the
     *  colors are whatever the active theme assigns to each slot. */
    @Immutable
    data class ChipTones(
        val terracotta: ToneTriple,
        val sage: ToneTriple,
        val amber: ToneTriple,
        val brick: ToneTriple,
        val stone: ToneTriple,
    )

    @Immutable
    data class ListTone(val band: Color, val wash: Color, val nameFg: Color)

    /** Deterministic name -> avatar-palette index (same hash the old
     *  OrbitAvatarTones used, so existing avatar colors are preserved). */
    fun indexForName(name: String): Int {
        var hash = 0
        for (c in name) hash = hash * 31 + c.code
        return (hash and Int.MAX_VALUE) % avatarPalettes.size
    }

    fun avatarPalette(name: String): Pair<Color, Color> = avatarPalettes[indexForName(name)]

    /** Stable per-person rhythm bar keyed by contactId (the rhythm carries ids). */
    fun rhythmBarForId(id: Long): Color =
        rhythmBars[((id % rhythmBars.size + rhythmBars.size) % rhythmBars.size).toInt()]

    /** Bucket a density value in [0,1] into the 7-stop heat ramp. Thresholds
     *  match the pre-theming ramp so HeatStrip emphasis is unchanged. */
    fun heatColor(v: Float): Color = when {
        v < 0.08f -> heatRamp[0]
        v < 0.20f -> heatRamp[1]
        v < 0.35f -> heatRamp[2]
        v < 0.50f -> heatRamp[3]
        v < 0.65f -> heatRamp[4]
        v < 0.80f -> heatRamp[5]
        else -> heatRamp[6]
    }

    /** Alternating A/B tone keyed by the card's row position. */
    fun listTone(key: Long): ListTone {
        val idx = ((key % listTones.size + listTones.size) % listTones.size).toInt()
        return listTones[idx]
    }
}

/**
 * Build a theme's [OrbitTones] from its personality hues + accent + neutral
 * anchors. Centralizing this keeps every curated theme structurally identical:
 * authors supply five [OrbitTones.ToneTriple]s and the accent/neutral anchors,
 * and the rhythm bars / avatar palettes / list tones / heat ramp all derive.
 *
 * @param toneTriples the five personality slots (terracotta/sage/amber/brick/stone order)
 * @param accentTint  selected-state wash; the start point of Home card A's band (see [accentListTone])
 * @param accentDeep  the accent's pressed/deep value, the start point of Home card A's name color + heat-ramp top
 * @param heatLow     low-density end of the heat ramp (a quiet near-background tone)
 * @param neutralBand Home card B band (a subtle surface zone), and the surface card A's band is held to
 * @param neutralWash Home card B wash (the base surface)
 * @param neutralName Home card B name color (primary fg)
 */
internal fun deriveOrbitTones(
    toneTriples: List<OrbitTones.ToneTriple>,
    accentTint: Color,
    accentDeep: Color,
    heatLow: Color,
    neutralBand: Color,
    neutralWash: Color,
    neutralName: Color,
): OrbitTones {
    require(toneTriples.size == 5) { "OrbitTones needs exactly 5 personality hues" }
    val ease = listOf(0.06f, 0.18f, 0.33f, 0.49f, 0.65f, 0.82f, 1.0f)
    return OrbitTones(
        chip = OrbitTones.ChipTones(
            terracotta = toneTriples[0],
            sage = toneTriples[1],
            amber = toneTriples[2],
            brick = toneTriples[3],
            stone = toneTriples[4],
        ),
        avatarPalettes = toneTriples.map { it.bg to it.fg },
        rhythmBars = toneTriples.map { it.dot },
        heatRamp = ease.map { t -> lerp(heatLow, accentDeep, t) },
        listTones = listOf(
            // A, accent-tinted. Derived, not the raw tint: see accentListTone.
            accentListTone(
                accentTint = accentTint,
                accentDeep = accentDeep,
                neutralBand = neutralBand,
                neutralWash = neutralWash,
                neutralName = neutralName,
            ),
            // B, neutral
            OrbitTones.ListTone(
                band = neutralBand,
                wash = neutralWash,
                nameFg = neutralName,
            ),
        ),
    )
}

/**
 * Home card A: the accent-tinted band that carries the list's name row (the
 * name and the member count), and the wash under it that carries Next up (its
 * eyebrow and why line, since 2026-10-08, HOME-5) and the rhythm strip's
 * legend. Apart from the name, the text on it is the theme's ordinary fg,
 * fgMuted and fgSubtle, which the card reads from OrbitTheme.colors, so the
 * band has to be a surface those tokens are legible on (rules.md §Design 4),
 * for every hue the Wallpaper theme and the accent dial can produce.
 *
 * The raw accentTint is tuned as a selection wash under fg, not as a surface
 * for subtle text: across the hue wheel fgSubtle read between 3.0:1 and
 * 4.4:1 on it and fgMuted as low as 4.0:1, and in dark mode the accent used
 * as the list name read 3.9:1 on the dark tint. A 72% alpha member count on
 * the band was the one miss found by eye before ThemeContrastTest checked
 * these pairs (home-5). The derivation:
 *
 *  - The band is the tint stepped toward [neutralWash] (the surface) until it
 *    is no darker (light mode) or no lighter (dark mode) than [neutralBand],
 *    the neutral surface ThemeContrastTest certifies every text token on
 *    (bgSubtle in light, surfaceAlt in dark). Contrast depends on luminance
 *    alone, so a band on that side of a certified surface clears the same
 *    ratios, whatever its hue. Warm's light band moves from EDD6CE to about
 *    F6EAE6, still visibly warmer than card B's F2ECE2.
 *  - The name is [accentDeep] stepped toward [neutralName] (ink in light,
 *    cream in dark) until it clears 4.5:1 on that band. Light accents need no
 *    step; the lifted dark accents need a small one.
 *
 * Both loops end on the neutral itself at worst, so they always terminate,
 * and [ThemeRegistry]'s accent-dial path builds card A through this function
 * too, so a dial hue cannot bypass it.
 */
internal fun accentListTone(
    accentTint: Color,
    accentDeep: Color,
    neutralBand: Color,
    neutralWash: Color,
    neutralName: Color,
): OrbitTones.ListTone {
    val certifiedLum = neutralBand.relativeLuminance()
    // Light mode is ink on cream, so the safe side is lighter than the
    // certified surface; dark mode is cream on charcoal, so darker.
    val textIsDark = neutralName.relativeLuminance() < certifiedLum
    fun carriesText(surface: Color): Boolean {
        val lum = surface.relativeLuminance()
        return if (textIsDark) lum >= certifiedLum else lum <= certifiedLum
    }
    var t = 0f
    var band = accentTint
    while (!carriesText(band) && t < 1f) {
        t = (t + TONE_STEP).coerceAtMost(1f)
        band = lerp(accentTint, neutralWash, t)
    }
    var u = 0f
    var nameFg = accentDeep
    while (contrastRatio(nameFg, band) < TEXT_AA && u < 1f) {
        u = (u + TONE_STEP).coerceAtMost(1f)
        nameFg = lerp(accentDeep, neutralName, u)
    }
    return OrbitTones.ListTone(
        band = band,
        // Lighter (or in dark mode darker) than the band it sits under, as
        // before; based on the derived band so the two keep their relation.
        wash = lerp(band, neutralWash, 0.55f),
        nameFg = nameFg,
    )
}

/** How far each step of [accentListTone] moves toward the neutral; 20 steps span the whole way. */
private const val TONE_STEP = 0.05f

/** WCAG AA for normal-size text, the floor rules.md §Design 4 holds every text token to. */
private const val TEXT_AA = 4.5f

internal val LocalOrbitTones = staticCompositionLocalOf { WarmTones }
