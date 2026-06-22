// android/app/src/main/java/app/orbit/ui/theme/Tones.kt
//
// Tonal families promoted from inline literals in ui/components/Chip.kt:24-28,
// ui/components/Avatar.kt:22-26, and ui/screens/card/CardViewScreen.kt:413-418
// (see RESEARCH §Pattern 3, PATTERNS §"Tones.kt"). Per D-04 these are plain
// `object`s, not `staticCompositionLocalOf` — design enumerations don't vary
// between light and dark mode (Pitfall 7).
//
// SPLASH BOUNDARY (D-09): res/values/colors.xml carries cream/charcoal/terracotta
// for the pre-Compose splash. Do NOT consolidate those into OrbitPrimitives —
// the Android framework reads them before Compose exists.
package app.orbit.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

object OrbitChipTones {
    // Each tone exposes (bg, fg, dot). Sourced from Chip.kt:24-28 verbatim;
    // cross-referenced to OrbitPrimitives where a primitive matches.
    object Terracotta {
        val bg  = OrbitPrimitives.TerracottaTint   // 0xFFEDD6CE
        val fg  = OrbitPrimitives.TerracottaDark   // 0xFF9B4A32
        val dot = OrbitPrimitives.Terracotta       // 0xFFC8654A
    }
    object Sage {
        val bg  = OrbitPrimitives.SageTint         // 0xFFD8E3D6
        val fg  = Color(0xFF4E6A4B)                // no OrbitPrimitives entry — documented here
        val dot = OrbitPrimitives.Sage             // 0xFF87A383
    }
    object Amber {
        val bg  = OrbitPrimitives.AmberTint        // 0xFFF2E2BF
        val fg  = Color(0xFF8B6821)                // no OrbitPrimitives entry
        val dot = OrbitPrimitives.Amber            // 0xFFD4A144
    }
    object Brick {
        val bg  = Color(0xFFF5E0DC)                // no OrbitPrimitives entry
        val fg  = Color(0xFFA04838)                // no OrbitPrimitives entry
        val dot = Color(0xFFA04838)                // intentional: same as fg per Chip.kt:27
    }
    object Stone {
        val bg  = OrbitPrimitives.CreamDeep        // 0xFFF2ECE2
        val fg  = OrbitPrimitives.Stone            // 0xFF6B6560
        val dot = OrbitPrimitives.StoneSoft        // 0xFF9A928B
    }
}

object OrbitAvatarTones {
    // Deterministic warm palette keyed off name hash. Each pair is (bg, fg).
    // Verbatim from Avatar.kt:21-27; cross-referenced to OrbitPrimitives where possible.
    val palettes: List<Pair<Color, Color>> = listOf(
        OrbitPrimitives.TerracottaTint to OrbitPrimitives.TerracottaDark,
        OrbitPrimitives.SageTint       to Color(0xFF4E6A4B),
        OrbitPrimitives.AmberTint      to Color(0xFF8B6821),
        Color(0xFFF5E0DC)              to Color(0xFFA04838),
        Color(0xFFE8DDD1)              to OrbitPrimitives.Stone,
    )

    // Shared name→palette hash (the loop was inline in Avatar.kt). Exposed so the
    // rhythm strip ([OrbitRhythmTones]) can pick the SAME index for a person, so
    // their avatar and their rhythm bars read as one color.
    fun indexFor(name: String): Int {
        var hash = 0
        for (c in name) hash = hash * 31 + c.code
        return (hash and Int.MAX_VALUE) % palettes.size
    }
}

object OrbitHeatRamp {
    // 7-stop warmth ramp keyed by float v in [0, 1]. Used by HeatStrip in
    // CardViewScreen. Two ramps (2026-06-09 dark-mode sweep): the original
    // light ramp starts near-cream, which on the dark card inverted the
    // visual emphasis — empty hours glowed brighter than active ones. The
    // dark ramp starts at a quiet warm charcoal and rises to the same
    // terracotta family, so density reads identically in both themes.
    fun colorFor(v: Float, isDark: Boolean = false): Color =
        if (isDark) darkRamp(v) else lightRamp(v)

    private fun lightRamp(v: Float): Color = when {
        v < 0.08f -> Color(0xFFEFE8DC)
        v < 0.20f -> Color(0xFFE8DCCA)
        v < 0.35f -> Color(0xFFDDC9AE)
        v < 0.50f -> Color(0xFFD5B992)
        v < 0.65f -> Color(0xFFCE9F62)
        v < 0.80f -> Color(0xFFC8854A)
        else      -> Color(0xFFA76337)
    }

    private fun darkRamp(v: Float): Color = when {
        v < 0.08f -> Color(0xFF383330)
        v < 0.20f -> Color(0xFF453B32)
        v < 0.35f -> Color(0xFF584736)
        v < 0.50f -> Color(0xFF6F563A)
        v < 0.65f -> Color(0xFF8A663F)
        v < 0.80f -> Color(0xFFA87844)
        else      -> Color(0xFFC8854A)
    }
}

/**
 * Tonal card treatments for the Home redesign (vision HOME-5 / HOME-6).
 *
 * Two tones only — both inside Orbit's single-accent identity (warm cream +
 * terracotta), deliberately NO cool colours:
 *   - **A** — soft terracotta: a tinted header [band] over a lighter terracotta
 *     [wash].
 *   - **B** — warm neutral: a cream-deep band over a near-white wash, with
 *     terracotta left to do its job as the lone accent (chevron, today marker).
 *
 * Cards alternate A/B **by position** ([forKey] with the row index) so adjacent
 * cards separate without painting the screen in arbitrary per-list colours. The
 * earlier 5-family per-id palette read as a generic dashboard and buried the
 * terracotta accent under sage/slate; this keeps Home unmistakably Orbit.
 * Mode-aware (band/wash differ light vs dark), so this is a function, not a
 * plain enumeration (cf. [OrbitHeatRamp]).
 */
object OrbitListTones {
    @Immutable
    data class ListTone(val band: Color, val wash: Color, val nameFg: Color)

    private val light = listOf(
        ListTone(band = Color(0xFFEDD6CE), wash = Color(0xFFF7EDE7), nameFg = Color(0xFF9B4A32)), // A terracotta
        ListTone(band = Color(0xFFF2ECE2), wash = Color(0xFFFFFFFF), nameFg = Color(0xFF211E1C)), // B neutral
    )
    private val dark = listOf(
        ListTone(band = Color(0xFF4A2D24), wash = Color(0xFF382722), nameFg = Color(0xFFEDC3B4)), // A terracotta
        ListTone(band = Color(0xFF35302C), wash = Color(0xFF2B2724), nameFg = Color(0xFFF0EBE4)), // B neutral
    )

    /** Alternating A/B tone, keyed by the card's row position. */
    fun forKey(key: Long, isDark: Boolean): ListTone {
        val palette = if (isDark) dark else light
        val idx = ((key % palette.size + palette.size) % palette.size).toInt()
        return palette[idx]
    }
}

/**
 * Saturated per-person bar colors for the Home 7-day rhythm strip (HOME-7).
 * Indexed by the same name hash as [OrbitAvatarTones] (length matches), so a
 * person's rhythm bars read as the same colour as their avatar. Warm family
 * only — no rainbow.
 */
object OrbitRhythmTones {
    private val bars = listOf(
        OrbitPrimitives.Terracotta, // 0
        OrbitPrimitives.Sage,       // 1
        OrbitPrimitives.Amber,      // 2
        Color(0xFFA04838),          // 3 — brick (matches avatar palette 3)
        OrbitPrimitives.StoneSoft,  // 4
    )

    fun barFor(name: String): Color = bars[OrbitAvatarTones.indexFor(name)]

    /** Stable per-person bar colour keyed by contactId (the rhythm carries ids, not names). */
    fun barForId(id: Long): Color = bars[((id % bars.size + bars.size) % bars.size).toInt()]
}
