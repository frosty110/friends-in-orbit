package app.orbit.ui.screens.lists.newlist

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.screens.lists.TemplateChoice
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * LIST-28: what New list's screen does with its state, on the JVM (it reads
 * the semantics tree and fires clicks; development-cycle.md, Verify). Back
 * and the close control: leaving with something entered asks "Discard this
 * list?" with "Keep going" and "Discard", leaving with nothing entered just
 * leaves, and Back past the first step steps back. Each step's button and
 * its progress, including the list that fills itself ending at How often
 * with "Create list", and the People step's two ways on with nobody chosen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class NewListContentTest {

    @get:Rule val compose = createComposeRule()

    private val innerOrbit = TemplateChoice.Catalog.first { it.id == "inner_orbit" }
    private val smart = TemplateChoice.Catalog.first { it.isSmart }

    private val fired = mutableListOf<String>()
    private var state by mutableStateOf(NewListUiState())

    private fun show(initial: NewListUiState) {
        state = initial
        compose.setContent {
            OrbitTheme {
                NewListContent(
                    state = state,
                    onSelectTemplate = { template, name -> fired += "select:${template.id}:$name" },
                    onNameChange = { fired += "name:$it" },
                    onNext = { fired += "next" },
                    onPreviousStep = { fired += "previous" },
                    onIntervalCommit = { fired += "interval:$it" },
                    onAddPeople = { fired += "add people" },
                    onRemovePerson = { fired += "remove:$it" },
                    onCreate = { fired += "create" },
                    onLeave = { fired += "leave" },
                    autoFocus = false,
                )
            }
        }
    }

    private val back get() = compose.onNodeWithContentDescription("Back")

    // ── Leaving ────────────────────────────────────────────────────────────

    @Test
    fun back_on_the_first_step_with_nothing_entered_just_leaves() {
        show(NewListUiState())

        back.performClick()

        compose.onNodeWithText("Discard this list?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("leave"), fired) }
    }

    @Test
    fun back_on_the_first_step_with_a_template_picked_asks_and_keep_going_stays() {
        show(NewListUiState(template = innerOrbit, name = "Inner orbit"))

        back.performClick()
        compose.onNodeWithText("Discard this list?").assertIsDisplayed()
        compose.onNodeWithText("Keep going").performClick()

        compose.onNodeWithText("Discard this list?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), fired) }
    }

    @Test
    fun discard_leaves() {
        show(NewListUiState(template = innerOrbit, name = "Inner orbit"))

        back.performClick()
        compose.onNodeWithText("Discard").performClick()

        compose.runOnIdle { assertEquals(listOf("leave"), fired) }
    }

    @Test
    fun back_after_the_first_step_steps_back_and_the_close_control_asks() {
        show(NewListUiState(step = NewListStep.HowOften, template = innerOrbit, name = "Inner orbit"))

        back.performClick()
        compose.runOnIdle { assertEquals(listOf("previous"), fired) }

        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Discard this list?").assertIsDisplayed()
    }

    @Test
    fun the_first_step_has_no_second_way_out() {
        show(NewListUiState())

        // The arrow already leaves; a close control beside it would be the
        // same job twice.
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
    }

    // ── Each step's button and progress ───────────────────────────────────

    @Test
    fun the_first_step_asks_how_to_start_and_next_waits_for_a_template() {
        show(NewListUiState())

        compose.onNode(isHeading() and hasText("How do you want to start?")).assertExists()
        compose.onNodeWithText("Step 1 of 4").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()

        compose.onNodeWithText("Family").performClick()
        compose.runOnIdle { assertEquals(listOf("select:family:Family"), fired) }
    }

    @Test
    fun a_blank_name_disables_next_and_a_name_enables_it() {
        show(NewListUiState(step = NewListStep.Name, template = TemplateChoice.Catalog.first()))
        compose.onNode(isHeading() and hasText("Name your list")).assertExists()
        compose.onNodeWithText("Step 2 of 4").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()

        compose.runOnIdle { state = state.copy(name = "Night owls") }

        compose.onNodeWithText("Next").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("next"), fired) }
    }

    @Test
    fun the_name_field_starts_from_the_templates_name() {
        show(NewListUiState(step = NewListStep.Name, template = innerOrbit, name = "Inner orbit"))

        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("Inner orbit")))
            .assertExists()
    }

    @Test
    fun the_list_that_fills_itself_ends_at_how_often_with_create_list() {
        show(NewListUiState(step = NewListStep.HowOften, template = smart, name = "New faces"))

        compose.onNode(isHeading() and hasText("How often")).assertExists()
        compose.onNodeWithText("Step 3 of 3").assertExists()
        compose.onNodeWithText("Next").assertDoesNotExist()
        compose.onNodeWithText("Create list").performClick()

        compose.runOnIdle { assertEquals(listOf("create"), fired) }
    }

    @Test
    fun a_regular_list_goes_on_from_how_often() {
        show(NewListUiState(step = NewListStep.HowOften, template = innerOrbit, name = "Inner orbit"))

        compose.onNodeWithText("Step 3 of 4").assertExists()
        compose.onNodeWithText("Next").performClick()

        compose.runOnIdle { assertEquals(listOf("next"), fired) }
    }

    @Test
    fun the_people_step_with_nobody_chosen_offers_add_people_or_create_without() {
        show(NewListUiState(step = NewListStep.People, template = innerOrbit, name = "Inner orbit"))

        compose.onNodeWithText("Step 4 of 4").assertExists()
        compose.onNodeWithText("Create list").assertDoesNotExist()
        // The heading says "Add people" too; the button is the one that acts.
        compose.onNode(hasText("Add people") and hasClickAction()).performClick()
        compose.onNodeWithText("Create without people").performClick()

        compose.runOnIdle { assertEquals(listOf("add people", "create"), fired) }
    }

    @Test
    fun the_people_step_with_people_chosen_lists_them_and_creates() {
        show(
            NewListUiState(step = NewListStep.People, template = innerOrbit, name = "Inner orbit", people = previewPeople),
        )

        compose.onNodeWithText("3 people").assertExists()
        compose.onNodeWithText("Create without people").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove Maya Okafor from list").performClick()
        compose.onNodeWithText("Create list").performClick()

        compose.runOnIdle { assertEquals(listOf("remove:2", "create"), fired) }
    }

    @Test
    fun nothing_is_pressable_while_create_is_in_flight() {
        show(
            NewListUiState(
                step = NewListStep.People,
                template = innerOrbit,
                name = "Inner orbit",
                people = previewPeople,
                creating = true,
            ),
        )

        compose.onNodeWithText("Create list").assertIsNotEnabled()
    }
}
