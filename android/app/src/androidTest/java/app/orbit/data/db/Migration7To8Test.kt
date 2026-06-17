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
 * MigrationTestHelper-based test for schema v=7 → v=8.
 *
 * Verifies that [MIGRATION_7_8] adds two additive non-unique indices with no
 * data change:
 *  - `index_call_events_occurredAt` on `call_events(occurredAt)` — eliminates
 *    filesort on Call Log open (`observeForLog ORDER BY occurredAt DESC`).
 *  - `index_contacts_isIgnored_ignoredAt` on `contacts(isIgnored, ignoredAt)`
 *    — eliminates full-table scan on Settings → Ignored Contacts.
 *
 * Pre-existing rows survive untouched (no schema changes to columns or
 * constraints). The test seeds one contact + one call_event at v=7 and
 * confirms both rows are still present and queryable post-migration.
 *
 * Pattern matches [Migration1To2Test] and [Migration2To3Test] exactly — uses
 * the raw [FrameworkSQLiteOpenHelperFactory] (no SQLCipher), since the helper
 * opens via the exported schema JSON under
 * `android/app/schemas/app.orbit.data.db.OrbitDatabase/`. SQLCipher correctness
 * is asserted separately by an adb bytes-check.
 *
 * Index existence is verified via `PRAGMA index_list('<table>')`, which
 * returns one row per index attached to the table. The index names are
 * Room's auto-generated `index_<table>_<cols>` form — they must match
 * exactly or Room's TableInfo validation in [runMigrationsAndValidate]
 * will fail before this test even runs its own assertions.
 *
 * Runs only when a connected device is available; the gate is
 * `./gradlew connectedDebugAndroidTest --tests 'Migration7To8Test'`.
 */
@RunWith(AndroidJUnit4::class)
class Migration7To8Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrbitDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_7_to_8_adds_two_indices_and_preserves_data() {
        // Create v=7 schema and seed one contact + one call_event using the
        // v=7 column set. We pick `isIgnored = 1` and a non-null `ignoredAt`
        // to exercise the composite index domain on the contacts side.
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                """
                INSERT INTO contacts
                  (phoneContactId, phoneNumber, normalizedPhone, displayName, photoUri,
                   firstSeenByAppAt, isIgnored, isOrphaned, pausedUntil, ruleOverrideJson,
                   ignoredAt, preIgnoreListMembershipsJson, isArchived)
                VALUES (NULL, '+15551234567', '+15551234567', 'Pre-migration row', NULL,
                        1700000000000, 1, 0, NULL, NULL,
                        1700000500000, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO call_events
                  (contactId, occurredAt, direction, durationSeconds, source)
                VALUES (1, 1700000600000, 'OUTGOING', 42, 'SYSTEM')
                """.trimIndent(),
            )
        }

        // Run MIGRATION_7_8 and validate the runtime DDL matches the exported 8.json.
        // If the migration's CREATE INDEX names diverge from Room's auto-generated
        // names, this call throws before our PRAGMA assertions run.
        val migrated = helper.runMigrationsAndValidate(
            /* name = */ TEST_DB,
            /* version = */ 8,
            /* validateDroppedTables = */ true,
            MIGRATION_7_8,
        )
        assertNotNull(migrated)

        // PRAGMA index_list('call_events') — column 1 is the index name.
        // Expect both the pre-existing composite (contactId, occurredAt) and
        // the newly added (occurredAt) single-column index.
        migrated.query("PRAGMA index_list('call_events')").use { cursor ->
            val indices = buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
            assertTrue(
                indices.any { it == "index_call_events_occurredAt" },
                "expected index_call_events_occurredAt after MIGRATION_7_8; got $indices",
            )
            assertTrue(
                indices.any { it == "index_call_events_contactId_occurredAt" },
                "pre-existing composite index should survive; got $indices",
            )
        }

        // PRAGMA index_list('contacts') — expect new composite + pre-existing
        // unique normalizedPhone + non-unique phoneNumber.
        migrated.query("PRAGMA index_list('contacts')").use { cursor ->
            val indices = buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
            assertTrue(
                indices.any { it == "index_contacts_isIgnored_ignoredAt" },
                "expected index_contacts_isIgnored_ignoredAt after MIGRATION_7_8; got $indices",
            )
            assertTrue(
                indices.any { it == "index_contacts_normalizedPhone" },
                "pre-existing unique normalizedPhone index should survive; got $indices",
            )
            assertTrue(
                indices.any { it == "index_contacts_phoneNumber" },
                "pre-existing phoneNumber index should survive; got $indices",
            )
        }

        // Confirm seeded rows survived — no data change in M9.
        migrated.query("SELECT COUNT(*) FROM contacts").use { c ->
            assertTrue(c.moveToFirst(), "contacts count cursor empty")
            assertEquals(1, c.getInt(0), "contacts row should survive migration")
        }
        migrated.query("SELECT COUNT(*) FROM call_events").use { c ->
            assertTrue(c.moveToFirst(), "call_events count cursor empty")
            assertEquals(1, c.getInt(0), "call_events row should survive migration")
        }

        // Spot-check that the seeded contact's ignored sort key is intact.
        migrated.query(
            "SELECT displayName, isIgnored, ignoredAt FROM contacts " +
                "WHERE displayName = 'Pre-migration row'",
        ).use { c ->
            assertTrue(c.moveToFirst(), "seeded contact missing after migration")
            assertEquals("Pre-migration row", c.getString(0))
            assertEquals(1, c.getInt(1), "isIgnored should remain 1 after migration")
            assertEquals(1700000500000, c.getLong(2), "ignoredAt should be preserved")
        }

        migrated.close()
    }

    private companion object {
        private const val TEST_DB = "orbit-migration-7-8-test.db"
    }
}
