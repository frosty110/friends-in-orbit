package app.orbit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

/**
 * Orbit's checkbox mark, for multi-select rows (the pickers, Browse's
 * selection mode). It replaces Material's `Checkbox`, whose square, stock
 * look was one of the "another app" parts the UX rubric names (D4).
 *
 * Display only. In every Orbit list the whole row is the tap target and
 * carries the state for TalkBack (role Checkbox, checked or not checked), so
 * the mark is hidden from accessibility and takes no gesture: a second
 * clickable inside the row would be a second writer for one value
 * (rules.md Code 7) and a second, smaller target.
 *
 * Checked is ink with a cream tick, not terracotta: selection repeats on every
 * chosen row, and the accent is spent once per screen (rules.md §Design 5).
 * The row's `accentTint` wash is the cluster-tier signal; this mark is the
 * shape signal, so colour is never the only cue. The unchecked outline uses
 * `fgSubtle`, which clears the 3:1 floor for a control's boundary
 * (rules.md §Design 4).
 */
@Composable
fun OrbitCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = OrbitTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clearAndSetSemantics {}
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(OrbitTheme.spacing.x5)
            .clip(OrbitTheme.shapes.xs)
            .background(if (checked) c.fg else Color.Transparent)
            .then(
                if (checked) {
                    Modifier
                } else {
                    // 2dp, the same weight as the keyboard-focus outline in
                    // OrbitPressIndication; a 1dp hairline read as disabled.
                    Modifier.border(BorderStroke(2.dp, c.fgSubtle), OrbitTheme.shapes.xs)
                },
            ),
    ) {
        if (checked) {
            PhIcon(name = "check", size = 14.dp, tint = c.bg)
        }
    }
}

private const val DISABLED_ALPHA = 0.4f

@PreviewLightDark
@Composable
private fun OrbitCheckboxPreview() {
    OrbitTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x4),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(OrbitTheme.colors.bg)
                .padding(OrbitTheme.spacing.x4),
        ) {
            OrbitCheckbox(checked = false)
            OrbitCheckbox(checked = true)
            Box(
                modifier = Modifier
                    .background(OrbitTheme.colors.accentTint)
                    .padding(OrbitTheme.spacing.x2),
            ) {
                OrbitCheckbox(checked = true)
            }
            OrbitCheckbox(checked = false, enabled = false)
        }
    }
}
