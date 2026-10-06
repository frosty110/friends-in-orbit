package app.orbit.ui.screens.contact

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.orderedForMenu
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contact detail overflow: its order and its variants.
 *
 * Regressions pinned here: there was no Unpause, so an indefinite pause could
 * never be undone once its snackbar was gone; an ignored person's page offered
 * Ignore again and no way back (CONTACT-10); and the README promised a hand-off
 * to the phone's Contacts app that no menu had.
 *
 * The labels come from string resources (strings_contact.xml), so the menu is
 * built against real resources under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ContactOverflowMenuTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun actions(
        isPaused: Boolean = false,
        isIgnored: Boolean = false,
        hasPhoneContact: Boolean = false,
        orphaned: Boolean = false
    ): List<OrbitMenuAction> = contactOverflowActions(
        resources = resources,
        isPaused = isPaused,
        isIgnored = isIgnored,
        hasPhoneContact = hasPhoneContact,
        orphaned = orphaned,
        onViewAllCalls = {},
        onPause = {},
        onUnpause = {},
        onOpenInContacts = {},
        onIgnore = {},
        onUnignore = {}
    )

    private fun labels(
        isPaused: Boolean = false,
        isIgnored: Boolean = false,
        hasPhoneContact: Boolean = false,
        orphaned: Boolean = false
    ) = actions(isPaused, isIgnored, hasPhoneContact, orphaned).orderedForMenu().map { it.label }

    @Test
    fun `not paused offers Pause`() {
        assertEquals(listOf("View all calls", "Pause", "Ignore"), labels())
    }

    @Test
    fun `paused offers Unpause in the same slot`() {
        assertEquals(listOf("View all calls", "Unpause", "Ignore"), labels(isPaused = true))
    }

    @Test
    fun `someone in the phone's contacts gets Open in Contacts after the pause slot and before Ignore`() {
        assertEquals(
            listOf("View all calls", "Pause", "Open in Contacts", "Ignore"),
            labels(hasPhoneContact = true)
        )
        assertEquals(
            listOf("View all calls", "Unpause", "Open in Contacts", "Ignore"),
            labels(isPaused = true, hasPhoneContact = true)
        )
    }

    @Test
    fun `ignored offers Unignore in place of Ignore, and no Pause`() {
        assertEquals(listOf("View all calls", "Unignore"), labels(isIgnored = true))
        // A pause in force changes nothing while ignored.
        assertEquals(
            listOf("View all calls", "Unignore"),
            labels(isIgnored = true, isPaused = true)
        )
        assertEquals(
            listOf("View all calls", "Open in Contacts", "Unignore"),
            labels(isIgnored = true, hasPhoneContact = true)
        )
    }

    @Test
    fun `an orphaned person keeps only View all calls`() {
        assertEquals(
            listOf("View all calls"),
            labels(orphaned = true, isPaused = true, hasPhoneContact = true)
        )
    }

    @Test
    fun `ignore stays the only destructive entry, and Unignore is not one`() {
        val destructive = actions(isPaused = true, hasPhoneContact = true)
            .filter { it.tone == OrbitMenuTone.Destructive }
        assertEquals(listOf("Ignore"), destructive.map { it.label })
        assertTrue(actions(isIgnored = true).none { it.tone == OrbitMenuTone.Destructive })
    }

    @Test
    fun `every entry carries an icon, in every variant`() {
        // OrbitDropdownMenu contract point 3: leading icons are all or none.
        listOf(
            actions(),
            actions(isPaused = true, hasPhoneContact = true),
            actions(isIgnored = true, hasPhoneContact = true),
            actions(orphaned = true)
        ).forEach { variant ->
            assertTrue(
                variant.all { it.icon != null },
                "an entry has no icon: ${variant.map { it.label }}"
            )
        }
    }
}
