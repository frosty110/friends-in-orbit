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
 * MigrationTestHelper-based test for the schema v=12 → v=13 migration.
 *
 * Verifies that [MIGRATION_12_13]:
 *  - Adds the `contacts.deviceUpdatedAt` column (nullable INTEGER) — every
 *    pre-existing row reads NULL until the next ingest COALESCE-backfills it from
 *    the device's CONTACT_LAST_UPDATED_TIMESTAMP.
 *  - Leaves existing contact data (displayName, normalizedPhone, isStarred,
 *    user-owned isIgnored) untouched.
 *  - Yields a writable column post-migration (the ingest backfill path).
 *
 * Pattern matches [Migration10To11Test] exactly — raw
 * [FrameworkSQLiteOpenHelperFactory] (no SQLCipher), schema JSON under
 * `android/app/schemas/app.orbit.data.db.OrbitDatabase/`.
 *
 * Runs only when a connected device is available; the gate is
 * `./gradlew connectedDebugAndroidTest --tests 'Migration12To13Test'`.
 */
@RunWith(AndroidJUnit4::class)
class Migration12To13Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrbitDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_12_to_13_adds_deviceUpdatedAt_defaulting_to_null() {
        val seededAt = 1_700_000_000_000L

        helper.createDatabase(TEST_DB, 12).use { db ->
            // v=12 contacts carries isStarred (added v=11); deviceUpdatedAt does not
            // exist yet.
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, normalizedPhone, displayName, photoUri,
                   isStarred, firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil,
                   ruleOverrideJson, ignoredAt, preIgnoreListMembershipsJson, isArchived)
                VALUES (10, '+15551234567', '+15551234567', 'Fixture A', NULL,
                        0, $seededAt, 0, 0, NULL, NULL, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, normalizedPhone, displayName, photoUri,
                   isStarred, firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil,
                   ruleOverrideJson, ignoredAt, preIgnoreListMembershipsJson, isArchived)
                VALUES (11, '(555) 123-4568', '+15551234568', 'Fixture B', NULL,
                        1, $seededAt, 1, 0, NULL, NULL, $seededAt, NULL, 0)
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            /* name = */ TEST_DB,
            /* version = */ 13,
            /* validateDroppedTables = */ true,
            MIGRATION_12_13,
        )
        assertNotNull(migrated)

        // Every pre-existing row reads deviceUpdatedAt = NULL; other columns intact.
        migrated.query(
            "SELECT displayName, normalizedPhone, isStarred, isIgnored, deviceUpdatedAt " +
                "FROM contacts ORDER BY id ASC",
        ).use { c ->
            assertEquals(2, c.count, "contacts row count should survive migration")
            assertTrue(c.moveToFirst())
            assertEquals("Fixture A", c.getString(0))
            assertEquals("+15551234567", c.getString(1))
            assertEquals(0, c.getInt(2), "device-owned isStarred survives untouched")
            assertEquals(0, c.getInt(3))
            assertTrue(c.isNull(4), "pre-existing rows have no device timestamp yet")
            assertTrue(c.moveToNext())
            assertEquals("Fixture B", c.getString(0))
            assertEquals("+15551234568", c.getString(1))
            assertEquals(1, c.getInt(2), "device-owned isStarred survives untouched")
            assertEquals(1, c.getInt(3), "user-owned isIgnored survives untouched")
            assertTrue(c.isNull(4), "pre-existing rows have no device timestamp yet")
        }

        // The column is writable post-migration (the ingest backfill path).
        migrated.execSQL(
            "UPDATE contacts SET deviceUpdatedAt = $seededAt WHERE displayName = 'Fixture A'",
        )
        migrated.query(
            "SELECT COUNT(*) FROM contacts WHERE deviceUpdatedAt IS NOT NULL",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        migrated.close()
    }

    private companion object {
        private const val TEST_DB = "orbit-migration-12-13-test.db"
    }
}
