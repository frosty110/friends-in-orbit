package app.orbit.ui.components

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one "On ..." lists line, shared by the Add people picker's rows
 * (PICK-04) and Search's results (BROWSE-03) since 2026-10-08, when Search's
 * row of list chips became this line (rules.md Design 9). Resolved against
 * the real resources under Robolectric, so the English each case reads is
 * pinned: the names in the user's order, three at most and then "and N
 * more", only the count under the privacy curtain (PRIV-03), nothing at all
 * for a person on no list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OnListsLineTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun line(names: List<String>, curtain: Boolean = false): String? =
        onListsLineText(names, curtain)?.asString(context)

    @Test
    fun one_list_is_named() {
        assertEquals("On Inner orbit", line(listOf("Inner orbit")))
    }

    @Test
    fun two_and_three_lists_are_all_named_in_order() {
        assertEquals("On Inner orbit, Late night", line(listOf("Inner orbit", "Late night")))
        assertEquals(
            "On Inner orbit, Late night, Family",
            line(listOf("Inner orbit", "Late night", "Family")),
        )
    }

    @Test
    fun past_three_the_rest_are_counted() {
        assertEquals(
            "On Inner orbit, Late night, Family and 1 more",
            line(listOf("Inner orbit", "Late night", "Family", "Work")),
        )
        assertEquals(
            "On Inner orbit, Late night, Family and 2 more",
            line(listOf("Inner orbit", "Late night", "Family", "Work", "School")),
        )
    }

    @Test
    fun under_the_curtain_only_the_count_is_said() {
        assertEquals("On 1 list", line(listOf("Inner orbit"), curtain = true))
        val four = line(listOf("Inner orbit", "Late night", "Family", "Work"), curtain = true)
        assertEquals("On 4 lists", four)
        // Not even the curtain's own word for a list's name: a count only.
        assertFalse(four.orEmpty().contains("Inner"), "a list name showed under the curtain: $four")
    }

    @Test
    fun no_lists_is_no_line_with_or_without_the_curtain() {
        assertNull(line(emptyList()))
        assertNull(line(emptyList(), curtain = true))
    }
}
