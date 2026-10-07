package app.orbit.ui.screens.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.entity.ListType
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.orderedForMenu
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's long-press menu, pinned (features/home/README.md "List tile
 * long-press"): Add people, List settings, Pause or Resume nudges, then the
 * divider, Archive, Delete. Until 2026-10-06 the menu was the one list menu
 * built inline with no order test, so a reorder or a relabel of `home_menu_*`
 * passed the suite; `OrbitMenuOrderTest` fakes its labels.
 *
 * The labels come from string resources, so the menu is built against real
 * resources under Robolectric, like `ListRowMenuOrderTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HomeTileMenuTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun actions(
        notificationsEnabled: Boolean = true,
        type: ListType = ListType.STATIC,
        listName: String = "Inner orbit",
    ): List<OrbitMenuAction> = homeTileMenuActions(
        resources = resources,
        listName = listName,
        notificationsEnabled = notificationsEnabled,
        type = type,
        onAddPeople = {},
        onListSettings = {},
        onToggleNudges = {},
        onArchive = {},
        onDelete = {},
    ).orderedForMenu()

    @Test
    fun `the order is Add people, List settings, Pause nudges, Archive, Delete`() {
        assertEquals(
            listOf("Add people", "List settings", "Pause nudges", "Archive", "Delete"),
            actions().map { it.label },
        )
    }

    // voice.md glossary: a list's nudges are paused and resumed; the list
    // itself is not paused, so the inverse is never "Unpause".
    @Test
    fun `paused nudges read Resume nudges in the same slot`() {
        assertEquals(
            listOf("Add people", "List settings", "Resume nudges", "Archive", "Delete"),
            actions(notificationsEnabled = false).map { it.label },
        )
    }

    @Test
    fun `archive and delete are the only destructive entries`() {
        val destructive = actions().filter { it.tone == OrbitMenuTone.Destructive }
        assertEquals(listOf("Archive", "Delete"), destructive.map { it.label })
    }

    // A smart list's members are what its rule matches; people added by hand
    // were removed by the next reconcile with no message (rules.md Code 3).
    // Lists Manager hides its "+" for smart lists; Home's menu now agrees.
    @Test
    fun `a smart list is not offered Add people`() {
        assertEquals(
            listOf("List settings", "Pause nudges", "Archive", "Delete"),
            actions(type = ListType.SMART).map { it.label },
        )
    }

    // The same Archive is explained on the Lists row; it was a bare word here.
    @Test
    fun `archive says what it does to the named list`() {
        val archive = actions().first { it.label == "Archive" }
        assertEquals("Hides Inner orbit from home. You can restore it.", archive.supporting)
    }

    // The builder interpolates whatever name it is given. Whether Home hands
    // it the curtain's "List" rather than the real name (PRIV-03) is the
    // screen's doing, pinned by HomeContentTest
    // `under_the_curtain_the_menu_names_the_list_as_List`; until 2026-10-06
    // this case claimed to cover the curtain while never touching it.
    @Test
    fun `the supporting line names the list it is given`() {
        val archive = actions(listName = "List").first { it.label == "Archive" }
        assertEquals("Hides List from home. You can restore it.", archive.supporting)
    }

    @Test
    fun `only archive carries a supporting line and no entry carries an icon`() {
        val others = actions().filter { it.label != "Archive" }
        assertTrue(others.all { it.supporting == null }, "only Archive explains itself")
        // Icons are all-or-none within a menu (OrbitMenu.kt contract point 3).
        actions().forEach { assertNull(it.icon, "${it.label} carries an icon in an icon-free menu") }
    }
}
