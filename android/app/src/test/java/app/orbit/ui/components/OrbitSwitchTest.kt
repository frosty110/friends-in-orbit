package app.orbit.ui.components

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * DESIGN.md: OrbitSwitch is "announced as a switch". Standalone it is its own
 * `Role.Switch` with an on/off state; inside a toggleable row
 * (`onCheckedChange = null`) it only draws, and the row carries the state, so
 * TalkBack hears one control and not two (rules.md Code 7, one owner).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OrbitSwitchTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun standalone_it_is_a_switch_with_a_state_and_toggles_on_tap() {
        compose.setContent {
            OrbitTheme {
                var checked by androidx.compose.runtime.remember { mutableStateOf(false) }
                OrbitSwitch(checked = checked, onCheckedChange = { checked = it })
            }
        }

        compose.onNode(isToggleable())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()

        compose.onNode(isToggleable()).performClick()

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun inside_a_row_it_only_draws() {
        compose.setContent {
            OrbitTheme {
                OrbitSwitch(checked = true, onCheckedChange = null)
            }
        }

        // No toggleable node of its own: the row that wraps it owns the
        // gesture and the state for TalkBack.
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }
}
