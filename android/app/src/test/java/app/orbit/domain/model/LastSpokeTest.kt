package app.orbit.domain.model

import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "When you last spoke" (HOME-3, 2026-11-10): the newest call that connected
 * or connection logged by hand, never an attempt. Home's Next up line took the
 * newest event of any kind until then, so an unanswered dial read "Spoke
 * today" there while the card's line did not.
 */
class LastSpokeTest {

    private val now = Instant.parse("2026-11-10T12:00:00Z")

    private fun event(id: Long, hoursAgo: Long, source: CallSource) = CallEventEntity(
        id = id,
        contactId = 1L,
        occurredAt = now.minusSeconds(hoursAgo * 3_600L),
        direction = CallDirection.OUTGOING,
        durationSeconds = if (source == CallSource.CALL_LOG) 600 else 0,
        source = source,
    )

    @Test
    fun `a connected call and a logged connection are connections, an attempt is not`() {
        assertTrue(event(1L, 1L, CallSource.CALL_LOG).isConnection)
        assertTrue(event(2L, 1L, CallSource.MANUAL).isConnection)
        assertFalse(event(3L, 1L, CallSource.ATTEMPT).isConnection)
    }

    @Test
    fun `a newer attempt does not move when you last spoke`() {
        val spoke = event(1L, 72L, CallSource.CALL_LOG)
        val events = listOf(event(2L, 1L, CallSource.ATTEMPT), spoke, event(3L, 2L, CallSource.ATTEMPT))
        assertEquals(spoke.occurredAt, events.lastSpokeAt())
    }

    @Test
    fun `the newest connection wins, whichever kind it is`() {
        val logged = event(2L, 24L, CallSource.MANUAL)
        assertEquals(logged.occurredAt, listOf(event(1L, 72L, CallSource.CALL_LOG), logged).lastSpokeAt())
    }

    @Test
    fun `attempts alone, or nothing, mean nobody has spoken`() {
        assertNull(listOf(event(1L, 1L, CallSource.ATTEMPT), event(2L, 30L, CallSource.ATTEMPT)).lastSpokeAt())
        assertNull(emptyList<CallEventEntity>().lastSpokeAt())
    }
}
