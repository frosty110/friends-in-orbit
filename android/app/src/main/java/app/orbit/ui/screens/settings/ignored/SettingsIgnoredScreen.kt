package app.orbit.ui.screens.settings.ignored

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitListSkeleton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * IGNORE-06: Settings → Ignored full nav destination.
 *
 * Renders the user's ignored people as a sorted [LazyColumn] under one intro
 * line saying what ignoring means, a one-tap "Unignore" Secondary action per
 * row, and a 4-second Undo snackbar that re-ignores on tap. The empty and
 * error states are the shared [OrbitScreenMessage] (DESIGN.md, one state
 * message for every screen), and Loading is the shared [OrbitListSkeleton],
 * so the screen never sits blank or says something false while its one
 * cold query runs (ADR 0006 as amended, rubric D6). Copy is factual and
 * without shame framing (IGNORE-10).
 *
 * Two-layer composable: the outer [SettingsIgnoredScreen] wires Hilt + the
 * [SnackbarHostState] / [SharedFlow] collector; the inner
 * [SettingsIgnoredContent] is stateless + preview-friendly and renders the
 * four UiState branches (Loading / Empty / Ready / Error). VM never imports
 * Compose APIs (project architecture conventions: ViewModels never know about composables).
 *
 * Privacy curtain (PRIV-03): row name is replaced with "Contact" when
 * `LocalPrivacyCurtain.current == true`, mirroring every other surface that
 * displays `displayName`. The avatar is masked the same way:
 * under the curtain the row renders initials derived from the masked name
 * (never the real initials) and the contact photo is suppressed. With the
 * curtain off, the photo renders when present.
 */
@Composable
fun SettingsIgnoredScreen(
    onBack: () -> Unit,
    vm: SettingsIgnoredViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    SettingsIgnoredContent(
        state = state,
        snackbarEvents = vm.snackbarEvents,
        onBack = onBack,
        onUnignore = vm::onUnignore,
        onUndo = vm::onUndo,
        onRetry = vm::onRetry,
    )
}

@Composable
private fun SettingsIgnoredContent(
    state: SettingsIgnoredUiState,
    snackbarEvents: SharedFlow<SnackbarEvent>,
    onBack: () -> Unit,
    onUnignore: (Long, String) -> Unit,
    onUndo: () -> Unit,
    onRetry: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Snackbar copy is UiText; resolved when shown.
    val context = LocalContext.current

    // Snackbar event collector; mirrors the BrowseListScreen pattern.
    // VM emits SnackbarEvent on un-ignore commit; Undo tap runs the inverse
    // closure recorded on UndoStack (re-ignore via IgnoreContactUseCase).
    LaunchedEffect(Unit) {
        snackbarEvents.collect { event ->
            val r = snackbarHostState.showSnackbar(
                message = event.message.asString(context),
                actionLabel = event.actionLabel?.asString(context),
                duration = SnackbarDuration.Short,
                withDismissAction = false,
            )
            if (r == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    OrbitScreen {
        OrbitAppBar(
            title = stringResource(R.string.settings_ignored_title),
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = stringResource(R.string.components_action_back),
                )
            },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                SettingsIgnoredUiState.Loading -> OrbitListSkeleton()
                SettingsIgnoredUiState.Empty -> OrbitScreenMessage(
                    icon = "eye-slash",
                    title = stringResource(R.string.settings_ignored_none),
                    body = stringResource(R.string.settings_ignored_empty_body),
                )
                SettingsIgnoredUiState.Error -> OrbitScreenMessage(
                    icon = "warning-circle",
                    title = stringResource(R.string.settings_ignored_error_title),
                    body = stringResource(R.string.components_error_body),
                    actionLabel = stringResource(R.string.components_error_retry),
                    onAction = onRetry,
                )
                is SettingsIgnoredUiState.Ready -> ReadyList(state.ignored, onUnignore)
            }
            OrbitSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(OrbitTheme.spacing.x4),
            )
        }
    }
}

/**
 * The rows under one intro line (IGNORE-10): what ignoring does and what it
 * keeps, in the same words as the empty state, so a person who ignored
 * someone months ago is told the consequence before they undo it.
 */
@Composable
private fun ReadyList(items: List<IgnoredContactRow>, onUnignore: (Long, String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = OrbitTheme.spacing.x2),
    ) {
        item(key = "intro", contentType = "intro") {
            Text(
                text = stringResource(R.string.settings_ignored_empty_body),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x2,
                ),
            )
        }
        items(items, key = { it.id }, contentType = { "ignored-contact" }) { row ->
            IgnoredContactRowComposable(row = row, onUnignore = onUnignore)
        }
    }
}

@Composable
private fun IgnoredContactRowComposable(
    row: IgnoredContactRow,
    onUnignore: (Long, String) -> Unit,
) {
    val curtain = LocalPrivacyCurtain.current
    val displayName = if (curtain) stringResource(R.string.components_curtain_contact) else row.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .padding(
                horizontal = OrbitTheme.spacing.x4,
                vertical = OrbitTheme.spacing.x2,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
    ) {
        // PRIV-03: avatar inputs are masked exactly like the
        // row text: under the curtain the photo is suppressed and the
        // initials derive from the masked name, so neither a face nor real
        // initials survive a glance.
        Avatar(name = displayName, size = 44.dp, photoUri = if (curtain) null else row.photoUri)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = row.ignoredRelativeLabel.asString(),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            )
        }
        OrbitButton(
            text = stringResource(R.string.settings_ignored_unignore),
            onClick = { onUnignore(row.id, row.name) },
            variant = OrbitButtonVariant.Secondary,
        )
    }
}

// Preview fixtures for the stateless SettingsIgnoredContent (THEME-04 /
// THEME-05), one per state so each renders in the screenshot gallery and its
// audits: the Ready rows carry names the curtain audit watches for (PRIV-03),
// one with a photo so the masked avatar is exercised too.
private val previewReady: SettingsIgnoredUiState = SettingsIgnoredUiState.Ready(
    ignored = listOf(
        IgnoredContactRow(
            id = 1L,
            name = "Alex Chen",
            photoUri = null,
            ignoredAtMs = 0L,
            ignoredRelativeLabel = UiText.res(R.string.settings_ignored_relative, UiText.res(R.string.time_ago_today)),
        ),
        IgnoredContactRow(
            id = 2L,
            name = "Priya Natarajan",
            photoUri = "content://com.android.contacts/display_photo/2",
            ignoredAtMs = 0L,
            ignoredRelativeLabel = UiText.res(
                R.string.settings_ignored_relative,
                UiText.plural(R.plurals.time_ago_weeks, 3, 3),
            ),
        ),
    ),
)

@Composable
private fun SettingsIgnoredPreviewHost(state: SettingsIgnoredUiState) {
    OrbitTheme {
        SettingsIgnoredContent(
            state = state,
            snackbarEvents = MutableSharedFlow<SnackbarEvent>().asSharedFlow(),
            onBack = {},
            onUnignore = { _, _ -> },
            onUndo = {},
            onRetry = {},
        )
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun SettingsIgnoredContentPreview() {
    SettingsIgnoredPreviewHost(state = SettingsIgnoredUiState.Empty)
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun SettingsIgnoredReadyPreview() {
    SettingsIgnoredPreviewHost(state = previewReady)
}

@PreviewLightDark
@Composable
private fun SettingsIgnoredLoadingPreview() {
    SettingsIgnoredPreviewHost(state = SettingsIgnoredUiState.Loading)
}

@PreviewLightDark
@Composable
private fun SettingsIgnoredErrorPreview() {
    SettingsIgnoredPreviewHost(state = SettingsIgnoredUiState.Error)
}
