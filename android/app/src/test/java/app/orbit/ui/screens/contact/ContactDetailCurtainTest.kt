package app.orbit.ui.screens.contact

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * PRIV-03: under the privacy curtain, Contact detail says the person's name
 * nowhere, including to TalkBack.
 *
 * The overflow button's label read "More actions for Avery Quinn" through the
 * curtain: the screen masked every visible name but built that label from the
 * raw contact. A label is as public as text (it is spoken aloud, and read by
 * anything with accessibility access), so this checks every node of the
 * unmerged semantics tree, text and content descriptions alike, for the first
 * name or the full name.
 *
 * PRIV-07: the number reads "Number hidden" and does not dial. The row used
 * to keep a disabled clickable under the curtain, which TalkBack still
 * announced as a button, so this asserts the node carries no click action
 * at all. The pane title (what TalkBack announces on arrival) is masked
 * like the hero.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ContactDetailCurtainTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun the_name_reaches_no_text_and_no_label() {
        compose.setContent { ContactDetailCurtainContent() }

        val first = CURTAIN_FIXTURE_NAME.substringBefore(' ')
        val leaks = compose.onAllNodes(
            SemanticsMatcher("any node") { true },
            useUnmergedTree = true
        )
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }
            .filter { it.contains(first) }

        assertTrue(leaks.isEmpty(), "Under the curtain, these still say the name: $leaks")
    }

    @Test
    fun the_number_reads_hidden_and_does_not_dial() {
        compose.setContent { ContactDetailCurtainContent() }

        // Merged tree on purpose: a clickable row would fold the text into a
        // node that has OnClick (enabled or not), and that is the regression.
        compose.onNodeWithText("Number hidden")
            .assertExists()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun the_pane_title_is_the_curtain_word() {
        compose.setContent { ContactDetailCurtainContent() }

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Contact"),
            useUnmergedTree = true
        ).assertExists()
    }
}
