package app.orbit.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.orbit.R
import app.orbit.ui.theme.OrbitTheme

/**
 * The one snackbar host. Material's default snackbar draws its action (the
 * Undo every reversible change in Orbit offers) as a 40dp TextButton, under
 * the 48dp floor (rules.md §Design 3, rubric gate G2); the preview gallery's
 * audit flagged it. This is Material's snackbar, themed by OrbitTheme's
 * colour scheme as before, with the action held to 48dp.
 */
@Composable
fun OrbitSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data -> OrbitSnackbar(data) }
}

@Composable
fun OrbitSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    OrbitSnackbar(
        message = data.visuals.message,
        actionLabel = data.visuals.actionLabel,
        onAction = data::performAction,
        onDismiss = if (data.visuals.withDismissAction) data::dismiss else null,
        modifier = modifier,
    )
}

@Composable
fun OrbitSnackbar(
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Snackbar(
        modifier = modifier,
        action = actionLabel?.let { label ->
            {
                TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    modifier = Modifier.heightIn(min = OrbitTheme.spacing.tapMin),
                ) { Text(label) }
            }
        },
        dismissAction = onDismiss?.let { dismiss ->
            {
                OrbitIconButton(
                    icon = "x",
                    onClick = dismiss,
                    contentDescription = stringResource(R.string.components_snackbar_dismiss),
                )
            }
        },
    ) {
        Text(message)
    }
}

@PreviewLightDark
@Composable
private fun OrbitSnackbarPreview() {
    OrbitTheme {
        OrbitSnackbar(
            message = pluralStringResource(R.plurals.picker_snackbar_added, 3, 3, "In touch"),
            actionLabel = stringResource(R.string.components_action_undo),
            onAction = {},
        )
    }
}
