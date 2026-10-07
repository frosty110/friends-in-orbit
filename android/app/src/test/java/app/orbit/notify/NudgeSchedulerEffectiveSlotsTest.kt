package app.orbit.notify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure assertions for [NudgeScheduler.effectiveSchedule]: the D-09 slot at the
 * window start is added only when every chosen time falls outside the active-hours
 * window, and never adds days or revives an emptied schedule.
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
        listRepo = StubListRepository
    )

    private val weekdays = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY
    )
    private val nineToFive = LocalTime.of(9, 0) to LocalTime.of(17, 0)

    private fun effective(explicit: NudgeSchedule, window: Pair<LocalTime, LocalTime>?) =
        scheduler.effectiveSchedule(explicit, window?.first, window?.second)

    // ─── No window: the fire-time gate never applies ─────────────────────────

    @Test
    fun effectiveSchedule_withNoWindow_returnsExplicitUnchanged() {
        val explicit = NudgeSchedule.DEFAULT // all 7 days, 10:00
        assertEquals(explicit, effective(explicit, window = null))
    }

    @Test
    fun effectiveSchedule_withOnlyOneWindowEnd_returnsExplicitUnchanged() {
        // The worker gates only when both ends are set, so half a window gates nothing.
        val explicit = NudgeSchedule.DEFAULT
        assertEquals(explicit, scheduler.effectiveSchedule(explicit, LocalTime.of(21, 0), null))
    }

    // ─── The user's choices survive a window ─────────────────────────────────

    @Test
    fun effectiveSchedule_keepsTheUsersDays() {
        // Regression: the window used to merge in all seven days, so "Weekdays at
        // 10am" nudged on weekends too.
        val explicit = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(10, 0)))
        assertEquals(weekdays, effective(explicit, nineToFive).days)
    }

    @Test
    fun effectiveSchedule_doesNotAddASlotWhenAChosenTimeIsInsideTheWindow() {
        // Regression: 10:00 inside 9-5 already posts; the old injection added 9:00
        // as a second daily nudge.
        val explicit = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(10, 0)))
        assertEquals(explicit, effective(explicit, nineToFive))
    }

    @Test
    fun effectiveSchedule_withNoDays_staysOff() {
        // Regression: "No days selected - nudges off" kept nudging once a window was set.
        val explicit = NudgeSchedule(days = emptySet(), times = listOf(LocalTime.of(10, 0)))
        val eff = effective(explicit, LocalTime.of(21, 0) to LocalTime.of(23, 0))
        assertEquals(explicit, eff)
        assertNull(eff.nextSlot(ZonedDateTime.of(2026, 10, 5, 8, 0, 0, 0, ZoneId.of("UTC"))))
    }

    @Test
    fun effectiveSchedule_withNoTimes_staysOff() {
        val explicit = NudgeSchedule(days = weekdays, times = emptyList())
        assertEquals(explicit, effective(explicit, nineToFive))
    }

    // ─── D-09: every chosen time is outside the window ───────────────────────

    @Test
    fun effectiveSchedule_addsTheWindowStartWhenEveryTimeIsOutside() {
        // 10:00 can never post inside 21:00-23:00; without the added slot the
        // gate would suppress this list forever.
        val explicit = NudgeSchedule.DEFAULT
        val eff = effective(explicit, LocalTime.of(21, 0) to LocalTime.of(23, 0))
        assertEquals(listOf(LocalTime.of(10, 0), LocalTime.of(21, 0)), eff.times)
        assertEquals(explicit.days, eff.days)
    }

    @Test
    fun effectiveSchedule_addsTheWindowStartOnTheUsersDaysOnly() {
        val explicit = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(9, 0)))
        val eff = effective(explicit, LocalTime.of(18, 0) to LocalTime.of(22, 0))
        assertTrue(LocalTime.of(18, 0) in eff.times)
        assertEquals(weekdays, eff.days, "the added slot must not add weekend days")
    }

    @Test
    fun effectiveSchedule_respectsAWindowThatSpansMidnight() {
        // 23:30 is inside 22:00-02:00, so nothing is added.
        val inside = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(23, 30)))
        assertEquals(inside, effective(inside, LocalTime.of(22, 0) to LocalTime.of(2, 0)))
        // 12:00 is outside it, so the window start is added.
        val outside = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(12, 0)))
        val lateWindow = LocalTime.of(22, 0) to LocalTime.of(2, 0)
        assertTrue(LocalTime.of(22, 0) in effective(outside, lateWindow).times)
    }

    @Test
    fun effectiveSchedule_doesNotDuplicateTheWindowStart() {
        // A window whose start is also a chosen time is covered by the inside-window
        // rule; the added slot can never duplicate it.
        val explicit = NudgeSchedule(days = weekdays, times = listOf(LocalTime.of(21, 0)))
        val eff = effective(explicit, LocalTime.of(21, 0) to LocalTime.of(23, 0))
        assertEquals(eff.times.distinct(), eff.times)
    }

    // ─── One definition of "inside the window" ───────────────────────────────

    @Test
    fun isInActiveWindow_isInclusiveAndWrapsMidnight() {
        assertTrue(isInActiveWindow(LocalTime.of(9, 0), LocalTime.of(9, 0), LocalTime.of(17, 0)))
        assertTrue(isInActiveWindow(LocalTime.of(17, 0), LocalTime.of(9, 0), LocalTime.of(17, 0)))
        assertFalse(isInActiveWindow(LocalTime.of(8, 59), LocalTime.of(9, 0), LocalTime.of(17, 0)))
        assertTrue(isInActiveWindow(LocalTime.of(1, 0), LocalTime.of(22, 0), LocalTime.of(2, 0)))
        assertFalse(isInActiveWindow(LocalTime.of(12, 0), LocalTime.of(22, 0), LocalTime.of(2, 0)))
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
        override fun observeMembershipsForContact(contactId: Long) =
            throw UnsupportedOperationException()
        override suspend fun incrementSkipCount(
            contactId: Long,
            listId: Long,
            newNextDueAt: java.time.Instant
        ) = throw UnsupportedOperationException()
        override suspend fun updateNextDueAt(
            contactId: Long,
            listId: Long,
            nextDueAt: java.time.Instant
        ) = throw UnsupportedOperationException()
        override suspend fun restoreMembershipSchedule(
            contactId: Long,
            listId: Long,
            nextDueAt: java.time.Instant?,
            skipCount: Int
        ) = throw UnsupportedOperationException()
        override suspend fun create(list: app.orbit.data.entity.ListEntity) =
            throw UnsupportedOperationException()
        override suspend fun update(list: app.orbit.data.entity.ListEntity) =
            throw UnsupportedOperationException()
        override suspend fun setArchived(listId: Long, archived: Boolean) =
            throw UnsupportedOperationException()
        override suspend fun reorder(fromIndex: Int, toIndex: Int) =
            throw UnsupportedOperationException()
        override suspend fun setSmartRuleJson(listId: Long, json: String?) =
            throw UnsupportedOperationException()
        override suspend fun setRuleParamsOverrideJson(listId: Long, json: String?) =
            throw UnsupportedOperationException()
        override suspend fun convertSmartToStatic(listId: Long) =
            throw UnsupportedOperationException()
        override suspend fun delete(listId: Long) = throw UnsupportedOperationException()
        override suspend fun updateRuleTemplate(listId: Long, templateId: Long) =
            throw UnsupportedOperationException()
        override suspend fun updateActiveHours(
            listId: Long,
            start: java.time.LocalTime?,
            end: java.time.LocalTime?
        ) = throw UnsupportedOperationException()
        override suspend fun updateNotificationsEnabled(listId: Long, enabled: Boolean) =
            throw UnsupportedOperationException()
        override suspend fun updateName(listId: Long, name: String) =
            throw UnsupportedOperationException()
        override suspend fun addMember(listId: Long, contactId: Long, addedAt: java.time.Instant) =
            throw UnsupportedOperationException()
        override fun observeById(id: Long) = throw UnsupportedOperationException()
        override fun observeMemberCountsByListId() = throw UnsupportedOperationException()
        override suspend fun setNudgeScheduleJson(listId: Long, json: String?) =
            throw UnsupportedOperationException()
        override suspend fun dueCountForList(listId: Long) = 0
        override suspend fun recomputeDueCountForList(listId: Long, now: java.time.Instant) =
            throw UnsupportedOperationException()
        override suspend fun recomputeDueCountForActive(now: java.time.Instant) =
            throw UnsupportedOperationException()
    }
