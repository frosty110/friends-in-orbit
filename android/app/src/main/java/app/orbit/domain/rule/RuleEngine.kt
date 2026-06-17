package app.orbit.domain.rule

import app.orbit.domain.clock.Clock
import java.time.Instant

/**
 * Strategy interface for rule-based contact surfacing (DOM-02). Three sealed
 * implementations — [KeepInTouchEngine], [LateNightEngine], [EnergizeEngine] — each
 * reads its own [RuleParams] subtype loaded from `RuleTemplateEntity.paramsJson`.
 *
 * Engines MUST NOT call `Instant.now()` or `System.currentTimeMillis()` directly;
 * time is read via the injected [Clock]. Enforced by project convention.
 */
sealed interface RuleEngine {
    /**
     * Returns the [Instant] at which the given contact should next surface, or
     * `null` when the engine determines the contact is never due in the supplied
     * context (e.g. ignored contact — engines short-circuit before cooldown math).
     */
    fun nextDue(contact: ContactSnapshot, ctx: RuleContext, clock: Clock): Instant?
}
