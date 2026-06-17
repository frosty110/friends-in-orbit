package app.orbit.ui.screens.lists

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeSchedule
import java.time.LocalTime

/**
 * List Configuration state contract.
 *
 * Sealed interface with three variants — every variant is `@Immutable`. Replaces
 * the original read-only stub: [Ready] now carries the full editable surface
 * (ruleKind + resolved [RuleParams] for STATIC, [SmartListRule] for SMART,
 * active hours, notifications flag, and a Flow-driven members preview). Writes
 * are dispatched via [ListConfigViewModel] save-on-change setters; this state
 * type is read-only — the screen never mutates it locally.
 */
sealed interface ListConfigUiState {
    @Immutable data object Loading : ListConfigUiState

    /**
     * Editable list snapshot. SMART vs STATIC is union-typed:
     *  - SMART carries [smartRule] (non-null) and [members] from
     *    `SmartListEngine.membership(rule)`.
     *  - STATIC carries [ruleKind] + [ruleParams] (resolved per-list override or
     *    template default) and [members] from `ListRepository.observeMembersOfList`
     *    joined to `ContactRepository.observeAll`.
     *
     * `ruleParams` is null only when the list has no `ruleTemplateId` AND no
     * `ruleParamsOverrideJson` (a partially-configured row); the screen renders
     * the rule-template picker but skips the interval slider in that case.
     */
    @Immutable
    data class Ready(
        val id: Long,
        val name: String,
        val type: ListType,
        val ruleKind: RuleKind?,
        val ruleParams: RuleParams?,
        val smartRule: SmartListRule?,
        val activeHoursStart: LocalTime?,
        val activeHoursEnd: LocalTime?,
        val notificationsEnabled: Boolean,
        val nudgeSchedule: NudgeSchedule?,
        val members: List<ListConfigContactSnapshot>,
    ) : ListConfigUiState

    @Immutable data object NotFound : ListConfigUiState
}

/**
 * UI-local contact projection for the Members preview row. Distinct from
 * [app.orbit.domain.rule.ContactSnapshot] (engine-scoped: id/isIgnored/pausedUntil)
 * — this one carries the display fields the preview row needs.
 *
 * Uncapped (the old 20-cap made the count a lie and rows 21+ unremovable).
 * [MembersPreview] collapses long lists visually with an honest
 * "Showing 20 of N" label + "Show all".
 */
@Immutable
data class ListConfigContactSnapshot(
    val id: Long,
    val displayName: String,
    val photoUri: String?,
)
