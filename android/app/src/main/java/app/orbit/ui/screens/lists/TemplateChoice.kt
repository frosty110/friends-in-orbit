package app.orbit.ui.screens.lists

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.orbit.R
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.smart.SmartListRule

/**
 * Six-template catalog for the Create List bottom sheet.
 *
 * Order in [Catalog] is locked — the picker grid renders rows in this
 * order, and tests assert on the id ordering.
 *
 * Names and subtitles are string resources (strings_lists.xml); the screen
 * resolves them. [defaultNameRes] is null for "Start from blank".
 *
 * Each entry maps to a single fresh [app.orbit.data.entity.ListEntity] shape:
 *   - Four "static named" templates (Inner orbit, Family, Mentors, Drifted)
 *     attach to the seeded [RuleKind.KEEP_IN_TOUCH] template with their own
 *     [intervalDays], written as the list's override at creation exactly as the
 *     interval slider would write it. They used to share the template's 2-day
 *     default, so "Mentors: Quarterly check-ins" surfaced each mentor every
 *     2 days. Each subtitle now names the rhythm the list actually gets.
 *   - Intervals stay within the slider's 1–60 day range (ADR 0010), which is
 *     why Mentors promises "every couple of months" rather than quarterly.
 *   - "Recently added, not called" is the only SMART entry; it carries
 *     [SmartListRule.RecentlyAddedNotCalled] with the SMART-02 default
 *     `daysWindow = 30`, and KEEP_IN_TOUCH like every other template so its
 *     members surface on the card (a smart list with no cadence surfaced no one).
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
    @StringRes val displayNameRes: Int,
    @StringRes val subtitleRes: Int,
    val iconName: String,
    val type: ListType,
    @StringRes val defaultNameRes: Int?,
    val smartRule: SmartListRule?,
    val ruleKind: RuleKind?,
    /** Keep-in-touch interval written at creation; null keeps the template default (2 days). */
    val intervalDays: Int? = null
) {
    companion object {
        val Catalog: List<TemplateChoice> = listOf(
            TemplateChoice(
                id = "inner_orbit",
                displayNameRes = R.string.lists_template_inner_orbit,
                subtitleRes = R.string.lists_template_inner_orbit_subtitle,
                iconName = "heart",
                type = ListType.STATIC,
                defaultNameRes = R.string.lists_template_inner_orbit,
                smartRule = null,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
                intervalDays = 7
            ),
            TemplateChoice(
                id = "family",
                displayNameRes = R.string.lists_template_family,
                subtitleRes = R.string.lists_template_family_subtitle,
                iconName = "users",
                type = ListType.STATIC,
                defaultNameRes = R.string.lists_template_family,
                smartRule = null,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
                intervalDays = 14
            ),
            TemplateChoice(
                id = "mentors",
                displayNameRes = R.string.lists_template_mentors,
                subtitleRes = R.string.lists_template_mentors_subtitle,
                // Spec'd `compass` not in assets/icons/ — `star` reads as guidance.
                iconName = "star",
                type = ListType.STATIC,
                defaultNameRes = R.string.lists_template_mentors,
                smartRule = null,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
                intervalDays = 60
            ),
            TemplateChoice(
                id = "drifted",
                displayNameRes = R.string.lists_template_drifted,
                subtitleRes = R.string.lists_template_drifted_subtitle,
                // Spec'd `wind` not in assets/icons/ — clock-counter-clockwise
                // carries the "time since last call" sense better than wind anyway.
                iconName = "clock-counter-clockwise",
                type = ListType.STATIC,
                defaultNameRes = R.string.lists_template_drifted,
                smartRule = null,
                ruleKind = RuleKind.KEEP_IN_TOUCH,
                intervalDays = 30
            ),
            TemplateChoice(
                id = "recently_added_not_called",
                displayNameRes = R.string.lists_template_recently_added,
                subtitleRes = R.string.lists_template_recently_added_subtitle,
                iconName = "shuffle-angular",
                type = ListType.SMART,
                defaultNameRes = R.string.lists_template_recently_added,
                smartRule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
                ruleKind = RuleKind.KEEP_IN_TOUCH
            ),
            TemplateChoice(
                id = "blank",
                displayNameRes = R.string.lists_template_blank,
                subtitleRes = R.string.lists_template_blank_subtitle,
                iconName = "plus",
                type = ListType.STATIC,
                defaultNameRes = null,
                smartRule = null,
                ruleKind = RuleKind.KEEP_IN_TOUCH
            )
        )
    }
}
