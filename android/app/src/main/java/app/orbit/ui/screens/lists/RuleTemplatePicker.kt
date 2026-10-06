package app.orbit.ui.screens.lists

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.R
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.ui.theme.OrbitTheme

/**
 * Rhythm picker for List settings.
 *
 * Renders the three [RuleKind] options as a stack of radio rows: one
 * `selectableGroup`, each row `selectable` with `Role.RadioButton` and its
 * selected state, so TalkBack says "Keep in touch, selected, radio button, 1
 * of 3" and not just the label (WCAG 4.1.2; the drawn dot was the only cue
 * until 2026-10-06). The `templates` parameter is plumbed through for v1.1
 * per-template subtitle resolution; v1 only uses [currentKind] to show the
 * selected dot.
 *
 * Token-clean — no inline color hex literals, no RoundedCornerShape, no fontSize literals.
 * Layout-local `dp` literals are acceptable per the project's design token conventions.
 *
 * Consumed by the `ListConfigScreen` rewrite.
 */
@Composable
fun RuleTemplatePicker(
    currentKind: RuleKind?,
    templates: List<RuleTemplateEntity>,
    onSelect: (RuleKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_PARAMETER") val unused = templates // v1.1 — per-template subtitle resolution
    Column(modifier = modifier.fillMaxWidth().selectableGroup()) {
        RuleKind.entries.forEachIndexed { index, kind ->
            if (index > 0) RuleRowDivider()
            RuleRow(
                label = stringResource(labelFor(kind)),
                sub = stringResource(subtitleFor(kind)),
                selected = currentKind == kind,
                onClick = { onSelect(kind) },
            )
        }
    }
}

@StringRes
private fun labelFor(kind: RuleKind): Int = when (kind) {
    RuleKind.KEEP_IN_TOUCH -> R.string.lists_rule_keep_in_touch
    RuleKind.LATE_NIGHT -> R.string.lists_rule_late_night
    RuleKind.ENERGIZE -> R.string.lists_rule_energize
}

/**
 * One warm sentence per template, checked against the actual engine behavior
 * ([app.orbit.domain.rule.RuleParams] defaults + the three engines):
 *  - Keep in touch: base cadence is the user's interval slider value.
 *  - Late night: longest cooldowns (72h base) and the gentlest resets — the
 *    engine does NOT gate on evenings (that's the list's active hours), so
 *    the copy describes the rhythm, not a time window.
 *  - Energize: shortest cooldowns (24h base) and the strongest short-call /
 *    incoming-call resets — people genuinely come back sooner after a call.
 */
@StringRes
private fun subtitleFor(kind: RuleKind): Int = when (kind) {
    RuleKind.KEEP_IN_TOUCH -> R.string.lists_rule_keep_in_touch_sub
    RuleKind.LATE_NIGHT -> R.string.lists_rule_late_night_sub
    RuleKind.ENERGIZE -> R.string.lists_rule_energize_sub
}

@Composable
private fun RuleRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(OrbitTheme.colors.lineSoft),
    )
}

@Composable
private fun RuleRow(
    label: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.rowY),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = sub,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = OrbitTheme.spacing.hair),
            )
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    2.dp,
                    // Ink, not accent: a selected option is cluster tier
                    // (rules.md §Design 5). The unselected ring is fgSubtle so
                    // the control reads at 3:1 (the old `line` ring did not).
                    if (selected) OrbitTheme.colors.fg else OrbitTheme.colors.fgSubtle,
                    CircleShape,
                )
                .background(
                    if (selected) OrbitTheme.colors.fg else OrbitTheme.colors.surface,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(OrbitTheme.colors.accentFg),
                )
            }
        }
    }
}

// region Previews

@Preview(name = "RuleTemplatePicker — light", showBackground = true)
@Composable
private fun RuleTemplatePickerLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(
            modifier = Modifier
                .background(OrbitTheme.colors.surface)
                .padding(OrbitTheme.spacing.x2),
        ) {
            RuleTemplatePicker(
                currentKind = RuleKind.LATE_NIGHT,
                templates = emptyList(),
                onSelect = {},
            )
        }
    }
}

@Preview(name = "RuleTemplatePicker — dark", showBackground = true)
@Composable
private fun RuleTemplatePickerDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(
            modifier = Modifier
                .background(OrbitTheme.colors.surface)
                .padding(OrbitTheme.spacing.x2),
        ) {
            RuleTemplatePicker(
                currentKind = RuleKind.LATE_NIGHT,
                templates = emptyList(),
                onSelect = {},
            )
        }
    }
}

// endregion
