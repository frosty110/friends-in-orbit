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
 * MigrationTestHelper-based test for schema v=9 → v=10.
 *
 * Verifies that [MIGRATION_9_10]:
 *  - Creates the `contact_phones` table with the unique `normalizedPhone`
 *    index and the non-unique `contactId` index.
 *  - Backfills one `isPrimary = 1` row per existing contact from its primary
 *    `phoneNumber` / `normalizedPhone`.
 *  - Skips contacts whose `normalizedPhone` is empty (an empty match key can
 *    never match a call-log row and would collide under the unique index).
 *  - Leaves the `contacts` table untouched (no data change).
 *
 * Pattern matches [Migration8To9Test] exactly — raw
 * [FrameworkSQLiteOpenHelperFactory] (no SQLCipher), schema JSON under
 * `android/app/schemas/app.orbit.data.db.OrbitDatabase/`.
 *
 * Runs only when a connected device is available; the gate is
 * `./gradlew connectedDebugAndroidTest --tests 'Migration9To10Test'`.
 */
@RunWith(AndroidJUnit4::class)
class Migration9To10Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrbitDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_9_to_10_creates_contact_phones_with_primary_backfill() {
        val seededAt = 1_700_000_000_000L

        helper.createDatabase(TEST_DB, 9).use { db ->
            // Two normal contacts + one with an empty normalizedPhone (legacy
            // garbage row) that the backfill must skip.
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
                        $seededAt, 0, 0, NULL, NULL, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, normalizedPhone, displayName, photoUri,
                   firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil, ruleOverrideJson,
                   ignoredAt, preIgnoreListMembershipsJson, isArchived)
                VALUES (NULL, 'garbage', '', 'Fixture Empty', NULL,
                        $seededAt, 0, 0, NULL, NULL, NULL, NULL, 0)
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            /* name = */ TEST_DB,
            /* version = */ 10,
            /* validateDroppedTables = */ true,
            MIGRATION_9_10,
        )
        assertNotNull(migrated)

        // Backfill: exactly one row per non-empty-key contact, all primary.
        migrated.query(
            "SELECT contactId, phoneNumber, normalizedPhone, isPrimary " +
                "FROM contact_phones ORDER BY contactId ASC",
        ).use { c ->
            assertEquals(2, c.count, "one backfilled row per non-empty-key contact")
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("+15551234567", c.getString(1))
            assertEquals("+15551234567", c.getString(2))
            assertEquals(1, c.getInt(3), "backfilled row must be primary")
            assertTrue(c.moveToNext())
            assertEquals(2L, c.getLong(0))
            assertEquals("(555) 123-4568", c.getString(1), "raw formatting preserved")
            assertEquals("+15551234568", c.getString(2))
            assertEquals(1, c.getInt(3))
        }

        // The unique index holds: inserting a duplicate normalizedPhone for a
        // different contact must violate the constraint.
        var uniqueViolated = false
        try {
            migrated.execSQL(
                "INSERT INTO contact_phones (contactId, phoneNumber, normalizedPhone, isPrimary) " +
                    "VALUES (2, '+15551234567', '+15551234567', 0)",
            )
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            uniqueViolated = true
        }
        assertTrue(uniqueViolated, "duplicate normalizedPhone must hit the unique index")

        // contacts untouched.
        migrated.query("SELECT COUNT(*) FROM contacts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0), "contacts row count should survive migration")
        }

        migrated.close()
    }

    private companion object {
        private const val TEST_DB = "orbit-migration-9-10-test.db"
    }
}
