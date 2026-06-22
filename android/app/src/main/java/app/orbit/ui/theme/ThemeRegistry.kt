// android/app/src/main/java/app/orbit/ui/theme/ThemeRegistry.kt
//
// User-selectable color themes (2026-06-22).
//
// Design contract: Orbit keeps ONE calm neutral foundation (warm cream / warm
// charcoal surfaces) across every theme — personality comes from the accent
// family + five tonal "personality hues", never from repainting the whole
// surface (README "Visual foundations": quiet surface, one warm accent). So
// each theme is the shared LightColors/DarkColors base with its accent slots
// swapped, plus a derived [OrbitTones]. Warm is authored to the exact legacy
// values (zero visual change); Cool/Forest/Plum are generated from a hue
// through the contrast-guaranteed [accentForHue]; Mono is authored neutral.
//
// Material You is intentionally NOT wired here yet (2026-06-22 product call) —
// the registry leaves room: add an entry that builds its OrbitColors from
// dynamicLightColorScheme()/dynamicDarkColorScheme() and the resolver picks it
// up unchanged.
package app.orbit.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Stable, persisted theme identifiers. `key` is the DataStore value. */
enum class OrbitThemeId(val key: String, val displayName: String) {
    WARM("warm", "Warm"),
    COOL("cool", "Cool"),
    FOREST("forest", "Forest"),
    PLUM("plum", "Plum"),
    MONO("mono", "Mono"),
    ;

    companion object {
        val DEFAULT = WARM
        fun fromKey(key: String?): OrbitThemeId = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Light/dark control, independent of theme choice. */
enum class OrbitDarkMode(val key: String, val displayName: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        val DEFAULT = SYSTEM
        fun fromKey(key: String?): OrbitDarkMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * The user's full appearance choice. [accentHue] is null for "use the theme's
 * own accent"; an Int in [0,359] is a dial override applied on top of the theme.
 */
@Immutable
data class ThemeSettings(
    val themeId: OrbitThemeId = OrbitThemeId.DEFAULT,
    val darkMode: OrbitDarkMode = OrbitDarkMode.DEFAULT,
    val accentHue: Int? = null,
) {
    companion object {
        val DEFAULT = ThemeSettings()
    }
}

/** A theme's full color + tonal definition for both modes. */
@Immutable
data class OrbitThemeDef(
    val id: OrbitThemeId,
    val light: OrbitColors,
    val dark: OrbitColors,
    val lightTones: OrbitTones,
    val darkTones: OrbitTones,
)

/** Final, ready-to-provide theme after mode + accent-dial resolution. */
@Immutable
data class ResolvedTheme(
    val colors: OrbitColors,
    val tones: OrbitTones,
    val isDark: Boolean,
)

// Heat-ramp low ends (quiet near-surface tones); top end is the accent.
private val LIGHT_HEAT_LOW = Color(0xFFEFE8DC)
private val DARK_HEAT_LOW = Color(0xFF383330)
private val WARM_INK = Color(0xFF211E1C)

// ---- Warm — authored to the exact pre-theming values (identity preserved) ----

private val warmTriples = listOf(
    OrbitTones.ToneTriple(Color(0xFFEDD6CE), Color(0xFF9B4A32), Color(0xFFC8654A)), // terracotta
    OrbitTones.ToneTriple(Color(0xFFD8E3D6), Color(0xFF4E6A4B), Color(0xFF87A383)), // sage
    OrbitTones.ToneTriple(Color(0xFFF2E2BF), Color(0xFF8B6821), Color(0xFFD4A144)), // amber
    OrbitTones.ToneTriple(Color(0xFFF5E0DC), Color(0xFFA04838), Color(0xFFA04838)), // brick
    OrbitTones.ToneTriple(Color(0xFFF2ECE2), Color(0xFF6B6560), Color(0xFF9A928B)), // stone
)

private val warmDef = OrbitThemeDef(
    id = OrbitThemeId.WARM,
    light = LightColors, // accent already terracotta in the base
    dark = DarkColors,
    // Chips/avatars/rhythm were mode-agnostic before theming, so dark reuses the
    // same warm triples (preserves the existing dark look); heat ramp + list
    // tones stay mode-specific via the derive anchors.
    lightTones = deriveOrbitTones(
        toneTriples = warmTriples,
        accentTint = LightColors.accentTint,
        accentDeep = LightColors.accentPress,
        heatLow = LIGHT_HEAT_LOW,
        neutralBand = LightColors.bgSubtle,
        neutralWash = LightColors.surface,
        neutralName = LightColors.fg,
    ),
    darkTones = deriveOrbitTones(
        toneTriples = warmTriples,
        accentTint = DarkColors.accentTint,
        accentDeep = DarkColors.accent,
        heatLow = DARK_HEAT_LOW,
        neutralBand = DarkColors.surfaceAlt,
        neutralWash = DarkColors.surface,
        neutralName = DarkColors.fg,
    ),
)

/** Default tones for the [LocalOrbitTones] CompositionLocal (Warm, light). */
internal val WarmTones: OrbitTones = warmDef.lightTones

// ---- Generated themes (Cool / Forest / Plum) ----

private fun toneTripleFor(hue: Float, isDark: Boolean, neutral: Boolean): OrbitTones.ToneTriple {
    val s = if (neutral) 0.10f else 0.42f
    return if (isDark) {
        OrbitTones.ToneTriple(
            bg = hsl(hue, s * 0.85f, 0.24f),
            fg = hsl(hue, s, 0.80f),
            dot = hsl(hue, s + 0.08f, 0.60f),
        )
    } else {
        OrbitTones.ToneTriple(
            bg = hsl(hue, s * 0.7f, 0.88f),
            fg = hsl(hue, s + 0.06f, 0.32f),
            dot = hsl(hue, s + 0.08f, 0.50f),
        )
    }
}

private fun triplesFor(hue: Float, isDark: Boolean): List<OrbitTones.ToneTriple> {
    // Five personality slots spread around the base hue; the 5th is a calm neutral.
    val hues = listOf(hue, hue + 34f, hue - 42f, hue + 72f, hue + 12f)
    return hues.mapIndexed { i, h -> toneTripleFor(h, isDark, neutral = i == 4) }
}

private fun generatedDef(id: OrbitThemeId, hue: Float): OrbitThemeDef {
    val la = accentForHue(hue, isDark = false)
    val da = accentForHue(hue, isDark = true)
    val light = LightColors.copy(
        accent = la.accent, accentHover = la.accentHover, accentPress = la.accentPress,
        accentTint = la.accentTint, accentFg = la.accentFg,
    )
    val dark = DarkColors.copy(
        accent = da.accent, accentHover = da.accentHover, accentPress = da.accentPress,
        accentTint = da.accentTint, accentFg = da.accentFg,
    )
    return OrbitThemeDef(
        id = id,
        light = light,
        dark = dark,
        lightTones = deriveOrbitTones(
            toneTriples = triplesFor(hue, false),
            accentTint = la.accentTint, accentDeep = la.accentPress, heatLow = LIGHT_HEAT_LOW,
            neutralBand = LightColors.bgSubtle, neutralWash = LightColors.surface, neutralName = LightColors.fg,
        ),
        darkTones = deriveOrbitTones(
            toneTriples = triplesFor(hue, true),
            accentTint = da.accentTint, accentDeep = da.accent, heatLow = DARK_HEAT_LOW,
            neutralBand = DarkColors.surfaceAlt, neutralWash = DarkColors.surface, neutralName = DarkColors.fg,
        ),
    )
}

// ---- Mono — authored neutral (the accent is ink itself) ----

private fun monoTriples(isDark: Boolean): List<OrbitTones.ToneTriple> =
    if (isDark) {
        listOf(
            OrbitTones.ToneTriple(Color(0xFF3A352F), Color(0xFFE3DDD3), Color(0xFFC9C2B9)),
            OrbitTones.ToneTriple(Color(0xFF332E2A), Color(0xFFD5CEC4), Color(0xFFB4ADA3)),
            OrbitTones.ToneTriple(Color(0xFF423B34), Color(0xFFEAE4DA), Color(0xFFD8D1C6)),
            OrbitTones.ToneTriple(Color(0xFF2E2A26), Color(0xFFC6BFB5), Color(0xFFA39B91)),
            OrbitTones.ToneTriple(Color(0xFF38332E), Color(0xFFBDB6AC), Color(0xFF8F887F)),
        )
    } else {
        listOf(
            OrbitTones.ToneTriple(Color(0xFFE7E1D8), Color(0xFF3A3531), Color(0xFF6B6560)),
            OrbitTones.ToneTriple(Color(0xFFEFE9DF), Color(0xFF4A443F), Color(0xFF8A837B)),
            OrbitTones.ToneTriple(Color(0xFFE0D9CE), Color(0xFF2E2A26), Color(0xFF5A544E)),
            OrbitTones.ToneTriple(Color(0xFFF2ECE2), Color(0xFF55504A), Color(0xFF9A928B)),
            OrbitTones.ToneTriple(Color(0xFFDCD5CA), Color(0xFF211E1C), Color(0xFF7A736B)),
        )
    }

private val monoDef = OrbitThemeDef(
    id = OrbitThemeId.MONO,
    light = LightColors.copy(
        accent = Color(0xFF3A3531), accentHover = Color(0xFF2C2825), accentPress = WARM_INK,
        accentTint = Color(0xFFE7E1D8), accentFg = Color.White,
    ),
    dark = DarkColors.copy(
        accent = Color(0xFFE3DDD3), accentHover = Color(0xFFF0EBE4), accentPress = Color(0xFFC9C2B9),
        accentTint = Color(0xFF3A352F), accentFg = WARM_INK,
    ),
    lightTones = deriveOrbitTones(
        toneTriples = monoTriples(false),
        accentTint = Color(0xFFE7E1D8), accentDeep = Color(0xFF3A3531), heatLow = LIGHT_HEAT_LOW,
        neutralBand = LightColors.bgSubtle, neutralWash = LightColors.surface, neutralName = LightColors.fg,
    ),
    darkTones = deriveOrbitTones(
        toneTriples = monoTriples(true),
        accentTint = Color(0xFF3A352F), accentDeep = Color(0xFFE3DDD3), heatLow = DARK_HEAT_LOW,
        neutralBand = DarkColors.surfaceAlt, neutralWash = DarkColors.surface, neutralName = DarkColors.fg,
    ),
)

/** The curated theme registry + the resolver the theme layer reads. */
object OrbitThemes {
    val all: List<OrbitThemeDef> = listOf(
        warmDef,
        generatedDef(OrbitThemeId.COOL, hue = 211f),   // slate blue
        generatedDef(OrbitThemeId.FOREST, hue = 146f),  // deep green
        generatedDef(OrbitThemeId.PLUM, hue = 322f),    // muted magenta
        monoDef,
    )

    fun def(id: OrbitThemeId): OrbitThemeDef = all.first { it.id == id }

    /** Hue that seeds the dial when a theme is first opened (its own accent). */
    fun defaultHueFor(id: OrbitThemeId): Int = def(id).light.accent.hueDegrees().toInt()

    /** Public live-preview of the dial accent for a hue — used by the Settings
     *  Appearance UI (which can't see the internal [accentForHue]). */
    fun previewAccent(hue: Int, isDark: Boolean): Color = accentForHue(hue.toFloat(), isDark).accent

    private val heatEase = listOf(0.06f, 0.18f, 0.33f, 0.49f, 0.65f, 0.82f, 1.0f)

    /** Fold [OrbitDarkMode] + the system dark flag into a final boolean. */
    fun effectiveDark(settings: ThemeSettings, systemDark: Boolean): Boolean =
        when (settings.darkMode) {
            OrbitDarkMode.SYSTEM -> systemDark
            OrbitDarkMode.LIGHT -> false
            OrbitDarkMode.DARK -> true
        }

    /**
     * Resolve a [ThemeSettings] into final colors+tones for the given (already
     * decided) [isDark]. When an accent-hue override is present, the accent
     * slots are regenerated (contrast-guaranteed) and the accent-derived tonal
     * surfaces — the heat ramp top and the Home "A" card — are recomputed so
     * they track the dial; the multi-hue personality palette
     * (avatars/rhythm/chips) stays the theme's.
     */
    fun resolve(settings: ThemeSettings, isDark: Boolean): ResolvedTheme {
        val def = def(settings.themeId)
        var colors = if (isDark) def.dark else def.light
        var tones = if (isDark) def.darkTones else def.lightTones

        val hue = settings.accentHue
        if (hue != null) {
            val a = accentForHue(hue.toFloat(), isDark)
            colors = colors.copy(
                accent = a.accent, accentHover = a.accentHover, accentPress = a.accentPress,
                accentTint = a.accentTint, accentFg = a.accentFg,
            )
            val heatLow = if (isDark) DARK_HEAT_LOW else LIGHT_HEAT_LOW
            val accentDeep = if (isDark) a.accent else a.accentPress
            tones = tones.copy(
                heatRamp = heatEase.map { t -> lerp(heatLow, accentDeep, t) },
                listTones = listOf(
                    OrbitTones.ListTone(
                        band = a.accentTint,
                        wash = lerp(a.accentTint, colors.surface, 0.55f),
                        nameFg = accentDeep,
                    ),
                    tones.listTones[1],
                ),
            )
        }
        return ResolvedTheme(colors = colors, tones = tones, isDark = isDark)
    }
}
