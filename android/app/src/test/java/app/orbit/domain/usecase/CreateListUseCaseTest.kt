package app.orbit.domain.usecase

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.db.RoomTransactionRunner
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.smart.SmartListRule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LIST-28: New list's Create, end to end over real Room (in memory), because
 * what it promises is a transaction's: the list, its rhythm and its people
 * land together or not at all. A pass-through transaction runner could not
 * show the "not at all", so this does not use one.
 *
 * The failure is a real one: a chosen person who is no longer in the
 * contacts table, whose membership row breaks its foreign key part way
 * through the write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CreateListUseCaseTest {

    private val now: Instant = Instant.parse("2026-10-07T18:00:00Z")
    private lateinit var db: OrbitDatabase
    private var keepInTouchId = 0L
    private var refreshes = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            keepInTouchId = db.ruleTemplateDao().insert(ruleTemplateFixture(id = 0L, kind = RuleKind.KEEP_IN_TOUCH))
            db.contactDao().insertAll((1L..3L).map { contactFixture(id = it, displayName = "Person $it") })
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun templates(): RuleTemplateRepository =
        FakeRuleTemplateRepository(listOf(ruleTemplateFixture(id = keepInTouchId, kind = RuleKind.KEEP_IN_TOUCH)))

    private fun useCase(templates: RuleTemplateRepository = templates()) = CreateListUseCase(
        txRunner = RoomTransactionRunner(db),
        listDao = db.listDao(),
        listMembershipDao = db.listMembershipDao(),
        ruleTemplateRepo = templates,
        clock = TestClock(now),
        widgetRefreshTrigger = WidgetRefreshTrigger { refreshes++ },
    )

    private fun request(
        name: String = "Inner orbit",
        smartRule: SmartListRule? = null,
        intervalHours: Int = 7 * 24,
        people: List<Long> = listOf(1L, 2L),
    ) = CreateListUseCase.Request(name = name, smartRule = smartRule, intervalHours = intervalHours, memberContactIds = people)

    @Test
    fun `makes the list, its rhythm and its people together`() = runBlocking {
        val id = useCase()(request(name = "  Inner orbit  "))

        val list = checkNotNull(db.listDao().get(id))
        assertEquals("Inner orbit", list.name, "the name is trimmed")
        assertEquals(ListType.STATIC, list.type)
        assertEquals(keepInTouchId, list.ruleTemplateId)
        // The interval through the slider's own path (toKeepInTouchEvery,
        // which moves both cooldown bounds), never cooldownMinHours alone.
        val params = JsonProvider.json.decodeFromString(RuleParams.serializer(), checkNotNull(list.ruleParamsOverrideJson))
        assertEquals(RuleParams.KeepInTouch().withIntervalHours(7 * 24), params)
        // What the create sheet set: nudges on, any time of day, no schedule
        // stored (the default applies).
        assertTrue(list.notificationsEnabled)
        assertNull(list.activeHoursStart)
        assertNull(list.activeHoursEnd)
        assertNull(list.nudgeScheduleJson)

        val members = db.listMembershipDao().getMembersOfList(id)
        assertEquals(setOf(1L, 2L), members.map { it.contactId }.toSet())
        assertTrue(members.all { it.nextDueAt == null && it.addedAt == now }, "everyone added is up now")
        assertEquals(2, db.listDao().dueCountForList(id), "the due count is recomputed in the same write")
        assertEquals(1, refreshes, "people were added, so the widget is asked to refresh")
    }

    @Test
    fun `a new list goes last, after archived lists too`() = runBlocking {
        db.listDao().insert(listFixture(id = 0L, name = "Old", sortOrder = 4, ruleTemplateId = keepInTouchId))
        db.listDao().insert(listFixture(id = 0L, name = "Archived", sortOrder = 9, isArchived = true, ruleTemplateId = keepInTouchId))

        val id = useCase()(request())

        assertEquals(10, checkNotNull(db.listDao().get(id)).sortOrder)
    }

    @Test
    fun `the first list starts at the top`() = runBlocking {
        val id = useCase()(request(people = emptyList()))

        assertEquals(0, checkNotNull(db.listDao().get(id)).sortOrder)
    }

    @Test
    fun `a failure part way leaves nothing behind`() = runBlocking {
        // Person 99 is not in the contacts table (gone between being chosen
        // and Create): their membership breaks its foreign key after the list
        // row is written.
        assertFailsWith<Exception> { useCase()(request(people = listOf(1L, 99L))) }

        assertTrue(db.listDao().observeAll().first().isEmpty(), "no half-made list")
        assertTrue(db.listMembershipDao().observeAll().first().isEmpty(), "no memberships")
        assertEquals(0, refreshes, "a failed create changes nothing the widget shows")
    }

    @Test
    fun `an empty list is allowed and asks for no widget refresh`() = runBlocking {
        val id = useCase()(request(people = emptyList()))

        assertTrue(db.listMembershipDao().getMembersOfList(id).isEmpty())
        assertEquals(0, refreshes)
    }

    @Test
    fun `the same person chosen twice is added once`() = runBlocking {
        val id = useCase()(request(people = listOf(3L, 3L)))

        assertEquals(listOf(3L), db.listMembershipDao().getMembersOfList(id).map { it.contactId })
    }

    @Test
    fun `a list that fills itself keeps its rule and takes no people`() = runBlocking {
        val rule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30)
        val id = useCase()(request(name = "Recently added, not called", smartRule = rule, intervalHours = 48, people = emptyList()))

        val list = checkNotNull(db.listDao().get(id))
        assertEquals(ListType.SMART, list.type)
        assertEquals(rule, JsonProvider.json.decodeFromString(SmartListRule.serializer(), checkNotNull(list.smartRuleJson)))
        // A smart list needs a rhythm to surface anyone.
        assertEquals(keepInTouchId, list.ruleTemplateId)
        assertTrue(db.listMembershipDao().getMembersOfList(id).isEmpty())
    }

    @Test
    fun `people handed in for a list that fills itself are refused, and nothing is written`() = runBlocking {
        val rule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30)

        assertFailsWith<IllegalArgumentException> { useCase()(request(smartRule = rule, people = listOf(1L))) }

        assertTrue(db.listDao().observeAll().first().isEmpty())
    }

    @Test
    fun `a blank name is refused, and nothing is written`() = runBlocking {
        assertFailsWith<IllegalArgumentException> { useCase()(request(name = "   ")) }

        assertTrue(db.listDao().observeAll().first().isEmpty())
    }

    @Test
    fun `a missing Keep in touch template is refused rather than leaving a list that surfaces no one`() = runBlocking {
        assertFailsWith<IllegalStateException> { useCase(FakeRuleTemplateRepository())(request()) }

        assertTrue(db.listDao().observeAll().first().isEmpty())
    }
}
