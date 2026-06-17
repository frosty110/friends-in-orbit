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
            "3 due in Late night.",
            NotificationCopy.nudgeBody(listName = "Late night", dueCount = 3),
        )
        assertEquals(
            "1 due in Inner orbit.",
            NotificationCopy.nudgeBody(listName = "Inner orbit", dueCount = 1),
        )
    }

    @Test
    fun nudgeTitle_returnsRawListName() {
        assertEquals("Late night", NotificationCopy.nudgeTitle(listName = "Late night"))
    }

    @Test
    fun followUpCopy_goldenString() {
        assertEquals("Alice called you.", NotificationCopy.followUpTitle(contactName = "Alice"))
        assertEquals("Want to call back?", NotificationCopy.followUpBody())
    }

    // --- Forbidden-pattern audit ---

    @Test
    fun copy_hasNoForbiddenPatterns() {
        val allCopyStrings = listOf(
            NotificationCopy.nudgeTitle(listName = "Late night"),
            NotificationCopy.nudgeBody(listName = "Late night", dueCount = 3),
            NotificationCopy.nudgeBody(listName = "Late night", dueCount = 1),
            NotificationCopy.followUpTitle(contactName = "Alice"),
            NotificationCopy.followUpBody(),
            NotificationCopy.LABEL_ADD_TIME,
            NotificationCopy.LABEL_MUTED_BADGE,
            NotificationCopy.CHANNEL_LABEL_LIST_PROMPTS,
            NotificationCopy.CHANNEL_DESC_LIST_PROMPTS,
            NotificationCopy.CHANNEL_LABEL_FOLLOW_UPS,
            NotificationCopy.CHANNEL_DESC_FOLLOW_UPS,
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
