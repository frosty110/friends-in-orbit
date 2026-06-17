package app.orbit.domain.clock

import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ClockTest {

    @Test
    fun `TestClock default initial is 2026-01-01T12 UTC`() {
        val clock = TestClock()
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), clock.now())
    }

    @Test
    fun `TestClock advance by 1h moves now forward`() {
        val clock = TestClock(Instant.parse("2026-01-01T12:00:00Z"))
        clock.advance(Duration.ofHours(1))
        assertEquals(Instant.parse("2026-01-01T13:00:00Z"), clock.now())
    }

    @Test
    fun `TestClock set replaces now`() {
        val clock = TestClock()
        clock.set(Instant.parse("2030-06-15T03:30:00Z"))
        assertEquals(Instant.parse("2030-06-15T03:30:00Z"), clock.now())
    }

    @Test
    fun `TestClock custom initial honored`() {
        val custom = Instant.parse("1999-12-31T23:59:59Z")
        val clock = TestClock(custom)
        assertEquals(custom, clock.now())
    }

    @Test
    fun `SystemClock returns a recent Instant`() {
        val before = Instant.now()
        val observed = SystemClock().now()
        val after = Instant.now()
        assertTrue(!observed.isBefore(before), "observed $observed should not be before $before")
        assertTrue(!observed.isAfter(after), "observed $observed should not be after $after")
    }
}
