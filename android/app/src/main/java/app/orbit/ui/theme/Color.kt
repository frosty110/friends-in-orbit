package app.orbit.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Raw primitives — ported 1:1 from colors_and_type.css :root block.
// Never reference these from screens; go through OrbitColors instead.
internal object OrbitPrimitives {
    val Terracotta     = Color(0xFFC8654A)
    val TerracottaDark = Color(0xFF9B4A32)
    val TerracottaTint = Color(0xFFEDD6CE)
    val Sage           = Color(0xFF87A383)
    val SageTint       = Color(0xFFD8E3D6)
    val Olive          = Color(0xFFB49A3A)
    val OliveTint      = Color(0xFFEEE3B8)
    val Amber          = Color(0xFFD4A144)
    val AmberTint      = Color(0xFFF2E2BF)
    val Clay           = Color(0xFF8E5141)
    val StoneDeep      = Color(0xFF524B45)
    val Slate          = Color(0xFF6B7570)
    val SlateTint      = Color(0xFFDCE1DE)

    val Cream          = Color(0xFFFAF6F0)
    val CreamDeep      = Color(0xFFF2ECE2)
    val Paper          = Color(0xFFFFFFFF)
    val Ink            = Color(0xFF211E1C)
    val InkSoft        = Color(0xFF3A3531)
    val Stone          = Color(0xFF6B6560)
    val StoneSoft      = Color(0xFF9A928B)
    val Line           = Color(0xFFE5DDD1)
    val LineSoft       = Color(0xFFEFE8DC)

    val Charcoal       = Color(0xFF1F1C1A)
    val Graphite       = Color(0xFF2B2724)
    val GraphiteDeep   = Color(0xFF35302C)
    val CreamText      = Color(0xFFF0EBE4)
    val CreamDim       = Color(0xFFC9C2B9)
    val LineDark       = Color(0xFF3D3631)

    val AccentHover    = Color(0xFFB85A40)   // light
    val AccentDark     = Color(0xFFD87560)   // dark-mode lifted terracotta
    val AccentDarkHover = Color(0xFFE18670)
    val AccentTintDark = Color(0xFF4A2D24)
    val BgSubtleDark   = Color(0xFF25211F)
    val SoftDark       = Color(0xFFDDD5CC)
    val PositiveTintDk = Color(0xFF344035)
    val WarningTintDk  = Color(0xFF4A3E25)
    val LineSoftDark   = Color(0xFF332E2A)
    val FgSubtleDark   = Color(0xFF8F887F)
}

// Semantic slots. Mirrors the CSS `--bg`, `--fg`, `--accent`, etc.
@Immutable
data class OrbitColors(
    val bg: Color,
    val bgSubtle: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val fg: Color,
    val fgStrong: Color,
    val fgSoft: Color,
    val fgMuted: Color,
    val fgSubtle: Color,
    val line: Color,
    val lineSoft: Color,
    val accent: Color,
    val accentHover: Color,
    val accentPress: Color,
    val accentTint: Color,
    val accentFg: Color,
    val positive: Color,
    val positiveTint: Color,
    val warning: Color,
    val warningTint: Color,
    val urgent: Color,
    val urgentTint: Color,
    val dangerSoft: Color,
    val danger: Color,
    val info: Color,
    val infoTint: Color,
    val swipeGhostDefer: Color,     // MT-05 — "Later" drag hint; never on danger family
    val swipeGhostSooner: Color,    // MT-06 — "Sooner" drag hint; semantic alias to positive
    val isDark: Boolean,
)

internal val LightColors = OrbitColors(
    bg = OrbitPrimitives.Cream,
    bgSubtle = OrbitPrimitives.CreamDeep,
    surface = OrbitPrimitives.Paper,
    surfaceAlt = OrbitPrimitives.CreamDeep,
    fg = OrbitPrimitives.Ink,
    fgStrong = OrbitPrimitives.Ink,
    fgSoft = OrbitPrimitives.InkSoft,
    fgMuted = OrbitPrimitives.Stone,
    fgSubtle = OrbitPrimitives.StoneSoft,
    line = OrbitPrimitives.Line,
    lineSoft = OrbitPrimitives.LineSoft,
    accent = OrbitPrimitives.Terracotta,
    accentHover = OrbitPrimitives.AccentHover,
    accentPress = OrbitPrimitives.TerracottaDark,
    accentTint = OrbitPrimitives.TerracottaTint,
    accentFg = Color.White,
    positive = OrbitPrimitives.Sage,
    positiveTint = OrbitPrimitives.SageTint,
    warning = OrbitPrimitives.Olive,
    warningTint = OrbitPrimitives.OliveTint,
    urgent = OrbitPrimitives.Amber,
    urgentTint = OrbitPrimitives.AmberTint,
    dangerSoft = OrbitPrimitives.StoneDeep,
    danger = OrbitPrimitives.Clay,
    info = OrbitPrimitives.Slate,
    infoTint = OrbitPrimitives.SlateTint,
    swipeGhostDefer = OrbitPrimitives.InkSoft,    // MT-05 — muted fg, never clay/terracotta
    swipeGhostSooner = OrbitPrimitives.Sage,      // MT-06 — matches positive
    isDark = false,
)

internal val DarkColors = OrbitColors(
    bg = OrbitPrimitives.Charcoal,
    bgSubtle = OrbitPrimitives.BgSubtleDark,
    surface = OrbitPrimitives.Graphite,
    surfaceAlt = OrbitPrimitives.GraphiteDeep,
    fg = OrbitPrimitives.CreamText,
    fgStrong = OrbitPrimitives.CreamText,
    fgSoft = OrbitPrimitives.SoftDark,
    fgMuted = OrbitPrimitives.CreamDim,
    fgSubtle = OrbitPrimitives.FgSubtleDark,
    line = OrbitPrimitives.LineDark,
    lineSoft = OrbitPrimitives.LineSoftDark,
    accent = OrbitPrimitives.AccentDark,
    accentHover = OrbitPrimitives.AccentDarkHover,
    accentPress = OrbitPrimitives.TerracottaDark,
    accentTint = OrbitPrimitives.AccentTintDark,
    accentFg = Color.White,
    positive = OrbitPrimitives.Sage,
    positiveTint = OrbitPrimitives.PositiveTintDk,
    warning = OrbitPrimitives.Olive,
    warningTint = OrbitPrimitives.WarningTintDk,
    urgent = OrbitPrimitives.Amber,
    urgentTint = OrbitPrimitives.AmberTint,
    dangerSoft = OrbitPrimitives.StoneDeep,
    danger = OrbitPrimitives.Clay,
    info = OrbitPrimitives.Slate,
    infoTint = OrbitPrimitives.SlateTint,
    swipeGhostDefer = OrbitPrimitives.SoftDark,   // MT-05 — dark-mode fg-soft
    swipeGhostSooner = OrbitPrimitives.Sage,      // MT-06 — same sage in dark per UI-SPEC
    isDark = true,
)

internal val LocalOrbitColors = staticCompositionLocalOf { LightColors }
