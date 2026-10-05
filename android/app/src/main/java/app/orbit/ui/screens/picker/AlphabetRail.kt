package app.orbit.ui.screens.picker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

/**
 * Height of one letter cell — drives the y → index mapping below. Bumped from
 * 16dp to give each letter a bigger vertical target (the rail was cramped and
 * easy to mis-tap); still overlays the list edge without taking layout width.
 */
private val RailCellHeight = 20.dp

/**
 * Right-edge fast-scroll rail for the picker's alphabetical sort. Renders the
 * section letters present in the current filtered list (not a fixed A–Z, so
 * every letter is actionable) and maps a press or drag anywhere on the strip
 * to [onLetterSelected] with the letter's index.
 *
 * Tap-target note: individual letter cells are below the 48dp floor (rules.md
 * Design 3). The mitigation is that the WHOLE rail is one continuous, widened
 * gesture surface — pressing or dragging anywhere resolves to the nearest
 * letter, the same pattern the system contacts app uses for its scrubber. The
 * letter currently under the finger — or, at rest, the section in view
 * ([activeIndex]): is highlighted (bold ink) so the user can see where they
 * are and slide to correct without lifting. It was accent until 2026-10-05,
 * a second accent element beside the commit button (rules.md §Design 5).
 *
 * Only shown in [PickerSort.ByName] with a blank search query (the caller
 * gates this); hidden otherwise because rank- or recency-ordered lists have
 * no stable letter geography.
 */
@Composable
fun AlphabetRail(
    letters: List<String>,
    onLetterSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // Index of the section currently in view, for at-rest highlighting. -1
    // when unknown. Overridden by the pressed letter while the rail is touched.
    activeIndex: Int = -1,
) {
    if (letters.isEmpty()) return

    var pressedIndex by remember { mutableIntStateOf(-1) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            // Wider than the letters need, so the strip is easy to land on.
            .width(OrbitTheme.spacing.x7)
            .pointerInput(letters) {
                // One gesture surface: every pressed position (down or drag)
                // maps to a letter index; re-fires only when the index changes.
                var lastIndex = -1
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            val cellPx = RailCellHeight.toPx()
                            val index = (change.position.y / cellPx)
                                .toInt()
                                .coerceIn(0, letters.lastIndex)
                            if (index != lastIndex) {
                                lastIndex = index
                                pressedIndex = index
                                onLetterSelected(index)
                            }
                            change.consume()
                        } else {
                            lastIndex = -1
                            pressedIndex = -1
                        }
                    }
                }
            },
    ) {
        // While pressed, the finger's letter wins; at rest, the in-view section.
        val highlightIndex = if (pressedIndex >= 0) pressedIndex else activeIndex
        letters.forEachIndexed { index, letter ->
            val isCurrent = index == highlightIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().height(RailCellHeight),
            ) {
                Text(
                    text = letter,
                    style = OrbitTheme.type.micro,
                    color = if (isCurrent) OrbitTheme.colors.fg else OrbitTheme.colors.fgMuted,
                    fontWeight = if (isCurrent) FontWeight.Bold else null,
                )
            }
        }
    }
}

@Preview(name = "AlphabetRail — light", showBackground = true)
@Composable
private fun AlphabetRailPreviewLight() {
    OrbitTheme(darkTheme = false) {
        AlphabetRail(
            letters = listOf("A", "B", "C", "D", "J", "M", "S", "Z", "#"),
            onLetterSelected = {},
        )
    }
}

@Preview(name = "AlphabetRail — dark", showBackground = true)
@Composable
private fun AlphabetRailPreviewDark() {
    OrbitTheme(darkTheme = true) {
        AlphabetRail(
            letters = ('A'..'Z').map { it.toString() },
            onLetterSelected = {},
        )
    }
}
