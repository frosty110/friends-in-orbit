package app.orbit.domain.smart

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rule schema for smart lists (SMART-01). Five subtypes cover the onboarding +
 * bulk-picker feature set. Stored as JSON in `ListEntity.smartRuleJson`; decoded
 * at engine construction via [app.orbit.domain.JsonProvider.json] (configured with
 * `classDiscriminator = "type"`).
 *
 * Invariants (enforced by [SmartListEngine]):
 *   - Ignored contacts NEVER appear in any rule's output (SMART-05)
 *   - Zero-call contacts fall into [NeverCalled] only — never into
 *     [CommonlyCalled] or [RarelyCalled] bands (SMART-07)
 *   - [RecentlyAddedNotCalled] uses `ContactEntity.firstSeenByAppAt` as the
 *     "added" timestamp — NEVER Android ContactsContract creation metadata,
 *     which is unreliable across OEMs (SMART-08)
 *
 * Voice rule: membership is read-only (SMART-04) — there is no manual add/remove.
 * To exclude a person, they go through Ignore (IGNORE-04).
 */
@Serializable
sealed class SmartListRule {

    /**
     * Contacts first observed by Orbit within [daysWindow] that have ZERO CallEvents.
     * The onboarding template "Recently added, not called" uses this with
     * `daysWindow = 30` (SMART-02).
     */
    @Serializable
    @SerialName("recentlyAddedNotCalled")
    data class RecentlyAddedNotCalled(val daysWindow: Int = 30) : SmartListRule()

    /**
     * Contacts whose most-recent CallEvent is older than [daysThreshold]. Contacts
     * with no call history are EXCLUDED (a "gap" is not measurable without prior
     * calls — use [NeverCalled] for that cohort).
     */
    @Serializable
    @SerialName("longGap")
    data class LongGap(val daysThreshold: Int) : SmartListRule()

    /**
     * Top-[topPercent] of contacts by call count. Computed over contacts with
     * ≥1 CallEvent (SMART-07). Default 20% matches the bulk-picker convention
     * (PICK-07).
     */
    @Serializable
    @SerialName("commonlyCalled")
    data class CommonlyCalled(val topPercent: Int = 20) : SmartListRule()

    /**
     * Bottom-[bottomPercent] of contacts by call count. Computed over contacts
     * with ≥1 CallEvent (SMART-07). Default 50% matches the bulk-picker convention.
     */
    @Serializable
    @SerialName("rarelyCalled")
    data class RarelyCalled(val bottomPercent: Int = 50) : SmartListRule()

    /**
     * Contacts with zero CallEvent rows. The catch-all for the "I haven't called
     * this person yet" band.
     */
    @Serializable
    @SerialName("neverCalled")
    data object NeverCalled : SmartListRule()
}
