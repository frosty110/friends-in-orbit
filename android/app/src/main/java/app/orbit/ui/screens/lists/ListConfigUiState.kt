package app.orbit.ui.screens.lists

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.rule.baseIntervalHours
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeSchedule

/**
 * List Configuration state contract.
 *
 * Sealed interface with three variants — every variant is `@Immutable`. Replaces
 * the original read-only stub: [Ready] now carries the full editable surface
 * (ruleKind + resolved [RuleParams] for STATIC, [SmartListRule] for SMART,
 * the time-of-day window, notifications flag, and a Flow-driven members
 * preview). Writes
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
     * `ruleParams` is null when the list has no `ruleTemplateId` AND no
     * `ruleParamsOverrideJson` (a partially-configured row), or when the stored
     * override no longer decodes; How often then says the list has no rhythm
     * yet and lets the slider set one.
     *
     * One value is derived once, at construction (ARCH-02), so the body and
     * the tests read the same answer: [intervalHours], the base interval How
     * often shows for every rule type (LIST-30: 72h for Late night, 24h for
     * Energize). It is a body property, so it stays out of the constructor and
     * out of `equals`: it follows from the fields.
     *
     * No active-hours window: since 2026-10-08 a list's nudge timing is
     * [nudgeSchedule] alone (LIST-25), and the Time of day it was read back as
     * is gone.
     */
    @Immutable
    data class Ready(
        val id: Long,
        val name: String,
        val type: ListType,
        val ruleKind: RuleKind?,
        val ruleParams: RuleParams?,
        val smartRule: SmartListRule?,
        val notificationsEnabled: Boolean,
        val nudgeSchedule: NudgeSchedule?,
        val members: List<ListConfigContactSnapshot>,
    ) : ListConfigUiState {
        val intervalHours: Int? = ruleParams?.baseIntervalHours
    }

    @Immutable data object NotFound : ListConfigUiState

    /** LIST-22: the list could not be read; shown with Try again (rubric D6). */
    @Immutable data object Error : ListConfigUiState
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
