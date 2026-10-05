package app.orbit.ui.screens.contact

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.orderedForMenu
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contact detail overflow. Regression: there was no Unpause, so an indefinite
 * pause could never be undone once its snackbar was gone.
 *
 * The labels come from string resources (strings_contact.xml), so the menu is
 * built against real resources under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ContactOverflowMenuTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun labels(isPaused: Boolean) = contactOverflowActions(
        resources = resources,
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
        val destructive = contactOverflowActions(resources, true, {
        }, {}, {}, {}).filter { it.tone == OrbitMenuTone.Destructive }
        assertEquals(listOf("Ignore"), destructive.map { it.label })
    }
}
