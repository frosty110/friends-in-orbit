package app.orbit.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typed characters must reach the field and stay there.
 *
 * The contact picker shipped twice with a search box that ate keystrokes: the
 * field was bound to the ViewModel's debounced query, so a recomposition
 * mid-debounce re-applied the stale value and reverted the character. On a
 * screen that recomposes constantly off live Room flows the revert is instant,
 * so typing appears to do nothing at all.
 *
 * [OrbitSearchField] is a pure controlled component — it renders whatever
 * `query` it is handed and reports edits through `onQueryChange`. These tests
 * pin that contract from both ends: an edit is reported with the full new text,
 * and a re-render with the hoisted value displays it. A consumer that fails to
 * write the reported value straight back into the state it passes as `query` is
 * the bug; this class is the reference for what "wired correctly" looks like.
 *
 * The field is located by [hasSetTextAction] rather than by its placeholder —
 * the placeholder is a sibling [androidx.compose.material3.Text], not the
 * editable node.
 */
@RunWith(AndroidJUnit4::class)
class OrbitSearchFieldTest {

    @get:Rule val composeTestRule = createComposeRule()

    private companion object {
        const val PLACEHOLDER = "Search name or number"
    }

    /** The field as a consumer is supposed to wire it: hoisted, remembered. */
    private fun setControlledField(
        initial: String = "",
        onEdit: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            OrbitTheme {
                var query by remember { mutableStateOf(initial) }
                OrbitSearchField(
                    query = query,
                    onQueryChange = {
                        onEdit(it)
                        query = it
                    },
                    placeholder = PLACEHOLDER,
                )
            }
        }
    }

    @Test
    fun typed_text_is_displayed() {
        setControlledField()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("Sarah")

        composeTestRule.onNodeWithText("Sarah").assertIsDisplayed()
    }

    @Test
    fun placeholder_shows_only_while_the_query_is_empty() {
        setControlledField()

        composeTestRule.onNodeWithText(PLACEHOLDER).assertIsDisplayed()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("S")

        composeTestRule.onNodeWithText(PLACEHOLDER).assertDoesNotExist()
    }

    @Test
    fun edits_are_reported_through_onQueryChange() {
        val reported = mutableListOf<String>()
        setControlledField(onEdit = { reported += it })

        composeTestRule.onNode(hasSetTextAction()).performTextInput("ab")

        composeTestRule.runOnIdle {
            assertEquals("ab", reported.last())
        }
    }

    @Test
    fun a_field_bound_to_a_stale_value_never_shows_the_keystroke() {
        // The regression itself, pinned as a characterisation test: a consumer
        // that drops the reported edit — the shape of "bind the field to the
        // debounced VM query" — renders nothing the user typed. If this ever
        // starts passing text through, OrbitSearchField has grown internal
        // state and is no longer the controlled component consumers assume.
        composeTestRule.setContent {
            OrbitTheme {
                OrbitSearchField(
                    query = "",
                    onQueryChange = { /* dropped, as a stale binding would */ },
                    placeholder = PLACEHOLDER,
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("Sarah")

        composeTestRule.onNodeWithText("Sarah").assertDoesNotExist()
        composeTestRule.onNodeWithText(PLACEHOLDER).assertIsDisplayed()
    }

    @Test
    fun clear_affordance_reports_an_empty_query() {
        val reported = mutableListOf<String>()
        setControlledField(initial = "Sarah", onEdit = { reported += it })

        composeTestRule.onNodeWithContentDescription("Clear search").performClick()

        composeTestRule.runOnIdle {
            assertEquals("", reported.last())
        }
        composeTestRule.onNodeWithText(PLACEHOLDER).assertIsDisplayed()
    }
}
