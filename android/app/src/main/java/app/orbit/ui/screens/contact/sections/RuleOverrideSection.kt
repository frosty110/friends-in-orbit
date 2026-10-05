package app.orbit.ui.screens.contact.sections

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.data.entity.RuleKind
import app.orbit.domain.rule.RuleParams
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.screens.lists.RuleTemplatePicker
import app.orbit.ui.components.SectionLabel
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
 * tunables — like List Configuration, a quiet rhythm note replaces the slider
 * for those kinds.
 *
 * **Reuse, not duplication.** The kind picker is the same composable List
 * Configuration uses. The interval slider mirrors the
 * `IntervalSliderLocal` pattern in ListConfigBody — same
 * `onValueChangeFinished` save-on-commit semantics — but operates on
 * `RuleParams` rather than the list-level state because the per-contact
 * override path writes `Contact.ruleOverrideJson`.
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
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        // Visibility gate: listsOn.size >= 2 — contacts on a single list
        // surface only their list's template, so the override editor would
        // have nothing to override.
        visible = listsOnSize >= 2,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
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
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                // Secondary, in plain words: the hero Call button is this
                // screen's one accent element (rules.md §Design 5), and
                // "Override" was engineering vocabulary (rubric D7).
                OrbitButton(
                    text = stringResource(R.string.contact_schedule_set),
                    onClick = onOverride,
                    variant = OrbitButtonVariant.Secondary,
                )
            } else {
                OverrideEditor(params = currentParams, onChange = onParamsChange)
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                OrbitButton(
                    text = stringResource(R.string.contact_schedule_reset),
                    onClick = onResetDefault,
                    variant = OrbitButtonVariant.Ghost,
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
            onSelect = { newKind -> onChange(defaultParamsFor(newKind)) },
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        when (params) {
            is RuleParams.KeepInTouch -> IntervalDaysSlider(
                currentHours = params.cooldownMinHours,
                onCommit = { days -> onChange(commitOverrideInterval(params, days)) },
            )
            is RuleParams.LateNight, is RuleParams.Energize -> Text(
                text = rhythmNoteFor(params.toRuleKind())?.let { stringResource(it) }.orEmpty(),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = OrbitTheme.spacing.x4,
                        vertical = OrbitTheme.spacing.x3,
                    ),
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
 * Floors at 1 day per ADR 0010, matching `INTERVAL_MIN_DAY` in ListConfigBody —
 * the two sliders must stay in lockstep, so a change here needs the same change
 * there and an ADR to go with it.
 */
internal fun commitOverrideInterval(
    params: RuleParams.KeepInTouch,
    days: Int,
): RuleParams.KeepInTouch = params.withIntervalHours(days.coerceAtLeast(1) * 24)

/**
 * Interval slider — mirrors ListConfigBody's `IntervalSliderLocal` ("Aim for
 * every N days", 1..60). `onValueChangeFinished` is the commit point so the
 * VM only writes `Contact.ruleOverrideJson` once per drag, not on every frame.
 */
@Composable
private fun IntervalDaysSlider(
    currentHours: Int,
    onCommit: (days: Int) -> Unit,
) {
    val initialDays = (currentHours / 24f).coerceAtLeast(1f)
    var days by remember(currentHours) { mutableFloatStateOf(initialDays) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x4),
    ) {
        val rounded = days.toInt().coerceAtLeast(1)
        val everyLabel = pluralStringResource(R.plurals.lists_interval_every_days, rounded, rounded)
        val aimLabel = stringResource(R.string.lists_interval_aim)
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = aimLabel,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier.weight(1f),
            )
            // Ink, not accentPress: the Call button is the screen's accent.
            Text(
                text = pluralStringResource(R.plurals.lists_interval_days, rounded, rounded),
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        }
        Slider(
            value = days,
            onValueChange = { days = it },
            onValueChangeFinished = { onCommit(days.toInt().coerceAtLeast(1)) },
            valueRange = 1f..60f,
            // One step per day, so the thumb lands on whole days and TalkBack's
            // adjust gesture moves a day at a time.
            steps = 58,
            colors = SliderDefaults.colors(
                thumbColor = OrbitTheme.colors.fg,
                activeTrackColor = OrbitTheme.colors.fgSoft,
                inactiveTrackColor = OrbitTheme.colors.line,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier
                .padding(top = OrbitTheme.spacing.x1)
                // TalkBack read "10 percent"; it now says what the value means
                // (rubric D8: sliders announce meaningful values).
                .semantics {
                    contentDescription = aimLabel
                    stateDescription = everyLabel
                },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = OrbitTheme.spacing.x1),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = pluralStringResource(R.plurals.lists_interval_days, 1, 1),
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
            Text(
                text = pluralStringResource(R.plurals.lists_interval_days, 60, 60),
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
        }
    }
}

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
        Box(modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x4)) {
            RuleOverrideSection(
                listsOnSize = 2,
                currentTemplateName = UiText.res(R.string.contact_rhythm_name_keep_in_touch),
                primaryListName = "Inner orbit",
                hasOverride = false,
                currentParams = RuleParams.KeepInTouch(),
                onOverride = {},
                onParamsChange = {},
                onResetDefault = {},
            )
        }
    }
}

@Preview(name = "RuleOverrideSection — override, dark", showBackground = true)
@Composable
private fun PreviewWithOverrideDark() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x4)) {
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
                onResetDefault = {},
            )
        }
    }
}

@Preview(name = "RuleOverrideSection — gated off (1 list)", showBackground = true)
@Composable
private fun PreviewGatedOff() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.surface).padding(OrbitTheme.spacing.x4)) {
            RuleOverrideSection(
                listsOnSize = 1,
                currentTemplateName = UiText.res(R.string.contact_rhythm_name_keep_in_touch),
                primaryListName = "Inner orbit",
                hasOverride = false,
                currentParams = RuleParams.KeepInTouch(),
                onOverride = {},
                onParamsChange = {},
                onResetDefault = {},
            )
        }
    }
}
