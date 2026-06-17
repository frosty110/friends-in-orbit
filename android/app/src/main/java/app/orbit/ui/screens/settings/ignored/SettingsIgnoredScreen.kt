package app.orbit.ui.screens.settings.ignored

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.PhIcon
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.theme.OrbitTheme
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * IGNORE-06 — Settings → Ignored full nav destination.
 *
 * Renders the user's ignored-contacts list with a sorted [LazyColumn], a
 * one-tap "Un-ignore" Secondary action per row, and a 4-second Undo snackbar
 * that re-ignores on tap. Empty state explains the surfacing semantic without
 * shame framing — voice rule lock per IGNORE-10.
 *
 * Two-layer composable — the outer [SettingsIgnoredScreen] wires Hilt + the
 * [SnackbarHostState] / [SharedFlow] collector; the inner
 * [SettingsIgnoredContent] is stateless + preview-friendly and renders the
 * three terminal UiState branches (Loading / Empty / Ready). VM never imports
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
    )
}

@Composable
private fun SettingsIgnoredContent(
    state: SettingsIgnoredUiState,
    snackbarEvents: SharedFlow<SnackbarEvent>,
    onBack: () -> Unit,
    onUnignore: (Long, String) -> Unit,
    onUndo: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Snackbar event collector — mirrors the BrowseListScreen pattern.
    // VM emits SnackbarEvent on un-ignore commit; Undo tap runs the inverse
    // closure recorded on UndoStack (re-ignore via IgnoreContactUseCase).
    LaunchedEffect(Unit) {
        snackbarEvents.collect { event ->
            val r = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = SnackbarDuration.Short,
                withDismissAction = false,
            )
            if (r == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    OrbitScreen {
        OrbitAppBar(
            title = "Ignored",
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = onBack,
                    contentDescription = "Back",
                )
            },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                SettingsIgnoredUiState.Loading -> Unit
                SettingsIgnoredUiState.Empty -> EmptyState()
                is SettingsIgnoredUiState.Ready -> ReadyList(state.ignored, onUnignore)
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(OrbitTheme.spacing.x4),
            )
        }
    }
}

/**
 * Empty-state copy. "Hidden from surfacing — history kept" carries the
 * IGNORE-10 voice rule — factual and non-judgmental.
 */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhIcon(name = "eye-slash", size = 32.dp, tint = OrbitTheme.colors.fgMuted)
        Spacer(Modifier.height(OrbitTheme.spacing.x4))
        Text(
            text = "No ignored contacts",
            style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        Text(
            text = "Hidden from surfacing — history kept",
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

@Composable
private fun ReadyList(items: List<IgnoredContactRow>, onUnignore: (Long, String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = OrbitTheme.spacing.x4),
    ) {
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
    val displayName = if (curtain) "Contact" else row.name
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
        // PRIV-03 — avatar inputs are masked exactly like the
        // row text: under the curtain the photo is suppressed and the
        // initials derive from the masked name, so neither a face nor real
        // initials survive a glance. Photo-vs-initials branch mirrors
        // PickerContactRow.
        if (!curtain && !row.photoUri.isNullOrBlank()) {
            AsyncImage(
                model = row.photoUri,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
            )
        } else {
            Avatar(name = displayName, size = 44.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = row.ignoredRelativeLabel,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            )
        }
        OrbitButton(
            text = "Un-ignore",
            onClick = { onUnignore(row.id, row.name) },
            variant = OrbitButtonVariant.Secondary,
        )
    }
}

// Preview fixture for the stateless SettingsIgnoredContent
// (THEME-04 / THEME-05 — D-06). Empty state renders the "No ignored contacts"
// copy without a row fixture.
private val previewState: SettingsIgnoredUiState = SettingsIgnoredUiState.Empty

@PreviewLightDark
@PreviewFontScale
@Composable
private fun SettingsIgnoredContentPreview() {
    OrbitTheme {
        SettingsIgnoredContent(
            state = previewState,
            snackbarEvents = MutableSharedFlow<SnackbarEvent>().asSharedFlow(),
            onBack = {},
            onUnignore = { _, _ -> },
            onUndo = {},
        )
    }
}
