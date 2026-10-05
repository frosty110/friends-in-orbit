package app.orbit.notify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.R
import app.orbit.ui.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Voice audit for [NotificationCopy] — NOTIF-05 (no shame framing) and NOTIF-09
 * (golden-string assertions).
 *
 * All strings produced by [NotificationCopy] are exercised here with representative
 * inputs. The forbidden-pattern check iterates the output and asserts none contains
 * any shame-framing substring (case-insensitive).
 *
 * The copy lives in string resources (strings_notify.xml; the schedule editor's
 * words in strings_lists.xml), so this runs under Robolectric and resolves every
 * string against the real English resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CopyAuditTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun UiText.text(): String = asString(context)

    // --- Golden-string assertions ---

    @Test
    fun nudgeCopy_goldenString() {
        assertEquals(
            "A few people in Late night are ready when you are. Start with one?",
            NotificationCopy.nudgeBody(listName = "Late night", dueCount = 3).text(),
        )
        assertEquals(
            "Someone in Inner orbit is ready when you are. Want to call?",
            NotificationCopy.nudgeBody(listName = "Inner orbit", dueCount = 1).text(),
        )
    }

    @Test
    fun nudgeTitle_returnsRawListName() {
        assertEquals("Late night", NotificationCopy.nudgeTitle(listName = "Late night"))
    }

    /** NOTIF-14: the named nudge, in the same invitation the name-free one uses. */
    @Test
    fun namedNudge_goldenStrings() {
        assertEquals("Kai is ready when you are. Want to call?", NotificationCopy.nudgeNamedBody("Kai").text())
        assertEquals("Call Kai", NotificationCopy.callActionLabel("Kai").text())
    }

    @Test
    fun firstNameOf_takesTheFirstWord_orTheWholeOneWordName() {
        assertEquals("Kai", NotificationCopy.firstNameOf("Kai Nakamura"))
        assertEquals("Kai", NotificationCopy.firstNameOf("  Kai Nakamura "))
        assertEquals("Mom", NotificationCopy.firstNameOf("Mom"))
    }

    /** NOTIF-13: the lock-screen version is fixed copy, with nothing to fill in. */
    @Test
    fun lockScreenVersion_goldenStrings() {
        assertEquals("Someone is ready when you are", NotificationCopy.PUBLIC_TITLE.text())
        assertEquals("Want to call?", NotificationCopy.PUBLIC_BODY.text())
    }

    // --- Forbidden-pattern audit ---

    @Test
    fun copy_hasNoForbiddenPatterns() {
        val allCopyStrings = listOf(
            NotificationCopy.nudgeTitle(listName = "Late night"),
            NotificationCopy.nudgeBody(listName = "Late night", dueCount = 3).text(),
            NotificationCopy.nudgeBody(listName = "Late night", dueCount = 1).text(),
            NotificationCopy.nudgeNamedBody(firstName = "Kai").text(),
            NotificationCopy.callActionLabel(firstName = "Kai").text(),
            NotificationCopy.PUBLIC_TITLE.text(),
            NotificationCopy.PUBLIC_BODY.text(),
            NotificationCopy.CHANNEL_LABEL_LIST_PROMPTS.text(),
            NotificationCopy.CHANNEL_DESC_LIST_PROMPTS.text(),
            // The nudge schedule editor's words, which lived on NotificationCopy
            // until 2026-10-05 and are audited with the rest.
            context.getString(R.string.lists_nudge_add_time),
            context.getString(R.string.lists_nudge_paused_badge),
            context.getString(R.string.lists_nudge_summary_no_days),
            context.getString(R.string.lists_nudge_summary_no_time),
        )
        val forbiddenPatterns = listOf(
            "haven't called",
            "days since",
            "overdue",
            "it's been",
            "streak",
            "level",
            "achievement",
            "you missed",
            "due",
            "caught up",
        )
        allCopyStrings.forEach { copy ->
            forbiddenPatterns.forEach { pattern ->
                assertFalse(
                    "Copy '$copy' must not contain forbidden pattern '$pattern'",
                    copy.contains(pattern, ignoreCase = true),
                )
            }
            assertFalse("No exclamation marks in: '$copy'", copy.contains("!"))
            // voice.md: no em dash in product copy. Three of the editor strings
            // carried one until 2026-10-05.
            assertFalse("No em dash in: '$copy'", copy.contains('\u2014'))
        }
    }
}
