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
import app.orbit.data.repository.ListRepository
import app.orbit.nav.Routes
import app.orbit.ui.screens.lists.spansMidnight
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalTime
import timber.log.Timber

/**
 * NOTIF-12 — self-re-enqueueing nudge worker for per-list prompts.
 *
 * ### 5-Gate doWork
 * 1. [ListEntity.notificationsEnabled] — list-level mute flag
 * 2. [Context.areNotificationsEnabled] — POST_NOTIFICATIONS system gate (NOTIF-01 residual)
 * 3. [Context.isDndBlocking] — DND gate (NOTIF-06)
 * 4. Active-hours window gate — honors midnight-spanning ranges (NOTIF-03)
 * 5. [ListRepository.dueCountForList] ≥ 1 — only post when someone is due
 *
 * Any gate failure returns [Result.success] (never [Result.failure] — failure triggers
 * backoff retries, which is wrong for a fire-time gate miss). The gate result does NOT
 * affect the re-enqueue: the finally block re-enqueues the next slot unconditionally
 * — the re-enqueue must never live inside a gate branch.
 *
 * ### Thread safety
 * The `try { ... } finally { reEnqueue(listId) }` structure guarantees re-enqueue even
 * when an unhandled exception escapes the gate block. A DND night or empty-due-count day
 * can NEVER silently kill the chain.
 *
 * ### Tap navigation
 * Tapping the notification opens [MainActivity] with extra
 * `"app.orbit.extra.NAVIGATE_TO"` carrying `Routes.card(listId.toString())`. MainActivity
 * reads this extra after NavHost composition and navigates. `FLAG_IMMUTABLE` prevents
 * another app from rewriting the tap target (T-10-09).
 */
@HiltWorker
open class ListPromptWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val nudgeScheduler: NudgeScheduler,
    private val listRepo: ListRepository,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** WorkData key carrying the list primary key. */
        const val KEY_LIST_ID = "list_id"

        /** Intent extra key for the tap-destination route. */
        const val EXTRA_NAVIGATE_TO = "app.orbit.extra.NAVIGATE_TO"

        private const val TAG = "nudge"
    }

    override suspend fun doWork(): Result {
        val listId = inputData.getLong(KEY_LIST_ID, -1L)
        if (listId == -1L) {
            Timber.tag(TAG).w("invalid_list_id — no input data")
            return Result.failure()
        }

        return try {
            evaluateGatesAndPost(listId)
        } finally {
            reEnqueue(listId)
        }
    }

    // ─── Gate evaluation ──────────────────────────────────────────────────────

    private suspend fun evaluateGatesAndPost(listId: Long): Result {
        // Gate 1 — list-level notificationsEnabled flag
        val list = listRepo.getById(listId) ?: run {
            Timber.tag(TAG).d("list_gone list=%d", listId)
            return Result.success()
        }
        if (!list.notificationsEnabled) {
            Timber.tag(TAG).d("gate_list_muted list=%d", listId)
            return Result.success()
        }

        // Gate 2 — POST_NOTIFICATIONS permission + system-level app notification toggle (NOTIF-01)
        if (!appContext.areNotificationsEnabled()) {
            Timber.tag(TAG).d("gate_notifications_disabled list=%d", listId)
            return Result.success()
        }

        // Gate 3 — DND (NOTIF-06)
        if (dndBlocking()) {
            Timber.tag(TAG).d("gate_dnd_blocking list=%d", listId)
            return Result.success()
        }

        // Gate 4 — active-hours window (NOTIF-03)
        val activeStart = list.activeHoursStart
        val activeEnd = list.activeHoursEnd
        if (activeStart != null && activeEnd != null) {
            val now = currentLocalTime()
            if (!isWithinActiveHours(now, activeStart, activeEnd)) {
                Timber.tag(TAG).d("gate_outside_active_hours list=%d now=%s", listId, now)
                return Result.success()
            }
        }

        // Gate 5 — dueCount ≥ 1
        val dueCount = listRepo.dueCountForList(listId)
        if (dueCount < 1) {
            Timber.tag(TAG).d("gate_due_count_zero list=%d", listId)
            return Result.success()
        }

        // All gates passed — post the nudge notification
        postNudge(listId = listId, listName = list.name, dueCount = dueCount)
        return Result.success()
    }

    // ─── Overridable gate hooks (injectable for testing) ──────────────────────

    /**
     * Returns the current local time used by the active-hours gate.
     * Overridden in test subclasses to inject a deterministic fixed time.
     */
    internal open fun currentLocalTime(): LocalTime = LocalTime.now()

    /**
     * Returns true when DND would block a default-importance notification.
     * Delegates to [Context.isDndBlocking] in production; overridden in tests
     * where the Robolectric shadow does not expose a public DND setter.
     */
    internal open fun dndBlocking(): Boolean = appContext.isDndBlocking()

    // ─── Active-hours window helper ────────────────────────────────────────────

    /**
     * Returns true when [time] falls within the [start]..[end] window, honoring
     * midnight-spanning ranges where [start] > [end] (e.g. 22:00–02:00).
     *
     * - Normal range (start ≤ end, e.g. 09:00–17:00): inclusive on both ends.
     * - Midnight-spanning range (start > end, e.g. 22:00–02:00): [time] is inside
     *   when it is ≥ start OR ≤ end (wraps around midnight).
     *
     * Mirror of [spansMidnight] from [app.orbit.ui.screens.lists.ActiveHoursEditor]
     * — same invariant, now in the fire-time gate path.
     */
    internal fun isWithinActiveHours(time: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        if (spansMidnight(start, end)) {
            // Midnight-spanning: inside if time >= start OR time <= end
            time >= start || time <= end
        } else {
            // Normal range: inside if start <= time <= end
            time >= start && time <= end
        }

    // ─── Notification post ────────────────────────────────────────────────────

    private fun postNudge(listId: Long, listName: String, dueCount: Int) {
        val pendingIntent = buildTapIntent(listId)

        val notification = NotificationCompat.Builder(appContext, OrbitNotifications.CHANNEL_LIST_PROMPT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(NotificationCopy.nudgeTitle(listName))
            .setContentText(NotificationCopy.nudgeBody(listName, dueCount))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // Double-check system gate before calling notify() (belt + suspenders vs race).
        if (appContext.areNotificationsEnabled()) {
            NotificationManagerCompat.from(appContext)
                .notify(NotificationIds.listPrompt(listId), notification)
            Timber.tag(TAG).i("posted list=%d due=%d", listId, dueCount)
        }
    }

    /**
     * Builds the tap PendingIntent for the nudge notification.
     *
     * - Target: [MainActivity] with extra [EXTRA_NAVIGATE_TO] = [Routes.card(listId)]
     * - Flags: [PendingIntent.FLAG_IMMUTABLE] | [PendingIntent.FLAG_UPDATE_CURRENT] (T-10-09)
     * - [FLAG_ACTIVITY_SINGLE_TOP] | [FLAG_ACTIVITY_CLEAR_TOP]: if MainActivity is already
     *   running, brings it to front and delivers the intent without a new instance.
     * - requestCode = [NotificationIds.listPrompt(listId)] so per-list intents do not collide.
     */
    private fun buildTapIntent(listId: Long): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO, Routes.card(listId.toString()))
        }
        return PendingIntent.getActivity(
            appContext,
            NotificationIds.listPrompt(listId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ─── Re-enqueue (MUST live in finally block) ──────────────────────────────

    /**
     * Re-enqueues the next slot for [listId]. Called unconditionally from the
     * `finally` block in [doWork] so no gate skip or exception can kill the chain.
     */
    private suspend fun reEnqueue(listId: Long) {
        val list = listRepo.getById(listId) ?: run {
            Timber.tag(TAG).d("re_enqueue_skipped_list_gone list=%d", listId)
            return
        }
        nudgeScheduler.scheduleFromEntity(list)
        Timber.tag(TAG).d("re_enqueued list=%d", listId)
    }
}
