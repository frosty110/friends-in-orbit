package app.orbit.domain

/**
 * Domain-layer seam for scheduling a widget refresh after a mutation that
 * changes who-is-due (WIDGET-06).
 *
 * Declared as a `fun interface` so plain-JVM test fixtures can supply a
 * no-op SAM lambda `WidgetRefreshTrigger { }` without any WorkManager or
 * Android context dependency.
 *
 * Production binding: [app.orbit.widget.WorkManagerWidgetRefreshTrigger]
 * delegates to [app.orbit.widget.WidgetUpdateScheduler.scheduleImmediate].
 * The binding is registered in [app.orbit.di.WidgetModule].
 *
 * This interface MUST NOT import any `androidx.work`, `android.content.Context`,
 * or widget-layer types — keeping domain free of WorkManager/Glance dependencies
 * is the point of the seam (test fixtures remain plain JVM, no Robolectric needed).
 */
fun interface WidgetRefreshTrigger {
    fun scheduleRefresh()
}
