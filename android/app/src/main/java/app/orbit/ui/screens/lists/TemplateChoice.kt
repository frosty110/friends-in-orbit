package app.orbit.ui.screens.lists

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.orbit.R
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.smart.SmartListRule

/**
 * LIST-29: what New list's first step offers, "Start with", in the order it
 * shows them (the owner's review of 2026-10-07, decision 13: "start from
 * blank at the very top", fewer templates, sorted by how often):
 *
 *  1. "Start from blank" ("Choose your own rhythm."), full width: no
 *     default name, and the slider on the next steps starts at Keep in
 *     touch's default (every 2 days).
 *  2. Three [Group.Rhythm] templates, most frequent first, each tinted a step
 *     along one ramp (`rhythmTemplateTints`): "Inner orbit", about weekly (7
 *     days); "Family", every couple of weeks (14); "Drifted", about monthly
 *     (30). Each subtitle names the rhythm its [intervalDays] gives, so the
 *     tint is decoration, never the only cue.
 *  3. Set apart under a small label, the list that fills itself, "Recently
 *     added, not called" ("Auto-updates as you add people."):
 *     [SmartListRule.RecentlyAddedNotCalled] with the SMART-02 default
 *     `daysWindow = 30`. Its people come from its rule, so the flow has no
 *     People step for it.
 *
 * "Mentors" (every couple of months) went with LIST-29: the How often
 * slider on the next step reaches 60 days for anyone who wants that rhythm.
 *
 * Every list New list makes runs Keep in touch at the interval the How often
 * step ends on (LIST-24: one control, one calculation), so a template carries
 * a starting interval, not a rule kind. `CreateListTemplateCatalogTest` pins
 * the order, the intervals, the smart rule and that Mentors is gone.
 *
 * Names and subtitles are string resources (strings_lists.xml); the screen
 * resolves them. A template's name is also the new list's starting name
 * ([defaultNameRes]; null for blank).
 *
 * Icons are Phosphor names in the bundle: `clock-counter-clockwise` for
 * Drifted (time since the last call; the spec's `wind` is not in the set).
 */
@Immutable
data class TemplateChoice(
    val id: String,
    @StringRes val displayNameRes: Int,
    @StringRes val subtitleRes: Int,
    val iconName: String,
    val group: Group,
    @StringRes val defaultNameRes: Int?,
    /** The rule a list that fills itself runs; null for every regular list. */
    val smartRule: SmartListRule? = null,
    /** The rhythm the How often step starts at; null starts at Keep in touch's default. */
    val intervalDays: Int? = null,
) {
    /** Where the template sits on "Start with", and whether it is tinted. */
    enum class Group { Blank, Rhythm, Smart }

    val isSmart: Boolean get() = smartRule != null

    /** The How often slider's starting point for this template, in hours. */
    val startingIntervalHours: Int get() = intervalDays?.let { it * HOURS_PER_DAY } ?: DEFAULT_INTERVAL_HOURS

    companion object {
        private const val HOURS_PER_DAY = 24

        /** Keep in touch's own starting interval (every 2 days): blank's and the smart list's. */
        val DEFAULT_INTERVAL_HOURS: Int = RuleParams.KeepInTouch().cooldownMinHours

        val Catalog: List<TemplateChoice> = listOf(
            TemplateChoice(
                id = "blank",
                displayNameRes = R.string.lists_template_blank,
                subtitleRes = R.string.lists_template_blank_subtitle,
                iconName = "plus",
                group = Group.Blank,
                defaultNameRes = null,
            ),
            TemplateChoice(
                id = "inner_orbit",
                displayNameRes = R.string.lists_template_inner_orbit,
                subtitleRes = R.string.lists_template_inner_orbit_subtitle,
                iconName = "heart",
                group = Group.Rhythm,
                defaultNameRes = R.string.lists_template_inner_orbit,
                intervalDays = 7,
            ),
            TemplateChoice(
                id = "family",
                displayNameRes = R.string.lists_template_family,
                subtitleRes = R.string.lists_template_family_subtitle,
                iconName = "users",
                group = Group.Rhythm,
                defaultNameRes = R.string.lists_template_family,
                intervalDays = 14,
            ),
            TemplateChoice(
                id = "drifted",
                displayNameRes = R.string.lists_template_drifted,
                subtitleRes = R.string.lists_template_drifted_subtitle,
                iconName = "clock-counter-clockwise",
                group = Group.Rhythm,
                defaultNameRes = R.string.lists_template_drifted,
                intervalDays = 30,
            ),
            TemplateChoice(
                id = "recently_added_not_called",
                displayNameRes = R.string.lists_template_recently_added,
                subtitleRes = R.string.lists_template_recently_added_subtitle,
                iconName = "shuffle-angular",
                group = Group.Smart,
                defaultNameRes = R.string.lists_template_recently_added,
                smartRule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30),
            ),
        )

        fun byId(id: String?): TemplateChoice? = Catalog.firstOrNull { it.id == id }
    }
}
