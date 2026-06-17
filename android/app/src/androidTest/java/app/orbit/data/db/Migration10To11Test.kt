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
 * MigrationTestHelper-based test for the schema v=10 → v=11 migration.
 *
 * Verifies that [MIGRATION_10_11]:
 *  - Adds the `contacts.isStarred` column with DEFAULT 0 — every pre-existing
 *    row reads "not starred" until the next ingest delta-sync refreshes the
 *    flag from the device.
 *  - Leaves existing contact data (displayName, normalizedPhone) and the
 *    `contact_phones` table untouched.
 *
 * Pattern matches [Migration9To10Test] exactly — raw
 * [FrameworkSQLiteOpenHelperFactory] (no SQLCipher), schema JSON under
 * `android/app/schemas/app.orbit.data.db.OrbitDatabase/`.
 *
 * Runs only when a connected device is available; the gate is
 * `./gradlew connectedDebugAndroidTest --tests 'Migration10To11Test'`.
 */
@RunWith(AndroidJUnit4::class)
class Migration10To11Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrbitDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_10_to_11_adds_isStarred_defaulting_to_zero() {
        val seededAt = 1_700_000_000_000L

        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, normalizedPhone, displayName, photoUri,
                   firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil, ruleOverrideJson,
                   ignoredAt, preIgnoreListMembershipsJson, isArchived)
                VALUES (10, '+15551234567', '+15551234567', 'Fixture A', NULL,
                        $seededAt, 0, 0, NULL, NULL, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, normalizedPhone, displayName, photoUri,
                   firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil, ruleOverrideJson,
                   ignoredAt, preIgnoreListMembershipsJson, isArchived)
                VALUES (11, '(555) 123-4568', '+15551234568', 'Fixture B', NULL,
                        $seededAt, 1, 0, NULL, NULL, $seededAt, NULL, 0)
                """.trimIndent(),
            )
            // A contact_phones row to prove the v=10 table survives untouched.
            db.execSQL(
                "INSERT INTO contact_phones (contactId, phoneNumber, normalizedPhone, isPrimary) " +
                    "VALUES (1, '+15551234567', '+15551234567', 1)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            /* name = */ TEST_DB,
            /* version = */ 11,
            /* validateDroppedTables = */ true,
            MIGRATION_10_11,
        )
        assertNotNull(migrated)

        // Every pre-existing row backfills to isStarred = 0; other columns intact.
        migrated.query(
            "SELECT displayName, normalizedPhone, isStarred, isIgnored " +
                "FROM contacts ORDER BY id ASC",
        ).use { c ->
            assertEquals(2, c.count, "contacts row count should survive migration")
            assertTrue(c.moveToFirst())
            assertEquals("Fixture A", c.getString(0))
            assertEquals("+15551234567", c.getString(1))
            assertEquals(0, c.getInt(2), "pre-existing rows default to not starred")
            assertEquals(0, c.getInt(3))
            assertTrue(c.moveToNext())
            assertEquals("Fixture B", c.getString(0))
            assertEquals("+15551234568", c.getString(1))
            assertEquals(0, c.getInt(2), "pre-existing rows default to not starred")
            assertEquals(1, c.getInt(3), "user-owned isIgnored survives untouched")
        }

        // The flag is writable post-migration (the ingest refresh path).
        migrated.execSQL("UPDATE contacts SET isStarred = 1 WHERE displayName = 'Fixture A'")
        migrated.query(
            "SELECT COUNT(*) FROM contacts WHERE isStarred = 1",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        // contact_phones untouched.
        migrated.query("SELECT COUNT(*) FROM contact_phones").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0), "contact_phones rows should survive migration")
        }

        migrated.close()
    }

    private companion object {
        private const val TEST_DB = "orbit-migration-10-11-test.db"
    }
}
