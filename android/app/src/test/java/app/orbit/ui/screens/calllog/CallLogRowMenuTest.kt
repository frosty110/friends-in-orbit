package app.orbit.ui.screens.calllog

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.orderedForMenu
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Call history's row menu (the "More actions for {name}" button and the
 * long-press), pinned against real resources under Robolectric. The
 * call-history README fixes the order, "Call again" then "Open details", and
 * neither action removes anything, so neither sinks below a divider in
 * danger. Until 2026-10-06 the labels and order were asserted only by KDoc.
 *
 * The labels come from string resources (strings_calllog.xml and
 * strings_components.xml), so the menu is built against real resources, the
 * `BrowseRowMenuTest` precedent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CallLogRowMenuTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun actions(onCallAgain: () -> Unit = {}, onOpen: () -> Unit = {}) =
        callLogRowActions(resources, onCallAgain = onCallAgain, onOpen = onOpen).orderedForMenu()

    @Test
    fun `offers Call again, then Open details`() {
        assertEquals(listOf("Call again", "Open details"), actions().map { it.label })
    }

    @Test
    fun `nothing in the menu is destructive`() {
        assertTrue(actions().none { it.tone == OrbitMenuTone.Destructive })
    }

    @Test
    fun `each entry fires its own callback`() {
        var dialled = 0
        var opened = 0
        val menu = actions(onCallAgain = { dialled += 1 }, onOpen = { opened += 1 })
        menu.first { it.label == "Call again" }.onClick()
        menu.first { it.label == "Open details" }.onClick()
        assertEquals(1, dialled)
        assertEquals(1, opened)
    }
}
