package app.orbit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitPressIndication
import app.orbit.ui.theme.OrbitTheme

/**
 * Orbit's one selectable chip, for every filter, single choice and chip-shaped
 * menu trigger: Browse's "Called recently", Call history's "All / Incoming /
 * Outgoing", the picker's filters and its applied filters.
 *
 * Before 2026-10-05 the app drew filter chips four ways (Material
 * `FilterChip` with three different colour overrides, and a Browse-only pill
 * built from the display chip), and the Material ones looked stock
 * (rubric D4). This is the replacement. The display chip, a read-only label,
 * went on 2026-10-08 (rules.md Design 9): a chip shape is a control here, and
 * this is the only chip left.
 *
 * - **Selected** fills with `accentTint`, the cluster tier, never `accent`
 *   (rules.md §Design 5), and shows a check, so colour is never the only
 *   signal (rubric D8). A chip with a [trailingIcon] (the applied filter's
 *   "x", a menu's caret) shows that instead of the check.
 * - **Touch** is a target at least 48dp each way around a smaller pill (rules.md §Design 3).
 *   The press overlay is drawn on the pill, so it matches what was touched.
 * - **Semantics** follow [role]: [Role.Checkbox] toggles (filters),
 *   [Role.RadioButton] picks one of a group, anything else is a plain button
 *   (a menu trigger). TalkBack hears "Called recently, checkbox, checked".
 * - **Large text** grows the pill (min height, never fixed) and keeps the
 *   label on one line; callers put chips in a scrolling row or a flow row so a
 *   label is never broken mid-word ("Outgoin g" at 200% before).
 */
@Composable
fun OrbitFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
    trailingIcon: String? = null,
    contentDescription: String? = null,
) {
    val c = OrbitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val labelColor = when {
        !enabled -> c.fgSubtle
        selected -> c.fg
        else -> c.fgSoft
    }
    val container = when {
        !enabled -> Color.Transparent
        selected -> c.accentTint
        else -> c.bgSubtle
    }
    val gesture = when (role) {
        Role.Checkbox -> Modifier.toggleable(
            value = selected,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onValueChange = { onClick() },
        )
        Role.RadioButton -> Modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
        else -> Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
            .then(gesture)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
            modifier = Modifier
                .heightIn(min = OrbitTheme.spacing.x7)
                .clip(OrbitTheme.shapes.full)
                .background(container)
                .then(
                    if (!enabled) {
                        Modifier.border(BorderStroke(1.dp, c.line), OrbitTheme.shapes.full)
                    } else {
                        Modifier
                    },
                )
                .indication(interaction, OrbitPressIndication)
                .padding(horizontal = OrbitTheme.spacing.x3, vertical = OrbitTheme.spacing.x1),
        ) {
            if (selected && trailingIcon == null) {
                PhIcon(name = "check", size = 16.dp, tint = labelColor)
            }
            Text(
                text = label,
                style = OrbitTheme.type.meta.copy(color = labelColor, fontWeight = FontWeight.Medium),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingIcon != null) {
                PhIcon(name = trailingIcon, size = 14.dp, tint = labelColor)
            }
        }
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OrbitFilterChipPreview() {
    OrbitTheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x1),
            modifier = Modifier
                .background(OrbitTheme.colors.bg)
                .padding(OrbitTheme.spacing.x4),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2)) {
                OrbitFilterChip(label = "Called recently", selected = true, onClick = {})
                OrbitFilterChip(label = "Not called yet", selected = false, onClick = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2)) {
                OrbitFilterChip(label = "Long gap · 3", selected = true, onClick = {}, trailingIcon = "x")
                OrbitFilterChip(label = "On a list", selected = false, onClick = {}, role = Role.Button, trailingIcon = "caret-down")
                OrbitFilterChip(label = "Starred", selected = false, enabled = false, onClick = {})
            }
        }
    }
}
