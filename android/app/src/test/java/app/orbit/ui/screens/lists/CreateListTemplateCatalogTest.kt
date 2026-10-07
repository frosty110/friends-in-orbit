package app.orbit.ui.screens.lists

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeScheduler
import app.orbit.testutil.MainDispatcherRule
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Catalog invariants for [TemplateChoice.Catalog]
 * and the SMART-02 Recently-added-not-called JSON shape.
 *
 * Tests bind the contract that the bottom sheet picks up:
 *   - 6 entries, locked id order
 *   - Verbatim displayNames
 *   - The single SMART entry encodes [SmartListRule.RecentlyAddedNotCalled]
 *     with `daysWindow = 30` (SMART-02 default)
 *   - 4 named static templates default to [RuleKind.KEEP_IN_TOUCH]
 *   - "Start from blank" carries an empty default name + KEEP_IN_TOUCH kind
 *   - VM-level integration: createList(recently_added, name) persists a SMART
 *     ListEntity whose smartRuleJson decodes back to RecentlyAddedNotCalled(30)
 *
 * Robolectric is needed because the ListsManagerViewModel ctor takes a
 * NudgeScheduler which requires ApplicationContext; this test's VM-level
 * integration test supplies a no-op NudgeScheduler subclass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CreateListTemplateCatalogTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Template names and subtitles are string resources; resolve them here.
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // ============================================================================
    // Test 1 — locked size + id order.
    // ============================================================================

    @Test
    fun catalog_has_six_templates_in_locked_order() {
        val ids = TemplateChoice.Catalog.map { it.id }
        assertEquals(6, TemplateChoice.Catalog.size)
        assertEquals(
            listOf(
                "inner_orbit",
                "family",
                "mentors",
                "drifted",
                "recently_added_not_called",
                "blank"
            ),
            ids
        )
    }

    // ============================================================================
    // Test 2 — verbatim displayNames.
    // ============================================================================

    @Test
    fun displayNames_are_verbatim_per_ui_spec() {
        val byId = TemplateChoice.Catalog.associateBy { it.id }
        fun name(id: String): String = context.getString(byId.getValue(id).displayNameRes)
        assertEquals("Inner orbit", name("inner_orbit"))
        assertEquals("Family", name("family"))
        assertEquals("Mentors", name("mentors"))
        assertEquals("Drifted", name("drifted"))
        assertEquals("Recently added, not called", name("recently_added_not_called"))
        assertEquals("Start from blank", name("blank"))
    }

    // ============================================================================
    // Test 3 — SMART-02 default: RecentlyAddedNotCalled(daysWindow = 30) round-trips.
    // ============================================================================

    @Test
    fun recently_added_template_emits_correct_smart_rule_json() {
        val recently = TemplateChoice.Catalog.single { it.id == "recently_added_not_called" }
        val rule =
            assertNotNull(recently.smartRule, "Recently added template must carry a SmartListRule")
        val encoded = JsonProvider.json.encodeToString(SmartListRule.serializer(), rule)
        val decoded = JsonProvider.json.decodeFromString(SmartListRule.serializer(), encoded)
        assertTrue(
            decoded is SmartListRule.RecentlyAddedNotCalled,
            "decoded rule must be RecentlyAddedNotCalled"
        )
        assertEquals(30, decoded.daysWindow)
        assertEquals(ListType.SMART, recently.type)
        // A smart list needs a cadence to surface anyone: with no rule template
        // every surface (card, queue, Home's Next up) returned nothing.
        assertEquals(RuleKind.KEEP_IN_TOUCH, recently.ruleKind)
    }

    // ============================================================================
    // Test 4 — the 4 named static templates all default to KEEP_IN_TOUCH.
    // ============================================================================

    @Test
    fun static_named_templates_use_keep_in_touch_kind() {
        val staticIds = setOf("inner_orbit", "family", "mentors", "drifted")
        val staticTemplates = TemplateChoice.Catalog.filter { it.id in staticIds }
        assertEquals(4, staticTemplates.size)
        staticTemplates.forEach { tpl ->
            assertEquals(ListType.STATIC, tpl.type, "${tpl.id} must be STATIC")
            assertEquals(
                RuleKind.KEEP_IN_TOUCH,
                tpl.ruleKind,
                "${tpl.id} must default to KEEP_IN_TOUCH"
            )
            assertNull(tpl.smartRule, "${tpl.id} must not carry a smart rule")
        }
    }

    @Test
    fun named_templates_carry_the_rhythm_their_subtitle_promises() {
        // Regression: all four created the same 2-day list, so "Mentors:
        // Quarterly check-ins" surfaced each mentor every 2 days.
        val byId = TemplateChoice.Catalog.associateBy { it.id }
        assertEquals(7, byId.getValue("inner_orbit").intervalDays)
        assertEquals(14, byId.getValue("family").intervalDays)
        assertEquals(60, byId.getValue("mentors").intervalDays)
        assertEquals(30, byId.getValue("drifted").intervalDays)
        assertEquals("Every couple of months.", context.getString(byId.getValue("mentors").subtitleRes))
        // Every interval must be reachable on the 1-60 day slider (ADR 0010).
        TemplateChoice.Catalog.mapNotNull { it.intervalDays }.forEach { assertTrue(it in 1..60) }
    }

    @Test
    fun vm_createList_writes_the_template_interval_as_the_override() = runTest {
        val listRepo = FakeListRepository()
        val ruleRepo =
            FakeRuleTemplateRepository(
                initial = listOf(ruleTemplateFixture(id = 1L, kind = RuleKind.KEEP_IN_TOUCH))
            )
        val noOpNudge = object : NudgeScheduler(
            context = ApplicationProvider.getApplicationContext<Context>(),
            listRepo = FakeListRepository()
        ) {
            override fun cancel(listId: Long) = Unit
            override suspend fun scheduleFromEntity(list: app.orbit.data.entity.ListEntity) = Unit
        }
        val vm =
            ListsManagerViewModel(
                listRepo = listRepo,
                ruleTemplateRepo = ruleRepo,
                nudgeScheduler = noOpNudge
            )

        vm.createList(TemplateChoice.Catalog.single { it.id == "mentors" }, "Mentors").join()

        val overrideJson = assertNotNull(listRepo.createCalls.single().ruleParamsOverrideJson)
        val params = JsonProvider.json.decodeFromString(
            app.orbit.domain.rule.RuleParams.serializer(),
            overrideJson
        )
        assertTrue(params is app.orbit.domain.rule.RuleParams.KeepInTouch)
        assertEquals(60 * 24, params.cooldownMinHours, "Mentors must surface every 60 days")
    }

    // ============================================================================
    // Test 5 — "Start from blank" has empty default name + KEEP_IN_TOUCH kind.
    // ============================================================================

    @Test
    fun blank_template_has_empty_default_name() {
        val blank = TemplateChoice.Catalog.single { it.id == "blank" }
        // No default name: the sheet leaves the field empty for the user to fill.
        assertNull(blank.defaultNameRes)
        assertEquals(RuleKind.KEEP_IN_TOUCH, blank.ruleKind)
        assertEquals(ListType.STATIC, blank.type)
        assertNull(blank.smartRule)
    }

    // ============================================================================
    // Test 6 — VM.createList wires SMART entry → ListEntity with the right JSON.
    // ============================================================================

    @Test
    fun vm_createList_with_recently_added_persists_smart_list() = runTest {
        val seedTemplate = ruleTemplateFixture(id = 1L, kind = RuleKind.KEEP_IN_TOUCH)
        val listRepo = FakeListRepository()
        val ruleRepo = FakeRuleTemplateRepository(initial = listOf(seedTemplate))
        // NudgeScheduler injection on the VM ctor; provide a
        // no-op subclass so WorkManager is never touched in this catalog test.
        val noOpNudge = object : NudgeScheduler(
            context = ApplicationProvider.getApplicationContext<Context>(),
            listRepo = FakeListRepository()
        ) {
            override fun cancel(listId: Long) = Unit
            override suspend fun scheduleFromEntity(list: app.orbit.data.entity.ListEntity) = Unit
        }
        val vm =
            ListsManagerViewModel(
                listRepo = listRepo,
                ruleTemplateRepo = ruleRepo,
                nudgeScheduler = noOpNudge
            )

        val recently = TemplateChoice.Catalog.single { it.id == "recently_added_not_called" }

        vm.createList(recently, "My recent contacts").join()

        val captured = listRepo.createCalls.single()
        assertEquals("My recent contacts", captured.name)
        assertEquals(ListType.SMART, captured.type)
        // SMART entries carry KEEP_IN_TOUCH like every other template, or nothing
        // ever surfaces from them.
        assertEquals(1L, captured.ruleTemplateId)
        val ruleJson = assertNotNull(captured.smartRuleJson, "SMART list must carry smartRuleJson")
        val decoded = JsonProvider.json.decodeFromString(SmartListRule.serializer(), ruleJson)
        assertTrue(decoded is SmartListRule.RecentlyAddedNotCalled)
        assertEquals(30, decoded.daysWindow)
    }
}
