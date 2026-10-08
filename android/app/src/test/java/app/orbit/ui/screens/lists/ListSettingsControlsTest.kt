package app.orbit.ui.screens.lists

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.rule.RuleParams
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What TalkBack hears from List settings' new controls, read from the
 * semantics tree on the JVM (the gallery's audit checks labels and 48dp, not
 * roles or placement):
 *  - LIST-25: a list's nudge timing is set in "When to nudge" alone (no Time
 *    of day group, in List settings or Make your first list), and the line
 *    under it says the days and times as they are.
 *  - LIST-26: the title is a "Rename list, {name}" button that becomes a
 *    field with labelled 48dp "Save list name" and "Cancel"; Save and the
 *    keyboard's Done save, a blank name keeps the old one, Cancel and Back
 *    leave without saving, and no Done is offered while the edit is open;
 *    under the curtain the title and field say "List".
 *  - LIST-27: Add people sits in the People header, once, and smart lists
 *    have none.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ListSettingsControlsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    // ─── LIST-26: rename from the title ──────────────────────────────────────

    private fun ready(name: String = "Inner orbit", type: ListType = ListType.STATIC) = ListConfigUiState.Ready(
        id = 1L,
        name = name,
        type = type,
        ruleKind = RuleKind.KEEP_IN_TOUCH,
        ruleParams = RuleParams.KeepInTouch(),
        smartRule = null,
        notificationsEnabled = true,
        nudgeSchedule = null,
        members = listOf(
            ListConfigContactSnapshot(1L, "Alex Rivera", null),
            ListConfigContactSnapshot(2L, "Sam Patel", null),
        ),
    )

    private fun setScreen(
        state: ListConfigUiState.Ready = ready(),
        curtain: Boolean = false,
        renamed: MutableList<String> = mutableListOf(),
        done: MutableList<String> = mutableListOf(),
    ) {
        compose.setContent {
            OrbitTheme {
                CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                    ListConfigContent(
                        state = state,
                        snackbarHostState = SnackbarHostState(),
                        onBack = {},
                        onDone = { done += "done" },
                        onRename = { renamed += it },
                        onIntervalChange = {},
                        onNotificationsToggle = {},
                        onNudgeScheduleChange = {},
                        onSmartRuleChange = {},
                        onConfirmConvert = {},
                        onRemoveMember = { _, _ -> },
                        onAddContacts = {},
                    )
                }
            }
        }
    }

    private val renameTitle = hasContentDescription("Rename list, Inner orbit")
    private val saveName = hasContentDescription("Save list name")
    private val cancel = hasContentDescription("Cancel")

    @Test
    fun the_title_is_a_rename_button_that_stays_the_heading_and_the_pane_title() {
        setScreen()

        compose.onNode(renameTitle).assert(button).assert(heading).assertHeightIsAtLeast(48.dp)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Inner orbit")).assertExists()
        // The Name section is gone: the title is the one place to rename.
        compose.onAllNodes(hasText("Name")).assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun tapping_the_title_opens_a_field_with_labelled_save_and_cancel() {
        setScreen()
        compose.onNode(renameTitle).performClick()

        compose.onNode(hasSetTextAction()).assert(editableTextIs("Inner orbit"))
        compose.onNode(saveName).assert(button).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNode(cancel).assert(button).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        // The bar holds the edit and nothing else.
        compose.onAllNodes(hasContentDescription("Back")).assertCountEquals(0)
        // Neither Done: the edit ends in Save or Cancel (the foot's goes too).
        compose.onAllNodes(hasText("Done") and button).assertCountEquals(0)
        // The pane title is still the screen's.
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Inner orbit")).assertExists()
    }

    @Test
    fun save_list_name_saves_the_trimmed_name_and_closes_the_field() {
        val renamed = mutableListOf<String>()
        setScreen(renamed = renamed)
        compose.onNode(renameTitle).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("  Close friends  ")
        compose.onNode(saveName).performClick()

        assertEquals(listOf("Close friends"), renamed)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun the_keyboards_done_saves_too() {
        val renamed = mutableListOf<String>()
        setScreen(renamed = renamed)
        compose.onNode(renameTitle).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Close friends")
        compose.onNode(hasSetTextAction()).performImeAction()

        assertEquals(listOf("Close friends"), renamed)
    }

    @Test
    fun a_blank_or_unchanged_name_keeps_the_old_one_and_writes_nothing() {
        val renamed = mutableListOf<String>()
        setScreen(renamed = renamed)
        compose.onNode(renameTitle).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("   ")
        compose.onNode(saveName).performClick()

        compose.onNode(renameTitle).performClick()
        compose.onNode(saveName).performClick()

        assertTrue(renamed.isEmpty(), "blank and unchanged names are not written")
        compose.onNode(renameTitle).assertExists()
    }

    @Test
    fun cancel_and_back_leave_the_edit_without_saving() {
        val renamed = mutableListOf<String>()
        setScreen(renamed = renamed)

        compose.onNode(renameTitle).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Close friends")
        compose.onNode(cancel).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        compose.onNode(renameTitle).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Close friends")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNode(renameTitle).assertExists()

        assertTrue(renamed.isEmpty())
    }

    @Test
    fun the_foot_of_form_done_is_gone_while_renaming_so_it_cannot_drop_the_typed_name() {
        // Until 2026-10-08 the foot's Done stayed during the edit: typing a
        // name and tapping it closed the screen and dropped the name unsaved.
        val renamed = mutableListOf<String>()
        val done = mutableListOf<String>()
        setScreen(renamed = renamed, done = done)
        compose.onAllNodes(hasText("Done") and button).assertCountEquals(2) // the bar's and the foot's

        compose.onNode(renameTitle).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Close friends")
        compose.onAllNodes(hasText("Done")).assertCountEquals(0)

        compose.onNode(cancel).performClick()
        compose.onAllNodes(hasText("Done") and button).assertCountEquals(2)
        assertTrue(renamed.isEmpty())
        assertTrue(done.isEmpty(), "nothing closed the screen")
    }

    @Test
    fun under_the_curtain_the_title_and_the_field_say_list() {
        setScreen(curtain = true)

        compose.onAllNodes(renameTitle).assertCountEquals(0)
        compose.onNode(hasContentDescription("Rename list") and button).assertExists()
        compose.onAllNodes(hasText("Inner orbit")).assertCountEquals(0)

        compose.onNode(hasContentDescription("Rename list")).performClick()
        compose.onNode(hasSetTextAction()).assert(editableTextIs("List"))
    }

    // ─── LIST-25: one place for a list's nudge timing ───────────────────────

    @Test
    fun list_settings_sets_nudge_timing_in_when_to_nudge_alone() {
        // The owner's review (2026-10-08): "Time of day" above Nudges and
        // "When to nudge" below it were two sections deciding one thing.
        setScreen()

        compose.onAllNodes(hasText("Time of day")).assertCountEquals(0)
        listOf("Any time", "Mornings", "Afternoons", "Evenings", "Nights").forEach {
            compose.onAllNodes(hasText(it)).assertCountEquals(0)
        }
        compose.onAllNodes(hasText("When to nudge")).assertCountEquals(1)
        compose.onNodeWithText("Every day at 10am").assertExists()
    }

    @Test
    fun when_to_nudge_says_the_days_and_times_as_they_are() {
        // Nothing narrows the times any more, so the line is the times.
        compose.setContent {
            OrbitTheme {
                NudgeScheduleSection(
                    schedule = app.orbit.notify.NudgeSchedule(
                        days = java.time.DayOfWeek.entries.toSet(),
                        times = listOf(LocalTime.of(18, 0), LocalTime.of(10, 0)),
                    ),
                    notificationsEnabled = true,
                    onScheduleChange = {},
                )
            }
        }

        compose.onNodeWithText("Every day at 10am and 6pm").assertExists()
        compose.onAllNodes(hasText("outside this list", substring = true)).assertCountEquals(0)
    }

    @Test
    fun make_your_first_list_has_no_time_of_day_and_says_the_nudge() {
        compose.setContent {
            OrbitTheme {
                Column {
                    ListConfigBody(
                        state = ready(),
                        isOnboarding = true,
                        snackbarHostState = SnackbarHostState(),
                        onIntervalChange = {},
                        onNotificationsToggle = {},
                        onNudgeScheduleChange = {},
                        onSmartRuleChange = {},
                        onConfirmConvert = {},
                    )
                }
            }
        }

        compose.onAllNodes(hasText("Time of day")).assertCountEquals(0)
        compose.onNodeWithText("Every day at 10am").assertExists()
    }

    // ─── LIST-27: Add people in the People header ────────────────────────────

    @Test
    fun add_people_sits_in_the_header_above_the_people_and_not_at_the_foot() {
        compose.setContent {
            OrbitTheme {
                MembersPreview(
                    members = listOf(
                        ListConfigContactSnapshot(1L, "Alex Rivera", null),
                        ListConfigContactSnapshot(2L, "Sam Patel", null),
                        ListConfigContactSnapshot(3L, "Jordan Lee", null),
                    ),
                    isSmart = false,
                )
            }
        }

        compose.onAllNodes(hasText("Add people")).assertCountEquals(1)
        val add = compose.onNode(hasText("Add people") and button)
            .assertHeightIsAtLeast(48.dp)
            .getBoundsInRoot()
        val count = compose.onNode(hasText("3 people")).getBoundsInRoot()
        val firstPerson = compose.onNodeWithText("Alex Rivera").getBoundsInRoot()
        val lastPerson = compose.onNodeWithText("Jordan Lee").getBoundsInRoot()

        assertTrue(add.bottom <= firstPerson.top, "Add people is above the first person, in the header")
        assertTrue(add.top < lastPerson.top, "and not under the last one")
        assertTrue(add.left >= count.right, "on the right of the count")
        assertTrue(add.top < count.bottom && count.top < add.bottom, "on the count's row")
    }

    @Test
    fun a_smart_list_has_no_add_people() {
        compose.setContent {
            OrbitTheme {
                MembersPreview(members = listOf(ListConfigContactSnapshot(1L, "Alex Rivera", null)), isSmart = true)
            }
        }
        compose.onAllNodes(hasText("Add people")).assertCountEquals(0)
    }

    private fun editableTextIs(expected: String) =
        SemanticsMatcher("editable text is \"$expected\"") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
        }
}
