package app.orbit.ui.screens.browse

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
 * The selection bar's overflow. Regression: MOVE-05 "Select all" had a
 * ViewModel method and a README line but no control on screen (browse-3), so
 * the user tapped rows one by one. The order and the enabled states are what
 * TalkBack and a sighted user both meet, so they are pinned here against real
 * resources under Robolectric (the BrowseRowMenuTest pattern).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MultiSelectOverflowMenuTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun actions(hasSelection: Boolean = true, enabled: Boolean = true) =
        multiSelectOverflowActions(resources, hasSelection, enabled, {}, {}, {}).orderedForMenu()

    @Test
    fun `select all leads, then pause all, then ignore all`() {
        assertEquals(listOf("Select all", "Pause all", "Ignore all"), actions().map { it.label })
    }

    @Test
    fun `ignore all is the only destructive entry`() {
        val destructive = actions().filter { it.tone == OrbitMenuTone.Destructive }
        assertEquals(listOf("Ignore all"), destructive.map { it.label })
    }

    @Test
    fun `every row carries an icon, so none reads as a gap`() {
        // OrbitMenu.kt: leading icons are all-or-none within a menu.
        assertEquals(listOf("check-circle", "pause-circle", "eye-slash"), actions().map { it.icon })
    }

    @Test
    fun `with nothing selected only select all is live`() {
        // The app bar's Select enters with an empty selection; a bulk write on
        // nothing would be a silent no-op (rules.md Code 3), so those wait.
        val byLabel = actions(hasSelection = false).associateBy { it.label }
        assertEquals(true, byLabel.getValue("Select all").enabled)
        assertEquals(false, byLabel.getValue("Pause all").enabled)
        assertEquals(false, byLabel.getValue("Ignore all").enabled)
    }

    @Test
    fun `while a write is in flight every item waits`() {
        assertEquals(listOf(false, false, false), actions(enabled = false).map { it.enabled })
    }
}
