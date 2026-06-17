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
 * MigrationTestHelper-based test for schema v=2 → v=3.
 *
 * Verifies that [MIGRATION_2_3] adds three additive columns to `contacts`:
 *  - `ignoredAt` (INTEGER, NULL by default — IGNORE-06 sort key)
 *  - `preIgnoreListMembershipsJson` (TEXT, NULL — un-ignore drift restore)
 *  - `isArchived` (INTEGER NOT NULL DEFAULT 0 — archive flag)
 *
 * Pre-existing rows survive with `ignoredAt = NULL`, `preIgnoreListMembershipsJson = NULL`,
 * `isArchived = 0`, which is the "never ignored / never archived" branch in
 * the ignore/archive use cases.
 *
 * Pattern matches [Migration1To2Test] exactly — uses the raw
 * [FrameworkSQLiteOpenHelperFactory] (no SQLCipher), since the helper opens
 * via the exported schema JSON under
 * `android/app/schemas/app.orbit.data.db.OrbitDatabase/`. SQLCipher correctness
 * is asserted separately by an adb bytes-check.
 *
 * Runs only when a connected device is available; the gate is
 * `./gradlew connectedDebugAndroidTest --tests 'Migration2To3Test'`.
 */
@RunWith(AndroidJUnit4::class)
class Migration2To3Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrbitDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_2_to_3_adds_three_additive_columns_to_contacts() {
        // Create v=2 schema and seed a single contact row using the v=2 column set
        // (no ignoredAt / preIgnoreListMembershipsJson / isArchived — they don't
        // exist yet at this point).
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, displayName, photoUri,
                   firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil, ruleOverrideJson)
                VALUES (NULL, '+15551234567', 'Pre-migration row', NULL,
                        1700000000000, 0, 0, NULL, NULL)
                """.trimIndent(),
            )
        }

        // Run MIGRATION_2_3 and validate the runtime DDL matches the exported 3.json.
        val migrated = helper.runMigrationsAndValidate(
            /* name = */ TEST_DB,
            /* version = */ 3,
            /* validateDroppedTables = */ true,
            MIGRATION_2_3,
        )
        assertNotNull(migrated)

        // Verify the seeded row survived with the three new columns at their defaults.
        migrated.query(
            """
            SELECT displayName, ignoredAt, preIgnoreListMembershipsJson, isArchived
            FROM contacts
            WHERE displayName = 'Pre-migration row'
            """.trimIndent(),
        ).use { c ->
            assertTrue(c.moveToFirst(), "seeded row missing after migration")
            assertEquals("Pre-migration row", c.getString(0))
            assertTrue(c.isNull(1), "ignoredAt should default to NULL after migration")
            assertTrue(c.isNull(2), "preIgnoreListMembershipsJson should default to NULL after migration")
            assertEquals(0, c.getInt(3), "isArchived should default to 0 after migration")
        }
        migrated.close()
    }

    private companion object {
        private const val TEST_DB = "orbit-migration-2-3-test.db"
    }
}
