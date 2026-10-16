package app.orbit.ui.screens.picker

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Add to lists picker's row (BULK-06), amended 2026-10-08: a list the
 * person is already on says "Already added" as a second line under its name,
 * where it sat in a small filled tag between the name and the mark. A tag on
 * a row is a badge (rules.md Design 9); the words stay, as a line.
 *
 * The row is still the checkbox and still cannot be picked: TalkBack hears
 * "Inner orbit, Already added, checkbox, disabled" from the one merged node,
 * names first, and a tap changes nothing (until 2026-10-06 such a list could
 * be ticked, and Undo removed the membership the person already had).
 * Robolectric, reading the merged semantics tree on the JVM.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ListPickerRowTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun a_list_the_person_is_on_says_already_added_under_its_name_and_cannot_be_picked() {
        var toggles = 0
        compose.setContent {
            OrbitTheme {
                ListPickerRow(name = "Inner orbit", isSelected = false, isMember = true, onToggle = { toggles++ })
            }
        }

        // One merged node: the name, then the second line, then the role.
        val row = compose.onNodeWithText("Inner orbit")
        row.assert(
            SemanticsMatcher("reads the name, then Already added") {
                it.config[SemanticsProperties.Text].map { text -> text.text } ==
                    listOf("Inner orbit", "Already added")
            },
        )
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        row.assertIsOff()
        row.assertIsNotEnabled()

        row.performClick()
        assertEquals(0, toggles, "a list the person is already on was picked")
        compose.onNodeWithText("Inner orbit").assertIsOff()
    }

    @Test
    fun a_list_the_person_is_not_on_has_no_second_line_and_can_be_picked() {
        var toggles = 0
        compose.setContent {
            OrbitTheme {
                ListPickerRow(name = "Late night", isSelected = false, isMember = false, onToggle = { toggles++ })
            }
        }

        compose.onAllNodesWithText("Already added", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Late night")
            .assertIsEnabled()
            .performClick()
        assertEquals(1, toggles)
    }
}
