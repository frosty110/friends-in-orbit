package app.orbit.ui.screens.picker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * App-bar title pluralization for [pickerModeTitle].
 * "Move 1 person" / "Copy 1 person", never "1 people". Add mode ignores
 * the count entirely ("Add people").
 *
 * The titles are string resources (strings_picker.xml), resolved here against
 * the real English resources under Robolectric. They said "contact(s)" until
 * 2026-10-05; the app says "people" for the people in Orbit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PickerModeTitleTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun title(mode: PickerMode, count: Int): String = pickerModeTitle(mode, count).asString(context)

    @Test
    fun add_mode_ignores_selection_count() {
        assertEquals("Add people", title(PickerMode.Add, 0))
        assertEquals("Add people", title(PickerMode.Add, 1))
        assertEquals("Add people", title(PickerMode.Add, 7))
    }

    @Test
    fun move_mode_singularizes_at_one() {
        assertEquals("Move 1 person", title(PickerMode.Move, 1))
        assertEquals("Move 2 people", title(PickerMode.Move, 2))
        assertEquals("Move 0 people", title(PickerMode.Move, 0))
    }

    @Test
    fun copy_mode_singularizes_at_one() {
        assertEquals("Copy 1 person", title(PickerMode.Copy, 1))
        assertEquals("Copy 12 people", title(PickerMode.Copy, 12))
    }

    @Test
    fun relink_mode_ignores_selection_count() {
        // CONTACT-07: one pick, so no count to show.
        assertEquals("Re-link contact", title(PickerMode.Relink, 0))
        assertEquals("Re-link contact", title(PickerMode.Relink, 1))
    }
}
