package app.orbit.ui.screens.lists

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.orbit.domain.JsonProvider
import app.orbit.domain.smart.SmartListRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LIST-29: what New list's first step offers, [TemplateChoice.Catalog], in
 * the owner's order (vision/flows/owner-review-2026-10-07.md, decision 13):
 * "Start from blank" first, the three rhythm templates from most to least
 * often, then the list that fills itself; Mentors gone.
 *
 * Each template still makes the rhythm its subtitle names: the flow's How
 * often step starts at [TemplateChoice.startingIntervalHours] and Create
 * writes what it ends on (`NewListViewModelTest`, `CreateListUseCaseTest`).
 * Until 2026-10-05 every template made the same 2-day list.
 *
 * Robolectric for the English string resources the names and subtitles are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CreateListTemplateCatalogTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val byId = TemplateChoice.Catalog.associateBy { it.id }

    private fun name(id: String): String = context.getString(byId.getValue(id).displayNameRes)
    private fun subtitle(id: String): String = context.getString(byId.getValue(id).subtitleRes)

    @Test
    fun blank_first_then_the_rhythms_most_frequent_first_then_the_smart_list() {
        assertEquals(
            listOf("blank", "inner_orbit", "family", "drifted", "recently_added_not_called"),
            TemplateChoice.Catalog.map { it.id },
        )
        assertEquals(
            listOf(
                TemplateChoice.Group.Blank,
                TemplateChoice.Group.Rhythm,
                TemplateChoice.Group.Rhythm,
                TemplateChoice.Group.Rhythm,
                TemplateChoice.Group.Smart,
            ),
            TemplateChoice.Catalog.map { it.group },
        )
    }

    @Test
    fun the_rhythm_templates_sort_from_most_to_least_often() {
        // The tile tints follow this order (more often reads warmer), so the
        // catalogue's order and the intervals must agree.
        val intervals = TemplateChoice.Catalog.filter { it.group == TemplateChoice.Group.Rhythm }.map { it.intervalDays }
        assertEquals(listOf(7, 14, 30), intervals)
    }

    @Test
    fun mentors_is_gone() {
        // LIST-29: the How often slider reaches 60 days for anyone who wants
        // "every couple of months".
        assertTrue(TemplateChoice.Catalog.none { it.id == "mentors" })
        assertEquals(5, TemplateChoice.Catalog.size)
    }

    @Test
    fun names_and_subtitles_say_what_each_is() {
        assertEquals("Start from blank", name("blank"))
        assertEquals("Choose your own rhythm.", subtitle("blank"))
        assertEquals("Inner orbit", name("inner_orbit"))
        assertEquals("Closest people, about weekly.", subtitle("inner_orbit"))
        assertEquals("Family", name("family"))
        assertEquals("Steady, every couple of weeks.", subtitle("family"))
        assertEquals("Drifted", name("drifted"))
        assertEquals("Reconnect about once a month.", subtitle("drifted"))
        assertEquals("Recently added, not called", name("recently_added_not_called"))
        assertEquals("Auto-updates as you add people.", subtitle("recently_added_not_called"))
    }

    @Test
    fun each_template_starts_how_often_at_the_rhythm_its_subtitle_promises() {
        assertEquals(7 * 24, byId.getValue("inner_orbit").startingIntervalHours)
        assertEquals(14 * 24, byId.getValue("family").startingIntervalHours)
        assertEquals(30 * 24, byId.getValue("drifted").startingIntervalHours)
        // No rhythm of their own: Keep in touch's every 2 days.
        assertEquals(48, byId.getValue("blank").startingIntervalHours)
        assertEquals(48, byId.getValue("recently_added_not_called").startingIntervalHours)
        // Every interval is one the 1 to 60 day slider can show (ADR 0010).
        TemplateChoice.Catalog.mapNotNull { it.intervalDays }.forEach { assertTrue(it in 1..60) }
    }

    @Test
    fun the_smart_template_carries_recently_added_not_called_for_30_days() {
        val recently = byId.getValue("recently_added_not_called")
        val rule = assertNotNull(recently.smartRule, "the list that fills itself needs its rule")
        assertTrue(recently.isSmart)
        val decoded = JsonProvider.json.decodeFromString(
            SmartListRule.serializer(),
            JsonProvider.json.encodeToString(SmartListRule.serializer(), rule),
        )
        assertEquals(SmartListRule.RecentlyAddedNotCalled(daysWindow = 30), decoded)
    }

    @Test
    fun only_the_smart_template_fills_itself() {
        TemplateChoice.Catalog.filter { it.group != TemplateChoice.Group.Smart }.forEach {
            assertNull(it.smartRule, "${it.id} is a regular list")
            assertFalse(it.isSmart)
        }
    }

    @Test
    fun a_template_names_the_list_after_itself_and_blank_leaves_it_empty() {
        assertNull(byId.getValue("blank").defaultNameRes)
        TemplateChoice.Catalog.filter { it.id != "blank" }.forEach {
            assertEquals(it.displayNameRes, it.defaultNameRes, "${it.id} starts the list with its own name")
        }
    }
}
