package app.orbit.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.domain.model.PauseDuration
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * Duration picker for the multi-select overflow's "Pause all" action.
 * Material3 [AlertDialog] with three stacked Secondary [OrbitButton]
 * choices + a Ghost Cancel.
 *
 * Locked copy (verbatim):
 *  - Title: "Pause for how long?"
 *  - Choice 1: "1 week"
 *  - Choice 2: "1 month"
 *  - Choice 3: "Indefinitely"  (capital I)
 *  - Cancel: "Cancel"
 *
 * Each choice maps to a [PauseDuration] sealed-class member and dismisses the
 * dialog after invoking [onSelect]. Tap-Cancel is a separate path (no
 * [onSelect]) — caller's `showPauseDialog = false` runs from `onDismiss`.
 *
 * Mirrors [app.orbit.ui.screens.lists.ConvertToStaticDialog] precedent for
 * `containerColor = OrbitTheme.colors.surface` + dismiss-on-confirm pattern.
 */
@Composable
fun PauseDurationDialog(
    onSelect: (PauseDuration) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = "Pause for how long?",
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OrbitButton(
                    text = "1 week",
                    onClick = { onSelect(PauseDuration.OneWeek) },
                    variant = OrbitButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                OrbitButton(
                    text = "1 month",
                    onClick = { onSelect(PauseDuration.OneMonth) },
                    variant = OrbitButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                OrbitButton(
                    text = "Indefinitely",
                    onClick = { onSelect(PauseDuration.Indefinite) },
                    variant = OrbitButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        // No confirmButton — choice IS the confirm. Cancel-only dismiss button.
        confirmButton = {},
        dismissButton = {
            OrbitButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = OrbitButtonVariant.Ghost,
            )
        },
    )
}

// region Previews

@Preview(name = "PauseDurationDialog — light", showBackground = true)
@Composable
private fun PauseDurationDialogLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            PauseDurationDialog(onSelect = {}, onDismiss = {})
        }
    }
}

@Preview(name = "PauseDurationDialog — dark", showBackground = true)
@Composable
private fun PauseDurationDialogDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            PauseDurationDialog(onSelect = {}, onDismiss = {})
        }
    }
}

// endregion
