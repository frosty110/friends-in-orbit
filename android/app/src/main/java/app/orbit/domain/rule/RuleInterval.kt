package app.orbit.domain.rule

/*
 * LIST-30: one "How often" control for every list.
 *
 * Keep in touch, Late night and Energize are one algorithm with different
 * numbers (LateNightEngine and EnergizeEngine say so: their bodies are
 * KeepInTouchEngine's, kept in lockstep). So every list's rhythm can be read,
 * and set, as one number: its base interval, how long after an ordinary call a
 * person comes up again. The templates only set the starting numbers; List
 * settings and the Lists row show the interval. The two helpers below are the
 * whole of that idea, so the screens never decide it for themselves.
 */

/**
 * How long after an ordinary call a person on this list comes up again, in
 * hours: `cooldownMinHours`, the base every engine starts from before skips,
 * short calls and incoming calls move it. 72h for Late night ("every 3 days"),
 * 24h for Energize ("every day"), the chosen interval for Keep in touch.
 */
val RuleParams.baseIntervalHours: Int
    get() = when (this) {
        is RuleParams.KeepInTouch -> cooldownMinHours
        is RuleParams.LateNight -> cooldownMinHours
        is RuleParams.Energize -> cooldownMinHours
    }

/**
 * The parameters a list runs once the user moves "How often" to [hours]. The
 * result is always Keep in touch, built through its single entry point
 * [RuleParams.KeepInTouch.withIntervalHours], which moves both cooldown bounds
 * together (never `cooldownMinHours` alone: the rule engine README's
 * "Interval honesty").
 *
 * - **Keep in touch** keeps every other number it has (skip penalty, reset
 *   percentages), as the slider always did.
 * - **Late night and Energize** become Keep in touch at [hours] with Keep in
 *   touch's own numbers, so what changes besides the interval is: the skip
 *   headroom becomes [RuleParams.KeepInTouch.SKIP_HEADROOM_HOURS] above the
 *   interval (Late night allowed 432h above its 72h, Energize 144h above its
 *   24h); a Later pushes someone back 24h (Late night 48h, Energize 12h); a
 *   short call takes 25% off the wait (Late night 20%, Energize 30%) and an
 *   incoming call 50% (Late night 40%, Energize 60%). The short-call threshold
 *   is 60 seconds in all three, so it does not change. The escalation factor
 *   becomes Keep in touch's 1.5; no engine reads it.
 * - **Null** (nothing configured, or a blob that no longer decodes) starts
 *   from Keep in touch's defaults the same way, rather than refusing the move.
 *
 * Only the parameters change. The caller writes them with the Keep in touch
 * template (a list surfaces nothing without a template, and the engine runs
 * whichever subtype the parameters decode to). Nothing here touches the
 * list's people or each person's next turn (`nextDueAt`, `skipCount`): as with
 * any move of the slider, the new interval applies from that person's next
 * call, Later or Sooner. The engines stay; a list nobody moves keeps running
 * Late night or Energize.
 */
fun RuleParams?.toKeepInTouchEvery(hours: Int): RuleParams.KeepInTouch = when (this) {
    is RuleParams.KeepInTouch -> withIntervalHours(hours)
    is RuleParams.LateNight, is RuleParams.Energize, null -> RuleParams.KeepInTouch().withIntervalHours(hours)
}
