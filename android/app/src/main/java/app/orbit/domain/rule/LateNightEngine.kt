package app.orbit.domain.rule

import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import app.orbit.domain.clock.Clock
import java.time.Duration
import java.time.Instant

/**
 * Longer-cooldown engine for contacts reached during late-night active-hours
 * windows. The engine itself does NOT gate on active hours — active-hours filtering
 * is `SurfaceNextUseCase`'s responsibility, which combines this engine's
 * `nextDue` output with the list's `activeHoursStart/End`. Here we only own the
 * cooldown math.
 *
 * Algorithm body is identical to [KeepInTouchEngine]; params differ
 * (cooldownMin=72h, cooldownMax=504h/21d, shorter reset pcts). Duplication over
 * abstraction is deliberate — three engines whose truth tables can each be tested
 * in isolation beats a single parameterised function whose behaviour is harder to
 * reason about at the unit-test level.
 */
class LateNightEngine(private val params: RuleParams.LateNight) : RuleEngine {

    override fun nextDue(contact: ContactSnapshot, ctx: RuleContext, clock: Clock): Instant? {
        if (contact.isIgnored) return null

        val now = clock.now()

        contact.pausedUntil?.let { pausedUntil ->
            if (pausedUntil.isAfter(now)) return pausedUntil
        }

        val lastCall = ctx.lastCallAt ?: return now

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
