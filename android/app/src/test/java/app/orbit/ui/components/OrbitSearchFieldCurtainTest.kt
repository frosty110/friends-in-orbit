package app.orbit.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * PRIV-03 on the search pill (browse-8). A typed query is a name as often as
 * not, and it stayed visible under the curtain while the rows beneath it read
 * "Contact". The field now draws "Contact" over a non-empty query and leaves
 * the buffer alone, so the consumer's query and results survive the curtain.
 *
 * The JVM twin of the curtain case in androidTest's OrbitSearchFieldTest, so
 * it gates every push (development-cycle.md, Verify: a check that only reads
 * the semantics tree may run under Robolectric).
 *
 * The checks read what is drawn and spoken: `EditableText`, `Text` and
 * content descriptions, the same properties the gallery's curtain audit
 * reads. The field's `InputText` is its buffer, exposed for autofill, and
 * is the real query on purpose (see CurtainMaskTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrbitSearchFieldCurtainTest {

    @get:Rule val compose = createComposeRule()

    private fun setField(curtain: Boolean, query: String) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    OrbitSearchField(query = query, onQueryChange = {}, placeholder = "Search people")
                }
            }
        }
    }

    @Test
    fun under_the_curtain_a_query_reads_as_Contact() {
        setField(curtain = true, query = "Maya")

        compose.onNode(hasSetTextAction()).assert(editableTextIs("Contact"))
        assertTrue(spokenOrShown().none { it.contains("Maya") }, "the query still reaches text or a label")
    }

    @Test
    fun under_the_curtain_an_empty_field_still_shows_its_placeholder() {
        setField(curtain = true, query = "")

        compose.onNodeWithText("Search people").assertExists()
        compose.onNode(hasSetTextAction()).assert(editableTextIs(""))
    }

    @Test
    fun with_the_curtain_up_the_query_is_visible() {
        setField(curtain = false, query = "Maya")

        compose.onNode(hasSetTextAction()).assert(editableTextIs("Maya"))
    }

    private fun editableTextIs(expected: String) = SemanticsMatcher("editable text is \"$expected\"") { node ->
        node.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
    }

    /** Every string a person sees or TalkBack says, across the whole tree. */
    private fun spokenOrShown(): List<String> =
        compose.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                val c = node.config
                c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    listOfNotNull(c.getOrNull(SemanticsProperties.EditableText)?.text)
            }
}
