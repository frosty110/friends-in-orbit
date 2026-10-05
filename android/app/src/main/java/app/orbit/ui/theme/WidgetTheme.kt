// android/app/src/main/java/app/orbit/ui/theme/WidgetTheme.kt
//
// Widget theme root — mirrors the shape of OrbitTheme but for Glance.
// The widget calls `OrbitWidgetTheme { Widget2x2Content() }` from `provideGlance`.
//
// Glance has NO LocalGlanceTypography or LocalGlanceShape (D-02), so widget
// text styles live as top-level vals (OrbitWidgetTextStyles). Shapes come from
// the system: the widget's corners use Android's own widget radius
// (system_app_widget_background_radius), and avatars and buttons are circles.
//
// FONT NOTE: Glance only supports system FontFamily — custom TTFs
// (Inter) are NOT available in widgets. Widget text renders with system
// SansSerif regardless of app font choice. Avatar monograms are the exception:
// they are drawn as bitmaps in Inter (AvatarBitmaps), so they match the app.
package app.orbit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle

/**
 * Widget theme root. [colors] defaults to the static Warm providers; the
 * widgets pass [orbitWidgetColorProviders] built from the user's chosen theme
 * so a placed widget matches the in-app appearance (THEMING 2026-06-22).
 */
@Composable
fun OrbitWidgetTheme(
    colors: ColorProviders = OrbitWidgetColorProviders,
    content: @Composable () -> Unit,
) {
    GlanceTheme(colors = colors, content = content)
}

// Top-level text style vals. Note: this TextStyle is `androidx.glance.text.TextStyle`,
// NOT `androidx.compose.ui.text.TextStyle` used by OrbitTypography in Type.kt.
//
// 2026-10-05: nothing below 16sp (rules.md Design 2). The alternatives' names
// and the empty state used a 12sp `meta` style; on a home screen, read at arm's
// length, they were the hardest text in the app to read. `meta` and `eyebrow`
// are gone with their last call sites.
object OrbitWidgetTextStyles {
    /** The person a widget leads with. */
    val contactName = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)

    /** Everyone else, and the empty state. */
    val body        = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal)

    /** The Call button's word. */
    val label       = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
}

// Plain Kotlin spacing vals mirroring OrbitSpacing's grid. Call sites apply `.dp`:
//   GlanceModifier.padding(WidgetSpacing.x4.dp)
object WidgetSpacing {
    val x1 = 4
    val x2 = 8
    val x3 = 12
    val x4 = 16
    val x5 = 20
    val x6 = 24
}

// Widget element sizes in dp, the widget twin of OrbitSpacing.tapMin and the
// in-app avatar sizes. Call sites apply `.dp`.
object WidgetSizes {
    /** rules.md Design 3: every tap target, the quiet ones included. */
    const val tapMin = 48

    /** The lead person's avatar on a roomy widget, and the photo bitmap size. */
    const val avatarLarge = 56

    /** The lead person's avatar on a compact widget. */
    const val avatarMedium = 44

    /** One-row widgets and the alternatives' rows. */
    const val avatarSmall = 36

    /** Icons inside buttons. */
    const val icon = 20

    /** The Orbit glyph in the empty state. */
    const val glyph = 28
}
