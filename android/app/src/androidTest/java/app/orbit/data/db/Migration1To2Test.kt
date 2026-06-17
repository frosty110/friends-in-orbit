package app.orbit.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MigrationTestHelper-based test for schema v=1 → v=2.
 *
 * Verifies that [MIGRATION_1_2] adds the nullable `ruleParamsOverrideJson` column to
 * `lists` and that pre-existing rows survive with the new column NULL.
 *
 * Pattern matches [OrbitDatabaseSchemaTest] (the v=1 baseline) — uses the
 * raw [FrameworkSQLiteOpenHelperFactory] (no SQLCipher), since the helper opens via the
 * exported schema JSON under `android/app/schemas/app.orbit.data.db.OrbitDatabase/`
 * (made visible to androidTest by `sourceSets["androidTest"].assets.srcDirs(...)` in
 * `app/build.gradle.kts`). SQLCipher encryption correctness is asserted separately by
 * an adb bytes-check.
 *
 * Runs only when a connected device is available; the gate is
 * `./gradlew connectedDebugAndroidTest --tests 'Migration1To2Test'`.
 */
@RunWith(AndroidJUnit4::class)
class Migration1To2Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrbitDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_1_to_2_adds_ruleParamsOverrideJson_column_nullable() {
        // Create v=1 schema and seed a single list row using the v=1 column set
        // (no ruleParamsOverrideJson — it doesn't exist yet at this point).
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO lists
                  (name, sortOrder, isArchived, type, smartRuleJson, ruleTemplateId,
                   activeHoursStart, activeHoursEnd, notificationsEnabled)
                VALUES ('Test list', 0, 0, 'STATIC', NULL, NULL, NULL, NULL, 1)
                """.trimIndent(),
            )
        }

        // Run MIGRATION_1_2 and validate the runtime DDL matches the exported 2.json.
        val migrated = helper.runMigrationsAndValidate(
            /* name = */ TEST_DB,
            /* version = */ 2,
            /* validateDroppedTables = */ true,
            MIGRATION_1_2,
        )
        assertNotNull(migrated)

        // Verify the seeded row survived and the new column is NULL.
        migrated.query(
            "SELECT name, ruleParamsOverrideJson FROM lists WHERE name = 'Test list'",
        ).use { c ->
            assertTrue(c.moveToFirst(), "seeded row missing after migration")
            assertEquals("Test list", c.getString(0))
            assertTrue(c.isNull(1), "ruleParamsOverrideJson should default to NULL after migration")
        }
        migrated.close()
    }

    private companion object {
        private const val TEST_DB = "orbit-migration-1-2-test.db"
    }
}
