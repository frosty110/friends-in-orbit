package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

@Composable
fun OrbitAppBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    subtle: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // The title is the screen's pane title, so TalkBack announces the
            // new screen when navigation swaps it in. In one activity there is
            // no window change for it to announce otherwise (rubric D8).
            .semantics { if (title.isNotBlank()) paneTitle = title }
            // Min, not fixed: a fixed 56dp clipped two-line titles at 200%
            // font scale (rubric gate G3).
            .heightIn(min = 56.dp)
            .background(if (subtle) Color.Transparent else OrbitTheme.colors.bg)
            .padding(start = OrbitTheme.spacing.x4, end = OrbitTheme.spacing.x2),
    ) {
        if (leading != null) {
            Box { leading() }
        }
        Text(
            text = title,
            style = OrbitTheme.type.h3.copy(
                color = OrbitTheme.colors.fg,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(start = if (leading != null) OrbitTheme.spacing.x1 else 0.dp, top = OrbitTheme.spacing.x2, bottom = OrbitTheme.spacing.x2)
                // The screen title is a heading, so TalkBack users can jump
                // to it and hear where they are.
                .semantics { heading() },
        )
        if (trailing != null) {
            Box { trailing() }
        }
    }
}

/**
 * Text action for the app bar's trailing slot — "Done", "Save", "Skip".
 *
 * Screens that save on every change still need a way out that reads as
 * finished: the back arrow works, but it says "go back", not "I'm done here"
 * (2026-08-15 UAT, list creation). Accent-coloured so it registers as the
 * screen's exit, 48dp tap target like every other control.
 */
@Composable
fun OrbitAppBarTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(OrbitTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else Modifier,
            )
            .padding(horizontal = OrbitTheme.spacing.x3),
    ) {
        Text(
            text = text,
            style = OrbitTheme.type.button.copy(color = OrbitTheme.colors.accent),
        )
    }
}
