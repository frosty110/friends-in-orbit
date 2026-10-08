package app.orbit.notify

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.orbit.data.entity.ListEntity
import app.orbit.data.repository.ListRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * NOTIF-12 — Hilt @Singleton that enqueues, cancels, and re-anchors
 * per-list nudge work.
 *
 * ### Self-re-enqueueing OneTimeWork (D-07)
 * Each list has a unique work name `nudge_list_{listId}`. [schedule] computes
 * the next slot from the effective schedule and enqueues with [setInitialDelay]
 * + [ExistingWorkPolicy.REPLACE]. The worker ([ListPromptWorker]) always
 * re-enqueues from its `finally` block, so the chain continues indefinitely.
 *
 * ### Active-hours interplay (D-09)
 * A list whose chosen times all fall outside its active-hours window would have
 * every nudge suppressed forever by the fire-time gate. [effectiveSchedule] adds
 * the window start as a slot in exactly that case, on the list's own days. It
 * never adds days and never revives a schedule the user emptied: the earlier
 * version merged in all seven days and injected the slot unconditionally, which
 * turned "Weekdays at 10am" into two nudges every day and kept "No days
 * selected - nudges off" nudging. No list has a window since LIST-25's fold
 * (2026-10-08); see [effectiveSchedule].
 *
 * ### Cold-start re-anchor (D-08)
 * [reAnchorAll] reads every non-archived list, decodes its schedule, and calls
 * [schedule] with `ExistingWorkPolicy.REPLACE` — idempotent on every cold start.
 */
@Singleton
open class NudgeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listRepo: ListRepository
) {

    companion object {
        /**
         * Stable unique-work name prefix for per-list nudge chains.
         * The ResetService calls
         * `workManager.cancelAllWorkByTag(TAG_NUDGES)` on full reset.
         */
        const val TAG_NUDGES = "orbit.tag.nudge"

        /** Builds the unique WorkManager work name for [listId]. */
        fun uniqueNudgeName(listId: Long): String = "nudge_list_$listId"

        /** Minimum enqueue delay — prevents zero-delay loops during tests. */
        private const val MIN_DELAY_MS = 1_000L

        /**
         * Resolves D-09 at the scheduling level: the schedule the chain actually runs.
         *
         * Returns [explicit] unchanged unless every chosen time falls outside the
         * list's active-hours window, in which case the fire-time gate would suppress
         * them all forever. Then, and only then, the window start is added as a slot
         * on the list's own days, so the list can post at least once on each of them.
         *
         * Deliberately unchanged:
         * - **No window** (either end null): the gate only applies when both ends are
         *   set, so every chosen time can already post.
         * - **Nudges off** (no days or no times): the user emptied the schedule. A
         *   slot injected here is what kept "No days selected - nudges off" nudging.
         * - **A chosen time inside the window**: it can post, and an extra slot would
         *   be a second nudge per day the user never asked for (onboarding promises
         *   one).
         *
         * Since 2026-10-08 no list has a window (LIST-25): Time of day was
         * retired, [app.orbit.data.db.MIGRATION_13_14] folded every stored window
         * into the list's own times ([foldActiveWindow], which gives exactly the
         * times this plus the gate let through), the importer folds one from an
         * older backup, and nothing writes the columns. So this returns
         * [explicit] for every list, and the screens say the stored times. It
         * stays, with the worker's gate, because removing the columns and both
         * checks is a cleanup of its own.
         *
         * Pure: no Context and no WorkManager, so [NudgeSchedulerEffectiveSlotsTest]
         * calls it on the JVM directly.
         */
        fun effectiveSchedule(
            explicit: NudgeSchedule,
            activeHoursStart: LocalTime?,
            activeHoursEnd: LocalTime?
        ): NudgeSchedule {
            if (activeHoursStart == null || activeHoursEnd == null) return explicit
            if (explicit.days.isEmpty() || explicit.times.isEmpty()) return explicit
            if (explicit.times.any { isInActiveWindow(it, activeHoursStart, activeHoursEnd) }) {
                return explicit
            }
            return explicit.copy(times = (explicit.times + activeHoursStart).distinct())
        }
    }

    // ─── Public scheduling surface ────────────────────────────────────────────

    /**
     * Enqueues (or replaces) the next slot for [listId] with the given [schedule]
     * and the list's active-hours window ([activeHoursStart]..[activeHoursEnd],
     * both null when the list is always active).
     *
     * Both window ends are required, with no defaults: [effectiveSchedule] needs
     * the end to know whether a chosen time can post, and a caller that dropped it
     * would silently lose the D-09 slot.
     *
     * Uses [setInitialDelay]; MUST NOT use `setExpedited` (mutual exclusion —
     * WorkManager rejects both together with an
     * `IllegalArgumentException: Expedited jobs cannot be delayed`).
     *
     * No-ops when [effectiveSchedule] returns an empty schedule (no next slot).
     */
    open fun schedule(
        listId: Long,
        schedule: NudgeSchedule,
        activeHoursStart: LocalTime?,
        activeHoursEnd: LocalTime?
    ) {
        val eff = effectiveSchedule(schedule, activeHoursStart, activeHoursEnd)
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val next = eff.nextSlot(now) ?: run {
            Timber.tag("nudge").d("schedule_skipped list=%d empty_effective_schedule", listId)
            return
        }
        val delayMs = ChronoUnit.MILLIS.between(now.toInstant(), next.toInstant())
            .coerceAtLeast(MIN_DELAY_MS)

        val request = OneTimeWorkRequestBuilder<ListPromptWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ListPromptWorker.KEY_LIST_ID to listId))
            .addTag(TAG_NUDGES)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueNudgeName(listId), ExistingWorkPolicy.REPLACE, request)

        Timber.tag("nudge").d(
            "scheduled list=%d next=%s delay_ms=%d",
            listId,
            next,
            delayMs
        )
    }

    /**
     * Cancels any pending nudge work for [listId]. Called on list deletion and
     * archive (D-08 / folded D-25 todo / NOTIF-11).
     *
     * Declared `open` (like [schedule] and [scheduleFromEntity]) so test
     * subclasses can capture calls without touching WorkManager.
     */
    open fun cancel(listId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueNudgeName(listId))
        Timber.tag("nudge").d("cancelled list=%d", listId)
    }

    /**
     * Re-anchors nudge work for every non-archived list — idempotent via
     * [ExistingWorkPolicy.REPLACE] (D-08).
     *
     * Called from `OrbitApp.onCreate` on cold start so WorkManager persistence
     * aligns with the current schedule (accounts for time-zone changes, schedule
     * edits that happened while the app was backgrounded, etc.).
     */
    suspend fun reAnchorAll() {
        val lists = listRepo.observeActive().first()
        Timber.tag("nudge").d("re_anchor_all count=%d", lists.size)
        for (list in lists) {
            scheduleFromEntity(list)
        }
    }

    /**
     * Convenience helper that decodes [ListEntity.nudgeScheduleJson] and forwards
     * the list's active-hours window into [schedule].
     *
     * Centralizes the decode-or-default + D-09-injection logic so callers
     * ([ListPromptWorker] re-enqueue, [reAnchorAll], [ListConfigViewModel] save)
     * don't duplicate it.
     */
    open suspend fun scheduleFromEntity(list: ListEntity) {
        val nudgeSchedule = NudgeSchedule.fromStoredJson(list.nudgeScheduleJson)
        schedule(list.id, nudgeSchedule, list.activeHoursStart, list.activeHoursEnd)
    }
}
