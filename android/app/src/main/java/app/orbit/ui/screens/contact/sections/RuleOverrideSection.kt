package app.orbit.ui.screens.contact.sections

import android.content.res.Configuration
import androidx.annotation.StringRes
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.data.entity.RuleKind
import app.orbit.domain.rule.RuleParams
import app.orbit.ui.components.IntervalDaysPicker
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.SectionLabel
import app.orbit.ui.screens.lists.RuleTemplatePicker
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString

/**
 * Per-contact rule override editor (CONTACT-03).
 *
 * **Visibility gate:** rendered only when the contact appears on at least two
 * lists — i.e. `listsOn.size >= 2`. The wrapping AnimatedVisibility flips the
 * section in/out as the contact's membership count crosses the threshold.
 *
 * **No-override branch (`hasOverride == false`):** shows eyebrow "Custom
 * schedule" + body "Follows the {template} rhythm from {primaryListName}."
 * ("from its list" when [primaryListName] is null, under the curtain) +
 * Secondary "Set a schedule for this person" button (2026-10-05: it was a
 * Primary "Override", a second accent element and engineering vocabulary).
 *
 * **Override branch (`hasOverride == true`):** REUSES the List Configuration
 * [RuleTemplatePicker] to switch between [RuleKind] templates, plus — for
 * [RuleParams.KeepInTouch] only — an interval slider ("Aim for every N
 * days"). Both controls emit fresh [RuleParams] via `onParamsChange` on
 * commit. A Ghost "Reset to default" button calls `onResetDefault` to clear
 * `Contact.ruleOverrideJson`.
 *
 * **Interval commits through [RuleParams.KeepInTouch.withIntervalHours]** so
 * BOTH cooldown bounds move with the chosen interval, mirroring
 * ListConfigBody's slider. Committing only `cooldownMinHours` let the default
 * 336h cap silently turn "aim for every 30 days" into every 14 (see the
 * withIntervalHours KDoc). Late night and Energize carry no user-facing
 * tunables; like List Configuration, a quiet rhythm note replaces the day
 * wheel for those kinds.
 *
 * **Reuse, not duplication.** The kind picker is the same composable List
 * Configuration uses, and so is the interval: the shared [IntervalDaysPicker]
 * (ADR 0011), which commits once per gesture. Only the commit differs: it
 * writes `RuleParams` rather than the list-level state, because the
 * per-contact override path writes `Contact.ruleOverrideJson`. Until 2026-10-06 this
 * was a stock Material slider styled by hand, so the same control looked
 * different here and in List settings.
 *
 * **Corrupted JSON recovery.** When the VM cannot decode `ruleOverrideJson`
 * (`currentParams == null`), the screen passes a fresh default RuleParams
 * here; an override is stored, so the editor branch shows and the user can
 * tap Reset to default to clear the corrupted column.
 *
 * Copy lives in strings_contact.xml; the interval slider reuses List
 * settings' slider strings (strings_lists.xml), since it mirrors that slider.
 *
 * Token-clean — zero hardcoded color/shape/fontSize. Sentence case copy with
 * zero exclamation marks (voice contract).
 */
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

@Composable
fun RuleOverrideSection(
    listsOnSize: Int,
    currentTemplateName: UiText?,
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
                // The VM always names the inherited rhythm here; "keep in
                // touch" is its own fallback for a list without a template.
                val rhythm = currentTemplateName?.asString()
                    ?: stringResource(R.string.contact_rhythm_name_keep_in_touch)
                Text(
                    text = if (primaryListName != null) {
                        stringResource(R.string.contact_schedule_follows, rhythm, primaryListName)
                    } else {
                        stringResource(R.string.contact_schedule_follows_hidden_list, rhythm)
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
                OverrideEditor(params = currentParams, onChange = onParamsChange)
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
 * Inner editor — REUSES the List Configuration [RuleTemplatePicker] for kind
 * selection. Keep in touch renders the interval slider; Late night / Energize
 * render a quiet rhythm note (no user-facing tunables — mirrors ListConfigBody).
 *
 * Switching kinds emits a fresh default RuleParams of the new subtype so
 * the contact's stored override doesn't carry stale fields after a kind
 * change (matches the List Configuration semantics).
 */
@Composable
private fun OverrideEditor(params: RuleParams, onChange: (RuleParams) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Kind picker — same composable List Configuration uses.
        // `templates = emptyList()` is fine; the picker only consults its
        // `currentKind` for the selected radio dot.
        RuleTemplatePicker(
            currentKind = params.toRuleKind(),
            templates = emptyList(),
            onSelect = { newKind -> onChange(defaultParamsFor(newKind)) }
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        when (params) {
            // The same day wheel as List settings' "How often" (ADR 0011).
            is RuleParams.KeepInTouch -> IntervalDaysPicker(
                currentHours = params.cooldownMinHours,
                onCommit = { days -> onChange(commitOverrideInterval(params, days)) }
            )
            is RuleParams.LateNight, is RuleParams.Energize -> Text(
                text = rhythmNoteFor(params.toRuleKind())?.let { stringResource(it) }.orEmpty(),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = OrbitTheme.spacing.x4,
                        vertical = OrbitTheme.spacing.x3
                    )
            )
        }
    }
}

/**
 * The per-contact interval commit. Routes through
 * [RuleParams.KeepInTouch.withIntervalHours] so BOTH cooldown bounds track
 * the chosen interval (same fix ListConfigBody's slider got — committing
 * `cooldownMinHours` alone let the 336h default cap lie about long
 * intervals). Internal so the unit test can assert both bounds move.
 *
 * Floors at 1 day per ADR 0010. Both screens draw the one [IntervalDaysPicker]
 * since ADR 0011, so its 1 to 60 day range is shared by construction rather
 * than kept in lockstep by hand.
 */
internal fun commitOverrideInterval(
    params: RuleParams.KeepInTouch,
    days: Int
): RuleParams.KeepInTouch = params.withIntervalHours(days.coerceAtLeast(1) * 24)

// ─── RuleParams ↔ RuleKind helpers ──────────────────────────────────────────

private fun RuleParams.toRuleKind(): RuleKind = when (this) {
    is RuleParams.KeepInTouch -> RuleKind.KEEP_IN_TOUCH
    is RuleParams.LateNight -> RuleKind.LATE_NIGHT
    is RuleParams.Energize -> RuleKind.ENERGIZE
}

/**
 * Mirrors ListConfigBody's `rhythmNoteFor` (private to the lists package, so
 * replicated rather than widened). Subject reworded from "This list" to the
 * rhythm itself — here the note describes a per-contact override, not a list.
 */
@StringRes
private fun rhythmNoteFor(kind: RuleKind): Int? = when (kind) {
    RuleKind.KEEP_IN_TOUCH -> null
    RuleKind.LATE_NIGHT -> R.string.contact_rhythm_note_late_night
    RuleKind.ENERGIZE -> R.string.contact_rhythm_note_energize
}

private fun defaultParamsFor(kind: RuleKind): RuleParams = when (kind) {
    RuleKind.KEEP_IN_TOUCH -> RuleParams.KeepInTouch()
    RuleKind.LATE_NIGHT -> RuleParams.LateNight()
    RuleKind.ENERGIZE -> RuleParams.Energize()
}

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
                currentTemplateName = UiText.res(R.string.contact_rhythm_name_keep_in_touch),
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
                currentTemplateName = UiText.res(R.string.contact_rhythm_name_keep_in_touch),
                primaryListName = "Inner orbit",
                hasOverride = true,
                // Built via withIntervalHours so the preview carries the same
                // both-bounds shape the slider commits.
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
                currentTemplateName = UiText.res(R.string.contact_rhythm_name_keep_in_touch),
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
