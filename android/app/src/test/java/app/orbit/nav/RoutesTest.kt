package app.orbit.nav

import kotlin.test.assertEquals
import org.junit.Test

/**
 * Pure tests for the [Routes] path builders — the single source of truth for nav
 * paths. No Android, no Compose (the NavHost composable is excluded from
 * coverage); these are the string-building functions screens call instead of
 * hand-writing route literals.
 */
class RoutesTest {

    @Test
    fun `path builders interpolate the id`() {
        assertEquals("card/7", Routes.card("7"))
        assertEquals("browse/7", Routes.browse("7"))
        assertEquals("contact/7", Routes.contact("7"))
        assertEquals("lists/7/config", Routes.listConfig("7"))
        assertEquals("onboard/first-list/7", Routes.firstList("7"))
        assertEquals("pick/lists?contactId=7", Routes.pickLists("7"))
    }

    @Test
    fun `lists toggles the openCreate query arg`() {
        assertEquals("lists?openCreate=false", Routes.lists())
        assertEquals("lists?openCreate=false", Routes.lists(false))
        assertEquals("lists?openCreate=true", Routes.lists(true))
    }

    @Test
    fun `contactWithFocus omits the query string when both args are absent`() {
        assertEquals("contact/9", Routes.contactWithFocus("9"))
    }

    @Test
    fun `contactWithFocus adds focusNote and scrollToCallEventId independently and together`() {
        assertEquals("contact/9?focusNote=1", Routes.contactWithFocus("9", focusNote = true))
        assertEquals(
            "contact/9?scrollToCallEventId=42",
            Routes.contactWithFocus("9", scrollToCallEventId = 42L),
        )
        assertEquals(
            "contact/9?focusNote=1&scrollToCallEventId=42",
            Routes.contactWithFocus("9", focusNote = true, scrollToCallEventId = 42L),
        )
    }

    @Test
    fun `pickContacts defaults mode to add and omits sourceListId`() {
        assertEquals("pick/contacts?targetListId=3&mode=add", Routes.pickContacts("3"))
    }

    @Test
    fun `pickContacts includes sourceListId only when provided (move)`() {
        assertEquals(
            "pick/contacts?targetListId=3&mode=move&sourceListId=1",
            Routes.pickContacts("3", mode = "move", sourceListId = "1"),
        )
    }
}
