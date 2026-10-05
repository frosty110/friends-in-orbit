package app.orbit.ui.screens.lists

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
 * 2026-08-15 UAT regression — "Archive" used to be the second entry in the
 * Lists Manager row menu, rendered in the same colour as everything else. It
 * is reversible but it takes a list off home, so it belongs at the bottom, in
 * danger, behind the divider.
 *
 * The labels come from string resources, so the menu is built against real
 * resources under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ListRowMenuOrderTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun actions() = listRowMenuActions(
        resources = resources,
        listName = "Inner orbit",
        onRename = {},
        onConfigure = {},
        onMoveUp = {},
        onMoveDown = {},
        onArchive = {},
    ).orderedForMenu()

    @Test
    fun `archive renders last`() {
        assertEquals(
            listOf("Rename", "List settings", "Move up", "Move down", "Archive"),
            actions().map { it.label },
        )
    }

    @Test
    fun `archive is the only destructive entry`() {
        val destructive = actions().filter { it.tone == OrbitMenuTone.Destructive }
        assertEquals(listOf("Archive"), destructive.map { it.label })
    }

    @Test
    fun `archive says what it does to the named list`() {
        val archive = actions().first { it.tone == OrbitMenuTone.Destructive }
        assertTrue(archive.supporting!!.contains("Inner orbit"))
    }
}
