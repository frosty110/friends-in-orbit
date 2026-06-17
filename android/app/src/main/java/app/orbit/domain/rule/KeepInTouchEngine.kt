package app.orbit.domain.rule

import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import app.orbit.domain.clock.Clock
import java.time.Duration
import java.time.Instant

/**
 * General-purpose "stay in touch" engine. Cooldown grows with [ctx.skipCount] via
 * [RuleParams.KeepInTouch.skipPenaltyHours], clamped to `cooldownMaxHours` (which
 * itself never undercuts `cooldownMinHours` — the user's "aim for every N days"
 * is a floor the cap cannot break), and shrinks for short calls (DOM-09) and
 * incoming calls (DOM-10) per the params' reset percents.
 *
 * Resolution order (DOM-04 is handled externally by `resolveParamsFor` before the
 * engine is constructed):
 *   1. Contact is ignored            -> null (never due)
 *   2. Contact is paused in the future -> return pausedUntil
 *   3. No call history               -> due now (cold start)
 *   4. Otherwise                     -> lastCallAt + adjusted cooldown
 *
 * Manual-source calls (CallSource.MANUAL) and zero-duration events are ignored for
 * cooldown adjustment per DOM-10 (they're treated as missed/declined — not a real
 * contact event).
 */
class KeepInTouchEngine(private val params: RuleParams.KeepInTouch) : RuleEngine {

    override fun nextDue(contact: ContactSnapshot, ctx: RuleContext, clock: Clock): Instant? {
        // 1. Ignored -> never due
        if (contact.isIgnored) return null

        val now = clock.now()

        // 2. Paused -> surface at pausedUntil
        contact.pausedUntil?.let { pausedUntil ->
            if (pausedUntil.isAfter(now)) return pausedUntil
        }

        // 3. Cold start — due immediately
        val lastCall = ctx.lastCallAt ?: return now

        // 4. Compute base cooldown, scaled by skipCount via skipPenaltyHours,
        //    clamped to cooldownMaxHours. Long arithmetic — int-overflow-safe
        //    even against future param bumps (T-02-P03-01).
        //    The cap bounds skip escalation only — it must never undercut the
        //    base cadence the user chose ("aim for every N days" is a floor).
        //    Overrides stored before the interval fix can carry max < min;
        //    clamping the cap to the base keeps the promise for those rows.
        val baseCooldownHours = params.cooldownMinHours.toLong()
        val skipExtension = params.skipPenaltyHours.toLong() * ctx.skipCount
        val escalationCapMinutes = maxOf(params.cooldownMaxHours.toLong(), baseCooldownHours) * 60L
        val rawCooldownMinutes = (baseCooldownHours + skipExtension) * 60L
        val cappedCooldownMinutes = minOf(rawCooldownMinutes, escalationCapMinutes)

        // 5. Short-call (DOM-09) + incoming-call (DOM-10) adjustments only apply to
        //    REAL calls — MANUAL source and zero-duration events are ignored.
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
