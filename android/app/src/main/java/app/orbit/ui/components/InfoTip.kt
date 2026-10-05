package app.orbit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme
import kotlinx.coroutines.launch

/**
 * A small "i" that explains the thing next to it. Tapping it shows [text]
 * until the user taps elsewhere; TalkBack reads [label] as a button and the
 * explanation when activated.
 *
 * Replaces two Material `Icons.Outlined.Info` glyphs (a second icon family in
 * a Phosphor app) whose tooltip only opened on long-press, so a tap did
 * nothing, and one of which was a 14dp target. The glyph stays small; the
 * 48dp box carries the gesture (rules.md §Design 3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoTip(
    text: String,
    modifier: Modifier = Modifier,
    label: String = "About this",
) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = state,
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(OrbitTheme.spacing.tapMin)
                .clip(OrbitTheme.shapes.full)
                .clickable(role = Role.Button, onClickLabel = label) { scope.launch { state.show() } }
                .semantics { contentDescription = label },
        ) {
            PhIcon(name = "info", size = 16.dp, tint = OrbitTheme.colors.fgMuted)
        }
    }
}
