package app.orbit.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
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
 * orphaned workers don't read a now-empty DB, paired with one [refreshNow]
 * AFTER the wipe so placed widgets re-render the empty state at once
 * instead of the wiped person's name (WIDGET-06).
 *
 * Enforcement: ONLY this file and [WidgetUpdateWorker] may update a widget
 * (`widget.update*()`, or [androidx.glance.appwidget.GlanceAppWidgetManager]
 * to find the widgets to update). Reading one widget's own id to read its
 * settings (`widgetSnapshot`) is not an update.
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
     * The one undebounced refresh: enqueues the same [WidgetUpdateWorker] on
     * [UNIQUE_WORK] with no initial delay and [ExistingWorkPolicy.REPLACE].
     *
     * Reset-only (WIDGET-06). Everywhere else the 30 second debounce in
     * [scheduleImmediate] is the contract (WIDGET-05), and REPLACE is normally
     * forbidden here because it would cancel an in-flight debounce (T-11-08).
     * It is safe in [app.orbit.data.repository.ResetService.resetAll] only
     * because [cancelAll] ran moments before, so no debounced work exists to
     * replace, and the wiped name must leave the home screen now, not in half
     * a minute. Do not call it from a mutation path.
     */
    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * WIDGET-12: redraws one widget now, for its settings screen. The user is
     * looking at the home screen behind it, so this is not debounced; it
     * updates only [appWidgetId], so it cannot coalesce with (or cancel) the
     * debounced fan-out the way [refreshNow] would. If the redraw throws, the
     * debounced refresh picks it up 30 seconds later rather than never
     * (rules.md Code 3), and the caller hears about it.
     */
    suspend fun refreshOne(context: Context, appWidgetId: Int) {
        val widget = widgetFor(context, appWidgetId) ?: return
        try {
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            widget.update(context, glanceId)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            scheduleImmediate(context)
            throw t
        }
    }

    /**
     * The Orbit widget placed as [appWidgetId], or null when that id is not
     * one of ours (Widget settings is exported, so any app could start it with
     * any id) or no longer placed.
     */
    fun widgetFor(context: Context, appWidgetId: Int): GlanceAppWidget? {
        val provider = AppWidgetManager.getInstance(context)
            .getAppWidgetInfo(appWidgetId)
            ?.provider
            ?: return null
        if (provider.packageName != context.packageName) return null
        return when (provider.className) {
            OrbitWidget2x2Receiver::class.java.name -> OrbitWidget2x2()
            OrbitWidget4x2Receiver::class.java.name -> OrbitWidget4x2()
            else -> null
        }
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
     * this with one [refreshNow] after the wipe so placed widgets immediately
     * re-render the empty state, "All quiet for now." (WIDGET-06).
     */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_WORK)
        wm.cancelUniqueWork(PERIODIC_WORK)
    }
}
