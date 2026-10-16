package app.orbit.ui.screens.browse

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.data.mappers.toUiContact
import app.orbit.domain.contactFixture
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * BROWSE-03, amended 2026-10-08: a result says the lists its person is on in
 * one line of words, the Add people picker's ("On Inner orbit, Late night"),
 * where it showed a row of Stone chips, one per list. A read-only chip is a
 * badge, and rules.md Design 9 says the fact in words on a line the row
 * already has. A chip drew its list's name as a text node of its own, so "no
 * node reads exactly a list's name" is the check that no chip is left.
 *
 * Under the privacy curtain the line says only how many ("On 2 lists"); the
 * chips said "List" once per list (PRIV-03). With no lists the result keeps
 * "Not on any list".
 *
 * Over the stateless content with no ViewModel (THEME-04), on the JVM under
 * Robolectric, as ContactDetailScreenTest does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class GlobalSearchContentTest {

    @get:Rule val compose = createComposeRule()

    private fun hit(id: Long, name: String, lists: List<String>) = SearchHit(
        contact = contactFixture(id = id, displayName = name).toUiContact(),
        lists = lists,
    )

    private fun show(vararg hits: SearchHit, curtain: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    GlobalSearchContent(
                        state = SearchUiState.Ready(results = hits.toList(), query = "maya"),
                        initialQuery = "maya",
                        onSearchChanged = {},
                        onRetry = {},
                        onBack = {},
                        onOpenContact = {},
                        onAddToLists = {},
                        onOpenSettings = {},
                        autoFocus = false,
                    )
                }
            }
        }
    }

    @Test
    fun a_result_names_its_lists_in_one_line_and_draws_no_chip() {
        show(hit(1L, "Maya Ahmed", listOf("Inner orbit", "Late night")))

        compose.onNodeWithText("On Inner orbit, Late night", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Inner orbit", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("Late night", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Add to lists", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun under_the_curtain_the_line_says_how_many_and_names_none() {
        show(hit(1L, "Maya Ahmed", listOf("Inner orbit", "Late night")), curtain = true)

        compose.onNodeWithText("On 2 lists", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Inner orbit", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("Late night", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        // The chips' curtain word, once per list, is gone with them.
        compose.onAllNodesWithText("List", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun a_result_on_no_list_still_says_so() {
        show(hit(2L, "Maya Brooks", emptyList()))

        compose.onNodeWithText("Not on any list", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("On ", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }
}
