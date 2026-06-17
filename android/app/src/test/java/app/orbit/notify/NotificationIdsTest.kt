package app.orbit.notify

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM collision-freedom test for [NotificationIds].
 *
 * Verifies RESEARCH Pitfall 5 guarantees:
 *   - listPrompt and followUp bases never overlap for any ID in the realistic range
 *   - Two distinct listIds yield distinct listPrompt IDs
 *   - Base offsets are exactly 2_000_000 and 3_000_000
 */
class NotificationIdsTest {

    @Test
    fun listPrompt_and_followUp_neverCollide_in_1_to_100() {
        for (n in 1L..100L) {
            assertNotEquals(
                "listPrompt($n) must not equal followUp($n)",
                NotificationIds.listPrompt(n),
                NotificationIds.followUp(n),
            )
        }
    }

    @Test
    fun listPrompt_usesBaseOffset_2_000_000() {
        // id=1 → 2_000_001; id=500_000 → 2_500_000
        assertEquals(2_000_001, NotificationIds.listPrompt(1L))
        assertEquals(2_000_042, NotificationIds.listPrompt(42L))
    }

    @Test
    fun followUp_usesBaseOffset_3_000_000() {
        // id=1 → 3_000_001; id=99 → 3_000_099
        assertEquals(3_000_001, NotificationIds.followUp(1L))
        assertEquals(3_000_099, NotificationIds.followUp(99L))
    }

    @Test
    fun twoDistinctListIds_yieldDistinct_listPromptIds() {
        val id1 = NotificationIds.listPrompt(1L)
        val id2 = NotificationIds.listPrompt(2L)
        assertNotEquals("Distinct listIds must yield distinct notification IDs", id1, id2)
    }

    @Test
    fun twoDistinctContactIds_yieldDistinct_followUpIds() {
        val id1 = NotificationIds.followUp(1L)
        val id2 = NotificationIds.followUp(2L)
        assertNotEquals("Distinct contactIds must yield distinct follow-up IDs", id1, id2)
    }
}
