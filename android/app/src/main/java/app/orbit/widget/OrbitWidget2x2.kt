// android/app/src/main/java/app/orbit/widget/OrbitWidget2x2.kt
//
// WIDGET-01 — 2×2 home-screen widget.
//
// FQN is permanent post-release: app.orbit.widget.OrbitWidget2x2
// FQN is permanent post-release: app.orbit.widget.OrbitWidget2x2Receiver
// NEVER rename either class — existing placed widgets break silently on FQN
// change.
//
// provideGlance reads everything once, before provideContent (widgetSnapshot):
// this widget's settings, its people (with their bitmaps) and the appearance.
// Widgets are one-shot renders; reading inside the composable would require a
// collected Flow and break the stateless-widget contract.
//
// WIDGET-07: SizeMode.Responsive. The widget resizes from one row to a wide
// card (WidgetBreakpoints.nextCall), and each size has its own arrangement.
package app.orbit.widget

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.state.PreferencesGlanceStateDefinition
import app.orbit.ui.theme.OrbitWidgetTheme

class OrbitWidget2x2 : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(WidgetBreakpoints.nextCall)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = widgetSnapshot(context, id, max = 1)

        provideContent {
            val snapshot by rememberWidgetSnapshot(context, id, max = 1, initial = initial)
            OrbitWidgetTheme(colors = snapshot.colors) {
                val chooseList = snapshot.chooseListIntent
                if (chooseList != null) {
                    ChooseListBody(onChoose = actionStartActivity(chooseList))
                } else {
                    NextCallBody(
                        people = snapshot.people,
                        onEmptyTap = actionStartActivity(snapshot.emptyTapIntent),
                    )
                }
            }
        }
    }
}
