package app.orbit.notify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.R
import app.orbit.testutil.VoiceRules
import app.orbit.ui.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** NOTIF-16: the notification after a call, and its name-free lock-screen version. */
    @Test
    fun postCall_goldenStrings() {
        assertEquals("How was your call with Kai?", NotificationCopy.postCallTitle("Kai").text())
        assertEquals("Add a note while it's fresh.", NotificationCopy.POST_CALL_BODY.text())
        assertEquals("How was your call?", NotificationCopy.POST_CALL_PUBLIC_TITLE.text())
        assertEquals("After a call", NotificationCopy.CHANNEL_LABEL_AFTER_CALL.text())
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
            // NOTIF-16: the notification after a call and its channel.
            NotificationCopy.postCallTitle(firstName = "Kai").text(),
            NotificationCopy.POST_CALL_BODY.text(),
            NotificationCopy.POST_CALL_PUBLIC_TITLE.text(),
            NotificationCopy.CHANNEL_LABEL_AFTER_CALL.text(),
            NotificationCopy.CHANNEL_DESC_AFTER_CALL.text(),
            // The nudge schedule editor's words, which lived on NotificationCopy
            // until 2026-10-05 and are audited with the rest, and the per-list
            // "Send nudges" switch's subtitle, which said "notification" until
            // 2026-10-06 (the glossary's word for it is nudge).
            context.getString(R.string.lists_nudge_add_time),
            context.getString(R.string.lists_nudge_paused_badge),
            context.getString(R.string.lists_nudge_summary_no_days),
            context.getString(R.string.lists_nudge_summary_no_time),
            context.getString(R.string.lists_send_nudges_sub),
        )
        // The never-say list, exclamation marks and dashes are one shared
        // rule set (testutil/VoiceRules.kt); VoiceAuditTest holds every string
        // resource to the same list.
        allCopyStrings.forEach { copy ->
            val broken = VoiceRules.violations(copy)
            assertTrue("Copy '$copy' breaks the voice rules: $broken", broken.isEmpty())
        }
        // The glossary's word for the notification is nudge, in UI copy.
        allCopyStrings.filter { it != NotificationCopy.nudgeTitle(listName = "Late night") }.forEach { copy ->
            assertFalse("Copy '$copy' says notification; the word is nudge", copy.contains("notification", ignoreCase = true))
        }
    }
}
