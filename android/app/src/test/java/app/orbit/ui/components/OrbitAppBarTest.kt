package app.orbit.ui.components

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
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
    fun a_blank_title_sets_no_pane_title() {
        compose.setContent { OrbitTheme { OrbitAppBar(title = "") } }

        compose.onAllNodes(paneTitle).assertCountEquals(0)
    }
}
