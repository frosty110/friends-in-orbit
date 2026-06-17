package app.orbit.domain.clock

import java.time.Duration
import java.time.Instant

/**
 * Injectable fake [Clock] for JVM unit tests. Starts at a deterministic default
 * (`2026-01-01T12:00:00Z`) so tests can assert absolute times without depending
 * on wall-clock.
 *
 * `instant` has a private setter — mutation goes through [advance] or [set],
 * making test intent obvious at the call site.
 */
class TestClock(initial: Instant = Instant.parse("2026-01-01T12:00:00Z")) : Clock {
    var instant: Instant = initial
        private set

    override fun now(): Instant = instant

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }

    fun set(instant: Instant) {
        this.instant = instant
    }
}
