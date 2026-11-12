// android/app/src/main/java/app/orbit/widget/OrbitWidget4x2Receiver.kt
//
// WIDGET-02 — AppWidget broadcast receiver for the 4×2 widget.
//
// FQN is permanent post-release: app.orbit.widget.OrbitWidget4x2Receiver
// NEVER rename this class — existing placed widgets break silently on FQN
// change.
package app.orbit.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class OrbitWidget4x2Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OrbitWidget4x2()

    /** WIDGET-16: a removed widget's settings go with it. */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        forgetWidgetSettings(context, appWidgetIds)
    }
}
