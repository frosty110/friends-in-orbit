package app.orbit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.RuleKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the three default RuleTemplate rows (KEEP_IN_TOUCH, LATE_NIGHT, ENERGIZE)
 * are seeded on first DB creation. The RuleEngine consumes these rows; without them,
 * the engine has no default templates to map against.
 *
 * NOTE ON SEED CALLBACK: the production seed callback lives at
 * `app.orbit.data.db.DatabaseFactory.SeedCallback` and is declared `private object`.
 * Because it is file-private, tests cannot reference it across package boundaries. This
 * test defines its own [TestSeedCallback] that mirrors the production behavior
 * line-by-line — three INSERTs into `rule_templates` with RuleKind enum names. If the
 * production SeedCallback's behavior ever drifts, this test must be updated to match.
 *
 * Uses `Room.inMemoryDatabaseBuilder(...)` intentionally — NOT the production
 * `DatabaseFactory.create()` — so SQLCipher is bypassed. See sibling test
 * [ContactDaoSmokeTest] for the locked harness rationale.
 */
@RunWith(AndroidJUnit4::class)
class RuleTemplateDaoSmokeTest {

    private lateinit var db: OrbitDatabase
    private lateinit var dao: RuleTemplateDao

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(TestSeedCallback)
            .build()
        dao = db.ruleTemplateDao()
        // Force the DB to open so onCreate fires before the test body runs.
        // observeAll() alone would not trigger onCreate until the first query —
        // reading writableDatabase forces opening.
        db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeAll_returnsThreeSeededRowsMatchingRuleKindEnum() = runTest {
        val rows = dao.observeAll().first()

        assertEquals(3, rows.size)

        val kinds = rows.map { it.kind }.toSet()
        assertEquals(setOf(RuleKind.KEEP_IN_TOUCH, RuleKind.LATE_NIGHT, RuleKind.ENERGIZE), kinds)

        // Every row has a non-empty name (the name equals kind.name per SeedCallback).
        assertTrue(rows.all { it.name.isNotEmpty() })
        // Every row has the placeholder paramsJson — the rule engine replaces with real params.
        assertTrue(rows.all { it.paramsJson == "{}" })
    }
}

/**
 * Mirrors `app.orbit.data.db.DatabaseFactory.SeedCallback` because the production symbol
 * is `private object` and inaccessible across packages. Keep in sync with production
 * whenever seed semantics change.
 */
private object TestSeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            insertTemplate(db, name = RuleKind.KEEP_IN_TOUCH.name, kind = RuleKind.KEEP_IN_TOUCH)
            insertTemplate(db, name = RuleKind.LATE_NIGHT.name, kind = RuleKind.LATE_NIGHT)
            insertTemplate(db, name = RuleKind.ENERGIZE.name, kind = RuleKind.ENERGIZE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertTemplate(db: SupportSQLiteDatabase, name: String, kind: RuleKind) {
        db.execSQL(
            "INSERT INTO rule_templates (name, kind, paramsJson) VALUES (?, ?, ?)",
            arrayOf(name, kind.name, "{}"),
        )
    }
}
