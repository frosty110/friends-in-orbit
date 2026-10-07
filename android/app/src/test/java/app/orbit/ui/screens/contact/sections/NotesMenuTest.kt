package app.orbit.ui.screens.contact.sections

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
 * A note's "More actions" menu (NOTE-01): Edit, then Delete in danger. The
 * README promises both as a visible alternative to the long press and the
 * swipe (rubric D5), so the labels and the order are pinned against real
 * resources, not left to the KDoc.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NotesMenuTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private val actions = noteMenuActions(resources = resources, onEdit = {}, onDelete = {})

    @Test
    fun `Edit comes first and Delete last`() {
        assertEquals(listOf("Edit", "Delete"), actions.orderedForMenu().map { it.label })
    }

    @Test
    fun `Delete is the one destructive entry`() {
        assertEquals(
            listOf("Delete"),
            actions.filter { it.tone == OrbitMenuTone.Destructive }.map { it.label }
        )
    }

    @Test
    fun `icons are all or none`() {
        assertTrue(actions.all { it.icon != null })
    }

    @Test
    fun `each entry fires its own callback`() {
        var edits = 0
        var deletes = 0
        val wired = noteMenuActions(resources, onEdit = { edits++ }, onDelete = { deletes++ })
        wired.first { it.label == "Edit" }.onClick()
        wired.first { it.label == "Delete" }.onClick()
        assertEquals(1, edits)
        assertEquals(1, deletes)
    }
}
