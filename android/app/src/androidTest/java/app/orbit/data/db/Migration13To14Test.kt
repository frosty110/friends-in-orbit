package app.orbit.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orbit.domain.JsonProvider
import app.orbit.notify.NudgeSchedule
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MigrationTestHelper-based test for the schema v=13 → v=14 migration.
 *
 * Verifies that [MIGRATION_13_14] (LIST-25):
 *  - Folds an Evenings window (5pm to 9pm) over the default 10am into a 5pm
 *    schedule, the time the nudge really came.
 *  - Leaves a list with no window exactly as stored.
 *  - Clears both window columns on every row, and validates against 14.json.
 *
 * [Migration13To14FoldTest] runs the same migration code on the JVM
 * (Robolectric, in-memory) with more cases; this one checks it through Room's
 * own v13 to v14 path. Pattern matches [Migration12To13Test].
 *
 * Runs only when a connected device is available; the gate is
 * `./gradlew connectedDebugAndroidTest --tests 'Migration13To14Test'`.
 */
@RunWith(AndroidJUnit4::class)
class Migration13To14Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrbitDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_13_to_14_folds_the_window_into_the_times_and_clears_it() {
        val custom = JsonProvider.json.encodeToString(
            NudgeSchedule.serializer(),
            NudgeSchedule(days = setOf(DayOfWeek.SATURDAY), times = listOf(LocalTime.of(9, 30))),
        )
        helper.createDatabase(TEST_DB, 13).use { db ->
            // Windows are seconds of the day (OrbitTypeConverters, M9).
            db.execSQL(
                "INSERT INTO lists (name, sortOrder, isArchived, type, activeHoursStart, activeHoursEnd, " +
                    "notificationsEnabled, dueCount, nudgeScheduleJson) VALUES (?, 0, 0, 'STATIC', ?, ?, 1, 0, ?)",
                arrayOf<Any>("Evenings", 17 * 3600, 21 * 3600, NudgeSchedule.DEFAULT_JSON),
            )
            db.execSQL(
                "INSERT INTO lists (name, sortOrder, isArchived, type, activeHoursStart, activeHoursEnd, " +
                    "notificationsEnabled, dueCount, nudgeScheduleJson) VALUES (?, 1, 0, 'STATIC', NULL, NULL, 1, 0, ?)",
                arrayOf<Any>("Any time", custom),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            /* name = */ TEST_DB,
            /* version = */ 14,
            /* validateDroppedTables = */ true,
            MIGRATION_13_14,
        )
        assertNotNull(migrated)

        migrated.query(
            "SELECT name, nudgeScheduleJson, activeHoursStart, activeHoursEnd FROM lists ORDER BY sortOrder ASC",
        ).use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("Evenings", c.getString(0))
            assertEquals(
                NudgeSchedule(DayOfWeek.entries.toSet(), listOf(LocalTime.of(17, 0))),
                NudgeSchedule.fromStoredJson(c.getString(1)),
            )
            assertTrue(c.isNull(2) && c.isNull(3), "the window is cleared")
            assertTrue(c.moveToNext())
            assertEquals("Any time", c.getString(0))
            assertEquals(custom, c.getString(1), "a list with no window is left as stored")
            assertTrue(c.isNull(2) && c.isNull(3))
        }
        migrated.close()
    }

    private companion object {
        private const val TEST_DB = "orbit-migration-13-14-test.db"
    }
}
