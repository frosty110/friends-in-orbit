package app.orbit.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Inter is the spec. Until TTF copies are bundled in res/font, we fall back to
// SansSerif (Roboto). Swap by setting FontFamily(Font(R.font.inter_regular, ...), ...).
private val OrbitFont: FontFamily = FontFamily.SansSerif

@Immutable
data class OrbitTypography(
    val hero: TextStyle,        // --fs-hero 32sp — contact name on card
    val title: TextStyle,       // --fs-title 24sp — screen titles
    val h2: TextStyle,          // --fs-h2 20sp
    val h3: TextStyle,          // --fs-h3 18sp — list row primary
    val body: TextStyle,        // --fs-body 16sp — never smaller
    val meta: TextStyle,        // --fs-meta 14sp — timestamps
    val listTile: TextStyle,    // --fs-list-tile 17sp Medium — Home list-tile primary (MT-01)
    val statValue: TextStyle,   // --fs-stat-value 14sp SemiBold — Card View stat rows (MT-02)
    val micro: TextStyle,       // --fs-micro 12sp — badges (legacy alias; CountBadge uses .badge)
    val badge: TextStyle,       // 13sp Medium — CountBadge count text (THEME-02b promotion)
    val eyebrow: TextStyle,        // micro + caps + tracking — section labels
    val timelineAxis: TextStyle,   // L2: 12sp Normal — HeatStrip hour axis labels (a11y floor)
    val skipAffordance: TextStyle, // L2: 14sp Normal — Card View Skip link
    val contactName: TextStyle,    // display treatment
    val button: TextStyle,         // 16sp medium
)

internal val OrbitType = OrbitTypography(
    hero = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 1.15.em,
        letterSpacing = (-0.01).em,
    ),
    title = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 1.3.em,
        letterSpacing = (-0.01).em,
    ),
    h2 = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 1.3.em,
    ),
    h3 = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 1.3.em,
    ),
    body = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 1.5.em,
    ),
    meta = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 1.5.em,
    ),
    listTile = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 1.3.em,
    ),
    statValue = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 1.3.em,
    ),
    micro = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 1.3.em,
        letterSpacing = 0.01.em,
    ),
    badge = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 1.3.em,
    ),
    eyebrow = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.08.em,
        lineHeight = 1.3.em,
    ),
    timelineAxis = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.01.em,
        lineHeight = 1.3.em,
    ),
    skipAffordance = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 1.5.em,
    ),
    contactName = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em,
        lineHeight = 1.15.em,
    ),
    button = TextStyle(
        fontFamily = OrbitFont,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
)

internal val LocalOrbitTypography = staticCompositionLocalOf { OrbitType }
