package app.orbit.ui.screens.onboarding

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
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Sync step's import-range chips are one choice of four, so TalkBack must
 * hear a radio group with a position ("3 months, radio button, 2 of 4"), not
 * four lone radio buttons. The chips carried `Role.RadioButton` from the
 * start, but the row had no `selectableGroup`, unlike the direction row on
 * Call history and the rhythm rows this project built the same way. The
 * gallery's a11y audit checks labels and 48dp, not roles or groups, so this
 * test reads the semantics tree directly (the TemplateSelectionSemanticsTest
 * shape).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class OnboardingSyncChipsSemanticsTest {

    @get:Rule val compose = createComposeRule()

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)

    private fun setReady(importDays: Int, onSelect: (Int) -> Unit = {}) {
        compose.setContent {
            OrbitTheme {
                OnboardingSyncContent(
                    state = OnboardingSyncUiState.Ready(
                        syncState = SyncState.InProgress,
                        callCount = 12,
                        contactCount = 4,
                        importDays = importDays,
                    ),
                    onContinue = {},
                    onRetry = {},
                    onImportDaysSelected = onSelect,
                )
            }
        }
    }

    @Test
    fun import_range_chips_are_one_radio_group_with_the_current_window_selected() {
        setReady(importDays = 90)

        compose.onNode(group).assertExists()
        compose.onAllNodes(isSelectable()).assertCountEquals(4)
        compose.onNode(isSelectable() and hasText("3 months")).assert(radio).assertIsSelected()
        compose.onNode(isSelectable() and hasText("1 month")).assert(radio).assertIsNotSelected()
        compose.onNode(isSelectable() and hasText("6 months")).assert(radio).assertIsNotSelected()
        compose.onNode(isSelectable() and hasText("1 year")).assert(radio).assertIsNotSelected()
    }

    @Test
    fun tapping_a_chip_reports_its_window_in_days() {
        var picked: Int? = null
        setReady(importDays = 90, onSelect = { picked = it })

        compose.onNode(isSelectable() and hasText("1 year")).performClick()

        assertEquals(365, picked)
    }
}
