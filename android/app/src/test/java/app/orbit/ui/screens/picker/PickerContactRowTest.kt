package app.orbit.ui.screens.picker

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What one Add people row says (PICK-04, PICK-08), amended 2026-10-08 under
 * rules.md Design 9 (no badges):
 *
 *  - An ignored row, shown by the eye toggle, says "Ignored" at the start of
 *    its call line ("Ignored · Never called"), where the word sat alone in
 *    the checkbox's slot. Its slot stays empty: it cannot be selected, so the
 *    row is a button (a tap opens its menu), never a checkbox.
 *  - The lists line is the shared one Search's results say too
 *    ("On Inner orbit, Late night"; "On 2 lists" under the curtain), so this
 *    pins it where it began.
 *
 * Robolectric, reading the semantics tree on the JVM.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class PickerContactRowTest {

    @get:Rule val compose = createComposeRule()

    private fun person(
        name: String,
        ignored: Boolean = false,
        callCount: Int = 0,
        lastCallAt: Instant? = null,
        lists: List<String> = emptyList(),
    ) = PickerContact(
        contactId = 1L,
        displayName = name,
        phone = "+15550001",
        photoUri = null,
        isIgnored = ignored,
        callCount = callCount,
        lastCallAt = lastCallAt,
        firstSeenByAppAt = Instant.parse("2026-01-01T00:00:00Z"),
        listIds = emptySet(),
        listNames = lists,
        isCommonlyCalled = false,
        isRarelyCalled = false,
        isRecentlyAdded = false,
        isLongGap = false,
    )

    private fun show(contact: PickerContact, curtain: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    PickerContactRow(
                        contact = contact,
                        isSelected = false,
                        onToggle = {},
                        onIgnore = {},
                        onUnignore = {},
                    )
                }
            }
        }
    }

    private val isCheckbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    @Test
    fun an_ignored_row_says_so_first_on_its_call_line_and_has_no_checkbox() {
        show(person("Dana Wells", ignored = true))

        compose.onNodeWithText("Ignored · Never called", useUnmergedTree = true).assertIsDisplayed()
        // Not a word on its own beside the name or in the mark's slot.
        compose.onAllNodesWithText("Ignored", useUnmergedTree = true).assertCountEquals(0)
        // Not selectable: a button that opens the menu, with no checked state.
        compose.onNodeWithText("Dana Wells")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        compose.onAllNodes(isCheckbox).assertCountEquals(0)
    }

    @Test
    fun an_ignored_row_with_calls_leads_the_same_call_line_with_ignored() {
        show(
            person(
                "Dana Wells",
                ignored = true,
                callCount = 4,
                lastCallAt = Instant.now().minus(Duration.ofDays(30)),
            ),
        )

        compose.onAllNodes(
            SemanticsMatcher("call line led by Ignored, then the calls") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any {
                    it.text.startsWith("Ignored · Last called ") && it.text.endsWith(" · 4 calls")
                }
            },
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    @Test
    fun a_normal_row_keeps_its_call_line_and_its_checkbox() {
        show(person("Sarah Levin"))

        compose.onNodeWithText("Never called", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Ignored", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Sarah Levin").assert(isCheckbox).assertIsOff()
    }

    @Test
    fun the_lists_line_names_the_lists() {
        show(person("Sarah Levin", lists = listOf("Inner orbit", "Late night")))

        compose.onNodeWithText("On Inner orbit, Late night", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun past_three_lists_the_rest_are_counted() {
        show(person("Sarah Levin", lists = listOf("Inner orbit", "Late night", "Family", "Work")))

        compose.onNodeWithText("On Inner orbit, Late night, Family and 1 more", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun under_the_curtain_the_lists_line_is_a_count() {
        show(person("Sarah Levin", lists = listOf("Inner orbit", "Late night")), curtain = true)

        compose.onNodeWithText("On 2 lists", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Inner orbit", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun a_person_on_no_list_has_no_lists_line() {
        show(person("Sarah Levin"))

        compose.onAllNodesWithText("On ", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }
}
