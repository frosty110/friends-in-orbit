package app.orbit.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.orbit.MainActivity
import app.orbit.R
import app.orbit.data.repository.ContactRepository
import app.orbit.nav.Routes
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Expedited follow-up worker — NOTIF-04 (D-11/D-13/D-14).
 *
 * Enqueued by [app.orbit.calllog.CallLogSyncWorker] after reconcile when a new
 * INCOMING event for a tracked, non-ignored, non-paused contact is detected.
 * Uses `setExpedited` WITHOUT `setInitialDelay` (RESEARCH Pitfall 4 — they are
 * mutually exclusive).
 *
 * ### Gate order (D-10 follow-up variant)
 * 1. contactId present in input data (else failure)
 * 2. POST_NOTIFICATIONS permission + system mute ([areNotificationsEnabled])
 * 3. DND blocking ([isDndBlocking])
 * 4. Contact still tracked + non-ignored at fire time (re-checked via [ContactRepository])
 * 5. 30-minute dedup window ([FollowUpDedupStore.isWithinDedupWindow]) — D-13
 *
 * All gate failures return [Result.success] — failure would trigger WorkManager
 * exponential backoff, which is inappropriate for a one-shot event-driven worker.
 *
 * ### Notification (D-14)
 * Channel: [OrbitNotifications.CHANNEL_INCOMING_FOLLOWUP_V2] (IMPORTANCE_HIGH).
 * Title: "{Name} called you." — the ONE notification with a contact name.
 * Body: "Want to call back?"
 * Tap: [MainActivity] with extra [EXTRA_NAVIGATE_TO] = "contact/{contactId}".
 *
 * ### Security (T-10-13/T-10-14)
 * - Name surfaces only after a user-initiated incoming call; contact still
 *   tracked + non-ignored at fire time (re-verified above).
 * - PendingIntent uses [PendingIntent.FLAG_IMMUTABLE] + unique requestCode per
 *   contact to prevent cross-contact collision.
 * - Does NOT re-enqueue (fires once).
 */
@HiltWorker
class IncomingFollowUpWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val dedupStore: FollowUpDedupStore,
    private val contactRepo: ContactRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val contactId = inputData.getLong(KEY_CONTACT_ID, -1L)
        if (contactId == -1L) {
            Timber.tag(TAG).w("follow_up_worker_no_contact_id")
            return Result.failure()
        }

        // Gate 1: system notification permission + user mute
        if (!appContext.areNotificationsEnabled()) {
            Timber.tag(TAG).d("follow_up_skip_notifications_disabled contact=%d", contactId)
            return Result.success()
        }

        // Gate 2: DND
        if (appContext.isDndBlocking()) {
            Timber.tag(TAG).d("follow_up_skip_dnd contact=%d", contactId)
            return Result.success()
        }

        // Gate 3: contact still tracked + non-ignored at fire time (T-10-13)
        val contact = contactRepo.getById(contactId)
        if (contact == null || contact.isIgnored || contact.isArchived) {
            Timber.tag(TAG).d("follow_up_skip_contact_gone_or_ignored contact=%d", contactId)
            return Result.success()
        }

        // Gate 4: 30-minute dedup window (D-13)
        val now = System.currentTimeMillis()
        val last = dedupStore.lastFollowUpAtMs(contactId)
        if (FollowUpDedupStore.isWithinDedupWindow(last, now)) {
            Timber.tag(TAG).d("follow_up_skip_dedup contact=%d last=%s", contactId, last)
            return Result.success()
        }

        // All gates passed — build and post the follow-up notification.
        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            putExtra(EXTRA_NAVIGATE_TO, Routes.contact(contactId.toString()))
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            NotificationIds.followUp(contactId),
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(appContext, OrbitNotifications.CHANNEL_INCOMING_FOLLOWUP_V2)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(NotificationCopy.followUpTitle(contact.displayName))
            .setContentText(NotificationCopy.followUpBody())
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(appContext)
            .notify(NotificationIds.followUp(contactId), notification)

        dedupStore.record(contactId, now)

        Timber.tag(TAG).i("follow_up_posted contact=%d", contactId)
        return Result.success()
    }

    companion object {
        /** WorkData key for the contactId (Long). */
        const val KEY_CONTACT_ID = "contact_id"

        /**
         * Intent extra carrying the navigation target for the follow-up tap.
         * Value: "contact/{contactId}" (D-17).
         */
        const val EXTRA_NAVIGATE_TO = "app.orbit.extra.NAVIGATE_TO"

        private const val TAG = "followup"
    }
}
