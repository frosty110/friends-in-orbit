package app.orbit.data.mappers

import app.orbit.data.entity.CallSource
import app.orbit.domain.callEventFixture
import app.orbit.domain.contactFixture
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.Test

/**
 * [withCallStats] average must
 * exclude zero-duration events. MANUAL marks carry `durationSeconds == 0`
 * (unverified connections, not measured calls); averaging them in dragged a
 * 22-minute average down to 19 after a single logged connection. They still
 * count toward `totalCalls` and drive `lastCalledLabel`.
 */
class ContactMapperTest {

    private val now: Instant = Instant.parse("2026-06-09T12:00:00Z")

    @Test
    fun `avg length excludes zero-duration manual marks`() {
        val base = contactFixture(id = 1L).toUiContact()
        val events = listOf(
            // Two measured 22-minute calls…
            callEventFixture(
                id = 1L, contactId = 1L,
                occurredAt = now.minusSeconds(7_200L), durationSeconds = 1_320,
            ),
            callEventFixture(
                id = 2L, contactId = 1L,
                occurredAt = now.minusSeconds(3_600L), durationSeconds = 1_320,
            ),
            // …and one manual mark. Averaged in, this read "19 min".
            callEventFixture(
                id = 3L, contactId = 1L,
                occurredAt = now, durationSeconds = 0,
                source = CallSource.MANUAL,
            ),
        )
        val hydrated = base.withCallStats(events, now)
        assertEquals("22 min", hydrated.avgLengthLabel)
        // The mark still counts as a call and still moves "last called".
        assertEquals(3, hydrated.totalCalls)
        assertEquals("today", hydrated.lastCalledLabel)
    }

    @Test
    fun `manual-only history yields the no-measured-calls dash`() {
        val base = contactFixture(id = 1L).toUiContact()
        val events = listOf(
            callEventFixture(
                id = 1L, contactId = 1L,
                occurredAt = now, durationSeconds = 0,
                source = CallSource.MANUAL,
            ),
            callEventFixture(
                id = 2L, contactId = 1L,
                occurredAt = now.minusSeconds(60L), durationSeconds = 0,
                source = CallSource.MANUAL,
            ),
        )
        val hydrated = base.withCallStats(events, now)
        assertEquals("—", hydrated.avgLengthLabel)
        assertEquals(2, hydrated.totalCalls)
    }

    @Test
    fun `empty event list leaves the contact untouched`() {
        // assertSame, not assertEquals — Contact.equals compares id only.
        val base = contactFixture(id = 1L).toUiContact()
        assertSame(base, base.withCallStats(emptyList(), now))
    }
}
