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
import app.orbit.notify.PostCallNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Call-log reconciliation worker.
 *
 * Every trigger resolves to this single worker via
 * [ContentObserverController.UNIQUE_NAME_SYNC]:
 * 1. First-run import after permission grant — enqueued from Settings with
 *    [ContentObserverController.enqueueImmediateSync] (fullResync = true).
 * 2. Debounced observer fire — enqueued from
 *    ContentObserverController.enqueueDebouncedSync with fullResync = false.
 *    The call-log trigger ([CallLogTriggerWorker], NOTIF-16), which wakes a
 *    process that was dead during the call, enqueues the same request.
 * 3. Manual "Resync now" — enqueued from Settings with fullResync = true.
 * 4. The resume sync and the card's return from the dialer (incremental).
 *
 * After a pass that wrote rows it hands the new calls to [PostCallNotifier]
 * (NOTIF-16), the same post-reconcile hook shape ADR 0004's amendment records
 * for the retired follow-up. A first import or a full resync never notifies.
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
    private val postCallNotifier: PostCallNotifier,
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

        // NOTIF-16: the newest call id before this pass, so the notifier can
        // tell the rows this pass writes (ids above it) from the ones before.
        val newestBefore = postCallNotifier.markBeforeSync()
        val summary = reconciler.reconcile(sinceMs = sinceMs, rows = rows)
        prefs.setLastCallLogSyncAt(now)

        // A missed inbound call still surfaces the contact in-app only (the
        // engine sets nextDueAt = the moment they rang; KeepInTouchEngine step
        // 3c), with no notification. The one notification a call earns is the
        // after-a-call prompt for a connected call worth a note (NOTIF-16,
        // ADR 0009's 2026-10-07 amendment). A failure there is logged and
        // never fails the sync: the calls are written, and Home shows them.
        if (summary.inserted > 0) {
            try {
                postCallNotifier.onSyncFinished(
                    insertedAfterId = newestBefore,
                    bulkPass = fullResync || lastSync == 0L,
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t // rules.md Code 5
                Timber.tag(TAG).w("post_call_notify_failed %s", t.javaClass.simpleName)
            }
        }

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
