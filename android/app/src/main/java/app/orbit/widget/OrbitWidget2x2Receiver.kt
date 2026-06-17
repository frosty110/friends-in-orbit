// android/app/src/main/java/app/orbit/widget/OrbitWidget2x2Receiver.kt
//
// WIDGET-01 — GlanceAppWidgetReceiver that binds [OrbitWidget2x2].
//
// FQN permanent post-release: app.orbit.widget.OrbitWidget2x2Receiver
// DO NOT rename — existing placed widgets break silently on FQN change.
//
// Registered in AndroidManifest.xml with android:exported="false" so only the
// system (APPWIDGET_UPDATE broadcast) can trigger updates (T-11-06 mitigation).
package app.orbit.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class OrbitWidget2x2Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OrbitWidget2x2()
}
