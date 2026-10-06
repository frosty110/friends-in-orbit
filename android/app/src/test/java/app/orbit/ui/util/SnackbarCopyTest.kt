package app.orbit.ui.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.R
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The snackbar sentences the domain use cases used to build in English
 * (`Result.label`: "Ignored Alex Chen", "Moved 2 to Inner orbit", ...). The
 * use cases now report data (a count, a name) and the screens build these
 * from string resources; this pins the English each resource reads, so the
 * golden strings the use-case tests asserted are still asserted, against the
 * real resources under Robolectric. The ViewModel tests check which resource
 * each screen picks.
 *
 * Two changed on purpose on 2026-10-05: counts of people say "people", not
 * "contacts" ("Ignored 3 people"), as the rest of the app does. On
 * 2026-10-06 Moved and Copied gained the noun too ("Moved 2 people to Inner
 * orbit", "Copied 1 person to ..."): they sat on the same screen as "Ignored
 * 3 people" and dropped it (browse-13; voice.md "People, not contacts").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SnackbarCopyTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun UiText.text(): String = asString(context)

    @Test
    fun `one person`() {
        assertEquals("Ignored Alex Chen", UiText.res(R.string.components_snackbar_ignored, "Alex Chen").text())
        assertEquals("Unignored Alex Chen", UiText.res(R.string.components_snackbar_unignored, "Alex Chen").text())
        assertEquals("Restored Alex Chen", UiText.res(R.string.components_snackbar_restored, "Alex Chen").text())
        assertEquals("Unpaused Kai", UiText.res(R.string.components_snackbar_unpaused, "Kai").text())
        assertEquals("Archived Alex", UiText.res(R.string.contact_snackbar_archived, "Alex").text())
        assertEquals("Re-linked to Mum", UiText.res(R.string.picker_snackbar_relinked, "Mum").text())
        assertEquals("Note deleted", UiText.res(R.string.contact_snackbar_note_deleted).text())
    }

    @Test
    fun `moving and copying name the count and the list`() {
        assertEquals(
            "Moved 2 people to Inner orbit",
            UiText.plural(R.plurals.components_snackbar_moved, 2, 2, "Inner orbit").text(),
        )
        assertEquals(
            "Moved 1 person to Inner orbit",
            UiText.plural(R.plurals.components_snackbar_moved, 1, 1, "Inner orbit").text(),
        )
        assertEquals(
            "Copied 2 people to Inner orbit",
            UiText.plural(R.plurals.components_snackbar_copied, 2, 2, "Inner orbit").text(),
        )
        assertEquals(
            "Copied 1 person to Inner orbit",
            UiText.plural(R.plurals.components_snackbar_copied, 1, 1, "Inner orbit").text(),
        )
        assertEquals(
            "Removed 1 person from Inner orbit",
            UiText.plural(R.plurals.browse_snackbar_removed, 1, 1, "Inner orbit").text(),
        )
        assertEquals(
            "Removed 3 people from Inner orbit",
            UiText.plural(R.plurals.browse_snackbar_removed, 3, 3, "Inner orbit").text(),
        )
        assertEquals(
            "Added 1 to In touch",
            UiText.plural(R.plurals.picker_snackbar_added, 1, 1, "In touch").text(),
        )
    }

    @Test
    fun `counts of people are honest singulars`() {
        assertEquals("Ignored 3 people", UiText.plural(R.plurals.browse_snackbar_ignored, 3, 3).text())
        // "1 person", never "1 people" (it was "1 contact").
        assertEquals("Ignored 1 person", UiText.plural(R.plurals.browse_snackbar_ignored, 1, 1).text())
        assertEquals("Added to 1 list", UiText.plural(R.plurals.picker_snackbar_added_to_lists, 1, 1).text())
        assertEquals("Added to 3 lists", UiText.plural(R.plurals.picker_snackbar_added_to_lists, 3, 3).text())
    }
}
