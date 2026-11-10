// android/app/src/main/java/app/orbit/widget/WidgetSnapshot.kt
//
// Everything one placed widget draws, read once in provideGlance. Both
// widgets did these reads inline; per-widget settings (WIDGET-12, WIDGET-15)
// added the widget's own id, its settings and the taps that follow from them,
// so the reads moved here once rather than twice.
//
// rememberWidgetSnapshot keeps a widget's running session in step with its
// settings. Found on a device, 2026-11-09: Glance keeps a widget's session
// alive for a while after it renders, and an update() that lands inside that
// window only recomposes the running session; provideGlance does not run
// again. A settings change is exactly that case (the user changes a setting
// seconds after placing or redrawing the widget), and the widget kept drawing
// the settings it was first given until the session expired.
package app.orbit.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.color.ColorProviders
import app.orbit.data.WidgetSettings
import app.orbit.data.WidgetSource
import app.orbit.ui.theme.deviceAccentHue
import app.orbit.ui.theme.orbitWidgetAvatarTones
import app.orbit.ui.theme.orbitWidgetColorProviders
import app.orbit.ui.theme.themeSettingsSnapshot
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * One widget's render inputs.
 *
 * [chooseListIntent] is non-null only when the widget's list is archived or
 * deleted (WIDGET-14): the widget then draws "Choose a list", which opens its
 * settings, instead of [people]. [emptyTapIntent] is the empty state's tap:
 * the widget's one list's deck, or Orbit for every list. [settings] are the
 * ones it was built from, so a running session can tell when they change.
 */
class WidgetSnapshot(
    val settings: WidgetSettings,
    val people: List<WidgetPerson>,
    val colors: ColorProviders,
    val emptyTapIntent: Intent,
    val chooseListIntent: Intent?,
)

/**
 * One-shot reads, never a live Flow: provideGlance renders once per update.
 * Reading the widget's id from [GlanceAppWidgetManager] is not an update;
 * updates still go only through WidgetUpdateScheduler (WIDGET-05).
 */
suspend fun widgetSnapshot(context: Context, glanceId: GlanceId, max: Int): WidgetSnapshot {
    val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
    val prefs = entry.appPrefs()
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    val settings = prefs.widgetSettings(appWidgetId).first()
    val data = entry.widgetSurfaceUseCase().invoke(settings.source, max)
    val themeSettings = prefs.themeSettingsSnapshot()
    val deviceHue = deviceAccentHue(context)
    val source = settings.source
    return WidgetSnapshot(
        settings = settings,
        people = widgetPeople(
            context = context,
            data = data,
            max = max,
            namesHidden = settings.namesHidden,
            tones = orbitWidgetAvatarTones(themeSettings, deviceHue),
        ),
        colors = orbitWidgetColorProviders(themeSettings, deviceHue),
        emptyTapIntent = when (source) {
            WidgetSource.EveryList -> openHomeIntent(context)
            is WidgetSource.OneList -> openListIntent(context, source.listId)
        },
        chooseListIntent = if (data.sourceMissing) widgetSettingsIntent(context, appWidgetId) else null,
    )
}

/**
 * The snapshot a running session draws: [initial], read in provideGlance,
 * until this widget's settings change, then a fresh read (WIDGET-12). See the
 * file comment for why the session has to watch for itself.
 *
 * Only a settings change re-reads here. Everything else still reaches the
 * widget through WidgetUpdateScheduler, as before. The work stays out of
 * composition: the coroutine reads, and the composition only recomposes when
 * the value it hands back changes.
 */
@Composable
fun rememberWidgetSnapshot(
    context: Context,
    glanceId: GlanceId,
    max: Int,
    initial: WidgetSnapshot,
): State<WidgetSnapshot> = produceState(initial, glanceId) {
    val prefs = EntryPointAccessors
        .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        .appPrefs()
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    prefs.widgetSettings(appWidgetId)
        .dropWhile { it == initial.settings }
        .distinctUntilChanged()
        .collectLatest { value = widgetSnapshot(context, glanceId, max) }
}

/**
 * WIDGET-16: deletes the settings of widgets the user removed. Called from
 * both receivers' `onDeleted`.
 *
 * Not through `goAsync`: Glance's own `onDeleted` takes the receiver's one
 * pending result, and a second `goAsync` returns null. The write is one small
 * DataStore edit; if the process dies before it lands, the keys left behind
 * name an `appWidgetId` Android does not hand out again, so nothing ever
 * reads them, and reset clears them with everything else. The log line is an
 * event and a count, never an id or a name (rules.md Code 4).
 */
fun forgetWidgetSettings(context: Context, appWidgetIds: IntArray) {
    val prefs = EntryPointAccessors
        .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        .appPrefs()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            prefs.clearWidgetSettings(appWidgetIds.toList())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.tag("widget").w(t, "widget_settings_forget_failed count=%d", appWidgetIds.size)
        }
    }
}
