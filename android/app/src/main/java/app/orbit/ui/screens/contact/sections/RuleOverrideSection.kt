package app.orbit.ui.screens.contact.sections

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.data.entity.RuleKind
import app.orbit.domain.rule.RuleParams
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.screens.lists.RuleTemplatePicker
import app.orbit.ui.theme.OrbitTheme

/**
 * Per-contact rule override editor (CONTACT-03).
 *
 * **Visibility gate:** rendered only when the contact appears on at least two
 * lists — i.e. `listsOn.size >= 2`. The wrapping AnimatedVisibility flips the
 * section in/out as the contact's membership count crosses the threshold.
 *
 * **No-override branch (`hasOverride == false`):** shows eyebrow "Custom
 * schedule" + body "Inherits {currentTemplateName} from {primaryListName}" +
 * Primary "Override" button.
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
 * here and the eyebrow flips to "Custom schedule (recovering)" via the
 * `currentTemplateName` argument. The user can then tap Reset to default to
 * clear the corrupted column.
 *
 * Token-clean — zero hardcoded color/shape/fontSize. Sentence case copy with
 * zero exclamation marks (voice contract).
 */
@Composable
fun RuleOverrideSection(
    listsOnSize: Int,
    currentTemplateName: String,
    primaryListName: String,
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
            Text(
                text = "Custom schedule",
                style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgMuted),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x3))

            if (!hasOverride) {
                Text(
                    text = "Inherits $currentTemplateName from $primaryListName",
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                OrbitButton(
                    text = "Override",
                    onClick = onOverride,
                    variant = OrbitButtonVariant.Primary,
                )
            } else {
                OverrideEditor(params = currentParams, onChange = onParamsChange)
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                OrbitButton(
                    text = "Reset to default",
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
                text = rhythmNoteFor(params.toRuleKind()).orEmpty(),
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
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Aim for every",
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                modifier = Modifier.weight(1f),
            )
            val rounded = days.toInt().coerceAtLeast(1)
            Text(
                text = "$rounded ${if (rounded == 1) "day" else "days"}",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.accentPress),
            )
        }
        Slider(
            value = days,
            onValueChange = { days = it },
            onValueChangeFinished = { onCommit(days.toInt().coerceAtLeast(1)) },
            valueRange = 1f..60f,
            colors = SliderDefaults.colors(
                thumbColor = OrbitTheme.colors.accent,
                activeTrackColor = OrbitTheme.colors.accent,
                inactiveTrackColor = OrbitTheme.colors.line,
            ),
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "1 day",
                style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgSubtle),
            )
            Text(
                text = "60 days",
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
private fun rhythmNoteFor(kind: RuleKind): String? = when (kind) {
    RuleKind.KEEP_IN_TOUCH -> null
    RuleKind.LATE_NIGHT -> "The late night rhythm runs on its own — slower and more patient, with nothing to set."
    RuleKind.ENERGIZE -> "The energize rhythm runs on its own — quicker, with nothing to set."
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
        Box(modifier = Modifier.background(OrbitTheme.colors.surface).padding(16.dp)) {
            RuleOverrideSection(
                listsOnSize = 2,
                currentTemplateName = "Keep in touch",
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
        Box(modifier = Modifier.background(OrbitTheme.colors.surface).padding(16.dp)) {
            RuleOverrideSection(
                listsOnSize = 2,
                currentTemplateName = "Keep in touch",
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
        Box(modifier = Modifier.background(OrbitTheme.colors.surface).padding(16.dp)) {
            RuleOverrideSection(
                listsOnSize = 1,
                currentTemplateName = "Keep in touch",
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
