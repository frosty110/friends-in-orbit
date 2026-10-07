package app.orbit.ui.screens.lists

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The create sheet's name field: named for TalkBack by the heading over it,
 * and the keyboard's Done creates the list once a template and a name are
 * in, like the Create button; before that it only puts the keyboard away.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class CreateListContentTest {

    @get:Rule val compose = createComposeRule()

    private val created = mutableListOf<Pair<TemplateChoice, String>>()
    private val nameField = hasSetTextAction()

    private fun sheet() {
        compose.setContent {
            OrbitTheme { CreateListContent(onCreate = { t, n -> created += t to n }, onDismiss = {}) }
        }
    }

    @Test
    fun the_field_is_named_by_its_heading() {
        sheet()
        compose.onNode(nameField).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Name your list")),
        )
    }

    @Test
    fun done_creates_once_a_template_and_a_name_are_in() {
        sheet()
        compose.onNode(isSelectable() and hasText("Family", substring = true)).performClick()
        // Choosing a template fills in its name; the person replaces it.
        compose.onNode(nameField).performTextReplacement("Cousins")
        compose.onNode(nameField).performImeAction()

        compose.runOnIdle {
            assertEquals(listOf("Cousins"), created.map { it.second })
        }
    }

    @Test
    fun done_without_a_template_creates_nothing() {
        sheet()
        compose.onNode(nameField).performTextInput("Cousins")
        compose.onNode(nameField).performImeAction()

        compose.runOnIdle { assertEquals(emptyList(), created) }
    }
}
