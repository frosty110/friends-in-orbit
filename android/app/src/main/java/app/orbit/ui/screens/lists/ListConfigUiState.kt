package app.orbit.ui.screens.lists

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.rule.baseIntervalHours
import app.orbit.domain.smart.SmartListRule
import app.orbit.notify.NudgeSchedule
import java.time.LocalTime

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
     * Two values are derived once, at construction (ARCH-02), so the body and
     * the tests read the same answer: [intervalHours], the base interval How
     * often shows for every rule type (LIST-24: 72h for Late night, 24h for
     * Energize), and [timeOfDay], the stored window read back as a part of the
     * day or a custom window (LIST-25). Both are body properties, so they stay
     * out of the constructor and out of `equals`: they follow from the fields.
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
    ) : ListConfigUiState {
        val intervalHours: Int? = ruleParams?.baseIntervalHours
        val timeOfDay: TimeOfDay = timeOfDayFor(activeHoursStart, activeHoursEnd)
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
