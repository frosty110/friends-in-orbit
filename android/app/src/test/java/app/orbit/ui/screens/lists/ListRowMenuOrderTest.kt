package app.orbit.ui.screens.lists

import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.orderedForMenu
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * 2026-08-15 UAT regression — "Archive" used to be the second entry in the
 * Lists Manager row menu, rendered in the same colour as everything else. It
 * is reversible but it takes a list off home, so it belongs at the bottom, in
 * danger, behind the divider.
 */
class ListRowMenuOrderTest {

    private fun actions() = listRowMenuActions(
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
