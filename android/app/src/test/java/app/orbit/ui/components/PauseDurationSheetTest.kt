package app.orbit.ui.components

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.domain.model.PauseDuration
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The one pause chooser (menus-4): its title is a heading, its three options
 * are radio rows TalkBack counts ("1 of 3"), and the third option is worded
 * "Until you unpause", the same words as the status line and the snackbar.
 * The content composable is tested rather than the sheet, which needs a
 * window Robolectric does not give a ModalBottomSheet.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class PauseDurationSheetTest {

    @get:Rule val compose = createComposeRule()

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun the_title_is_a_heading_and_the_options_are_three_radio_rows() {
        compose.setContent { OrbitTheme { PauseDurationSheetContent(onSelect = {}) } }

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertTextEquals("Pause for how long?")
        compose.onAllNodes(radio).assertCountEquals(3)
        compose.onNodeWithText("Until you unpause").assertExists()
    }

    @Test
    fun each_row_reports_its_duration() {
        val chosen = mutableListOf<PauseDuration>()
        compose.setContent { OrbitTheme { PauseDurationSheetContent(onSelect = { chosen += it }) } }

        compose.onNodeWithText("1 week").performClick()
        compose.onNodeWithText("1 month").performClick()
        compose.onNodeWithText("Until you unpause").performClick()

        compose.runOnIdle {
            assertEquals(listOf(PauseDuration.OneWeek, PauseDuration.OneMonth, PauseDuration.Indefinite), chosen)
        }
    }
}
