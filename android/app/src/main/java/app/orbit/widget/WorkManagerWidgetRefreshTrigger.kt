package app.orbit.widget

import android.content.Context
import app.orbit.domain.WidgetRefreshTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import timber.log.Timber

/**
 * Production implementation of [WidgetRefreshTrigger] that delegates to
 * [WidgetUpdateScheduler.scheduleImmediate].
 *
 * Bound via [app.orbit.di.WidgetModule] into the Hilt graph so every use
 * case that injects [WidgetRefreshTrigger] gets this implementation at
 * runtime. Test fixtures inject a no-op SAM `WidgetRefreshTrigger { }` instead.
 *
 * The domain layer's one door to a widget refresh: every use case that
 * changes who is due goes through this trigger (WIDGET-06). Two other
 * production call sites reach [WidgetUpdateScheduler.scheduleImmediate]
 * directly, because they live outside the domain layer and have a Context:
 * the Settings appearance writes (theme, dark mode and accent in
 * `SettingsViewModel`) and the data reset (`ResetService`, which cancels
 * pending refreshes and runs one more so a wiped name never lingers).
 */
class WorkManagerWidgetRefreshTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) : WidgetRefreshTrigger {

    /**
     * Never throws. Every caller asks for a refresh beside a write that has
     * already landed (WIDGET-06), so an enqueue that fails must not make that
     * write look failed. The widgets then redraw at the hourly sweep. An
     * event, never a name (rules.md Code 4).
     */
    override fun scheduleRefresh() {
        try {
            WidgetUpdateScheduler.scheduleImmediate(context)
        } catch (t: Throwable) {
            Timber.tag("widget").w(t, "widget_refresh_enqueue_failed")
        }
    }
}

/**
 * WIDGET-12: redraws one placed widget now, for Widget settings. A seam so the
 * ViewModel holds no Context (voice.md, "Where copy lives": ViewModels never
 * hold one) and its tests can count refreshes.
 */
fun interface OneWidgetRefresher {
    suspend fun refresh(appWidgetId: Int)
}

/** Production [OneWidgetRefresher]: [WidgetUpdateScheduler.refreshOne]. */
class GlanceOneWidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) : OneWidgetRefresher {
    override suspend fun refresh(appWidgetId: Int) {
        WidgetUpdateScheduler.refreshOne(context, appWidgetId)
    }
}
