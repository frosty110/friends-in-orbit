package app.orbit.domain.rule

import java.time.Duration

/**
 * Cooldown applied to a reach-out **attempt** ([app.orbit.data.entity.CallSource.ATTEMPT])
 * — an outgoing call that did not connect (voicemail / no answer).
 *
 * An attempt is not a connection: it should neither nag you to redial tomorrow
 * nor push the contact out for the full template cadence as if you'd actually
 * talked. So every engine short-circuits to `lastCall + [DURATION]` when the
 * latest event is an attempt, regardless of template (Keep in touch / Late
 * night / Energize all share this flat value — predictable beats per-template
 * scaling here).
 */
object AttemptCooldown {
    /** Flat resurface delay after an attempt — a few days, then they're due again. */
    val DURATION: Duration = Duration.ofDays(3)
}
