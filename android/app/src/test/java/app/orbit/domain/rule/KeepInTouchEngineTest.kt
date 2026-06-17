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

class KeepInTouchEngineTest {

    private val T0 = Instant.parse("2026-01-01T12:00:00Z")
    private val params = RuleParams.KeepInTouch()   // all defaults
    private val engine = KeepInTouchEngine(params)

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
                durationSec = 30,                           // under 60s threshold
                direction = CallDirection.OUTGOING,
                source = CallSource.CALL_LOG,
            ),
            clock,
        )
        // baseCooldownMinutes = cooldownMinHours * 60
        // reduction = baseCooldownMinutes * shortCallResetPct / 100
        // expected: T0 + (baseCooldownMinutes - reduction)
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
                durationSec = 300,                          // over threshold — no short-call adjustment
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
                durationSec = 30,                           // would be a short call ...
                direction = CallDirection.INCOMING,         // ... and incoming ...
                source = CallSource.MANUAL,                 // ... but MANUAL source means neither adjustment applies
            ),
            clock,
        )
        // expected: T0 + baseCooldownMinHours (no reductions)
        val expected = T0.plus(Duration.ofHours(params.cooldownMinHours.toLong()))
        assertEquals(expected, due)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Rule-correctness fix — "aim for every N days" must hold end to end.
    // Params built via withIntervalHours (the slider's commit path) so these
    // tests cover the exact values List Configuration writes.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `interval beyond the default cap surfaces at the chosen interval`() {
        // Regression: with only cooldownMinHours committed, the default 336h
        // cap dragged a 30-day interval down to 14 days.
        val tuned = RuleParams.KeepInTouch().withIntervalHours(30 * 24)
        val tunedEngine = KeepInTouchEngine(tuned)
        val clock = TestClock(T0)
        val due = tunedEngine.nextDue(
            snapshot(),
            RuleContext(
                lastCallAt = T0,
                lastCallDurationSec = 300,
                lastCallDirection = CallDirection.OUTGOING,
                lastCallSource = CallSource.CALL_LOG,
                skipCount = 0,
                params = tuned,
            ),
            clock,
        )
        assertEquals(T0.plus(Duration.ofDays(30)), due)
    }

    @Test
    fun `cap never forces surfacing more often than the chosen interval`() {
        val clock = TestClock(T0)
        for (days in listOf(1, 14, 30, 60)) {
            val tuned = RuleParams.KeepInTouch().withIntervalHours(days * 24)
            val tunedEngine = KeepInTouchEngine(tuned)
            val due = tunedEngine.nextDue(
                snapshot(),
                RuleContext(
                    lastCallAt = T0,
                    lastCallDurationSec = 300,
                    lastCallDirection = CallDirection.OUTGOING,
                    lastCallSource = CallSource.CALL_LOG,
                    skipCount = 0,
                    params = tuned,
                ),
                clock,
            )
            assertEquals(
                T0.plus(Duration.ofDays(days.toLong())),
                due,
                "aim for every $days days must surface exactly $days days after the last call",
            )
        }
    }

    @Test
    fun `skip escalation stays bounded to the headroom above the chosen interval`() {
        val tuned = RuleParams.KeepInTouch().withIntervalHours(30 * 24)
        val tunedEngine = KeepInTouchEngine(tuned)
        val clock = TestClock(T0)
        val due = tunedEngine.nextDue(
            snapshot(),
            RuleContext(
                lastCallAt = T0,
                lastCallDurationSec = 300,
                lastCallDirection = CallDirection.OUTGOING,
                lastCallSource = CallSource.CALL_LOG,
                skipCount = 1_000,
                params = tuned,
            ),
            clock,
        )
        val capHours = 30L * 24L + RuleParams.KeepInTouch.SKIP_HEADROOM_HOURS
        assertEquals(T0.plus(Duration.ofHours(capHours)), due)
    }

    @Test
    fun `stale override with cap below the base still honors the base interval`() {
        // Pre-fix slider commits wrote cooldownMinHours alone, leaving rows
        // shaped exactly like this (min 30d, max still the 14d default). The
        // engine clamps the cap to the base so even un-healed rows keep the
        // user's promised cadence.
        val stale = RuleParams.KeepInTouch(cooldownMinHours = 720, cooldownMaxHours = 336)
        val staleEngine = KeepInTouchEngine(stale)
        val clock = TestClock(T0)
        val due = staleEngine.nextDue(
            snapshot(),
            RuleContext(
                lastCallAt = T0,
                lastCallDurationSec = 300,
                lastCallDirection = CallDirection.OUTGOING,
                lastCallSource = CallSource.CALL_LOG,
                skipCount = 0,
                params = stale,
            ),
            clock,
        )
        assertEquals(T0.plus(Duration.ofHours(720)), due)
    }

    @Test
    fun `withIntervalHours moves both bounds and preserves the headroom`() {
        for (hours in listOf(24, 336, 720, 1440)) {
            val tuned = RuleParams.KeepInTouch().withIntervalHours(hours)
            assertEquals(hours, tuned.cooldownMinHours)
            assertEquals(
                hours + RuleParams.KeepInTouch.SKIP_HEADROOM_HOURS,
                tuned.cooldownMaxHours,
            )
            assertTrue(tuned.cooldownMaxHours > tuned.cooldownMinHours)
        }
        // Non-positive input coerces to the 1h floor.
        assertEquals(1, RuleParams.KeepInTouch().withIntervalHours(0).cooldownMinHours)
        // Untuned fields ride along untouched.
        val base = RuleParams.KeepInTouch(skipPenaltyHours = 12, incomingCallResetPct = 10)
        val tuned = base.withIntervalHours(240)
        assertEquals(12, tuned.skipPenaltyHours)
        assertEquals(10, tuned.incomingCallResetPct)
    }

    @Test
    fun `call-quality reductions still pull earlier than the chosen interval`() {
        val tuned = RuleParams.KeepInTouch().withIntervalHours(30 * 24)
        val tunedEngine = KeepInTouchEngine(tuned)
        val clock = TestClock(T0)
        val due = tunedEngine.nextDue(
            snapshot(),
            RuleContext(
                lastCallAt = T0,
                lastCallDurationSec = 300,
                lastCallDirection = CallDirection.INCOMING,
                lastCallSource = CallSource.CALL_LOG,
                skipCount = 0,
                params = tuned,
            ),
            clock,
        )
        // Incoming reset is deliberate pull-earlier signal: 50% of 30 days.
        val baseMinutes = 30L * 24L * 60L
        val expected = T0.plus(Duration.ofMinutes(baseMinutes - baseMinutes * tuned.incomingCallResetPct / 100L))
        assertEquals(expected, due)
        assertTrue(due!!.isBefore(T0.plus(Duration.ofDays(30))))
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
        // base + 2 * skipPenalty (under cooldownMaxHours, no cap)
        val expected = T0.plus(Duration.ofHours(params.cooldownMinHours.toLong() + 2L * params.skipPenaltyHours.toLong()))
        assertEquals(expected, due)
    }
}
