package app.orbit.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orbit.notify.NudgeSchedule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MigrationTestHelper-based test for schema v=11 → v=12.
 *
 * Verifies that [MIGRATION_11_12]:
 *  - Adds the `lists.nudgeScheduleJson` TEXT column.
 *  - Backfills every pre-existing list row with [NudgeSchedule.DEFAULT_JSON]
 *    (NOTIF-11, D-03 default-ON contract).
 *  - Leaves existing list columns (name, sortOrder, notificationsEnabled, dueCount) intact.
 *
 * Pattern matches [Migration10To11Test] exactly — raw [FrameworkSQLiteOpenHelperFactory]
 * (no SQLCipher), schema JSON under `android/app/schemas/app.orbit.data.db.OrbitDatabase/`.
 *
 * Runs only when a connected device is available; gate is
 * `./gradlew connectedDebugAndroidTest --tests 'Migration11To12Test'`.
 */
@RunWith(AndroidJUnit4::class)
class Migration11To12Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrbitDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_11_to_12_seeds_nudgeScheduleJson_on_existing_lists() {
        helper.createDatabase(TEST_DB, 11).use { db ->
            // Seed a list row with no nudgeScheduleJson column (v11 schema)
            db.execSQL(
                "INSERT INTO lists (name, sortOrder, isArchived, type, notificationsEnabled) " +
                    "VALUES ('Inner orbit', 0, 0, 'STATIC', 1)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 12, true, MIGRATION_11_12,
        )
        assertNotNull(migrated)

        migrated.query(
            "SELECT name, sortOrder, notificationsEnabled, nudgeScheduleJson " +
                "FROM lists WHERE name = 'Inner orbit'",
        ).use { c ->
            assertEquals(1, c.count, "seeded list row should survive migration")
            assertTrue(c.moveToFirst())
            assertEquals("Inner orbit", c.getString(0))
            assertEquals(0, c.getInt(1), "sortOrder intact")
            assertEquals(1, c.getInt(2), "notificationsEnabled intact")
            val json = c.getString(3)
            assertNotNull(json, "nudgeScheduleJson must be backfilled — not NULL")
            assertEquals(
                NudgeSchedule.DEFAULT_JSON,
                json,
                "backfilled JSON must equal DEFAULT_JSON",
            )
        }

        migrated.close()
    }

    private companion object {
        private const val TEST_DB = "orbit-migration-11-12-test.db"
    }
}
