package app.orbit.domain.clock

import java.time.Instant

/**
 * Injectable time source for the domain layer. Every class under `app.orbit.domain.*`
 * that reads the current time MUST depend on `Clock`, never on `Instant.now()` or
 * `System.currentTimeMillis()` directly. Enforced by project convention.
 *
 * Production wiring: [SystemClock]. Test wiring: `TestClock` in
 * `android/app/src/test/java/app/orbit/domain/clock/TestClock.kt`.
 */
interface Clock {
    fun now(): Instant
}
