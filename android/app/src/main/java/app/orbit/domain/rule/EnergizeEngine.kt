package app.orbit.domain.rule

import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import app.orbit.domain.clock.Clock
import java.time.Duration
import java.time.Instant

/**
 * Shorter-cooldown, higher-escalation engine for frequently-reached contacts —
 * people whose cadence is natural and who the user wants to hear from often. The
 * `shortCallResetPct` (30%) and `incomingCallResetPct` (60%) are the most
 * aggressive of the three presets, reflecting that a 30-second call from an
 * "Energize" contact is meaningful signal that the bond is active.
 *
 * Algorithm body is identical to [KeepInTouchEngine]; params differ
 * (cooldownMin=24h, cooldownMax=168h/7d, escalationFactor=1.75). Duplication over
 * abstraction is deliberate — see [LateNightEngine] rationale.
 */
class EnergizeEngine(private val params: RuleParams.Energize) : RuleEngine {

    override fun nextDue(contact: ContactSnapshot, ctx: RuleContext, clock: Clock): Instant? {
        if (contact.isIgnored) return null

        val now = clock.now()

        contact.pausedUntil?.let { pausedUntil ->
            if (pausedUntil.isAfter(now)) return pausedUntil
        }

        val lastCall = ctx.lastCallAt ?: return now

        // Attempt — reach-out that didn't connect. Flat short cooldown, never the
        // full cadence (see KeepInTouchEngine step 3b; bodies stay in lockstep).
        if (ctx.lastCallSource == CallSource.ATTEMPT) {
            return lastCall.plus(AttemptCooldown.DURATION)
        }

        val baseCooldownHours = params.cooldownMinHours.toLong()
        val skipExtension = params.skipPenaltyHours.toLong() * ctx.skipCount
        // Cap bounds skip escalation only — never the user's base cadence
        // (see KeepInTouchEngine step 4 comment; bodies stay in lockstep).
        val escalationCapMinutes = maxOf(params.cooldownMaxHours.toLong(), baseCooldownHours) * 60L
        val rawCooldownMinutes = (baseCooldownHours + skipExtension) * 60L
        val cappedCooldownMinutes = minOf(rawCooldownMinutes, escalationCapMinutes)

        val isRealCall = ctx.lastCallSource != CallSource.MANUAL && ctx.lastCallDurationSec > 0

        val shortCallReductionMinutes =
            if (isRealCall && ctx.lastCallDurationSec < params.shortCallThresholdSeconds) {
                cappedCooldownMinutes * params.shortCallResetPct / 100L
            } else 0L

        val incomingReductionMinutes =
            if (isRealCall && ctx.lastCallDirection == CallDirection.INCOMING) {
                cappedCooldownMinutes * params.incomingCallResetPct / 100L
            } else 0L

        val adjustedCooldownMinutes =
            (cappedCooldownMinutes - shortCallReductionMinutes - incomingReductionMinutes)
                .coerceAtLeast(0L)

        return lastCall.plus(Duration.ofMinutes(adjustedCooldownMinutes))
    }
}
