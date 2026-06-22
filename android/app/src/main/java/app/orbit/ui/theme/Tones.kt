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
 * @param accentTint  selected-state wash, used as Home card A band
 * @param accentDeep  the accent's pressed/deep value, used as Home card A name color + heat-ramp top
 * @param heatLow     low-density end of the heat ramp (a quiet near-background tone)
 * @param neutralBand Home card B band (a subtle surface zone)
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
            // A — accent-tinted
            OrbitTones.ListTone(
                band = accentTint,
                wash = lerp(accentTint, neutralWash, 0.55f),
                nameFg = accentDeep,
            ),
            // B — neutral
            OrbitTones.ListTone(
                band = neutralBand,
                wash = neutralWash,
                nameFg = neutralName,
            ),
        ),
    )
}

internal val LocalOrbitTones = staticCompositionLocalOf { WarmTones }
