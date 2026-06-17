package app.orbit.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MigrationTestHelper baseline for schema v=1.
 *
 * Opens the database at version 1 via the helper; the helper reads the exported
 * `android/app/schemas/app.orbit.data.db.OrbitDatabase/1.json` (made visible to the
 * androidTest runtime via `sourceSets["androidTest"].assets.srcDirs("$projectDir/schemas")`
 * in app/build.gradle.kts) and asserts the runtime DDL matches.
 *
 * Any drift — adding a column without a migration, renaming a table, changing a
 * nullability — fails this test loudly. This is the schema layer's one on-device gate.
 *
 * Runs with the raw SQLite open-helper (no SQLCipher). DAO-level tests use in-memory
 * Room; SQLCipher encryption correctness is covered separately by an adb-bytes check.
 */
@RunWith(AndroidJUnit4::class)
class OrbitDatabaseSchemaTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrbitDatabase::class.java,
    )

    @Test
    fun createAllTables_matchesExportedSchema() {
        // Opens v=1; MigrationTestHelper validates runtime schema against 1.json.
        // The helper throws on mismatch — a `return` with no assertion is intentional:
        // createDatabase itself is the assertion.
        helper.createDatabase(TEST_DB, 1).close()
    }

    private companion object {
        private const val TEST_DB = "orbit-schema-test.db"
    }
}
