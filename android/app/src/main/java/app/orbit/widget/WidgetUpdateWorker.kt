package app.orbit.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Fans out a widget content update to every placed 2×2 and 4×2 widget instance.
 *
 * Called by [WidgetUpdateScheduler] — the ONLY entry point for widget updates.
 *
 * SINGLE-CHOKE-POINT ENFORCEMENT: [GlanceAppWidgetManager] and
 * [GlanceAppWidget.update] MUST NOT appear anywhere outside this file and
 * [WidgetUpdateScheduler]. Any call outside these two files defeats the
 * update-storm prevention discipline.
 *
 * Must be a [HiltWorker] because [app.orbit.OrbitApp] implements
 * [androidx.work.Configuration.Provider] routing all worker construction through
 * [androidx.hilt.work.HiltWorkerFactory]. A non-Hilt worker fails instantiation
 * at runtime with "Could not instantiate ... no injected constructor found".
 */
@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // WR-05: update() runs provideGlance, which reads the SQLCipher DB and
        // DataStore — both can throw transiently (keystore unavailable
        // right after reboot, DB busy during a reset). Without a retry path the
        // one-time KEEP work would silently complete as failed and the
        // mutation-driven refresh would be lost until the hourly sweep
        // ("no silent fallbacks that hide errors").
        return try {
            val manager = GlanceAppWidgetManager(applicationContext)

            // 2×2 fan-out — update every placed 2×2 instance.
            val ids2x2 = manager.getGlanceIds(OrbitWidget2x2::class.java)
            ids2x2.forEach { id ->
                OrbitWidget2x2().update(applicationContext, id)
            }

            // 4×2 fan-out — update every placed 4×2 instance.
            val ids4x2 = manager.getGlanceIds(OrbitWidget4x2::class.java)
            ids4x2.forEach { id ->
                OrbitWidget4x2().update(applicationContext, id)
            }

            Timber.tag("widget").i(
                "widget_update_complete instances_2x2=%d instances_4x2=%d",
                ids2x2.size,
                ids4x2.size,
            )

            Result.success()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Never swallow cancellation — WorkManager stops the worker by
            // cancelling its coroutine; rethrow so cooperative cancellation
            // semantics hold.
            throw ce
        } catch (t: Throwable) {
            // Retry with WorkManager's default backoff — covers transient
            // failures and re-attempts any widget instances a mid-loop throw
            // skipped (the fan-out is idempotent per instance).
            Timber.tag("widget").w(t, "widget_update_failed — scheduling retry")
            Result.retry()
        }
    }
}
