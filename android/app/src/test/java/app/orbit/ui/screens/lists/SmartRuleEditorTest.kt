package app.orbit.ui.screens.lists

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.domain.smart.SmartListRule
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The smart rule's settings are chosen on the number wheel (ADR 0011): one
 * write per gesture, named by the sentence over it, and a stored value
 * outside the usual range shown as it is, not clamped.
 *
 * The Long gap rule's days were a typed field until 2026-10-07. Its tests
 * pinned that Done and a blur together committed once (they had committed
 * twice); the wheel keeps the same promise, and raises no keyboard at all.
 *
 * The editor is rendered with a rule that never changes, as it is between a
 * gesture and the write returning through Room.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class SmartRuleEditorTest {

    @get:Rule val compose = createComposeRule()

    private val changes = mutableListOf<SmartListRule>()
    private val res get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun editor(rule: SmartListRule) {
        compose.setContent {
            OrbitTheme {
                SmartRuleEditor(rule = rule, onChange = { changes += it })
            }
        }
    }

    @Test
    fun the_long_gap_wheel_commits_the_chosen_days_once() {
        editor(SmartListRule.LongGap(daysThreshold = 90))
        val sentence = res.getQuantityString(R.plurals.lists_smart_no_call_in, 90, 90)

        compose.onNodeWithContentDescription(sentence)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(45f) }
        compose.waitForIdle()

        assertEquals(listOf<SmartListRule>(SmartListRule.LongGap(daysThreshold = 45)), changes)
    }

    @Test
    fun the_long_gap_raises_no_keyboard() {
        editor(SmartListRule.LongGap(daysThreshold = 90))

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun the_wheel_is_named_by_the_sentence_and_says_its_value() {
        editor(SmartListRule.RecentlyAddedNotCalled(daysWindow = 30))
        val sentence = res.getQuantityString(R.plurals.lists_smart_added_within, 30, 30)

        compose.onNodeWithContentDescription(sentence).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                res.getQuantityString(R.plurals.lists_smart_days, 30, 30),
            ),
        )
    }

    @Test
    fun a_percentage_commits_once() {
        editor(SmartListRule.CommonlyCalled(topPercent = 20))
        val sentence = res.getString(R.string.lists_smart_top, 20)

        compose.onNodeWithContentDescription(sentence)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(35f) }
        compose.waitForIdle()

        assertEquals(listOf<SmartListRule>(SmartListRule.CommonlyCalled(topPercent = 35)), changes)
    }

    @Test
    fun a_stored_value_outside_the_usual_range_is_shown_as_it_is() {
        // 5 days is under the wheel's usual 7: it reads 5 and writes nothing,
        // rather than showing 7 and saving 7 on the first touch (ADR 0010).
        editor(SmartListRule.RecentlyAddedNotCalled(daysWindow = 5))
        val sentence = res.getQuantityString(R.plurals.lists_smart_added_within, 5, 5)

        compose.onNodeWithContentDescription(sentence).assertExists()
        compose.waitForIdle()
        assertEquals(emptyList(), changes)
    }

    @Test
    fun rangeIncluding_widens_only_to_take_in_the_value() {
        assertEquals(7..180, rangeIncluding(7..180, 30))
        assertEquals(5..180, rangeIncluding(7..180, 5))
        assertEquals(1..400, rangeIncluding(1..365, 400))
    }
}
