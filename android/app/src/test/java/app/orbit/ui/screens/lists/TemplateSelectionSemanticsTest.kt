package app.orbit.ui.screens.lists

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.orbit.ui.screens.lists.newlist.StartWithStep
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The template tiles on New list's first step (LIST-29; the create sheet's
 * until 2026-10-07) are one choice, so TalkBack must hear a radio button with
 * its selected state (WCAG 4.1.2) and not "Family, button". Until 2026-10-06
 * they were plain clickables whose only selection cue was drawn: a tint.
 * (This test also covered the rhythm rows, Keep in touch, Late night and
 * Energize, until LIST-30 removed the last of them on 2026-10-07.)
 * The gallery's a11y audit checks labels and 48dp, not roles, so this test
 * reads the semantics tree directly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class TemplateSelectionSemanticsTest {

    @get:Rule val compose = createComposeRule()

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)

    @Test
    fun template_tiles_are_one_radio_group_and_say_which_is_picked() {
        var picked by mutableStateOf<TemplateChoice?>(null)
        var pickedName: String? = null
        compose.setContent {
            OrbitTheme {
                StartWithStep(
                    selected = picked,
                    onSelect = { template, defaultName ->
                        picked = template
                        pickedName = defaultName
                    },
                )
            }
        }

        // All five, the smart one under its label included, are one group.
        compose.onNode(group).assertExists()
        compose.onAllNodes(isSelectable()).assertCountEquals(TemplateChoice.Catalog.size)
        compose.onNode(isSelectable() and hasText("Family")).assert(radio).assertIsNotSelected()
        compose.onNode(isSelectable() and hasText("Recently added, not called")).assert(radio)

        compose.onNode(isSelectable() and hasText("Family")).performClick()

        compose.onNode(isSelectable() and hasText("Family")).assertIsSelected()
        compose.onNode(isSelectable() and hasText("Inner orbit")).assertIsNotSelected()
        // The tile hands over its name, the new list's starting name.
        compose.runOnIdle { kotlin.test.assertEquals("Family", pickedName) }

        compose.onNode(isSelectable() and hasText("Start from blank")).performClick()
        compose.onNode(isSelectable() and hasText("Start from blank")).assertIsSelected()
        compose.runOnIdle { kotlin.test.assertEquals("", pickedName, "blank starts with no name") }
    }
}
