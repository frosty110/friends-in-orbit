package app.orbit.ui.screens.browse

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.ui.components.orderedForMenu
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Browse long-press menu. Regression: a paused row offered Pause again and no
 * way back, so an indefinite pause was permanent.
 *
 * The labels come from string resources (strings_browse.xml), so the menu is
 * built against real resources under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BrowseRowMenuTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun labels(isPaused: Boolean) =
        browseRowMenuActions(resources, isPaused, {}, {}, {}, {}, {}).orderedForMenu().map { it.label }

    @Test
    fun `an active row offers Pause`() {
        assertEquals(listOf("Call", "Select", "Pause", "Ignore"), labels(isPaused = false))
    }

    @Test
    fun `a paused row offers Unpause instead`() {
        assertEquals(listOf("Call", "Select", "Unpause", "Ignore"), labels(isPaused = true))
    }
}
