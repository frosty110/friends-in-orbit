// android/app/src/main/java/app/orbit/widget/OrbitWidget2x2.kt
//
// WIDGET-01 — 2×2 home-screen widget.
//
// FQN is permanent post-release: app.orbit.widget.OrbitWidget2x2
// FQN is permanent post-release: app.orbit.widget.OrbitWidget2x2Receiver
// NEVER rename either class — existing placed widgets break silently on FQN
// change.
//
// provideGlance reads everything once, before provideContent: the people
// (with their bitmaps), minimal mode and the appearance. Widgets are one-shot
// renders; reading inside the composable would require a collected Flow and
// break the stateless-widget contract.
//
// WIDGET-07: SizeMode.Responsive. The widget resizes from one row to a wide
// card (WidgetBreakpoints.nextCall), and each size has its own arrangement.
package app.orbit.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import app.orbit.ui.theme.OrbitWidgetTheme
import app.orbit.ui.theme.deviceAccentHue
import app.orbit.ui.theme.orbitWidgetAvatarTones
import app.orbit.ui.theme.orbitWidgetColorProviders
import app.orbit.ui.theme.themeSettingsSnapshot

class OrbitWidget2x2 : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(WidgetBreakpoints.nextCall)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(
            context,
            WidgetEntryPoint::class.java,
        )
        // One-shot snapshot — never keep a live Flow open in provideGlance.
        val data = entry.widgetSurfaceUseCase().invoke()
        // Minimal mode + appearance, one-shot (WIDGET-04 / THEMING).
        val prefs = entry.appPrefs()
        val minimalMode = prefs.minimalModeEnabled.first()
        val themeSettings = prefs.themeSettingsSnapshot()
        val deviceHue = deviceAccentHue(context)
        val widgetColors = orbitWidgetColorProviders(themeSettings, deviceHue)
        val people = widgetPeople(
            context = context,
            data = data,
            max = 1,
            minimalMode = minimalMode,
            tones = orbitWidgetAvatarTones(themeSettings, deviceHue),
        )

        provideContent {
            OrbitWidgetTheme(colors = widgetColors) {
                NextCallBody(
                    people = people,
                    onOpenApp = actionStartActivity(openHomeIntent(context)),
                )
            }
        }
    }
}
