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
 * Per-list tonal families for the Home redesign (vision HOME-5 / HOME-6). Each
 * list gets a stable warm color from its id hash; the card renders a soft header
 * band ([band]) over a lighter graph wash ([wash]), with [nameFg] for the list
 * name. Mode-aware (band/wash differ light vs dark), so this is a function, not a
 * plain enumeration (cf. [OrbitHeatRamp]). Shades ported from the approved
 * `vision/00-home/prototype` (two-tone).
 */
object OrbitListTones {
    @Immutable
    data class ListTone(val band: Color, val wash: Color, val nameFg: Color)

    private val light = listOf(
        ListTone(Color(0xFFEDD6CE), Color(0xFFF7EDE7), Color(0xFF9B4A32)), // terracotta
        ListTone(Color(0xFFD8E3D6), Color(0xFFEBF1E9), Color(0xFF4F6A4B)), // sage
        ListTone(Color(0xFFEEE3B8), Color(0xFFF7F0D9), Color(0xFF6F5E1C)), // olive
        ListTone(Color(0xFFDCE1DE), Color(0xFFEDF0EE), Color(0xFF46504B)), // slate
        ListTone(Color(0xFFECE4DA), Color(0xFFF6F0E8), Color(0xFF5C554E)), // stone
    )
    private val dark = listOf(
        ListTone(Color(0xFF4A2D24), Color(0xFF382722), Color(0xFFEDC3B4)),
        ListTone(Color(0xFF344035), Color(0xFF2C3329), Color(0xFFC6D8C2)),
        ListTone(Color(0xFF4A3E25), Color(0xFF38301F), Color(0xFFE6D69A)),
        ListTone(Color(0xFF353C39), Color(0xFF2B302D), Color(0xFFC7D0CB)),
        ListTone(Color(0xFF393430), Color(0xFF2F2B27), Color(0xFFCFC7BD)),
    )

    /** Stable family for a list, keyed by its (rename-proof) id. */
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
}
