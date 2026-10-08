package app.orbit.ui.screens.picker

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.ui.theme.OrbitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Recently added" is a filter chip again (2026-10-07). It was dropped while
 * it read first sight alone, which the first sync sets to one instant for the
 * whole address book; it reads `ContactEntity.addedAt` now (SMART-08).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class FilterChipsRowTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun recently_added_is_offered_and_toggles_its_filter() {
        var toggled: PickerFilter? = null
        compose.setContent {
            OrbitTheme {
                FilterChipsRow(
                    activeFilters = emptySet(),
                    onToggle = { toggled = it },
                    countFor = { 3 },
                    availableLists = emptyList(),
                    onSelectInList = { _, _ -> },
                    onClearInList = {}
                )
            }
        }

        compose.onNodeWithText("Recently added").performClick()

        assertEquals(PickerFilter.RecentlyAdded, toggled)
    }
}
