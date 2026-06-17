package app.orbit.domain.rule

import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import app.orbit.domain.clock.TestClock
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LateNightEngineTest {

    private val T0 = Instant.parse("2026-01-01T12:00:00Z")
    private val params = RuleParams.LateNight()   // all defaults
    private val engine = LateNightEngine(params)

    private fun snapshot(
        id: Long = 1L,
        ignored: Boolean = false,
        pausedUntil: Instant? = null,
    ) = ContactSnapshot(id = id, isIgnored = ignored, pausedUntil = pausedUntil)

    private fun ctx(
        lastCall: Instant? = null,
        durationSec: Int = 0,
        direction: CallDirection? = null,
        source: CallSource? = null,
        skipCount: Int = 0,
    ) = RuleContext(
        lastCallAt = lastCall,
        lastCallDurationSec = durationSec,
        lastCallDirection = direction,
        lastCallSource = source,
        skipCount = skipCount,
        params = params,
    )

    @Test
    fun `cold start no call history is due now`() {
        val clock = TestClock(T0)
        val due = engine.nextDue(snapshot(), ctx(lastCall = null), clock)
        assertEquals(T0, due)
    }

    @Test
    fun `half cooldown is not due yet`() {
        val clock = TestClock(T0)
        clock.advance(Duration.ofHours(params.cooldownMinHours / 2L))
        val due = engine.nextDue(
            snapshot(),
            ctx(lastCall = T0, durationSec = 300, direction = CallDirection.OUTGOING, source = CallSource.CALL_LOG),
            clock,
        )
        assertTrue(due!!.isAfter(clock.now()), "expected $due to be after ${clock.now()}")
    }

    @Test
    fun `over max cooldown is due`() {
        val clock = TestClock(T0)
        clock.advance(Duration.ofHours(params.cooldownMaxHours + 1L))
        val due = engine.nextDue(
            snapshot(),
            ctx(lastCall = T0, durationSec = 300, direction = CallDirection.OUTGOING, source = CallSource.CALL_LOG),
            clock,
        )
        assertTrue(!due!!.isAfter(clock.now()), "expected $due to be on or before ${clock.now()}")
    }

    @Test
    fun `ignored contact returns null`() {
        val clock = TestClock(T0)
        val due = engine.nextDue(snapshot(ignored = true), ctx(lastCall = T0), clock)
        assertNull(due)
    }

    @Test
    fun `paused future returns pausedUntil`() {
        val clock = TestClock(T0)
        val pausedUntil = T0.plus(Duration.ofDays(7))
        val due = engine.nextDue(snapshot(pausedUntil = pausedUntil), ctx(lastCall = T0), clock)
        assertEquals(pausedUntil, due)
    }

    @Test
    fun `short call reduces cooldown by shortCallResetPct`() {
        val clock = TestClock(T0)
        val due = engine.nextDue(
            snapshot(),
            ctx(
                lastCall = T0,
                durationSec = 30,
                direction = CallDirection.OUTGOING,
                source = CallSource.CALL_LOG,
            ),
            clock,
        )
        val baseMinutes = params.cooldownMinHours.toLong() * 60L
        val reduction = baseMinutes * params.shortCallResetPct / 100L
        val expected = T0.plus(Duration.ofMinutes(baseMinutes - reduction))
        assertEquals(expected, due)
    }

    @Test
    fun `incoming call reduces cooldown by incomingCallResetPct`() {
        val clock = TestClock(T0)
        val due = engine.nextDue(
            snapshot(),
            ctx(
                lastCall = T0,
                durationSec = 300,
                direction = CallDirection.INCOMING,
                source = CallSource.CALL_LOG,
            ),
            clock,
        )
        val baseMinutes = params.cooldownMinHours.toLong() * 60L
        val reduction = baseMinutes * params.incomingCallResetPct / 100L
        val expected = T0.plus(Duration.ofMinutes(baseMinutes - reduction))
        assertEquals(expected, due)
    }

    @Test
    fun `MANUAL source call is ignored for adjustments`() {
        val clock = TestClock(T0)
        val due = engine.nextDue(
            snapshot(),
            ctx(
                lastCall = T0,
                durationSec = 30,
                direction = CallDirection.INCOMING,
                source = CallSource.MANUAL,
            ),
            clock,
        )
        val expected = T0.plus(Duration.ofHours(params.cooldownMinHours.toLong()))
        assertEquals(expected, due)
    }

    @Test
    fun `skip count extends cooldown by skipPenaltyHours per skip`() {
        val clock = TestClock(T0)
        val due = engine.nextDue(
            snapshot(),
            ctx(
                lastCall = T0,
                durationSec = 300,
                direction = CallDirection.OUTGOING,
                source = CallSource.CALL_LOG,
                skipCount = 2,
            ),
            clock,
        )
        // LateNight: base=72, penalty=48, skipCount=2 -> 72 + 96 = 168h (under max=504)
        val expected = T0.plus(Duration.ofHours(params.cooldownMinHours.toLong() + 2L * params.skipPenaltyHours.toLong()))
        assertEquals(expected, due)
    }
}
