package app.orbit.ui.screens.picker

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
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
 * Copy: "Select all $matchingCount matches" ("Select the 1 match" for one).
 * It read "Select all matching (14)", a parenthesised count that read like a
 * developer label (rubric D7).
 */
@Composable
fun SelectAllMatchingChip(
    matchingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OrbitButton(
        text = pluralStringResource(R.plurals.picker_select_all_matching, matchingCount, matchingCount),
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

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "SelectAllMatchingChip — dark", showBackground = true)
@Composable
private fun SelectAllMatchingChipPreviewDark() {
    OrbitTheme(darkTheme = true) {
        SelectAllMatchingChip(matchingCount = 7, onClick = {})
    }
}
