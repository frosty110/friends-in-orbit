package app.orbit.ui.screens.lists

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A smart list's People section says why no one can be removed there and how
 * to keep someone off (Ignore); a regular list, where removing is a tap away,
 * does not.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class MembersPreviewTest {

    @get:Rule val compose = createComposeRule()

    private val hint = "Orbit fills this list from its rule. To keep someone off it, ignore them."
    private val people = listOf(ListConfigContactSnapshot(id = 1L, displayName = "Contact 1", photoUri = null))

    @Test
    fun a_smart_list_says_why_and_how() {
        compose.setContent { OrbitTheme { MembersPreview(members = people, isSmart = true) } }
        compose.onNodeWithText(hint).assertExists()
    }

    @Test
    fun a_regular_list_does_not() {
        compose.setContent { OrbitTheme { MembersPreview(members = people, isSmart = false) } }
        compose.onNodeWithText(hint).assertDoesNotExist()
    }
}
