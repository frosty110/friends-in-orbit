package app.orbit.ui.screens.lists

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.R
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
 * LIST-23 (2026-10-06): the menu also offers Pause nudges / Resume nudges,
 * which Home's long-press menu offered for the same list and this one did
 * not, and it uses Home's strings for that entry and for the Archive line, so
 * the two menus cannot drift apart word by word.
 *
 * The labels come from string resources, so the menu is built against real
 * resources under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ListRowMenuOrderTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun actions(notificationsEnabled: Boolean = true) = listRowMenuActions(
        resources = resources,
        listName = "Inner orbit",
        notificationsEnabled = notificationsEnabled,
        onRename = {},
        onConfigure = {},
        onToggleNudges = {},
        onMoveUp = {},
        onMoveDown = {},
        onArchive = {},
    ).orderedForMenu()

    @Test
    fun `archive renders last`() {
        assertEquals(
            listOf("Rename", "List settings", "Pause nudges", "Move up", "Move down", "Archive"),
            actions().map { it.label },
        )
    }

    @Test
    fun `the nudge entry reads resume while nudges are paused`() {
        assertEquals(
            listOf("Rename", "List settings", "Resume nudges", "Move up", "Move down", "Archive"),
            actions(notificationsEnabled = false).map { it.label },
        )
    }

    @Test
    fun `list settings and the nudge entry use the same words as home`() {
        // LIST-23 parity: Home's long-press menu and this menu name the same
        // actions with the same strings (features/orbit-lists, "same labels").
        val labels = actions().map { it.label }
        assertTrue(resources.getString(R.string.home_menu_list_settings) in labels)
        assertTrue(resources.getString(R.string.home_menu_pause_nudges) in labels)
        assertTrue(
            resources.getString(R.string.home_menu_resume_nudges) in actions(notificationsEnabled = false).map { it.label },
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
        assertEquals(
            resources.getString(R.string.components_menu_archive_supporting, "Inner orbit"),
            archive.supporting,
        )
        assertTrue(archive.supporting!!.contains("Inner orbit"))
    }
}
