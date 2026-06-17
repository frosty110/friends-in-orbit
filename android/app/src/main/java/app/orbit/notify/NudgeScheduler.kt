package app.orbit.notify

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.orbit.data.entity.ListEntity
import app.orbit.data.repository.ListRepository
import app.orbit.domain.JsonProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
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
 * [effectiveSchedule] injects an implicit daily slot at `activeHoursStart` when
 * the field is non-null. Without this injection, a list whose only explicit slots
 * all fall outside its active-hours window would have EVERY nudge suppressed
 * forever by the fire-time gate (active-hours gate rejects all of them and nothing
 * posts). Injecting `activeHoursStart` guarantees at least one slot that lands at
 * the boundary of the open window.
 *
 * ### Cold-start re-anchor (D-08)
 * [reAnchorAll] reads every non-archived list, decodes its schedule, and calls
 * [schedule] with `ExistingWorkPolicy.REPLACE` — idempotent on every cold start.
 */
@Singleton
open class NudgeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listRepo: ListRepository,
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
    }

    // ─── Public scheduling surface ────────────────────────────────────────────

    /**
     * Enqueues (or replaces) the next slot for [listId] with the given [schedule]
     * and optional [activeHoursStart].
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
        activeHoursStart: LocalTime? = null,
    ) {
        val eff = effectiveSchedule(schedule, activeHoursStart)
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
            listId, next, delayMs,
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
     * [ListEntity.activeHoursStart] into [schedule].
     *
     * Centralizes the decode-or-default + D-09-injection logic so callers
     * ([ListPromptWorker] re-enqueue, [reAnchorAll], [ListConfigViewModel] save)
     * don't duplicate it.
     */
    open suspend fun scheduleFromEntity(list: ListEntity) {
        val nudgeSchedule = list.nudgeScheduleJson
            ?.takeIf { it.isNotBlank() }
            ?.let { json ->
                runCatching {
                    JsonProvider.json.decodeFromString(NudgeSchedule.serializer(), json)
                }.getOrNull()
            }
            ?: NudgeSchedule.DEFAULT

        schedule(list.id, nudgeSchedule, list.activeHoursStart)
    }

    // ─── effectiveSchedule (internal — exercised by NudgeSchedulerEffectiveSlotsTest) ──

    /**
     * Resolves D-09 at the scheduling level: when [activeHoursStart] is non-null,
     * injects it as an additional daily slot (all 7 days) into [explicit].
     *
     * **Why this is required (D-09):**
     * A list whose only explicit slots all fall outside its own active-hours window
     * would have EVERY nudge suppressed forever — the active-hours fire-time gate
     * rejects all of them and nothing posts. Injecting `activeHoursStart` here
     * guarantees at least one candidate slot that lands at the boundary of the open
     * window, so the worker can post at least once per day (if other gates pass).
     *
     * Declared `internal` (not `private`) so [NudgeSchedulerEffectiveSlotsTest] in
     * the JVM test source set can exercise it directly without reflection.
     *
     * @param explicit The schedule stored on [ListEntity.nudgeScheduleJson].
     * @param activeHoursStart The start of the list's active-hours window; null means
     *   always active (no injection needed).
     * @return A [NudgeSchedule] with the implicit slot merged in, or [explicit]
     *   unchanged when [activeHoursStart] is null.
     */
    internal fun effectiveSchedule(
        explicit: NudgeSchedule,
        activeHoursStart: LocalTime?,
    ): NudgeSchedule {
        if (activeHoursStart == null) return explicit

        // Merge all 7 days with the explicit days (the implicit slot fires every day).
        val mergedDays = explicit.days + DayOfWeek.values().toSet()

        // Inject the implicit activeHoursStart time; de-duplicate.
        val mergedTimes = (explicit.times + activeHoursStart).distinct()

        return NudgeSchedule(days = mergedDays, times = mergedTimes)
    }
}
