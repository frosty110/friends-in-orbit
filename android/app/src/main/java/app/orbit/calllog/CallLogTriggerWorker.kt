package app.orbit.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * NOTIF-16: runs when the phone's call log changes, even if Orbit's process
 * was dead, and starts the ordinary call-log sync.
 *
 * Why it exists beside [ContentObserverController]'s observer: the observer
 * only hears changes while a live process holds its registration, and during
 * a call long enough to write about, Android often kills Orbit's process.
 * Before this, such a call was read only the next time Orbit came to the
 * foreground (the resume sync), which is too late to ask "How was your call
 * with Kai?" in the shade. WorkManager's content URI trigger (JobScheduler
 * underneath) watches `CallLog.Calls.CONTENT_URI` on Orbit's behalf and
 * starts the process when it changes. It needs no permission beyond the
 * READ_CALL_LOG Orbit already holds (see the exploration memo,
 * features/call-detection/real-time-detection-exploration.md).
 *
 * Why the observer stays: the trigger is batched by JobScheduler and can be
 * deferred by Doze and App Standby, while the observer reacts at once when
 * Orbit is alive, which is what returning from the card's dial counts on
 * (CORE-04). Both enqueue the same unique sync with KEEP, so when both fire
 * one sync runs; there is no second ingest path.
 *
 * A content URI trigger fires once per enqueue, so this worker re-arms
 * itself every time it runs ([ContentObserverController.rearmCallLogTrigger]),
 * then hands over to the sync. Without READ_CALL_LOG it does neither, so the
 * chain ends; granting the permission arms it again (the controller's start).
 */
@HiltWorker
class CallLogTriggerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val controller: ContentObserverController,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!hasCallLogPermission()) {
            Timber.tag(TAG).d("call_log_trigger_no_permission_chain_ends")
            return Result.success()
        }
        controller.rearmCallLogTrigger()
        controller.enqueueObservedSync()
        Timber.tag(TAG).d("call_log_trigger_fired")
        return Result.success()
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** Unique work name of the armed trigger. Part of the app's WorkManager ABI. */
        const val UNIQUE_NAME: String = "orbit.call_log_trigger"
        private const val TAG: String = "calllog"
    }
}
