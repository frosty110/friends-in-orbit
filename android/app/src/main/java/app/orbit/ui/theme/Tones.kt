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
