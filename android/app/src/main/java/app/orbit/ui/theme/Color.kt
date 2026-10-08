package app.orbit.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Raw primitives — ported 1:1 from colors_and_type.css :root block.
// Never reference these from screens; go through OrbitColors instead.
internal object OrbitPrimitives {
    // Brand terracotta. Deepened 2026-10-05 from #C8654A (same hue, lower
    // lightness): white text on it was 3.88:1, below WCAG AA's 4.5:1 for the
    // label of the app's main button. Now 4.85:1, and 4.5:1 as text on cream.
    val Terracotta     = Color(0xFFB85338)
    val TerracottaDark = Color(0xFF90412C)
    val TerracottaTint = Color(0xFFEDD6CE)
    val Sage           = Color(0xFF87A383)
    val SageTint       = Color(0xFFD8E3D6)
    val Olive          = Color(0xFFB49A3A)
    val OliveTint      = Color(0xFFEEE3B8)
    val Amber          = Color(0xFFD4A144)
    val AmberTint      = Color(0xFFF2E2BF)
    val Clay           = Color(0xFF8E5141)
    // Dark-mode danger — the light Clay reads only 2.4:1 on the dark surface
    // (destructive text was barely legible). Lifted to clear WCAG AA (~5.4:1),
    // mirroring how the accent is lifted for dark. THEMING 2026-06-22.
    val ClayDark       = Color(0xFFD9836F)
    val StoneDeep      = Color(0xFF524B45)
    val Slate          = Color(0xFF6B7570)
    val SlateTint      = Color(0xFFDCE1DE)

    val Cream          = Color(0xFFFAF6F0)
    val CreamDeep      = Color(0xFFF2ECE2)
    val Paper          = Color(0xFFFFFFFF)
    val Ink            = Color(0xFF211E1C)
    val InkSoft        = Color(0xFF3A3531)
    val Stone          = Color(0xFF6B6560)
    // Subtle text. Was #9A928B (2.84:1 on cream, failing AA); now 4.5:1 on the
    // darkest light surface it sits on (CreamDeep).
    val StoneSoft      = Color(0xFF716A63)
    val Line           = Color(0xFFE5DDD1)
    val LineSoft       = Color(0xFFEFE8DC)

    val Charcoal       = Color(0xFF1F1C1A)
    val Graphite       = Color(0xFF2B2724)
    val GraphiteDeep   = Color(0xFF35302C)
    val CreamText      = Color(0xFFF0EBE4)
    val CreamDim       = Color(0xFFC9C2B9)
    val LineDark       = Color(0xFF3D3631)

    // HOME-8: call-direction rim colors for the 7-day rhythm bars and the day
    // sheet's avatar rings. A bar's FILL is the person (OrbitTones.rhythmBars),
    // its RIM is the direction, so the two channels must never be confusable.
    // Pink and teal sit outside every theme's warm personality range (the
    // fills are terracotta, sage, amber, brick and stone dots), and they are
    // ~180° apart, so the hue difference survives a 3dp band.
    //
    // Was violet #5E3D96 / blue #2F84B8 (dark #B49BEA / #6FBBE0) until
    // 2026-10-07: a 2dp rim pressed straight against a pastel fill read as
    // part of the fill, and in dark the lifted violet sat close to the warm
    // fills. The owner could not tell who called whom. The fix has two parts:
    // this pair (CIELAB ΔE 101 light, 116 dark, against 47 / 40 before), and
    // the DirSeparator ring between rim and fill.
    //
    // Colour-vision deficiency: the pair is also separated in LIGHTNESS (1.6:1
    // light, 2.4:1 dark), which is what still tells them apart once the
    // red-green axis collapses. That is why the light pink is a deep raspberry
    // and not the brighter #EC4899: the brighter one matches the teal's
    // lightness and the deutan ΔE falls from 20 to 9.
    val DirOutgoing    = Color(0xFFBE185D)   // light: you reached out
    val DirIncoming    = Color(0xFF0D9488)   // light: they reached you
    val DirOutgoingDk  = Color(0xFFEC4899)   // dark: neon, against the black ring
    val DirIncomingDk  = Color(0xFF5EEAD4)
    // The insulating ring between a direction rim and the person fill it
    // wraps. Near-black in both modes: it is the darkest thing on the card, so
    // both rims stand off it (light 3.1 and 5.0:1, dark 5.3 and 12.6:1) and the
    // rim can no longer bleed into a fill of similar lightness.
    val DirSeparator   = Color(0xFF141210)

    val AccentHover    = Color(0xFFA44A32)   // light; hover/press deepen from Terracotta
    // Green status text ("Allowed"): Sage itself is 2.76:1 on paper, so text
    // uses this deeper sage (4.5:1); Sage stays for dots and fills.
    val SageText       = Color(0xFF5D7859)
    val AccentDark     = Color(0xFFD87560)   // dark-mode lifted terracotta
    val AccentDarkHover = Color(0xFFE18670)
    // Dark-mode press: deeper than AccentDark but still 4.5:1 with the ink
    // label dark mode uses on the accent.
    val AccentDarkPress = Color(0xFFD3654D)
    val AccentTintDark = Color(0xFF4A2D24)
    val BgSubtleDark   = Color(0xFF25211F)
    val SoftDark       = Color(0xFFDDD5CC)
    val PositiveTintDk = Color(0xFF344035)
    val WarningTintDk  = Color(0xFF4A3E25)
    val LineSoftDark   = Color(0xFF332E2A)
    // Was #8F887F (4.23:1 on Graphite); now 4.5:1 on GraphiteDeep.
    val FgSubtleDark   = Color(0xFF9D978F)
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
    // Text-safe positive (status words such as "Allowed"); `positive` is for
    // dots and fills and is too light to read as text.
    val positiveText: Color,
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
    // HOME-8 — rhythm-bar direction rims. Semantic (like positive/danger), not
    // personality: every theme inherits the same pair via `.copy()`, because a
    // per-theme direction hue would collide with that theme's own accent (Cool's
    // blue, Plum's violet) and the cue would vanish exactly where it's needed.
    val directionOutgoing: Color,
    val directionIncoming: Color,
    // The black ring that insulates a direction rim from the fill inside it.
    val directionSeparator: Color,
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
    positiveText = OrbitPrimitives.SageText,
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
    directionOutgoing = OrbitPrimitives.DirOutgoing,
    directionIncoming = OrbitPrimitives.DirIncoming,
    directionSeparator = OrbitPrimitives.DirSeparator,
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
    accentPress = OrbitPrimitives.AccentDarkPress,
    accentTint = OrbitPrimitives.AccentTintDark,
    // Dark mode puts an ink label on the lifted accent (5.2:1); white on it
    // was 3.17:1. Material's dark schemes do the same with on-primary.
    accentFg = OrbitPrimitives.Ink,
    positive = OrbitPrimitives.Sage,
    positiveText = OrbitPrimitives.Sage,
    positiveTint = OrbitPrimitives.PositiveTintDk,
    warning = OrbitPrimitives.Olive,
    warningTint = OrbitPrimitives.WarningTintDk,
    urgent = OrbitPrimitives.Amber,
    urgentTint = OrbitPrimitives.AmberTint,
    dangerSoft = OrbitPrimitives.StoneDeep,
    danger = OrbitPrimitives.ClayDark,
    info = OrbitPrimitives.Slate,
    infoTint = OrbitPrimitives.SlateTint,
    swipeGhostDefer = OrbitPrimitives.SoftDark,   // MT-05 — dark-mode fg-soft
    swipeGhostSooner = OrbitPrimitives.Sage,      // MT-06 — same sage in dark per UI-SPEC
    directionOutgoing = OrbitPrimitives.DirOutgoingDk,
    directionIncoming = OrbitPrimitives.DirIncomingDk,
    directionSeparator = OrbitPrimitives.DirSeparator,
    isDark = true,
)

internal val LocalOrbitColors = staticCompositionLocalOf { LightColors }
