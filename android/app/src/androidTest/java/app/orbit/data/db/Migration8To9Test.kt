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
 * MigrationTestHelper-based test for schema v=8 → v=9 (the `dueCount` column).
 *
 * Verifies that [MIGRATION_8_9]:
 *  - Adds a NOT NULL INTEGER `dueCount` column with DEFAULT 0 to `lists`.
 *  - Backfills `dueCount` correctly from `list_memberships` using a JVM-
 *    supplied `now` predicate so SQLCipher's CURRENT_TIMESTAMP semantics
 *    are not load-bearing.
 *
 * Backfill semantics under test: a membership counts toward `dueCount` iff
 * `nextDueAt IS NULL OR nextDueAt <= now`. The fixture seeds three due
 * rows (one null + two past) and one future row on the same list; the
 * post-migration `dueCount` must equal 3. A second list with zero
 * memberships verifies the default-0 ALTER branch.
 *
 * Pattern matches [Migration1To2Test], [Migration2To3Test], and
 * [Migration7To8Test] exactly — uses the raw [FrameworkSQLiteOpenHelperFactory]
 * (no SQLCipher), since the helper opens via the exported schema JSON under
 * `android/app/schemas/app.orbit.data.db.OrbitDatabase/`. SQLCipher
 * correctness is asserted separately by an adb bytes-check.
 *
 * Runs only when a connected device is available; the gate is
 * `./gradlew connectedDebugAndroidTest --tests 'Migration8To9Test'`.
 */
@RunWith(AndroidJUnit4::class)
class Migration8To9Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrbitDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_8_to_9_adds_dueCount_with_correct_backfill() {
        // The migration's UPDATE binds System.currentTimeMillis() at run
        // time, so the test fixture must use timestamps relative to that.
        // We use a baseline 1 hour before the test's "now" for the past
        // rows and 1 hour after for the future row.
        val baseline = System.currentTimeMillis()
        val onePastHour = baseline - 3_600_000L
        val twoPastHours = baseline - 7_200_000L
        val oneFutureHour = baseline + 3_600_000L

        helper.createDatabase(TEST_DB, 8).use { db ->
            // Four contacts (FK targets for list_memberships.contactId).
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, normalizedPhone, displayName, photoUri,
                   firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil, ruleOverrideJson,
                   ignoredAt, preIgnoreListMembershipsJson, isArchived)
                VALUES (NULL, '+15551234567', '+15551234567', 'Fixture A', NULL,
                        $twoPastHours, 0, 0, NULL, NULL, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, normalizedPhone, displayName, photoUri,
                   firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil, ruleOverrideJson,
                   ignoredAt, preIgnoreListMembershipsJson, isArchived)
                VALUES (NULL, '+15551234568', '+15551234568', 'Fixture B', NULL,
                        $twoPastHours, 0, 0, NULL, NULL, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, normalizedPhone, displayName, photoUri,
                   firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil, ruleOverrideJson,
                   ignoredAt, preIgnoreListMembershipsJson, isArchived)
                VALUES (NULL, '+15551234569', '+15551234569', 'Fixture C', NULL,
                        $twoPastHours, 0, 0, NULL, NULL, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, normalizedPhone, displayName, photoUri,
                   firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil, ruleOverrideJson,
                   ignoredAt, preIgnoreListMembershipsJson, isArchived)
                VALUES (NULL, '+15551234570', '+15551234570', 'Fixture D', NULL,
                        $twoPastHours, 0, 0, NULL, NULL, NULL, NULL, 0)
                """.trimIndent(),
            )

            // List 1 — three due memberships (1 null nextDueAt + 2 past) + 1 future.
            // List 2 — zero memberships (asserts default-0 branch).
            // active-hours columns are INTEGER seconds-of-day at v=8 (post-MIGRATION_4_5).
            db.execSQL(
                """
                INSERT INTO lists
                  (name, sortOrder, isArchived, type, smartRuleJson, ruleTemplateId,
                   activeHoursStart, activeHoursEnd, notificationsEnabled, ruleParamsOverrideJson)
                VALUES ('Has-due', 0, 0, 'STATIC', NULL, NULL, NULL, NULL, 1, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO lists
                  (name, sortOrder, isArchived, type, smartRuleJson, ruleTemplateId,
                   activeHoursStart, activeHoursEnd, notificationsEnabled, ruleParamsOverrideJson)
                VALUES ('Empty-list', 1, 0, 'STATIC', NULL, NULL, NULL, NULL, 1, NULL)
                """.trimIndent(),
            )

            // Three due memberships on list 1: one null, two in the past.
            // One future-dated membership: must NOT be counted.
            db.execSQL(
                """
                INSERT INTO list_memberships
                  (contactId, listId, addedAt, nextDueAt, skipCount)
                VALUES (1, 1, $twoPastHours, NULL, 0),
                       (2, 1, $twoPastHours, $onePastHour, 0),
                       (3, 1, $twoPastHours, $twoPastHours, 0),
                       (4, 1, $twoPastHours, $oneFutureHour, 0)
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            /* name = */ TEST_DB,
            /* version = */ 9,
            /* validateDroppedTables = */ true,
            MIGRATION_8_9,
        )
        assertNotNull(migrated)

        // dueCount column exists with affinity INTEGER and NOT NULL.
        // PRAGMA table_info columns: 0=cid, 1=name, 2=type, 3=notnull, 4=dflt_value, 5=pk.
        migrated.query("PRAGMA table_info('lists')").use { c ->
            var found = false
            while (c.moveToNext()) {
                if (c.getString(1) == "dueCount") {
                    found = true
                    assertEquals("INTEGER", c.getString(2), "dueCount affinity")
                    assertEquals(1, c.getInt(3), "dueCount NOT NULL")
                    assertEquals("0", c.getString(4), "dueCount DEFAULT 0")
                }
            }
            assertTrue(found, "dueCount column missing post-migration")
        }

        // Backfill: list 1 has 3 due (null + 2 past) out of 4 memberships.
        migrated.query(
            "SELECT dueCount FROM lists WHERE name = 'Has-due'",
        ).use { c ->
            assertTrue(c.moveToFirst(), "Has-due row missing post-migration")
            assertEquals(
                3,
                c.getInt(0),
                "Has-due dueCount should be 3 (1 null + 2 past, 1 future excluded)",
            )
        }

        // Empty list defaults to 0 from the ALTER … DEFAULT 0 branch (UPDATE
        // also produces 0 because the COUNT subquery returns 0).
        migrated.query(
            "SELECT dueCount FROM lists WHERE name = 'Empty-list'",
        ).use { c ->
            assertTrue(c.moveToFirst(), "Empty-list row missing post-migration")
            assertEquals(0, c.getInt(0), "Empty-list dueCount should be 0")
        }

        // Pre-existing rows survived.
        migrated.query("SELECT COUNT(*) FROM lists").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0), "lists row count should survive migration")
        }
        migrated.query("SELECT COUNT(*) FROM list_memberships").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4, c.getInt(0), "list_memberships row count should survive migration")
        }

        migrated.close()
    }

    private companion object {
        private const val TEST_DB = "orbit-migration-8-9-test.db"
    }
}
