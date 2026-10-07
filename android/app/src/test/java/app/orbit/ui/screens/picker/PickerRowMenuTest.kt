package app.orbit.ui.screens.picker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.orderedForMenu
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The picker row's menu (the trailing button and the long-press): its labels,
 * order, supporting lines and which action is destructive were asserted only
 * by KDoc until 2026-10-06. Built against real resources (strings_picker.xml)
 * under Robolectric, the browseRowMenuActions / BrowseRowMenuTest precedent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PickerRowMenuTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `a normal row offers Open in Contacts, then Ignore as the destructive action`() {
        val actions = pickerRowActions(
            resources = resources,
            name = "Sarah",
            isIgnored = false,
            onOpenInPhone = {},
            onIgnore = {},
            onUnignore = {}
        ).orderedForMenu()

        assertEquals(listOf("Open in Contacts", "Ignore"), actions.map { it.label })
        assertEquals(
            listOf(OrbitMenuTone.Default, OrbitMenuTone.Destructive),
            actions.map { it.tone }
        )
        assertEquals(
            "See their call and message history in your phone's contacts app.",
            actions[0].supporting
        )
        // The locked promise: ignoring touches only Orbit, never the address book.
        assertEquals(
            "Hide Sarah from Orbit. They stay in your phone's contacts.",
            actions[1].supporting
        )
    }

    @Test
    fun `an ignored row offers Unignore, which is not destructive`() {
        val actions = pickerRowActions(
            resources = resources,
            name = "Sarah",
            isIgnored = true,
            onOpenInPhone = {},
            onIgnore = {},
            onUnignore = {}
        ).orderedForMenu()

        assertEquals(listOf("Open in Contacts", "Unignore"), actions.map { it.label })
        assertEquals(OrbitMenuTone.Default, actions[1].tone)
        assertNull(actions[1].supporting)
    }

    @Test
    fun `a row with no device contact behind it has no Open in Contacts`() {
        val actions = pickerRowActions(
            resources = resources,
            name = "Sarah",
            isIgnored = false,
            onOpenInPhone = null,
            onIgnore = {},
            onUnignore = null
        ).orderedForMenu()

        assertEquals(listOf("Ignore"), actions.map { it.label })
    }

    @Test
    fun `a non-curation caller gets no ignore entry`() {
        val actions = pickerRowActions(
            resources = resources,
            name = "Sarah",
            isIgnored = false,
            onOpenInPhone = {},
            onIgnore = null,
            onUnignore = null
        ).orderedForMenu()

        assertEquals(listOf("Open in Contacts"), actions.map { it.label })
    }
}
