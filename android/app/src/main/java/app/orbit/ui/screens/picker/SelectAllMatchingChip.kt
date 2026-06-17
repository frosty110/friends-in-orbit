package app.orbit.ui.screens.picker

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * "Select all matching" affordance (PICK-03).
 *
 * Visible iff [ContactPickerUiState.canSelectAllMatching] is true. The caller
 * (ContactPickerScreen) places this BETWEEN the filter chips row and the
 * LazyColumn.
 *
 * Copy: "Select all matching ($matchingCount)"
 */
@Composable
fun SelectAllMatchingChip(
    matchingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OrbitButton(
        text = "Select all matching ($matchingCount)",
        onClick = onClick,
        variant = OrbitButtonVariant.Ghost,
        modifier = modifier.fillMaxWidth(),
    )
}

@Preview(name = "SelectAllMatchingChip — light", showBackground = true)
@Composable
private fun SelectAllMatchingChipPreviewLight() {
    OrbitTheme(darkTheme = false) {
        SelectAllMatchingChip(matchingCount = 14, onClick = {})
    }
}

@Preview(name = "SelectAllMatchingChip — dark", showBackground = true)
@Composable
private fun SelectAllMatchingChipPreviewDark() {
    OrbitTheme(darkTheme = true) {
        SelectAllMatchingChip(matchingCount = 7, onClick = {})
    }
}
