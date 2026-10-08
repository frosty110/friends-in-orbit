package app.orbit.domain.rule

import kotlin.test.assertEquals
import org.junit.Test

/**
 * LIST-30: every list's rhythm is one number, its base interval, and moving
 * "How often" makes the list Keep in touch at the chosen interval through
 * `withIntervalHours`. Pinned here so the screens (List settings, the Lists
 * row) cannot decide either part for themselves.
 */
class RuleIntervalTest {

    @Test
    fun the_base_interval_is_each_engines_cooldown_floor() {
        assertEquals(48, RuleParams.KeepInTouch().baseIntervalHours)
        assertEquals(14 * 24, RuleParams.KeepInTouch().withIntervalHours(14 * 24).baseIntervalHours)
        assertEquals(72, RuleParams.LateNight().baseIntervalHours, "Late night: every 3 days")
        assertEquals(24, RuleParams.Energize().baseIntervalHours, "Energize: every day")
    }

    @Test
    fun late_night_becomes_keep_in_touch_with_keep_in_touchs_numbers() {
        val moved = RuleParams.LateNight().toKeepInTouchEvery(10 * 24)
        assertEquals(RuleParams.KeepInTouch().withIntervalHours(10 * 24), moved)
        // Spelled out, so a change to the defaults is a visible decision.
        assertEquals(240, moved.cooldownMinHours)
        assertEquals(240 + RuleParams.KeepInTouch.SKIP_HEADROOM_HOURS, moved.cooldownMaxHours)
        assertEquals(24, moved.skipPenaltyHours, "Late night's was 48")
        assertEquals(25, moved.shortCallResetPct, "Late night's was 20")
        assertEquals(50, moved.incomingCallResetPct, "Late night's was 40")
        assertEquals(60, moved.shortCallThresholdSeconds, "the same in every engine")
    }

    @Test
    fun energize_becomes_keep_in_touch_with_keep_in_touchs_numbers() {
        val moved = RuleParams.Energize().toKeepInTouchEvery(24)
        assertEquals(RuleParams.KeepInTouch().withIntervalHours(24), moved)
        assertEquals(24, moved.skipPenaltyHours, "Energize's was 12")
        assertEquals(25, moved.shortCallResetPct, "Energize's was 30")
        assertEquals(50, moved.incomingCallResetPct, "Energize's was 60")
    }

    @Test
    fun keep_in_touch_keeps_its_own_numbers_and_moves_both_bounds() {
        val tuned = RuleParams.KeepInTouch(skipPenaltyHours = 36, shortCallResetPct = 10, incomingCallResetPct = 70)
        val moved = tuned.toKeepInTouchEvery(30 * 24)
        assertEquals(tuned.withIntervalHours(30 * 24), moved)
        assertEquals(36, moved.skipPenaltyHours)
        assertEquals(30 * 24 + RuleParams.KeepInTouch.SKIP_HEADROOM_HOURS, moved.cooldownMaxHours)
    }

    @Test
    fun nothing_readable_starts_from_keep_in_touchs_defaults() {
        val params: RuleParams? = null
        assertEquals(RuleParams.KeepInTouch().withIntervalHours(7 * 24), params.toKeepInTouchEvery(7 * 24))
    }
}
