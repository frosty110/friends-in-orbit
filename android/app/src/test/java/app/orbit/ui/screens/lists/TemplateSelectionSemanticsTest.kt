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
import app.orbit.data.entity.RuleKind
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The rhythm rows on List settings and the template tiles on the create sheet
 * are one choice each, so TalkBack must hear a radio button with its selected
 * state (WCAG 4.1.2) and not "Keep in touch, button". Until 2026-10-06 both
 * were plain clickables whose only selection cue was drawn: a dot, a tint.
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
    fun rhythm_rows_are_one_radio_group_with_the_current_one_selected() {
        compose.setContent {
            OrbitTheme {
                RuleTemplatePicker(
                    currentKind = RuleKind.LATE_NIGHT,
                    templates = emptyList(),
                    onSelect = {},
                )
            }
        }

        compose.onNode(group).assertExists()
        compose.onAllNodes(isSelectable()).assertCountEquals(3)
        compose.onNode(isSelectable() and hasText("Late night")).assert(radio).assertIsSelected()
        compose.onNode(isSelectable() and hasText("Keep in touch"))
            .assert(radio)
            .assertIsNotSelected()
        compose.onNode(isSelectable() and hasText("Energize")).assert(radio).assertIsNotSelected()
    }

    @Test
    fun template_tiles_are_one_radio_group_and_say_which_is_picked() {
        compose.setContent {
            OrbitTheme {
                CreateListContent(onCreate = { _, _ -> }, onDismiss = {})
            }
        }

        compose.onNode(group).assertExists()
        compose.onAllNodes(isSelectable()).assertCountEquals(TemplateChoice.Catalog.size)
        compose.onNode(isSelectable() and hasText("Family")).assert(radio).assertIsNotSelected()

        compose.onNode(isSelectable() and hasText("Family")).performClick()

        compose.onNode(isSelectable() and hasText("Family")).assertIsSelected()
        compose.onNode(isSelectable() and hasText("Inner orbit")).assertIsNotSelected()
    }
}
