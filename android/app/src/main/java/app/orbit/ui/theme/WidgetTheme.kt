// android/app/src/main/java/app/orbit/ui/theme/WidgetTheme.kt
//
// Widget theme root — mirrors the shape of OrbitTheme but for Glance.
// The widget calls `OrbitWidgetTheme { Widget2x2Content() }` from `provideGlance`.
//
// Glance has NO LocalGlanceTypography or LocalGlanceShape (D-02), so widget
// text styles live as top-level vals (OrbitWidgetTextStyles) and widget shapes
// live in res/drawable/widget_*.xml — neither is themed via CompositionLocal.
//
// FONT NOTE: Glance only supports system FontFamily — custom TTFs
// (Inter) are NOT available in widgets. Widget text renders with system
// SansSerif regardless of app font choice.
package app.orbit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceTheme
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle

@Composable
fun OrbitWidgetTheme(content: @Composable () -> Unit) {
    GlanceTheme(colors = OrbitWidgetColorProviders, content = content)
}

// Top-level text style vals. Note: this TextStyle is `androidx.glance.text.TextStyle`,
// NOT `androidx.compose.ui.text.TextStyle` used by OrbitTypography in Type.kt.
object OrbitWidgetTextStyles {
    val contactName = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val meta        = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val eyebrow     = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
