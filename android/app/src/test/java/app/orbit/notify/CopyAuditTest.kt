package app.orbit.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Voice audit for [NotificationCopy] — NOTIF-05 (no shame framing) and NOTIF-09
 * (golden-string assertions).
 *
 * All strings produced by [NotificationCopy] are exercised here with representative
 * inputs. The forbidden-pattern check iterates the output and asserts none contains
 * any shame-framing substring (case-insensitive).
 */
class CopyAuditTest {

    // --- Golden-string assertions ---

    @Test
    fun nudgeCopy_goldenString() {
        assertEquals(
            "A few people in Late night are ready when you are. Start with one?",
            NotificationCopy.nudgeBody(listName = "Late night", dueCount = 3),
        )
        assertEquals(
            "Someone in Inner orbit is ready when you are. Want to call?",
            NotificationCopy.nudgeBody(listName = "Inner orbit", dueCount = 1),
        )
    }

    @Test
    fun nudgeTitle_returnsRawListName() {
        assertEquals("Late night", NotificationCopy.nudgeTitle(listName = "Late night"))
    }

    /** NOTIF-14: the named nudge, in the same invitation the name-free one uses. */
    @Test
    fun namedNudge_goldenStrings() {
        assertEquals("Kai is ready when you are. Want to call?", NotificationCopy.nudgeNamedBody("Kai"))
        assertEquals("Call Kai", NotificationCopy.callActionLabel("Kai"))
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
        assertEquals("Someone is ready when you are", NotificationCopy.PUBLIC_TITLE)
        assertEquals("Want to call?", NotificationCopy.PUBLIC_BODY)
    }

    // --- Forbidden-pattern audit ---

    @Test
    fun copy_hasNoForbiddenPatterns() {
        val allCopyStrings = listOf(
            NotificationCopy.nudgeTitle(listName = "Late night"),
            NotificationCopy.nudgeBody(listName = "Late night", dueCount = 3),
            NotificationCopy.nudgeBody(listName = "Late night", dueCount = 1),
            NotificationCopy.nudgeNamedBody(firstName = "Kai"),
            NotificationCopy.callActionLabel(firstName = "Kai"),
            NotificationCopy.PUBLIC_TITLE,
            NotificationCopy.PUBLIC_BODY,
            NotificationCopy.LABEL_ADD_TIME,
            NotificationCopy.LABEL_MUTED_BADGE,
            NotificationCopy.CHANNEL_LABEL_LIST_PROMPTS,
            NotificationCopy.CHANNEL_DESC_LIST_PROMPTS,
            NotificationCopy.SUMMARY_NO_DAYS,
            NotificationCopy.SUMMARY_NO_TIME,
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
        }
    }
}
