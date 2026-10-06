package app.orbit.ui.screens.lists

import android.app.Application
import android.content.Context
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
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
 * The Long gap rule's day field (SmartRuleEditor's `DaysNumberInput`) commits
 * once per gesture and tells TalkBack what it sets.
 *
 * Until 2026-10-06 IME Done committed, then cleared focus, and the focus-loss
 * branch committed again: two identical writes per Done and, on a failing
 * write, two "Couldn't save your change" snackbars. The `parsed != value`
 * guard could not stop it because the saved value only changes after the
 * write round-trips through Room, which is why the editor here is rendered
 * with a rule that never changes, as it is between a tap and the write. The
 * field also had no name of its own (rules.md Design 7): the sentence above
 * it is a sibling node, so TalkBack said only "90, edit box".
 *
 * Reads the semantics tree under Robolectric, the TemplateSelectionSemanticsTest
 * precedent; the IME action and focus here are semantics actions, not real
 * windows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class SmartRuleEditorTest {

    @get:Rule val compose = createComposeRule()

    private val field = hasSetTextAction()
    private val changes = mutableListOf<SmartListRule>()
    private lateinit var focusManager: FocusManager

    private fun setLongGapEditor(days: Int) {
        compose.setContent {
            OrbitTheme {
                focusManager = LocalFocusManager.current
                SmartRuleEditor(
                    rule = SmartListRule.LongGap(daysThreshold = days),
                    onChange = { changes += it },
                )
            }
        }
    }

    @Test
    fun done_commits_the_typed_days_once() {
        setLongGapEditor(days = 90)

        compose.onNode(field).requestFocus()
        compose.onNode(field).performTextReplacement("45")
        compose.onNode(field).performImeAction()

        compose.runOnIdle {
            assertEquals(
                listOf<SmartListRule>(SmartListRule.LongGap(daysThreshold = 45)),
                changes,
                "Done commits exactly once, whichever path ends the gesture",
            )
        }
    }

    @Test
    fun leaving_the_field_without_done_commits_once() {
        setLongGapEditor(days = 90)

        compose.onNode(field).requestFocus()
        compose.onNode(field).performTextReplacement("45")
        compose.runOnIdle { focusManager.clearFocus() }

        compose.runOnIdle {
            assertEquals(
                listOf<SmartListRule>(SmartListRule.LongGap(daysThreshold = 45)),
                changes,
                "a blur without Done still commits, once",
            )
        }
    }

    @Test
    fun the_field_is_named_by_the_sentence_over_it() {
        setLongGapEditor(days = 90)
        val sentence = ApplicationProvider.getApplicationContext<Context>()
            .resources.getQuantityString(R.plurals.lists_smart_no_call_in, 90, 90)

        compose.onNode(field).assert(hasContentDescription(sentence))
    }
}
