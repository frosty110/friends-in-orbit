package app.orbit.ui.screens.settings

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
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.screens.settings.export.ExportUiState
import app.orbit.ui.screens.settings.export.ImportUiState
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Settings' "Import range" row is the same radio group onboarding's sync step
 * draws (`ImportRangeChipGroup`), read from the semantics tree under
 * Robolectric. Until 2026-10-07 this row drew Material FilterChips, so the
 * one setting announced as four checkboxes here and as a radio group in
 * onboarding (`OnboardingSyncChipsSemanticsTest` pins that side).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsImportRangeTest {

    @get:Rule val compose = createComposeRule()

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
    private val window = isSelectable() and radio

    private fun setSettings(importDays: Int, onImportDaysChanged: (Int) -> Unit = {}) {
        compose.setContent {
            OrbitTheme {
                SettingsContent(
                    state = SettingsUiState.Ready.INITIAL.copy(callLogImportDays = importDays),
                    exportState = ExportUiState.Idle,
                    importState = ImportUiState.Idle,
                    onBack = {},
                    onOpenIgnored = {},
                    onOpenCallHistory = {},
                    onOpenAndroidSettings = {},
                    onRequestContactsPermission = {},
                    onRequestCallLogPermission = {},
                    onRequestNotificationsPermission = {},
                    onManualResync = {},
                    onManualContactsResync = {},
                    onImportDaysChanged = onImportDaysChanged,
                    onCommitThresholds = {},
                    onExport = {},
                    onImport = {},
                    onResetConfirmed = {},
                    onSourceCode = {},
                    onSelectTheme = {},
                    onSelectDarkMode = {},
                    onAccentHue = {},
                )
            }
        }
    }

    @Test
    fun the_import_range_is_one_radio_group_with_the_current_window_selected() {
        setSettings(importDays = 180)

        compose.onNode(group).assertExists()
        compose.onAllNodes(window).assertCountEquals(4)
        compose.onNode(window and hasText("6 months")).assertIsSelected()
        compose.onNode(window and hasText("1 month")).assertIsNotSelected()
        compose.onNode(window and hasText("3 months")).assertIsNotSelected()
        compose.onNode(window and hasText("1 year")).assert(radio).assertIsNotSelected()
    }

    @Test
    fun tapping_a_window_reports_it_in_days() {
        var picked: Int? = null
        setSettings(importDays = 90, onImportDaysChanged = { picked = it })

        // The row sits below the first screenful of Settings: scroll it into
        // view first, or the injected tap lands outside the window.
        compose.onNode(window and hasText("1 month")).performScrollTo().performClick()

        assertEquals(30, picked)
    }
}
