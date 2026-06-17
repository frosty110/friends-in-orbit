package app.orbit.ui.screens.picker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

/** Height of one letter cell — drives the y → index mapping below. */
private val RailCellHeight = 16.dp

/**
 * Right-edge fast-scroll rail for the picker's alphabetical sort. Renders the
 * section letters present in the current filtered list (not a fixed A–Z, so
 * every letter is actionable) and maps a press or drag anywhere on the strip
 * to [onLetterSelected] with the letter's index.
 *
 * Tap-target note: individual letter cells are deliberately below the 48dp
 * floor (rules.md Design 3). The mitigation is that the WHOLE rail is one
 * continuous gesture surface — pressing or dragging anywhere resolves to the
 * nearest letter, the same pattern the system contacts app uses for its
 * scrubber.
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
) {
    if (letters.isEmpty()) return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(OrbitTheme.spacing.x5)
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
                                onLetterSelected(index)
                            }
                            change.consume()
                        } else {
                            lastIndex = -1
                        }
                    }
                }
            },
    ) {
        letters.forEach { letter ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.height(RailCellHeight),
            ) {
                Text(
                    text = letter,
                    style = OrbitTheme.type.micro,
                    color = OrbitTheme.colors.fgMuted,
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
