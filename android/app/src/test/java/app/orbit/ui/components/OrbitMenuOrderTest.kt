package app.orbit.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the menu-ordering contract every options menu in Orbit renders through
 * (see [OrbitDropdownMenu]):
 *
 *   - everyday actions keep the order the caller wrote them in,
 *   - destructive actions sink to the bottom, whatever position they were
 *     declared in.
 */
class OrbitMenuOrderTest {

    private fun action(label: String, tone: OrbitMenuTone = OrbitMenuTone.Default) =
        OrbitMenuAction(label = label, onClick = {}, tone = tone)

    @Test
    fun `destructive actions sink to the bottom`() {
        val ordered = listOf(
            action("Rename"),
            action("Archive", OrbitMenuTone.Destructive),
            action("List settings"),
        ).orderedForMenu()

        assertEquals(
            listOf("Rename", "List settings", "Archive"),
            ordered.map { it.label },
        )
    }

    @Test
    fun `everyday order is preserved`() {
        val ordered = listOf(
            action("Add people"),
            action("List settings"),
            action("Pause nudges"),
            action("Archive", OrbitMenuTone.Destructive),
            action("Delete", OrbitMenuTone.Destructive),
        ).orderedForMenu()

        assertEquals(
            listOf("Add people", "List settings", "Pause nudges", "Archive", "Delete"),
            ordered.map { it.label },
        )
    }

    @Test
    fun `a menu with no destructive action is left alone`() {
        val labels = listOf("Browse people", "Add contacts", "Edit list")
        assertEquals(labels, labels.map { action(it) }.orderedForMenu().map { it.label })
    }

    @Test
    fun `every destructive action lands after every everyday one`() {
        val ordered = listOf(
            action("Ignore", OrbitMenuTone.Destructive),
            action("Call"),
            action("Select"),
            action("Pause"),
        ).orderedForMenu()

        val lastEveryday = ordered.indexOfLast { it.tone == OrbitMenuTone.Default }
        val firstDestructive = ordered.indexOfFirst { it.tone == OrbitMenuTone.Destructive }
        assertTrue(lastEveryday < firstDestructive)
    }
}
