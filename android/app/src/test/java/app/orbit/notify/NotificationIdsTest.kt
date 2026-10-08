package app.orbit.notify

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM collision-freedom test for [NotificationIds].
 *
 * Verifies RESEARCH Pitfall 5 guarantees:
 *   - Two distinct listIds yield distinct listPrompt IDs
 *   - The list-nudge base offset is exactly 2_000_000
 */
class NotificationIdsTest {

    @Test
    fun listPrompt_usesBaseOffset_2_000_000() {
        // id=1 → 2_000_001; id=500_000 → 2_500_000
        assertEquals(2_000_001, NotificationIds.listPrompt(1L))
        assertEquals(2_000_042, NotificationIds.listPrompt(42L))
    }

    @Test
    fun twoDistinctListIds_yieldDistinct_listPromptIds() {
        val id1 = NotificationIds.listPrompt(1L)
        val id2 = NotificationIds.listPrompt(2L)
        assertNotEquals("Distinct listIds must yield distinct notification IDs", id1, id2)
    }

    /** NOTIF-16: one per person, in its own range, never a list nudge's id. */
    @Test
    fun postCall_usesBaseOffset_3_000_000_andNeverMeetsANudge() {
        assertEquals(3_000_007, NotificationIds.postCall(7L))
        assertNotEquals(NotificationIds.listPrompt(7L), NotificationIds.postCall(7L))
    }
}
