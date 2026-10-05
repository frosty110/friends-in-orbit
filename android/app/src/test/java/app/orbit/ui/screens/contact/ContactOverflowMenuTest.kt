package app.orbit.ui.screens.contact

import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.orderedForMenu
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Contact detail overflow. Regression: there was no Unpause, so an indefinite
 * pause could never be undone once its snackbar was gone.
 */
class ContactOverflowMenuTest {

    private fun labels(isPaused: Boolean) = contactOverflowActions(
        isPaused = isPaused,
        onViewAllCalls = {},
        onPause = {},
        onUnpause = {},
        onIgnore = {}
    ).orderedForMenu().map { it.label }

    @Test
    fun `not paused offers Pause`() {
        assertEquals(listOf("View all calls", "Pause", "Ignore"), labels(isPaused = false))
    }

    @Test
    fun `paused offers Unpause in the same slot`() {
        assertEquals(listOf("View all calls", "Unpause", "Ignore"), labels(isPaused = true))
    }

    @Test
    fun `ignore stays the only destructive entry`() {
        val destructive = contactOverflowActions(true, {
        }, {}, {}, {}).filter { it.tone == OrbitMenuTone.Destructive }
        assertEquals(listOf("Ignore"), destructive.map { it.label })
    }
}
