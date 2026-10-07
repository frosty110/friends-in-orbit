package app.orbit.ui.components

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * DESIGN.md: OrbitAppBar's "title is a heading and the screen's pane title,
 * so TalkBack announces each new screen". In one activity there is no window
 * change to announce a screen otherwise (rubric D8), and the heading lets a
 * TalkBack user jump to where they are. A blank title (a screen that draws
 * its own) sets no pane title, so nothing empty is announced.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrbitAppBarTest {

    @get:Rule val compose = createComposeRule()

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private val paneTitle = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)

    @Test
    fun the_title_is_a_heading_and_the_pane_title() {
        compose.setContent { OrbitTheme { OrbitAppBar(title = "Settings") } }

        compose.onNode(heading).assertTextEquals("Settings")
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Settings")).assertExists()
    }

    @Test
    fun a_control_in_the_titles_place_keeps_the_title_as_the_pane_title() {
        // List settings' rename (LIST-26): the slot shows a control, and
        // TalkBack still announces the screen by its name.
        compose.setContent {
            OrbitTheme {
                OrbitAppBar(title = "Inner orbit", titleContent = { Text("Rename me") })
            }
        }

        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Inner orbit")).assertExists()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("Inner orbit"))))
            .assertCountEquals(0)
    }

    @Test
    fun a_blank_title_sets_no_pane_title() {
        compose.setContent { OrbitTheme { OrbitAppBar(title = "") } }

        compose.onAllNodes(paneTitle).assertCountEquals(0)
    }
}
