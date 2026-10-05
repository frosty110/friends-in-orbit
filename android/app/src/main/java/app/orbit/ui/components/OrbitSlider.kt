package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

/**
 * The one slider (2026-10-05). Three screens styled Material's slider on their
 * own, each in the accent, and the stock 1.3 look (a tall bar thumb and a dot
 * at the track's end) read as another app's control.
 *
 * - **Says its value in words.** [valueDescription] ("every 2 weeks") is what
 *   TalkBack reads; without it the slider announced a bare percentage of its
 *   track ("10 percent"), which means nothing (rubric D8).
 * - **Spends no accent** (rules.md §Design 5): an ink track and an ink-ringed
 *   thumb, so the screen's one accent stays on its primary action.
 * - A 24dp round thumb inside Material's 48dp touch height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDescription: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val c = OrbitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        thumbColor = c.surface,
        activeTrackColor = c.fg,
        inactiveTrackColor = c.line,
        activeTickColor = c.surface,
        inactiveTickColor = c.fgSubtle,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        interactionSource = interaction,
        colors = colors,
        thumb = {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(c.surface)
                    .border(2.dp, c.fg, CircleShape),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
                modifier = Modifier.height(4.dp),
            )
        },
        modifier = modifier.semantics {
            stateDescription = valueDescription
            if (label != null) contentDescription = label
        },
    )
}
