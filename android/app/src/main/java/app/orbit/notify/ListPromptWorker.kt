package app.orbit.notify

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.orbit.data.AppPrefs
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.repository.ListRepository
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.domain.usecase.SurfaceResult
import app.orbit.nav.AppLinks
import app.orbit.ui.components.AvatarBitmaps
import app.orbit.ui.theme.ResolvedTheme
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * NOTIF-12 — self-re-enqueueing nudge worker for per-list prompts.
 *
 * ### 6-Gate doWork
 * 0. [ListEntity.isArchived]: an archived list never posts (NOTIF-11)
 * 1. [ListEntity.notificationsEnabled] — list-level mute flag
 * 2. [Context.areNotificationsEnabled] — POST_NOTIFICATIONS system gate (NOTIF-01 residual)
 * 3. [Context.isDndBlocking] — DND gate (NOTIF-06)
 * 4. Active-hours window gate — honors midnight-spanning ranges (NOTIF-03)
 * 5. [ListRepository.dueCountForList] ≥ 1 — only post when someone is due
 *
 * Any gate failure returns [Result.success] (never [Result.failure] — failure triggers
 * backoff retries, which is wrong for a fire-time gate miss). The gate result does NOT
 * affect the re-enqueue: the finally block re-enqueues the next slot unconditionally;
 * the re-enqueue must never live inside a gate branch. The two exceptions are a
 * list that is gone and a list that is archived: those chains are meant to end
 * (NOTIF-11), and [reEnqueue] lets them.
 *
 * ### Thread safety
 * The `try { ... } finally { reEnqueue(listId) }` structure guarantees re-enqueue even
 * when an unhandled exception escapes the gate block. A DND night or empty-due-count day
 * can NEVER silently kill the chain.
 *
 * ### What it posts (NOTIF-13, NOTIF-14, NOTIF-15)
 * [NudgeNotification] builds the nudge. It names the list's next person, the
 * head [SurfaceNextUseCase] gives Card view, with their face and a "Call"
 * action, unless the previous nudge for this list already named them
 * ([nudgeSubject]). Every nudge carries a name-free lock-screen version.
 *
 * ### Tap navigation
 * Tapping the notification opens [app.orbit.MainActivity] with extra
 * [AppLinks.EXTRA_NAVIGATE_TO] carrying `Routes.card(listId.toString())`. MainActivity
 * reads this extra after NavHost composition and navigates. `FLAG_IMMUTABLE` prevents
 * another app from rewriting the tap target (T-10-09).
 */
@HiltWorker
open class ListPromptWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val nudgeScheduler: NudgeScheduler,
    private val listRepo: ListRepository,
    private val surfaceNext: SurfaceNextUseCase,
    private val appPrefs: AppPrefs,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** WorkData key carrying the list primary key. */
        const val KEY_LIST_ID = "list_id"

        /** Intent extra key for the tap-destination route. */
        const val EXTRA_NAVIGATE_TO = AppLinks.EXTRA_NAVIGATE_TO

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
        val list = listRepo.getById(listId) ?: run {
            Timber.tag(TAG).d("list_gone list=%d", listId)
            return Result.success()
        }
        // Gate 0: archived (NOTIF-11). Archive cancels the chain, but a slot
        // already running, or one enqueued before the surface that archived
        // the list learned to cancel (Home until 2026-10-06), still fires.
        // `getById` returns archived rows, so without this gate the nudge
        // posted for a list the user had put away.
        if (list.isArchived) {
            Timber.tag(TAG).d("gate_list_archived list=%d", listId)
            return Result.success()
        }

        // Gate 1: list-level notificationsEnabled flag
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
        postNudge(list = list, dueCount = dueCount)
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
     * - Normal range (start ≤ end, e.g. 09:00 to 17:00): the start is in, the
     *   end is not.
     * - Midnight-spanning range (start > end, e.g. 22:00 to 02:00): [time] is
     *   inside when it is ≥ start OR < end (wraps around midnight).
     *
     * Delegates to [isInActiveWindow], the definition the scheduler also uses to
     * decide whether a chosen time can ever post.
     */
    internal fun isWithinActiveHours(time: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        isInActiveWindow(time, start, end)

    // ─── Notification post ────────────────────────────────────────────────────

    private suspend fun postNudge(list: ListEntity, dueCount: Int) {
        val head = (surfaceNext(list.id).first() as? SurfaceResult.Found)?.contact
        val subject = nudgeSubject(head, appPrefs.nudgeLastNamedContactId(list.id))
        val theme = resolveTheme()

        val notification = NudgeNotification.build(
            context = appContext,
            listId = list.id,
            listName = list.name,
            dueCount = dueCount,
            subject = subject,
            face = subject?.let { face(it, theme) },
            accent = theme.colors.accent.toArgb(),
        )

        // Double-check system gate before calling notify() (belt + suspenders vs race).
        if (appContext.areNotificationsEnabled()) {
            NotificationManagerCompat.from(appContext)
                .notify(NotificationIds.listPrompt(list.id), notification)
            // NOTIF-15: remember who was named, only once they really were.
            if (subject != null) appPrefs.setNudgeLastNamedContactId(list.id, subject.id)
            Timber.tag(TAG).i("posted list=%d due=%d named=%b", list.id, dueCount, subject != null)
        }
    }

    /**
     * The app's theme in the mode it is showing, so the face in the shade has
     * the colours the same person's avatar has in the app.
     */
    private suspend fun resolveTheme(): ResolvedTheme = resolveNotificationTheme(appContext, appPrefs)

    /**
     * NOTIF-14: the large icon. Their photo when they have one, otherwise the
     * monogram on the palette colour the in-app avatar gives the same name.
     * The photo is read from the address book, so on the IO dispatcher.
     */
    private suspend fun face(contact: ContactEntity, theme: ResolvedTheme): Bitmap =
        withContext(Dispatchers.IO) {
            val size = appContext.resources
                .getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
            AvatarBitmaps.photo(appContext, contact.photoUri, contact.phoneContactId, size)
                ?: theme.tones.avatarPalette(contact.displayName).let { (background, foreground) ->
                    AvatarBitmaps.initials(
                        appContext,
                        contact.displayName,
                        size,
                        background.toArgb(),
                        foreground.toArgb(),
                    )
                }
        }

    // ─── Re-enqueue (MUST live in finally block) ──────────────────────────────

    /**
     * Re-enqueues the next slot for [listId]. Called unconditionally from the
     * `finally` block in [doWork] so no gate skip or exception can kill the chain.
     *
     * The chain ends here only when the list itself has: it is gone, or it is
     * archived (NOTIF-11). An archived list's stale chain used to re-enqueue
     * itself forever; with Gate 0 it would never post, but it would still
     * wake the process on every slot. Unarchiving schedules a fresh chain
     * (`NudgeScheduler.scheduleFromEntity` from the surface that unarchives),
     * so nothing is lost by letting this one stop.
     */
    private suspend fun reEnqueue(listId: Long) {
        val list = listRepo.getById(listId) ?: run {
            Timber.tag(TAG).d("re_enqueue_skipped_list_gone list=%d", listId)
            return
        }
        if (list.isArchived) {
            Timber.tag(TAG).d("re_enqueue_skipped_list_archived list=%d", listId)
            return
        }
        nudgeScheduler.scheduleFromEntity(list)
        Timber.tag(TAG).d("re_enqueued list=%d", listId)
    }
}
