package app.orbit.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.data.Contact
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Who owns a [BrowseRow] tap.
 *
 * Regression: Browse wraps each row in a `combinedClickable` (tap opens the
 * contact, long-press opens quick actions, multi-select toggles), but the row
 * also put `.clickable(onTap)` on its own root. The inner clickable consumed
 * the press, so the wrapper never fired: rows ignored taps and long-presses,
 * and multi-select was unreachable.
 *
 * The third case is Browse's multi-select shape: the same wrapper with
 * `Role.Checkbox` and a `toggleableState`, so a tap toggles and TalkBack hears
 * "Ada Lovelace, checkbox, checked" (features/browse/README.md acceptance).
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
class BrowseRowGestureTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val ada = Contact(
        id = "c-1",
        name = "Ada Lovelace",
        phone = "+1 555 0100",
        lastCalledLabel = null,
        avgLengthLabel = null,
        pickupRateLabel = "",
        totalCalls = 0,
        due = false,
        listIds = emptyList(),
        bestWindowLabel = null,
        heat = FloatArray(24),
        history = emptyList(),
        notes = emptyList(),
        patternNote = "",
    )

    @Test
    fun a_parent_that_owns_the_gesture_receives_tap_and_long_press() {
        var opened = 0
        var quickActions = 0
        composeTestRule.setContent {
            OrbitTheme {
                Row(Modifier.combinedClickable(onClick = { opened++ }, onLongClick = { quickActions++ })) {
                    BrowseRow(contact = ada, onTap = null, onDial = {}, modifier = Modifier.weight(1f))
                }
            }
        }

        composeTestRule.onNodeWithText("Ada Lovelace").performClick()
        composeTestRule.onNodeWithText("Ada Lovelace").performTouchInput { longClick() }

        assertEquals(1, opened, "tap reaches the Browse wrapper")
        assertEquals(1, quickActions, "long-press reaches the Browse wrapper")
    }

    @Test
    fun a_row_with_its_own_onTap_still_handles_the_tap() {
        // Global Search's shape: no wrapper, the row opens the contact itself.
        var opened = 0
        composeTestRule.setContent {
            OrbitTheme {
                BrowseRow(contact = ada, onTap = { opened++ }, onDial = {})
            }
        }

        composeTestRule.onNodeWithText("Ada Lovelace").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun in_multi_select_the_row_is_a_checkbox_that_a_tap_toggles() {
        // Browse's multi-select wrapper: Role.Checkbox, a toggleableState, no
        // long-press, the dial hidden. The merged node is the row, so the text
        // finder lands on the checkbox TalkBack describes.
        var checked by mutableStateOf(false)
        composeTestRule.setContent {
            OrbitTheme {
                Row(
                    Modifier
                        .combinedClickable(role = Role.Checkbox, onClick = { checked = !checked })
                        .semantics { toggleableState = ToggleableState(checked) },
                ) {
                    BrowseRow(contact = ada, onTap = null, onDial = {}, showDial = false, modifier = Modifier.weight(1f))
                }
            }
        }

        val row = composeTestRule.onNodeWithText("Ada Lovelace")
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        row.assertIsOff()

        row.performClick()

        assertEquals(true, checked, "tap toggles the selection")
        composeTestRule.onNodeWithText("Ada Lovelace").assertIsOn()
    }
}
