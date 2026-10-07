package app.orbit.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.orbit.ui.theme.OrbitTheme

/**
 * The small label above a group ("Appearance", "Notes", "Today"). It is a
 * heading for accessibility, so TalkBack users can move between sections the
 * way sighted users scan them (WCAG 1.3.1 and 2.4.6). Before 2026-10-05 no
 * screen exposed a heading, so a long screen read as one flat list.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = OrbitTheme.colors.fgMuted,
) {
    Text(
        text = text,
        style = OrbitTheme.type.eyebrow.copy(color = color),
        modifier = modifier.semantics { heading() },
    )
}
