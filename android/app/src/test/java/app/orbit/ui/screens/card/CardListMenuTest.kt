package app.orbit.ui.screens.card

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.entity.ListType
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.components.orderedForMenu
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Card view's list menu ("More actions for {list}"). The README and the page
 * view pin the order Browse people, Add people, List settings; a smart list's
 * members are its rule's matches, so "Add people" is not offered there (the
 * sync removed anyone added by hand, with no word), nor while the list's type
 * is still unknown (the Loading and Error decks pass null), since an unknown
 * list may be smart.
 *
 * The labels come from string resources (strings_card.xml), so the menu is
 * built against real resources under Robolectric (the BrowseRowMenuTest
 * precedent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CardListMenuTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun actions(listType: ListType?) =
        cardListMenuActions(resources, listType, {}, {}, {}).orderedForMenu()

    @Test
    fun `a static list offers Browse people, Add people and List settings in that order`() {
        assertEquals(listOf("Browse people", "Add people", "List settings"), actions(ListType.STATIC).map { it.label })
    }

    @Test
    fun `a smart list does not offer Add people`() {
        assertEquals(listOf("Browse people", "List settings"), actions(ListType.SMART).map { it.label })
    }

    @Test
    fun `a list whose type is not yet known does not offer Add people`() {
        // The Loading and Error decks have no type (CardViewUiState.listType is
        // null there). The menu once read that as "not smart" and offered Add
        // people on a failed smart list.
        assertEquals(listOf("Browse people", "List settings"), actions(listType = null).map { it.label })
    }

    @Test
    fun `nothing in the menu is destructive and every row has an icon`() {
        val all = actions(ListType.STATIC) + actions(ListType.SMART) + actions(null)
        assertTrue(all.none { it.tone == OrbitMenuTone.Destructive }, "no row removes or hides anything")
        assertTrue(all.all { it.icon != null }, "leading icons are all or none within a menu (OrbitMenu.kt)")
    }
}
