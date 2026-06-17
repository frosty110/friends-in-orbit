package app.orbit.domain.rule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tunable parameters per rule template (DOM-03). Sealed + `@Serializable` + one
 * `@SerialName`-tagged data class per engine — stored as JSON in
 * `RuleTemplateEntity.paramsJson` and decoded by [engineFor].
 *
 * Adding a new field to an existing subtype is schema-safe (v1 defaults ship;
 * `ignoreUnknownKeys = true` covers forward-compat). Removing a field is a
 * breaking change requiring a migration pass over stored rows.
 *
 * Defaults are locked to the rule-engine shape's recommended values
 * (KeepInTouch: 48h/336h/1.5/24h/60s/25%/50%, etc.).
 */
@Serializable
sealed class RuleParams {

    @Serializable
    @SerialName("keepInTouch")
    data class KeepInTouch(
        val cooldownMinHours: Int = 48,
        val cooldownMaxHours: Int = 336,
        val escalationFactor: Double = 1.5,
        val skipPenaltyHours: Int = 24,
        val shortCallThresholdSeconds: Int = 60,
        val shortCallResetPct: Int = 25,
        val incomingCallResetPct: Int = 50,
    ) : RuleParams() {

        /**
         * Returns a copy tuned to the user's "aim for every N" interval with
         * both cooldown bounds kept consistent — the single honest entry point
         * for the List Configuration interval slider.
         *
         * Why both bounds must move together: the engine computes
         * `min(cooldownMinHours + skipPenaltyHours * skips, cooldownMaxHours)`.
         * `cooldownMaxHours` exists to bound skip-driven escalation, but
         * because the clamp applies to the whole sum it also caps the base
         * cadence. Committing only `cooldownMinHours` let the default 336h cap
         * silently turn "aim for every 30 days" into every 14 days.
         *
         * Chosen semantics:
         *  - `cooldownMinHours = interval` — with no skips and an ordinary
         *    call, the person surfaces exactly the chosen interval after the
         *    last call. The cap can never force them to surface more often
         *    than the rhythm the user chose.
         *  - `cooldownMaxHours = interval + SKIP_HEADROOM_HOURS` — skips keep
         *    the same bounded push-back room (the template default span,
         *    336h − 48h = 288h) at any interval, instead of going dead when
         *    the interval exceeds the old fixed cap.
         *  - Short-call and incoming-call reductions still pull people
         *    earlier — those are deliberate signals, untouched here.
         */
        fun withIntervalHours(hours: Int): KeepInTouch {
            val interval = hours.coerceAtLeast(1)
            return copy(
                cooldownMinHours = interval,
                cooldownMaxHours = interval + SKIP_HEADROOM_HOURS,
            )
        }

        companion object {
            /**
             * Skip-escalation headroom kept above the chosen interval — the
             * span between the template defaults (336h max − 48h min = 288h,
             * 12 days). Constant so the headroom stays the same wherever the
             * interval slider lands.
             */
            const val SKIP_HEADROOM_HOURS: Int = 288
        }
    }

    @Serializable
    @SerialName("lateNight")
    data class LateNight(
        val cooldownMinHours: Int = 72,
        val cooldownMaxHours: Int = 504,
        val escalationFactor: Double = 1.25,
        val skipPenaltyHours: Int = 48,
        val shortCallThresholdSeconds: Int = 60,
        val shortCallResetPct: Int = 20,
        val incomingCallResetPct: Int = 40,
    ) : RuleParams()

    @Serializable
    @SerialName("energize")
    data class Energize(
        val cooldownMinHours: Int = 24,
        val cooldownMaxHours: Int = 168,
        val escalationFactor: Double = 1.75,
        val skipPenaltyHours: Int = 12,
        val shortCallThresholdSeconds: Int = 60,
        val shortCallResetPct: Int = 30,
        val incomingCallResetPct: Int = 60,
    ) : RuleParams()
}
