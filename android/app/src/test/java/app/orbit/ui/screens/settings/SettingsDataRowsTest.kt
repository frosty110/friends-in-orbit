package app.orbit.ui.screens.settings

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.ui.screens.settings.export.ExportUiState
import app.orbit.ui.screens.settings.export.ImportUiState
import app.orbit.ui.theme.OrbitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the three Data rows say while one of them is busy, read from the
 * semantics tree under Robolectric (development-cycle.md: a check that only
 * reads the tree runs on the JVM and gates every push).
 *
 * SET-05 / SET-06: while an export, an import or a reset runs, all three rows
 * are disabled. The busy row says what is happening ("Saving…", "Restoring…",
 * "Resetting…"); the other two say "Waiting for the other backup step to
 * finish" instead of their default line. Until 2026-10-06 a disabled Reset
 * row changed only its title's colour, and Import kept "Replace what's here
 * with an exported file" while refusing taps during an export: colour, or a
 * vanished chevron, as the only signal (vision/ux-rubric.md D8).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsDataRowsTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(id: Int): String = context.getString(id)

    private fun setRows(
        state: SettingsUiState.Ready = SettingsUiState.Ready.INITIAL,
        exportState: ExportUiState = ExportUiState.Idle,
        importState: ImportUiState = ImportUiState.Idle,
    ) {
        compose.setContent {
            OrbitTheme {
                SettingsContent(
                    state = state,
                    exportState = exportState,
                    importState = importState,
                    onBack = {},
                    onOpenIgnored = {},
                    onOpenCallHistory = {},
                    onOpenAndroidSettings = {},
                    onRequestContactsPermission = {},
                    onRequestCallLogPermission = {},
                    onRequestNotificationsPermission = {},
                    onManualResync = {},
                    onManualContactsResync = {},
                    onImportDaysChanged = {},
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

    // The row is the clickable; its title and subtitle merge into it, so the
    // node found by title carries both texts and the enabled state.
    private fun row(titleId: Int) = compose.onNodeWithText(string(titleId))

    /** The row doing the work: disabled, saying what it is doing. */
    private fun assertBusy(titleId: Int, busySubtitleId: Int) {
        row(titleId).assertIsNotEnabled().assert(hasText(string(busySubtitleId)))
    }

    /** A row held by another row's work: disabled, saying it waits, its default line gone. */
    private fun assertWaiting(titleId: Int, defaultSubtitleId: Int) {
        row(titleId).assertIsNotEnabled().assert(hasText(string(R.string.settings_data_wait)))
        compose.onAllNodes(hasText(string(defaultSubtitleId))).assertCountEquals(0)
    }

    @Test
    fun idle_rows_are_enabled_and_say_their_default_line() {
        setRows()

        row(R.string.settings_export_title).assertIsEnabled()
            .assert(hasText(string(R.string.settings_export_sub)))
        row(R.string.settings_import_title).assertIsEnabled()
            .assert(hasText(string(R.string.settings_import_sub)))
        row(R.string.settings_reset_title).assertIsEnabled()
            .assert(hasText(string(R.string.settings_reset_sub)))
        compose.onAllNodes(hasText(string(R.string.settings_data_wait))).assertCountEquals(0)
    }

    @Test
    fun during_an_export_the_other_two_rows_say_they_are_waiting() {
        setRows(exportState = ExportUiState.InFlight)

        assertBusy(R.string.settings_export_title, R.string.settings_export_in_progress)
        assertWaiting(R.string.settings_import_title, R.string.settings_import_sub)
        assertWaiting(R.string.settings_reset_title, R.string.settings_reset_sub)
    }

    @Test
    fun during_a_restore_the_other_two_rows_say_they_are_waiting() {
        setRows(importState = ImportUiState.Applying)

        assertBusy(R.string.settings_import_title, R.string.settings_import_in_progress)
        assertWaiting(R.string.settings_export_title, R.string.settings_export_sub)
        assertWaiting(R.string.settings_reset_title, R.string.settings_reset_sub)
    }

    @Test
    fun while_the_file_is_checked_the_other_two_rows_say_they_are_waiting() {
        setRows(importState = ImportUiState.Validating)

        assertBusy(R.string.settings_import_title, R.string.settings_import_checking)
        assertWaiting(R.string.settings_export_title, R.string.settings_export_sub)
        assertWaiting(R.string.settings_reset_title, R.string.settings_reset_sub)
    }

    @Test
    fun during_a_reset_the_reset_row_says_so_and_the_other_two_wait() {
        setRows(state = SettingsUiState.Ready.INITIAL.copy(isResetting = true))

        assertBusy(R.string.settings_reset_title, R.string.settings_reset_in_progress)
        assertWaiting(R.string.settings_export_title, R.string.settings_export_sub)
        assertWaiting(R.string.settings_import_title, R.string.settings_import_sub)
    }
}
