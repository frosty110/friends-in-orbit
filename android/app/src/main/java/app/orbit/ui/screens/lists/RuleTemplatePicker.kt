package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.ui.theme.OrbitTheme

/**
 * Cadence picker for List Configuration.
 *
 * Renders the three [RuleKind] options as a stack of radio rows. The
 * `templates` parameter is plumbed through for v1.1 per-template subtitle
 * resolution; v1 only uses [currentKind] to show the selected dot.
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
    Column(modifier = modifier.fillMaxWidth()) {
        RuleKind.entries.forEachIndexed { index, kind ->
            if (index > 0) RuleRowDivider()
            RuleRow(
                label = labelFor(kind),
                sub = subtitleFor(kind),
                selected = currentKind == kind,
                onClick = { onSelect(kind) },
            )
        }
    }
}

private fun labelFor(kind: RuleKind): String = when (kind) {
    RuleKind.KEEP_IN_TOUCH -> "Keep in touch"
    RuleKind.LATE_NIGHT -> "Late night"
    RuleKind.ENERGIZE -> "Energize"
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
private fun subtitleFor(kind: RuleKind): String = when (kind) {
    RuleKind.KEEP_IN_TOUCH -> "Surfaces each person on a steady rhythm you set."
    RuleKind.LATE_NIGHT -> "A slower, more patient rhythm for people you reach at night."
    RuleKind.ENERGIZE -> "A quicker rhythm that brings people back sooner after a call."
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = sub,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    2.dp,
                    if (selected) OrbitTheme.colors.accent else OrbitTheme.colors.line,
                    CircleShape,
                )
                .background(
                    if (selected) OrbitTheme.colors.accent else OrbitTheme.colors.surface,
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
                .padding(8.dp),
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
                .padding(8.dp),
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
