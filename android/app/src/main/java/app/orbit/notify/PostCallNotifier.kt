package app.orbit.notify

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import app.orbit.data.AppPrefs
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.WaitingCalls
import app.orbit.data.repository.endedAt
import app.orbit.domain.clock.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * NOTIF-16: "How was your call with Kai?" after a call, when Orbit is not on
 * screen. The owner asked whether the post-call prompt could be a
 * notification; it can, with no new permission, because Orbit already reads
 * the call log and Android wakes it when the log changes (the call-log
 * trigger, `CallLogTriggerWorker`).
 *
 * ### When it posts
 * [CallLogSyncWorker][app.orbit.calllog.CallLogSyncWorker] asks for
 * [markBeforeSync] before it reconciles and calls [onSyncFinished] after, so
 * only calls that pass inserted are considered. One posts for each person
 * whose waiting call (NOTE-05, [WaitingCalls]: connected, a minute or more,
 * someone on a list, no note since, not dismissed) is one of those new rows
 * and ended within [FRESH_FOR]. Not for a first import or a full resync
 * ([onSyncFinished]'s `bulkPass`), and an old row is never fresh, so neither
 * can flood the shade. Not while Orbit is in the foreground
 * ([AppForeground]): on screen, Home's stack already says it. And only when
 * the gates the nudge respects pass: notifications allowed, this channel
 * on, Do Not Disturb off (NOTIF-01, NOTIF-06).
 *
 * ### When it goes away
 * Once that person has no call waiting: a note is saved for them (on the
 * note page, or on their own page), or the call is dismissed on Home, or a
 * day has passed since it started. [startShadeReconciliation] watches
 * [WaitingCalls] for the life of the process and [reconcileShade] cancels
 * what no longer waits, so one mechanism covers every change that stops a
 * call waiting, including Contact detail's note field, which knows nothing
 * about notifications. The day passing changes nothing in the database, so
 * that one is the notification's own timeout, set when it is posted. A tap
 * dismisses it too (auto-cancel).
 *
 * ### ADR 0009
 * This is the one notification that reacts to an event. ADR 0009 forbade
 * that; the owner decided this exception on 2026-10-07 and the ADR records
 * the amendment: it is about a call the user just had, never about absence,
 * it names one person once, and it has its own channel to turn it off.
 *
 * Logs ids and counts only (rules.md Code 4).
 */
@Singleton
open class PostCallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val waitingCalls: WaitingCalls,
    private val callEventRepo: CallEventRepository,
    private val appForeground: AppForeground,
    private val appPrefs: AppPrefs,
    private val clock: Clock,
) {

    /** Before a sync pass: the newest call event id so far, to compare against afterwards. */
    open suspend fun markBeforeSync(): Long = callEventRepo.maxId()

    /**
     * After a sync pass that wrote rows: post for the fresh, newly inserted
     * calls that wait for a note. [insertedAfterId] is [markBeforeSync]'s
     * answer; [bulkPass] is true for a first import or a full resync, which
     * never post.
     */
    open suspend fun onSyncFinished(insertedAfterId: Long, bulkPass: Boolean) {
        val skip = when {
            bulkPass -> "bulk_pass"
            appForeground.isForeground -> "foreground"
            !context.areNotificationsEnabled() -> "notifications_off"
            !context.isChannelEnabled(OrbitNotifications.CHANNEL_AFTER_CALL) -> "channel_off"
            dndBlocking() -> "dnd"
            else -> null
        }
        if (skip != null) {
            Timber.tag(TAG).d("post_call_skip reason=%s", skip)
            return
        }
        val now = clock.now()
        val fresh = waitingCalls.observe(now).first().filter { call ->
            call.callEventId > insertedAfterId && !call.endedAt.isBefore(now.minus(FRESH_FOR))
        }
        if (fresh.isEmpty()) return
        val accent = resolveNotificationTheme(context, appPrefs).colors.accent.toArgb()
        val manager = NotificationManagerCompat.from(context)
        fresh.forEach { call ->
            val firstName = NotificationCopy.firstNameOf(call.displayName).ifBlank { call.displayName }
            val stillWaits = Duration.between(now, call.occurredAt.plus(WaitingCalls.WINDOW))
            manager.notify(
                NotificationIds.postCall(call.contactId),
                PostCallNotification.build(
                    context,
                    call.contactId,
                    call.callEventId,
                    firstName,
                    accent,
                    timeoutAfter = stillWaits.coerceAtLeast(MIN_TIMEOUT),
                ),
            )
            Timber.tag(TAG).i("post_call_posted contact=%d call=%d", call.contactId, call.callEventId)
        }
    }

    /**
     * Cancels every notification after a call whose person no longer has a
     * call waiting. The waiting calls are read after the shade is, so a
     * notification posted a moment ago is never mistaken for a stale one: it
     * was posted for a call that is in this read unless it truly stopped
     * waiting since.
     */
    open suspend fun reconcileShade() {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val posted = manager.activeNotifications.filter {
            it.notification.channelId == OrbitNotifications.CHANNEL_AFTER_CALL
        }
        if (posted.isEmpty()) return
        val waiting = waitingCalls.current().mapTo(mutableSetOf()) { it.contactId }
        posted.forEach { shown ->
            val contactId = shown.notification.extras.getLong(PostCallNotification.EXTRA_CONTACT_ID, NO_CONTACT)
            if (contactId !in waiting) {
                manager.cancel(shown.tag, shown.id)
                Timber.tag(TAG).d("post_call_cancelled contact=%d", contactId)
            }
        }
    }

    /**
     * Keeps the shade in step with [WaitingCalls] for the life of the
     * process: every change to the calls, notes, lists or dismissals runs
     * [reconcileShade]. Started once, from `OrbitApp.onCreate`, so the first
     * read also clears what went stale while the process was dead.
     */
    fun startShadeReconciliation(scope: CoroutineScope): Job = scope.launch {
        waitingCalls.observe()
            .catch { Timber.tag(TAG).w("post_call_watch_failed %s", it.javaClass.simpleName) }
            .collect {
                try {
                    reconcileShade()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t // rules.md Code 5
                    Timber.tag(TAG).w("post_call_reconcile_failed %s", t.javaClass.simpleName)
                }
            }
    }

    /**
     * True when Do Not Disturb would block the notification (NOTIF-06). A
     * hook, as on [ListPromptWorker], because Robolectric's notification
     * manager offers no way to set the interruption filter.
     */
    internal open fun dndBlocking(): Boolean = context.isDndBlocking()

    companion object {
        /** How recently a call must have ended for its notification to be worth posting. */
        val FRESH_FOR: Duration = Duration.ofHours(2)

        /** A timeout of zero means none to Android, so a call at the window's edge still gets one. */
        private val MIN_TIMEOUT: Duration = Duration.ofSeconds(1)

        private const val NO_CONTACT = -1L
        private const val TAG = "post_call"
    }
}
