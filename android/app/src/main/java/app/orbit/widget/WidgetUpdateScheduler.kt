package app.orbit.widget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Single choke-point for all widget-update enqueues (RESEARCH §Pitfall 2).
 *
 * Kotlin `object` — no injected deps; [WorkManager.getInstance] takes `context`
 * at every call site (mirrors RESEARCH Example 4, lines 540-570).
 *
 * Debounce contract (WIDGET-05): [scheduleImmediate] uses [ExistingWorkPolicy.KEEP]
 * with a 30-second initial delay so rapid-fire mutation triggers (e.g., bulk-move
 * 40 contacts) coalesce into a single widget refresh rather than flooding the
 * launcher with up to 40 immediate re-renders.
 *
 * Periodic sweep (WIDGET-06): [schedulePeriodic] enqueues a 1-hour sweep so
 * active-hours boundary transitions (which no mutation triggers) are reflected
 * within ~1 hour even if the user never opens the app.
 *
 * Pitfall 6 (data-wipe path): [cancelAll] is called from
 * [app.orbit.data.repository.ResetService.resetAll] BEFORE the wipe so
 * orphaned workers don't read a now-empty DB, paired with one final
 * [scheduleImmediate] AFTER the wipe so placed widgets re-render the
 * empty state instead of the wiped contact's name (review WR-06).
 *
 * Enforcement: ONLY this file and [WidgetUpdateWorker] may call
 * [androidx.glance.appwidget.GlanceAppWidgetManager] or `widget.update*()`.
 * Any call outside these two files is Pitfall 2 — flag it in code review.
 */
object WidgetUpdateScheduler {

    /** Unique work name for the debounced one-time update request. */
    const val UNIQUE_WORK = "orbit_widget_update"

    /** Unique work name for the 1-hour periodic sweep. */
    const val PERIODIC_WORK = "orbit_widget_periodic"

    /**
     * Enqueues a one-time widget update delayed by 30 seconds using
     * [ExistingWorkPolicy.KEEP]. Rapid successive calls coalesce into a single
     * pending run — the in-flight work is never replaced (T-11-08 mitigation).
     */
    fun scheduleImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Enqueues (or re-anchors, idempotent) a 1-hour periodic widget sweep.
     *
     * [ExistingPeriodicWorkPolicy.KEEP] makes re-registration on every cold start
     * a no-op — the existing in-flight periodic work is never re-scheduled.
     * Called from [app.orbit.OrbitApp.onCreate] once per cold start so
     * active-hours boundary transitions are reflected within 1 hour.
     */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Cancels both the debounced one-time work and the 1-hour periodic sweep.
     *
     * Called from [app.orbit.data.repository.ResetService.resetAll] (the
     * Settings "delete all data" path) so orphaned WorkManager records do not
     * fire after a full data reset (RESEARCH §Pitfall 6). ResetService pairs
     * this with one final [scheduleImmediate] after the wipe so placed widgets
     * immediately re-render "No one due" (review WR-06).
     */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_WORK)
        wm.cancelUniqueWork(PERIODIC_WORK)
    }
}
