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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
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
 *  - LIST-25: Time of day is one radio group with one selection, a custom
 *    window as one more selected chip, and taps that write only a change.
 *  - LIST-25: "When to nudge" says when the nudge really comes under the
 *    list's time of day ("Every day at 5pm" for Evenings on the default
 *    10am, with a note on 10am), in List settings and in Make your first
 *    list's summary.
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

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    // ─── LIST-25: Time of day ────────────────────────────────────────────────

    @Test
    fun time_of_day_is_one_radio_group_with_the_current_part_selected() {
        val picked = mutableListOf<DayPart>()
        compose.setContent {
            OrbitTheme { TimeOfDayPicker(selection = TimeOfDay.Part(DayPart.Mornings), onSelect = { picked += it }) }
        }

        compose.onNode(group).assertExists()
        compose.onAllNodes(isSelectable()).assertCountEquals(5)
        compose.onAllNodes(isSelectable() and radio).assertCountEquals(5)
        compose.onNode(isSelectable() and hasText("Mornings")).assertIsSelected()
        listOf("Any time", "Afternoons", "Evenings", "Nights").forEach {
            compose.onNode(isSelectable() and hasText(it)).assertIsNotSelected()
        }
        compose.onNodeWithText("Nudges for this list come only in the morning, from 7am to 12pm.").assertExists()

        compose.onNode(isSelectable() and hasText("Evenings")).performClick()
        compose.onNode(isSelectable() and hasText("Mornings")).performClick()
        assertEquals(listOf(DayPart.Evenings), picked, "the selected part writes nothing when tapped again")
    }

    @Test
    fun a_custom_window_is_one_more_selected_chip_and_tapping_it_writes_nothing() {
        val picked = mutableListOf<DayPart>()
        compose.setContent {
            OrbitTheme {
                TimeOfDayPicker(
                    selection = TimeOfDay.Custom(LocalTime.of(9, 0), LocalTime.of(17, 0)),
                    onSelect = { picked += it },
                )
            }
        }

        compose.onAllNodes(isSelectable() and radio).assertCountEquals(6)
        compose.onNode(isSelectable() and hasText("Custom: 9am to 5pm")).assertIsSelected()
        compose.onAllNodes(isSelectable() and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertCountEquals(1)
        compose.onNodeWithText("Nudges for this list come only from 9am to 5pm.").assertExists()

        compose.onNode(isSelectable() and hasText("Custom: 9am to 5pm")).performClick()
        assertTrue(picked.isEmpty(), "the custom window is never rewritten by a tap on itself")
    }

    @Test
    fun any_time_says_nudges_can_come_at_any_time() {
        compose.setContent {
            OrbitTheme { TimeOfDayPicker(selection = TimeOfDay.Part(DayPart.AnyTime), onSelect = {}) }
        }
        compose.onNode(isSelectable() and hasText("Any time")).assertIsSelected()
        compose.onNodeWithText("Nudges can come at any time of day.").assertExists()
    }

    // ─── LIST-26: rename from the title ──────────────────────────────────────

    private fun ready(name: String = "Inner orbit", type: ListType = ListType.STATIC) = ListConfigUiState.Ready(
        id = 1L,
        name = name,
        type = type,
        ruleKind = RuleKind.KEEP_IN_TOUCH,
        ruleParams = RuleParams.KeepInTouch(),
        smartRule = null,
        activeHoursStart = null,
        activeHoursEnd = null,
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
                        onTimeOfDayChange = {},
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

    // ─── LIST-25: When to nudge says when the nudge really comes ────────────

    private val evenings = ready().copy(activeHoursStart = LocalTime.of(17, 0), activeHoursEnd = LocalTime.of(21, 0))

    @Test
    fun under_evenings_the_plan_says_five_pm_and_why_ten_am_is_not_in_it() {
        // The default schedule (every day at 10am) with Evenings: the
        // scheduler adds 5pm and the gate holds 10am, so the nudge comes at
        // 5pm. Until 2026-10-08 the line read the stored "Every day at 10am".
        setScreen(state = evenings)

        compose.onNodeWithText("Every day at 5pm").assertExists()
        compose.onNodeWithText("10am is outside this list's time of day, so the nudge comes at 5pm instead.")
            .assertExists()
        compose.onAllNodes(hasText("Every day at 10am")).assertCountEquals(0)
        // The chosen time is still the one to change.
        compose.onNodeWithText("10am").assertExists()
    }

    @Test
    fun a_time_outside_is_named_while_one_inside_still_comes() {
        compose.setContent {
            OrbitTheme {
                NudgeScheduleSection(
                    schedule = app.orbit.notify.NudgeSchedule(
                        days = java.time.DayOfWeek.entries.toSet(),
                        times = listOf(LocalTime.of(10, 0), LocalTime.of(18, 0)),
                    ),
                    activeHoursStart = DayPart.Evenings.start,
                    activeHoursEnd = DayPart.Evenings.end,
                    notificationsEnabled = true,
                    onScheduleChange = {},
                )
            }
        }

        compose.onNodeWithText("Every day at 6pm").assertExists()
        compose.onNodeWithText("10am is outside this list's time of day, so no nudge comes then.").assertExists()
    }

    @Test
    fun any_time_says_the_schedule_as_it_is_with_no_note() {
        setScreen()

        compose.onNodeWithText("Every day at 10am").assertExists()
        compose.onAllNodes(hasText("outside this list's time of day", substring = true)).assertCountEquals(0)
    }

    @Test
    fun make_your_first_lists_summary_says_five_pm_under_evenings_too() {
        compose.setContent {
            OrbitTheme {
                Column {
                    ListConfigBody(
                        state = evenings,
                        isOnboarding = true,
                        snackbarHostState = SnackbarHostState(),
                        onIntervalChange = {},
                        onTimeOfDayChange = {},
                        onNotificationsToggle = {},
                        onNudgeScheduleChange = {},
                        onSmartRuleChange = {},
                        onConfirmConvert = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Every day at 5pm").assertExists()
        compose.onAllNodes(hasText("Every day at 10am")).assertCountEquals(0)
        // Onboarding shows no nudge times, so there is no 10am to explain.
        compose.onAllNodes(hasText("outside this list's time of day", substring = true)).assertCountEquals(0)
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
