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
 * This class is the ONLY production call site of
 * [WidgetUpdateScheduler.scheduleImmediate] outside the scheduler's own file —
 * the single choke point for widget refresh scheduling.
 */
class WorkManagerWidgetRefreshTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) : WidgetRefreshTrigger {

    override fun scheduleRefresh() {
        WidgetUpdateScheduler.scheduleImmediate(context)
    }
}
