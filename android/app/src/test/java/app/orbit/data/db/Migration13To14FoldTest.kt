package app.orbit.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.entity.ListEntity
import app.orbit.domain.JsonProvider
import app.orbit.notify.NudgeSchedule
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LIST-25: [MIGRATION_13_14] folds each list's active-hours window (Time of
 * day) into its nudge times and clears both columns, so nobody's nudge moves.
 *
 * v13 and v14 have the same schema (the migration rewrites data only), so this
 * runs the migration's own code against an in-memory database on the JVM:
 * rows written through the DAO as a v13 install holds them, the migration run
 * on the open connection, the rows read back. [Migration13To14Test] is the
 * MigrationTestHelper version in androidTest, which needs a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class Migration13To14FoldTest {

    private lateinit var db: OrbitDatabase

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    private fun encode(schedule: NudgeSchedule) = JsonProvider.json.encodeToString(NudgeSchedule.serializer(), schedule)

    private val weekdays = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

    private suspend fun seed(
        name: String,
        start: LocalTime?,
        end: LocalTime?,
        scheduleJson: String?,
    ): Long = db.listDao().insert(
        ListEntity(
            name = name,
            sortOrder = 0,
            activeHoursStart = start,
            activeHoursEnd = end,
            nudgeScheduleJson = scheduleJson,
        ),
    )

    private fun migrate() = MIGRATION_13_14.migrate(db.openHelper.writableDatabase)

    private suspend fun scheduleOf(id: Long): NudgeSchedule =
        NudgeSchedule.fromStoredJson(checkNotNull(db.listDao().get(id)).nudgeScheduleJson)

    @Test
    fun an_evenings_list_on_the_default_ten_am_now_says_five_pm_and_keeps_no_window() = runTest {
        val id = seed("Evenings", t(17), t(21), NudgeSchedule.DEFAULT_JSON)

        migrate()

        val row = checkNotNull(db.listDao().get(id))
        assertNull(row.activeHoursStart)
        assertNull(row.activeHoursEnd)
        assertEquals(NudgeSchedule(DayOfWeek.entries.toSet(), listOf(t(17))), scheduleOf(id))
    }

    @Test
    fun a_custom_window_keeps_the_times_inside_it_and_the_days() = runTest {
        val id = seed("Work hours", t(9), t(17), encode(NudgeSchedule(weekdays, listOf(t(8), t(12, 30), t(18)))))

        migrate()

        assertEquals(NudgeSchedule(weekdays, listOf(t(12, 30))), scheduleOf(id))
    }

    @Test
    fun a_window_over_no_stored_schedule_folds_the_default_the_chain_was_running() = runTest {
        // A null or unreadable schedule nudged on DEFAULT (every day at 10am);
        // under Nights that came at 9pm, so 9pm is what the list now says.
        val missing = seed("Missing", t(21), t(7), null)
        val unreadable = seed("Unreadable", t(21), t(7), "{not json")

        migrate()

        val nine = NudgeSchedule(DayOfWeek.entries.toSet(), listOf(t(21)))
        assertEquals(nine, scheduleOf(missing))
        assertEquals(nine, scheduleOf(unreadable))
    }

    @Test
    fun a_list_with_no_window_is_left_exactly_as_stored() = runTest {
        val custom = encode(NudgeSchedule(weekdays, listOf(t(6, 30))))
        val anyTime = seed("Any time", null, null, custom)
        val unset = seed("Unset", null, null, null)
        // Half a window was never applied by the gate or the scheduler, so
        // there is nothing to fold; the stray end is still cleared.
        val half = seed("Half", t(17), null, custom)

        migrate()

        assertEquals(custom, db.listDao().get(anyTime)?.nudgeScheduleJson)
        assertNull(db.listDao().get(unset)?.nudgeScheduleJson)
        val halfRow = checkNotNull(db.listDao().get(half))
        assertEquals(custom, halfRow.nudgeScheduleJson)
        assertNull(halfRow.activeHoursStart)
    }

    @Test
    fun nudges_off_stay_off() = runTest {
        val noTimes = encode(NudgeSchedule(weekdays, emptyList()))
        val id = seed("No time", t(17), t(21), noTimes)

        migrate()

        assertEquals(NudgeSchedule(weekdays, emptyList()), scheduleOf(id))
    }
}
