package app.orbit.ui.screens.lists

import androidx.compose.runtime.Immutable
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.smart.SmartListRule

/**
 * Six-template catalog for the Create List bottom sheet.
 *
 * Order in [Catalog] is locked — the picker grid renders rows in this
 * order, and tests assert on the id ordering.
 *
 * Each entry maps to a single fresh [app.orbit.data.entity.ListEntity] shape:
 *   - Four "static named" templates (Inner orbit, Family, Mentors, Drifted) all
 *     attach to the seeded [RuleKind.KEEP_IN_TOUCH] template — that gives them a
 *     sensible cadence the moment the user creates the list.
 *   - "Recently added, not called" is the only SMART entry; it carries
 *     [SmartListRule.RecentlyAddedNotCalled] with the SMART-02 default
 *     `daysWindow = 30`.
 *   - "Start from blank" requires the user to type a name; the rule kind still
 *     defaults to KEEP_IN_TOUCH so the new list is immediately surfaceable.
 *
 * Icon-name notes (verified against `assets/icons/` 2026-04-25):
 *   - `compass` and `wind` from the original design are NOT in the bundle.
 *     Substituted: Mentors → `star` (mentors as guides), Drifted →
 *     `clock-counter-clockwise` (drift as time-since-last-call).
 *   - `heart`, `users`, `shuffle-angular`, `plus` all present and used as-spec'd.
 */
@Immutable
data class TemplateChoice(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val iconName: String,
    val type: ListType,
    val defaultName: String,
    val smartRule: SmartListRule?,
    val ruleKind: RuleKind?,
) {
    companion object {
        val Catalog: List<TemplateChoice> = listOf(
            TemplateChoice(
                id = "inner_orbit",
                displayName = "Inner orbit",
                subtitle = "Closest people, called often.",
                iconName = "heart",
                type = ListType.STATIC,
                defaultName = "Inner orbit",
                smartRule = null,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
            ),
            TemplateChoice(
                id = "family",
                displayName = "Family",
                subtitle = "Steady, longer cadence.",
                iconName = "users",
                type = ListType.STATIC,
                defaultName = "Family",
                smartRule = null,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
            ),
            TemplateChoice(
                id = "mentors",
                displayName = "Mentors",
                subtitle = "Quarterly check-ins.",
                // Spec'd `compass` not in assets/icons/ — `star` reads as guidance.
                iconName = "star",
                type = ListType.STATIC,
                defaultName = "Mentors",
                smartRule = null,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
            ),
            TemplateChoice(
                id = "drifted",
                displayName = "Drifted",
                subtitle = "People you've meant to reach.",
                // Spec'd `wind` not in assets/icons/ — clock-counter-clockwise
                // carries the "time since last call" sense better than wind anyway.
                iconName = "clock-counter-clockwise",
                type = ListType.STATIC,
                defaultName = "Drifted",
                smartRule = null,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
            ),
            TemplateChoice(
                id = "recently_added_not_called",
                displayName = "Recently added, not called",
                subtitle = "Auto-updates as you add people.",
                iconName = "shuffle-angular",
                type = ListType.SMART,
                defaultName = "Recently added, not called",
                smartRule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
                ruleKind = null,
            ),
            TemplateChoice(
                id = "blank",
                displayName = "Start from blank",
                subtitle = "Choose your own cadence.",
                iconName = "plus",
                type = ListType.STATIC,
                defaultName = "",
                smartRule = null,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
            ),
        )
    }
}
