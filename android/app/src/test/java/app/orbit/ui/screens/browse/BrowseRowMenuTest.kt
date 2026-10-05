package app.orbit.ui.screens.browse

import app.orbit.ui.components.orderedForMenu
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Browse long-press menu. Regression: a paused row offered Pause again and no
 * way back, so an indefinite pause was permanent.
 */
class BrowseRowMenuTest {

    private fun labels(isPaused: Boolean) =
        browseRowMenuActions(isPaused, {}, {}, {}, {}, {}).orderedForMenu().map { it.label }

    @Test
    fun `an active row offers Pause`() {
        assertEquals(listOf("Call", "Select", "Pause", "Ignore"), labels(isPaused = false))
    }

    @Test
    fun `a paused row offers Unpause instead`() {
        assertEquals(listOf("Call", "Select", "Unpause", "Ignore"), labels(isPaused = true))
    }
}
