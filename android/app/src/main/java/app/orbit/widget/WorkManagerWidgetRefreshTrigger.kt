package app.orbit.widget

import android.content.Context
import app.orbit.domain.WidgetRefreshTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

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

    override fun scheduleRefresh() {
        WidgetUpdateScheduler.scheduleImmediate(context)
    }
}
