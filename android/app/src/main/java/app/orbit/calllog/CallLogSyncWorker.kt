package app.orbit.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.orbit.data.AppPrefs
import app.orbit.data.android.CallLogReader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Call-log reconciliation worker.
 *
 * Three triggers resolve to this single worker via
 * [ContentObserverController.UNIQUE_NAME_SYNC]:
 * 1. First-run import after permission grant — enqueued from Settings with
 *    [ContentObserverController.enqueueImmediateSync] (fullResync = true).
 * 2. Debounced observer fire — enqueued from
 *    ContentObserverController.enqueueDebouncedSync with fullResync = false.
 * 3. Manual "Resync now" — enqueued from Settings with fullResync = true.
 *
 * Permission handling (CALL-06, Pitfall 3): revoked permission → Result.success(),
 * NOT failure. Failure triggers exponential-backoff retries that would spam logs.
 *
 * Window computation (CALL-02):
 * - fullResync=true → sinceMs = now - importDays*DAY_MS  (ignores lastSync; full window)
 * - fullResync=false → sinceMs = max(lastSync, now - importDays*DAY_MS)
 *   (incremental; capped at window)
 */
@HiltWorker
class CallLogSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reconciler: CallLogReconciler,
    private val reader: CallLogReader,
    private val prefs: AppPrefs,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!hasCallLogPermission()) {
            Timber.tag(TAG).d("permission_lost_clean_exit")
            return Result.success()
        }

        val fullResync = inputData.getBoolean(ContentObserverController.KEY_FULL_RESYNC, false)
        val importDays = prefs.callLogImportDays.first()
        val lastSync = prefs.lastCallLogSyncAt.first()
        val now = System.currentTimeMillis()
        val windowStart = now - importDays.toLong() * DAY_MS
        val sinceMs = if (fullResync) windowStart else maxOf(lastSync, windowStart)

        // readAll takes days; compute ceil of the ms window and let the reconciler's
        // sinceMs filter trim the per-row edge.
        val lookbackDays = maxOf(1, ((now - sinceMs) / DAY_MS + 1).toInt())
        val rows = reader.readAll(lookbackDays)

        val summary = reconciler.reconcile(sinceMs = sinceMs, rows = rows)
        prefs.setLastCallLogSyncAt(now)

        // A missed inbound call surfaces the contact in-app (the engine sets
        // nextDueAt = the moment they rang; see KeepInTouchEngine step 3c). There
        // is no follow-up notification — Orbit only sends user-defined reminders,
        // never event-driven pings (2026-07-03 notification principle).

        Timber.tag(TAG).i(
            "sync_complete full=%b scanned=%d inserted=%d skipped=%d propagated=%d",
            fullResync, summary.scanned, summary.inserted, summary.skipped, summary.contactsPropagated,
        )
        return Result.success()
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val DAY_MS: Long = 24L * 60 * 60 * 1000
        const val TAG: String = "calllog"
    }
}
