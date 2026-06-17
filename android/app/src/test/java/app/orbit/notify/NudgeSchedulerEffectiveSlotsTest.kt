package app.orbit.notify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pure assertions for [NudgeScheduler.effectiveSchedule] and the D-09 implicit
 * activeHoursStart slot injection at the scheduling level.
 *
 * Uses Robolectric Application only to satisfy [NudgeScheduler]'s @ApplicationContext
 * constructor — [effectiveSchedule] itself never calls Android framework methods.
 * No WorkManager, no Hilt, no DB connection required.
 *
 * Test analogues: mirrors [NudgeScheduleNextSlotTest]'s fixed-clock style,
 * but adds the scheduling-layer concern: the effective schedule merges the implicit
 * `activeHoursStart` slot before [NudgeSchedule.nextSlot] is computed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NudgeSchedulerEffectiveSlotsTest {

    /** Minimal scheduler constructed without WorkManager (methods under test don't call it). */
    private val scheduler = NudgeScheduler(
        context = ApplicationProvider.getApplicationContext<Application>(),
        listRepo = StubListRepository,
    )

    // ─── effectiveSchedule — null activeHoursStart ────────────────────────────

    @Test
    fun effectiveSchedule_withNullActiveHoursStart_returnsExplicitUnchanged() {
        val explicit = NudgeSchedule.DEFAULT // all 7 days, 10:00
        val effective = scheduler.effectiveSchedule(explicit, activeHoursStart = null)
        assertEquals(explicit, effective, "null activeHoursStart must return explicit unchanged")
    }

    @Test
    fun effectiveSchedule_withNullActiveHoursStart_preservesEmptySchedule() {
        val empty = NudgeSchedule(days = emptySet(), times = emptyList())
        val effective = scheduler.effectiveSchedule(empty, activeHoursStart = null)
        assertEquals(empty, effective)
    }

    // ─── effectiveSchedule — non-null activeHoursStart (D-09) ────────────────

    @Test
    fun effectiveSchedule_injectsActiveHoursStartAlongsideExplicitTimes() {
        // Default schedule: all 7 days at 10:00. activeHoursStart = 21:00.
        // Effective times must include BOTH 10:00 AND 21:00.
        val explicit = NudgeSchedule.DEFAULT
        val effective = scheduler.effectiveSchedule(explicit, activeHoursStart = LocalTime.of(21, 0))

        assertTrue(
            LocalTime.of(10, 0) in effective.times,
            "Explicit 10:00 slot must be preserved in effective schedule",
        )
        assertTrue(
            LocalTime.of(21, 0) in effective.times,
            "Implicit activeHoursStart 21:00 slot must be injected",
        )
        assertEquals(
            2,
            effective.times.distinct().size,
            "Effective times must have exactly 2 distinct slots: 10:00 and 21:00",
        )
    }

    @Test
    fun effectiveSchedule_withActiveHoursStart_mergesAllSevenDays() {
        // A weekdays-only explicit schedule + activeHoursStart should yield all 7 days,
        // because the implicit slot fires every day.
        val weekdays = NudgeSchedule(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            times = listOf(LocalTime.of(9, 0)),
        )
        val effective = scheduler.effectiveSchedule(weekdays, activeHoursStart = LocalTime.of(18, 0))

        assertEquals(
            DayOfWeek.values().toSet(),
            effective.days,
            "effectiveSchedule must include all 7 days when activeHoursStart is non-null",
        )
    }

    // ─── effectiveSchedule — de-duplication ──────────────────────────────────

    @Test
    fun effectiveSchedule_deduplicatesWhenActiveHoursStartEqualsExplicitTime() {
        // Explicit schedule already contains 10:00; injecting 10:00 via activeHoursStart
        // must not produce a duplicate.
        val explicit = NudgeSchedule(
            days = DayOfWeek.values().toSet(),
            times = listOf(LocalTime.of(10, 0)),
        )
        val effective = scheduler.effectiveSchedule(explicit, activeHoursStart = LocalTime.of(10, 0))

        assertEquals(
            1,
            effective.times.distinct().size,
            "Duplicate activeHoursStart must be de-duplicated; times should contain exactly one 10:00",
        )
        assertTrue(LocalTime.of(10, 0) in effective.times)
    }

    // ─── D-09 narrative: explains why the scheduling-layer injection prevents "silent forever" ──

    /**
     * Documents the D-09 failure mode and proves the fix.
     *
     * Scenario: a list with activeHoursStart=21:00 and an explicit schedule that only
     * fires at 10:00. Without D-09 injection the active-hours fire-time gate (10:00 is
     * before 21:00 window) suppresses every nudge forever — the chain re-enqueues but
     * never posts. With D-09 injection the effective schedule has BOTH 10:00 and 21:00;
     * the 21:00 slot lands at the boundary of the open window, so the worker posts at
     * least once per day when all other gates pass.
     *
     * This test asserts the scheduling layer produces both candidate slots.
     * The gate suppression at 10:00 (re-enqueue only) vs. the post at 21:00
     * is asserted in [ListPromptWorkerTest] (Task 2).
     */
    @Test
    fun effectiveSchedule_d09NarrativeProof_bothSlotsPresent() {
        val explicit = NudgeSchedule.DEFAULT // all days, 10:00 only
        val effective = scheduler.effectiveSchedule(explicit, activeHoursStart = LocalTime.of(21, 0))

        assertNotNull(effective)
        assertTrue(LocalTime.of(10, 0) in effective.times, "10:00 explicit slot present")
        assertTrue(LocalTime.of(21, 0) in effective.times, "21:00 activeHoursStart slot injected")
        assertFalse(
            effective.times.size > effective.times.distinct().size,
            "No duplicates in effective times",
        )
        // Document: without D-09, effective == NudgeSchedule.DEFAULT (only 10:00),
        // and the active-hours gate (window opens at 21:00) suppresses it forever.
        // With D-09, 21:00 is in effective.times and will fire inside the window.
    }
}

// ─── Stubs ────────────────────────────────────────────────────────────────────

/** A no-op ListRepository stub used only to satisfy the constructor. */
private val StubListRepository: app.orbit.data.repository.ListRepository =
    object : app.orbit.data.repository.ListRepository {
        override fun observeAll() = throw UnsupportedOperationException()
        override fun observeActive() = throw UnsupportedOperationException()
        override suspend fun getById(id: Long) = null
        override fun observeMembersOfList(listId: Long) = throw UnsupportedOperationException()
        override fun observeMembershipsForContact(contactId: Long) = throw UnsupportedOperationException()
        override suspend fun incrementSkipCount(contactId: Long, listId: Long, newNextDueAt: java.time.Instant) =
            throw UnsupportedOperationException()
        override suspend fun updateNextDueAt(contactId: Long, listId: Long, nextDueAt: java.time.Instant) =
            throw UnsupportedOperationException()
        override suspend fun restoreMembershipSchedule(contactId: Long, listId: Long, nextDueAt: java.time.Instant?, skipCount: Int) =
            throw UnsupportedOperationException()
        override suspend fun create(list: app.orbit.data.entity.ListEntity) = throw UnsupportedOperationException()
        override suspend fun update(list: app.orbit.data.entity.ListEntity) = throw UnsupportedOperationException()
        override suspend fun setArchived(listId: Long, archived: Boolean) = throw UnsupportedOperationException()
        override suspend fun reorder(fromIndex: Int, toIndex: Int) = throw UnsupportedOperationException()
        override suspend fun setSmartRuleJson(listId: Long, json: String?) = throw UnsupportedOperationException()
        override suspend fun setRuleParamsOverrideJson(listId: Long, json: String?) = throw UnsupportedOperationException()
        override suspend fun convertSmartToStatic(listId: Long) = throw UnsupportedOperationException()
        override suspend fun delete(listId: Long) = throw UnsupportedOperationException()
        override suspend fun updateRuleTemplate(listId: Long, templateId: Long) = throw UnsupportedOperationException()
        override suspend fun updateActiveHours(listId: Long, start: java.time.LocalTime?, end: java.time.LocalTime?) =
            throw UnsupportedOperationException()
        override suspend fun updateNotificationsEnabled(listId: Long, enabled: Boolean) = throw UnsupportedOperationException()
        override suspend fun updateName(listId: Long, name: String) = throw UnsupportedOperationException()
        override suspend fun addMember(listId: Long, contactId: Long, addedAt: java.time.Instant) = throw UnsupportedOperationException()
        override fun observeById(id: Long) = throw UnsupportedOperationException()
        override fun observeMemberCountsByListId() = throw UnsupportedOperationException()
        override suspend fun setNudgeScheduleJson(listId: Long, json: String?) = throw UnsupportedOperationException()
        override suspend fun dueCountForList(listId: Long) = 0
        override suspend fun recomputeDueCountForList(listId: Long, now: java.time.Instant) = throw UnsupportedOperationException()
        override suspend fun recomputeDueCountForActive(now: java.time.Instant) = throw UnsupportedOperationException()
    }
