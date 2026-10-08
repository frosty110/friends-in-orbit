package app.orbit.ui.screens.contact.sections

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.rule.baseIntervalHours
import app.orbit.domain.rule.toKeepInTouchEvery
import app.orbit.ui.components.IntervalDaysPicker
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString

/**
 * CONTACT-03: when Contact detail shows the custom schedule. For two or more
 * lists, where one person can need a rhythm of their own; and whenever a
 * schedule is saved, whatever the lists. A saved schedule keeps running on a
 * single list, so until 2026-10-08 someone left on one list (removed from
 * another, or another archived) had a rhythm the page neither showed nor let
 * them reset. Once reset, it has nothing left to show for one list and goes.
 * The ViewModel and this section's own animation read this one rule.
 */
internal fun showsCustomSchedule(listsOnSize: Int, hasSavedSchedule: Boolean): Boolean =
    listsOnSize >= 2 || hasSavedSchedule

/**
 * Per-contact rule override editor (CONTACT-03).
 *
 * **Visibility gate:** [showsCustomSchedule]: two or more lists, or a
 * schedule saved. The wrapping AnimatedVisibility flips the section in and
 * out as that changes.
 *
 * **No-override branch (`hasOverride == false`):** shows eyebrow "Custom
 * schedule", then how often the person comes up on the list they follow
 * ("Comes up every 14 days, like the rest of Inner orbit."; "their list"
 * under the curtain; "Follows the rhythm of Inner orbit." when the list's
 * rhythm cannot be read), then a Secondary "Set a schedule for this person"
 * button (2026-10-05: it was a Primary "Override", a second accent element
 * and engineering vocabulary).
 *
 * **Override branch (`hasOverride == true`):** List settings' own "How
 * often" control, the shared day wheel ([IntervalDaysPicker], ADR 0011),
 * and a Ghost "Reset to default" that clears `Contact.ruleOverrideJson`.
 * Since 2026-10-07 (LIST-30) there is no rhythm choice here either: Keep in
 * touch, Late night and Energize are one calculation with different starting
 * numbers, so a person's schedule is one number too. A Late night or
 * Energize override set before then shows its real starting interval;
 * letting the wheel settle where it started writes nothing, so it stays what
 * it was until it is turned, and turning it makes it Keep in touch at the
 * chosen interval ([commitOverrideInterval]).
 *
 * **Corrupted JSON recovery.** When the VM cannot decode `ruleOverrideJson`
 * (`currentParams == null`), the screen passes a fresh default RuleParams
 * here; an override is stored, so the editor branch shows and the user can
 * tap Reset to default to clear the corrupted column.
 *
 * Copy lives in strings_contact.xml; the wheel's words are List settings'
 * (strings_lists.xml), because it is the same control.
 *
 * Token-clean — zero hardcoded color/shape/fontSize. Sentence case copy with
 * zero exclamation marks (voice contract).
 */
@Composable
fun RuleOverrideSection(
    listsOnSize: Int,
    inheritedRhythm: UiText?,
    primaryListName: String?,
    hasOverride: Boolean,
    currentParams: RuleParams,
    onOverride: () -> Unit,
    onParamsChange: (RuleParams) -> Unit,
    onResetDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        // The same rule the ViewModel uses to add this item at all.
        visible = showsCustomSchedule(listsOnSize, hasOverride),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(text = stringResource(R.string.contact_schedule_title))
            Spacer(Modifier.height(OrbitTheme.spacing.x3))

            if (!hasOverride) {
                // How often, as it sits mid-sentence ("every 14 days"); null
                // when the list's rhythm cannot be read, and then the
                // sentence names the list alone.
                val rhythm = inheritedRhythm?.asString()
                Text(
                    text = when {
                        primaryListName != null && rhythm != null ->
                            stringResource(R.string.contact_schedule_follows, rhythm, primaryListName)
                        primaryListName != null ->
                            stringResource(R.string.contact_schedule_follows_list, primaryListName)
                        rhythm != null ->
                            stringResource(R.string.contact_schedule_follows_hidden_list, rhythm)
                        else -> stringResource(R.string.contact_schedule_follows_its_list)
                    },
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted)
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                // Secondary, in plain words: the hero Call button is this
                // screen's one accent element (rules.md §Design 5), and
                // "Override" was engineering vocabulary (rubric D7).
                OrbitButton(
                    text = stringResource(R.string.contact_schedule_set),
                    onClick = onOverride,
                    variant = OrbitButtonVariant.Secondary
                )
            } else {
                // The wheel commits only a value that differs from where it
                // opened, so an override nobody turns stays what it was.
                IntervalDaysPicker(
                    currentHours = currentParams.baseIntervalHours,
                    onCommit = { days -> onParamsChange(commitOverrideInterval(currentParams, days)) },
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                OrbitButton(
                    text = stringResource(R.string.contact_schedule_reset),
                    onClick = onResetDefault,
                    variant = OrbitButtonVariant.Ghost
                )
            }
        }
    }
}

/**
 * The per-contact interval commit: whatever the stored override was, the
 * person now runs Keep in touch at [days], built through the one entry point
 * List settings uses ([toKeepInTouchEvery], which moves both cooldown bounds
 * together: committing `cooldownMinHours` alone let the 336h default cap
 * lie about long intervals). Internal so the unit test can assert both
 * bounds move and that a Late night override becomes Keep in touch.
 *
 * Floors at 1 day per ADR 0010. Both screens draw the one [IntervalDaysPicker]
 * since ADR 0011, so its 1 to 60 day range is shared by construction rather
 * than kept in lockstep by hand.
 */
internal fun commitOverrideInterval(
    params: RuleParams,
    days: Int
): RuleParams.KeepInTouch = params.toKeepInTouchEvery(days.coerceAtLeast(1) * HOURS_PER_DAY)

private const val HOURS_PER_DAY = 24

// ─── Previews ──────────────────────────────────────────────────────────────

@Preview(name = "RuleOverrideSection — no override, light", showBackground = true)
@Composable
private fun PreviewNoOverrideLight() {
    OrbitTheme(darkTheme = false) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x4)
        ) {
            RuleOverrideSection(
                listsOnSize = 2,
                inheritedRhythm = UiText.plural(R.plurals.contact_rhythm_every_days, 14, 14),
                primaryListName = "Inner orbit",
                hasOverride = false,
                currentParams = RuleParams.KeepInTouch(),
                onOverride = {},
                onParamsChange = {},
                onResetDefault = {}
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "RuleOverrideSection — override, dark", showBackground = true)
@Composable
private fun PreviewWithOverrideDark() {
    OrbitTheme(darkTheme = true) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x4)
        ) {
            RuleOverrideSection(
                listsOnSize = 2,
                inheritedRhythm = UiText.plural(R.plurals.contact_rhythm_every_days, 14, 14),
                primaryListName = "Inner orbit",
                hasOverride = true,
                // Built via withIntervalHours so the preview carries the same
                // both-bounds shape the wheel commits.
                currentParams = RuleParams.KeepInTouch().withIntervalHours(14 * 24),
                onOverride = {},
                onParamsChange = {},
                onResetDefault = {}
            )
        }
    }
}

@Preview(name = "RuleOverrideSection — gated off (1 list)", showBackground = true)
@Composable
private fun PreviewGatedOff() {
    OrbitTheme(darkTheme = false) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x4)
        ) {
            RuleOverrideSection(
                listsOnSize = 1,
                inheritedRhythm = UiText.plural(R.plurals.contact_rhythm_every_days, 14, 14),
                primaryListName = "Inner orbit",
                hasOverride = false,
                currentParams = RuleParams.KeepInTouch(),
                onOverride = {},
                onParamsChange = {},
                onResetDefault = {}
            )
        }
    }
}
