package app.orbit.ui.screens.picker

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.ui.theme.OrbitTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * PICK-08, amended 2026-10-08: the control beside Sort that shows the people
 * you ignore is an eye icon alone (the owner: "Let's just have a toggle icon
 * for ignored vs not. We don't require accompanying text."). Until then it
 * was a "Show ignored" / "Hide ignored" text pill with a button role, so
 * TalkBack heard an action and never the state.
 *
 * The toggle drives the real filter here: the names listed are
 * [ContactPickerUiState.filteredContacts] for the toggle's value, the list
 * the screen draws, so a tap that did not reach `showIgnored` would leave
 * the ignored person out. Runs on the JVM under Robolectric: it reads the
 * semantics tree and fires click actions (development-cycle.md, Verify).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ShowIgnoredToggleTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val label: String get() = context.getString(R.string.picker_show_ignored_toggle)

    private fun person(id: Long, name: String, ignored: Boolean) = PickerContact(
        contactId = id,
        displayName = name,
        phone = "+1555000$id",
        photoUri = null,
        isIgnored = ignored,
        callCount = 0,
        lastCallAt = null,
        firstSeenByAppAt = Instant.parse("2026-01-01T00:00:00Z"),
        listIds = emptySet(),
        listNames = emptyList(),
        isCommonlyCalled = false,
        isRarelyCalled = false,
        isRecentlyAdded = false,
        isLongGap = false,
    )

    private val people = listOf(
        person(1L, "Sarah Levin", ignored = false),
        person(2L, "Marcus Reid", ignored = true),
    )

    private fun setPicker() {
        compose.setContent {
            // The screen's one writer for the value is the ViewModel
            // (onShowIgnoredToggle); here it is this state, read back the
            // same way through the UiState.
            var showIgnored by remember { mutableStateOf(false) }
            val state = ContactPickerUiState(
                phase = ContactPickerUiState.Phase.Ready,
                mode = PickerMode.Add,
                targetListName = "Inner orbit",
                searchQuery = "",
                activeFilters = emptySet(),
                showIgnored = showIgnored,
                allContacts = people,
                selectedIds = emptySet(),
            )
            OrbitTheme {
                Column {
                    ShowIgnoredToggle(showIgnored = state.showIgnored, onToggle = { showIgnored = it })
                    state.filteredContacts.forEach { Text(it.displayName) }
                }
            }
        }
    }

    @Test
    fun the_toggle_is_an_icon_only_switch_named_for_what_it_shows() {
        setPicker()

        compose.onNodeWithContentDescription(label)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        // No words beside the icon: the pill's labels are gone.
        compose.onAllNodesWithText("Show ignored", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Hide ignored", substring = true).assertCountEquals(0)
    }

    @Test
    fun a_tap_shows_the_ignored_people_and_says_on_and_another_hides_them() {
        setPicker()
        compose.onNodeWithText("Sarah Levin").assertExists()
        compose.onNodeWithText("Marcus Reid").assertDoesNotExist()

        compose.onNodeWithContentDescription(label).performClick()

        compose.onNodeWithContentDescription(label).assertIsOn()
        compose.onNodeWithText("Marcus Reid").assertExists()

        compose.onNodeWithContentDescription(label).performClick()

        compose.onNodeWithContentDescription(label).assertIsOff()
        compose.onNodeWithText("Marcus Reid").assertDoesNotExist()
    }
}
