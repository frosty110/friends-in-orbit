package app.orbit.ui.screens.contact.sections

import app.orbit.domain.rule.RuleParams
import kotlin.test.assertEquals
import org.junit.Test

/**
 * The per-contact override slider must commit through
 * [RuleParams.KeepInTouch.withIntervalHours] so BOTH cooldown bounds move with
 * the chosen interval. Committing `cooldownMinHours` alone let the default 336h
 * cap silently turn "aim for every 30 days" into every 14 — the same slider lie
 * fixed at list level.
 */
class RuleOverrideSectionTest {

    @Test
    fun `override interval commit moves both cooldown bounds`() {
        val tuned = commitOverrideInterval(RuleParams.KeepInTouch(), days = 30)
        assertEquals(30 * 24, tuned.cooldownMinHours)
        assertEquals(
            30 * 24 + RuleParams.KeepInTouch.SKIP_HEADROOM_HOURS,
            tuned.cooldownMaxHours,
        )
    }

    @Test
    fun `override interval commit floors at one day`() {
        val tuned = commitOverrideInterval(RuleParams.KeepInTouch(), days = 0)
        assertEquals(24, tuned.cooldownMinHours)
        assertEquals(24 + RuleParams.KeepInTouch.SKIP_HEADROOM_HOURS, tuned.cooldownMaxHours)
    }

    @Test
    fun `override interval commit preserves untouched fields`() {
        val base = RuleParams.KeepInTouch(escalationFactor = 2.0, skipPenaltyHours = 12)
        val tuned = commitOverrideInterval(base, days = 7)
        assertEquals(2.0, tuned.escalationFactor)
        assertEquals(12, tuned.skipPenaltyHours)
        assertEquals(base.shortCallThresholdSeconds, tuned.shortCallThresholdSeconds)
    }

    // LIST-30: the per-person schedule has no rhythm choice either. An
    // override stored as Late night (possible before 2026-10-07) becomes Keep
    // in touch at the chosen interval when its slider moves.
    @Test
    fun `a late night override becomes keep in touch at the chosen interval`() {
        val tuned = commitOverrideInterval(RuleParams.LateNight(), days = 10)
        assertEquals(10 * 24, tuned.cooldownMinHours)
        assertEquals(10 * 24 + RuleParams.KeepInTouch.SKIP_HEADROOM_HOURS, tuned.cooldownMaxHours)
        assertEquals(RuleParams.KeepInTouch().incomingCallResetPct, tuned.incomingCallResetPct)
    }
}
