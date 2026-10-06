package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitCheckbox
import app.orbit.ui.components.OrbitScreenMessage
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString

/**
 * ONB-19: H/β recency x frequency preview screen.
 *
 * Auto-skips to the manual first-list path when fewer than 3 candidates
 * match ("skipped entirely if fewer than 3 match"). Default name "In touch"
 * is passed forward when the user taps the primary CTA.
 *
 * Selection (decision D-02): every row starts selected; the user opts out per
 * row with the trailing checkbox or by tapping the row. A "Select all" /
 * "Deselect all" toggle at the top flips the whole list. Selection state
 * survives configuration change via [rememberSaveable] keyed on the candidate
 * ID set so a rerank re-defaults cleanly.
 *
 * States: Loading is a quiet skeleton under disabled CTAs; Error says the
 * suggestions could not be read and offers Try again; both keep "Start blank"
 * live as the exit, so this step never dead-ends (G4).
 *
 * Voice: per the auto-suggested preview spec: sentence case, no exclamation,
 * no "Awesome".
 */
@Composable
fun OnboardingPreviewScreen(
    onAccept: (defaultName: String, contactIds: List<Long>) -> Unit,
    onSkip: () -> Unit,
    vm: OnboardingPreviewViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ready = state as? OnboardingPreviewUiState.Ready

    LaunchedEffect(ready?.candidates?.size) {
        if (ready != null && ready.candidates.size < 3) onSkip()
    }

    val defaultName = ready?.defaultName?.asString().orEmpty()

    when (state) {
        OnboardingPreviewUiState.Loading -> OnboardingPreviewLoadingContent(onSkip = onSkip)
        OnboardingPreviewUiState.Error -> OnboardingPreviewErrorContent(onRetry = vm::onRetry, onSkip = onSkip)
        is OnboardingPreviewUiState.Ready -> {
            if (ready == null || ready.candidates.size < 3) return
            OnboardingPreviewContent(
                state = ready,
                onAccept = { selectedIds ->
                    onAccept(defaultName, selectedIds)
                },
                onSkip = onSkip,
            )
        }
    }
}

/**
 * Loading used to render nothing (blank flash while the H/β rank settles).
 * Quiet skeleton under disabled CTAs instead, the FirstListLoadingSkeleton
 * idiom. "Start blank" stays live as an exit.
 */
@Composable
private fun OnboardingPreviewLoadingContent(onSkip: () -> Unit) {
    OnboardingScaffold(
        title = stringResource(R.string.onb_preview_title),
        step = OnboardingStep.FirstList,
        onBack = null,
        primary = OnboardingAction(
            label = stringResource(R.string.onb_preview_accept),
            onClick = {},
            enabled = false,
        ),
        secondary = OnboardingAction(
            label = stringResource(R.string.onb_preview_start_blank),
            onClick = onSkip,
        ),
    ) {
        PreviewLoadingSkeleton()
    }
}

/**
 * The suggestions could not be read. The footer's Primary is Try again, the
 * screen's one accent, and "Start blank" stays live so the user is never
 * held here. The message carries no button of its own: one action, one place.
 */
@Composable
private fun OnboardingPreviewErrorContent(onRetry: () -> Unit, onSkip: () -> Unit) {
    OnboardingScaffold(
        title = stringResource(R.string.onb_preview_error_title),
        step = OnboardingStep.FirstList,
        onBack = null,
        primary = OnboardingAction(
            label = stringResource(R.string.components_error_retry),
            onClick = onRetry,
        ),
        secondary = OnboardingAction(
            label = stringResource(R.string.onb_preview_start_blank),
            onClick = onSkip,
        ),
        scrollable = false,
    ) {
        OrbitScreenMessage(
            icon = "warning-circle",
            title = stringResource(R.string.onb_preview_error_title),
            body = stringResource(R.string.components_error_body),
        )
    }
}

@Composable
private fun OnboardingPreviewContent(
    state: OnboardingPreviewUiState.Ready,
    onAccept: (List<Long>) -> Unit,
    onSkip: () -> Unit,
) {
    val candidateIds: List<Long> = state.candidates.map { it.contactId }
    // Key the saver on the candidate-ID set so a re-rank from the VM
    // re-defaults to "all selected" rather than retaining stale picks.
    val selectionKey = candidateIds.joinToString(",")
    val selectionState = rememberSaveable(
        selectionKey,
        stateSaver = LongSetSaver,
    ) { mutableStateOf(candidateIds.toSet()) }
    var selectedIds: Set<Long> by selectionState

    val allSelected = selectedIds.size == candidateIds.size && candidateIds.isNotEmpty()
    val noneSelected = selectedIds.isEmpty()
    val title = stringResource(R.string.onb_preview_title)

    OnboardingScaffold(
        title = title,
        step = OnboardingStep.FirstList, // shown as the first-list step: it leads straight into it
        onBack = null,
        primary = OnboardingAction(
            label = stringResource(R.string.onb_preview_accept),
            onClick = {
                onAccept(candidateIds.filter { it in selectedIds })
            },
            enabled = !noneSelected,
        ),
        secondary = OnboardingAction(
            label = stringResource(R.string.onb_preview_start_blank),
            onClick = onSkip,
        ),
    ) {
        Text(
            // No count in the heading: the gate admits 3 to 10 people and the
            // rows show how many; "5 to 10" was false for 3 or 4.
            text = title,
            style = OrbitTheme.type.title.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = stringResource(R.string.onb_preview_body),
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = pluralStringResource(R.plurals.onb_preview_selected, selectedIds.size, selectedIds.size),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            )
            OrbitButton(
                text = stringResource(if (allSelected) R.string.onb_preview_deselect_all else R.string.onb_preview_select_all),
                onClick = {
                    selectedIds = if (allSelected) emptySet() else candidateIds.toSet()
                },
                variant = OrbitButtonVariant.Ghost,
            )
        }
        Spacer(Modifier.height(OrbitTheme.spacing.x2))

        Column(verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2)) {
            state.candidates.forEach { candidate ->
                PreviewRow(
                    candidate = candidate,
                    isSelected = candidate.contactId in selectedIds,
                    onToggle = {
                        selectedIds = if (candidate.contactId in selectedIds) {
                            selectedIds - candidate.contactId
                        } else {
                            selectedIds + candidate.contactId
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PreviewRow(
    candidate: PreviewCandidate,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    // Rows start all-selected here, so a tinted selected state turned the
    // whole screen accent. Rows stay on the neutral surface; the checkbox mark
    // alone carries selection, in ink (rules.md §Design 5). The row is the
    // control and carries the state for TalkBack ("checkbox, checked"), as in
    // the pickers; the mark is display only (OrbitCheckbox, rules.md Code 7).
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.md)
            .background(OrbitTheme.colors.surface)
            .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(OrbitTheme.spacing.x3),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // PRIV-03: masked under the curtain like every other name.
                text = if (LocalPrivacyCurtain.current) stringResource(R.string.components_curtain_contact) else candidate.displayName,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = candidate.lastCallRelative.asString(),
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            )
        }
        OrbitCheckbox(checked = isSelected)
    }
}

/**
 * Quiet placeholder while [OnboardingPreviewUiState.Loading]:
 * a title-width muted bar plus a handful of row-height bars, mirroring
 * [OnboardingFirstListScreen]'s FirstListLoadingSkeleton idiom. No copy:
 * the headline would promise people before the rank has settled.
 */
@Composable
private fun PreviewLoadingSkeleton() {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(OrbitTheme.spacing.x6)
            .clip(OrbitTheme.shapes.md)
            .background(OrbitTheme.colors.bgSubtle),
    )
    Spacer(Modifier.height(OrbitTheme.spacing.x4))
    Column(verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2)) {
        repeat(5) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OrbitTheme.spacing.x10)
                    .clip(OrbitTheme.shapes.md)
                    .background(OrbitTheme.colors.bgSubtle),
            )
        }
    }
}

private val LongSetSaver = listSaver<Set<Long>, Long>(
    save = { it.toList() },
    restore = { it.toSet() },
)

// Loading skeleton under disabled CTAs (mirrors
// OnboardingFirstListLoadingPreview).
@PreviewLightDark
@Composable
private fun OnboardingPreviewLoadingPreview() {
    OrbitTheme {
        OnboardingPreviewLoadingContent(onSkip = {})
    }
}

// A failed read: Try again is the footer's one accent, Start blank stays live.
@PreviewLightDark
@Composable
private fun OnboardingPreviewErrorPreview() {
    OrbitTheme {
        OnboardingPreviewErrorContent(onRetry = {}, onSkip = {})
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingPreviewScreenPreview() {
    OrbitTheme {
        OnboardingPreviewContent(
            state = OnboardingPreviewUiState.Ready(
                candidates = listOf(
                    PreviewCandidate(1L, "Sam", UiText.res(R.string.onb_preview_called, "2 days ago")),
                    PreviewCandidate(2L, "Alex", UiText.res(R.string.onb_preview_called, "4 days ago")),
                    PreviewCandidate(3L, "Jordan", UiText.res(R.string.onb_preview_called, "a week ago")),
                ),
            ),
            onAccept = {},
            onSkip = {},
        )
    }
}
