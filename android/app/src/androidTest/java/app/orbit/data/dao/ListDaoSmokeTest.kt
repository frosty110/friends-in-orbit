package app.orbit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for [ListDao] — drives the DAO through an in-memory [OrbitDatabase]
 * instance and asserts insert/read round-trips plus the entity-level invariant
 * (DATA-09: [ListType.SMART] persists; [ListEntity.smartRuleJson] is nullable).
 *
 * Note on "seed ruleTemplate FK resolution": the in-memory DB builder is
 * intentionally constructed WITHOUT the production SeedCallback — so there are no
 * seeded rule templates available to foreign-key against. Instead, this test
 * asserts [ListEntity.ruleTemplateId] round-trips as null, which is the shape
 * actually exercised end-to-end (no list yet carries a rule template). FK
 * SET_NULL-on-delete is a separate cascade concern exercised by
 * `RuleTemplateDaoSmokeTest` once the seed callback is wired.
 */
@RunWith(AndroidJUnit4::class)
class ListDaoSmokeTest {

    private lateinit var db: OrbitDatabase
    private lateinit var dao: ListDao

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.listDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertStaticList_roundTrips() = runTest {
        val id = dao.insert(
            ListEntity(
                name = "Inner orbit",
                sortOrder = 0,
            ),
        )

        val fetched = dao.get(id)

        assertNotNull(fetched)
        assertEquals("Inner orbit", fetched.name)
        assertEquals(ListType.STATIC, fetched.type) // default per DATA-09
        assertEquals(null, fetched.smartRuleJson)
        assertEquals(null, fetched.ruleTemplateId)
        assertEquals(true, fetched.notificationsEnabled)
        assertEquals(false, fetched.isArchived)
    }

    @Test
    fun insertSmartList_roundTripsWithNullRuleJson() = runTest {
        // DATA-09: ListEntity must carry type = SMART end-to-end.
        // smartRuleJson stays null until real SmartListRule serializations are populated.
        val id = dao.insert(
            ListEntity(
                name = "Recently added",
                sortOrder = 1,
                type = ListType.SMART,
                smartRuleJson = null,
            ),
        )

        val fetched = dao.get(id)

        assertNotNull(fetched)
        assertEquals(ListType.SMART, fetched.type)
        assertEquals(null, fetched.smartRuleJson)

        // Flow read also sees it.
        val active = dao.observeActive().first()
        assertEquals(1, active.size)
        assertEquals(ListType.SMART, active[0].type)
    }
}
