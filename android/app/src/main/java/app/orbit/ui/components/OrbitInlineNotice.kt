package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.ui.theme.OrbitTheme

/**
 * A quiet, persistent strip above a list: one line of fact and one text
 * action that fixes it ("Orbit can't see your calls" / "Open settings").
 * Inline and dismiss-free on purpose: an honest state stays visible until it
 * is fixed, and a dialog would nag.
 *
 * One component because there were two (rubric D4, DESIGN.md "one component
 * per job"): Card view and Call history each kept a private copy of this row,
 * and they had already drifted. Call history's action announced as a button
 * and Card view's did not, so TalkBack users heard "Open settings" as plain
 * text on one screen and as something to tap on the other. Here the action
 * is always `Role.Button` (DESIGN.md, the OrbitButton row) and always at
 * least 48dp (rules.md §Design 3), without a full button fill so it stays
 * quieter than the screen's real actions.
 *
 * It insets itself to the screen's gutter (x4) like the rows around it, so a
 * caller drops it into a column above the list and nothing else moves. The
 * text is the screen's own string; the component owns only the layout.
 */
@Composable
fun OrbitInlineNotice(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x1)
            .clip(OrbitTheme.shapes.md)
            .background(OrbitTheme.colors.bgSubtle)
            .padding(start = OrbitTheme.spacing.x3),
    ) {
        Text(
            text = text,
            style = OrbitTheme.type.meta,
            color = OrbitTheme.colors.fgMuted,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = OrbitTheme.spacing.x2),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = OrbitTheme.spacing.tapMin, minHeight = OrbitTheme.spacing.tapMin)
                .clip(OrbitTheme.shapes.md)
                .clickable(role = Role.Button, onClick = onAction)
                .padding(horizontal = OrbitTheme.spacing.x3),
        ) {
            Text(
                text = actionLabel,
                style = OrbitTheme.type.button,
                color = OrbitTheme.colors.fg,
            )
        }
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OrbitInlineNoticePreview() {
    OrbitTheme {
        Column(Modifier.background(OrbitTheme.colors.bg)) {
            OrbitInlineNotice(
                text = "Orbit can't see your calls, so cards won't move on their own.",
                actionLabel = "Open settings",
                onAction = {},
            )
        }
    }
}
