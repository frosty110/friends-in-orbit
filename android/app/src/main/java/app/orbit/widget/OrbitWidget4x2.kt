// android/app/src/main/java/app/orbit/widget/OrbitWidget4x2.kt
//
// WIDGET-02: 4×2 home-screen widget, the lead person and up to two more.
//
// WIDGET-07: SizeMode.Responsive (WidgetBreakpoints.suggestions). Narrow, it
// is a one-person widget like "Next call"; at 4×2 the others are listed beside
// the lead; at 4×3 and up, below it with their own quiet call buttons.
// Glance 1.1.1 has no horizontal scroll, so the others are a static list, not
// a carousel. That stays the decision (widgets README, "Not in scope").
//
// FQN is permanent post-release: app.orbit.widget.OrbitWidget4x2
// FQN is permanent post-release: app.orbit.widget.OrbitWidget4x2Receiver
// NEVER rename either class — existing placed widgets break silently on FQN
// change.
//
// provideGlance reads everything once, before provideContent (see
// OrbitWidget2x2). Widgets are one-shot renders.
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

class OrbitWidget4x2 : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(WidgetBreakpoints.suggestions)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = widgetSnapshot(context, id, max = 3)

        provideContent {
            val snapshot by rememberWidgetSnapshot(context, id, max = 3, initial = initial)
            OrbitWidgetTheme(colors = snapshot.colors) {
                val chooseList = snapshot.chooseListIntent
                if (chooseList != null) {
                    ChooseListBody(onChoose = actionStartActivity(chooseList))
                } else {
                    SuggestionsBody(
                        people = snapshot.people,
                        onEmptyTap = actionStartActivity(snapshot.emptyTapIntent),
                    )
                }
            }
        }
    }
}
