package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.theme.OrbitTheme

/**
 * ONB-19 — H/β recency × frequency preview screen.
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
 * Voice: per the auto-suggested preview spec — sentence case, no exclamation,
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

    if (ready == null) {
        // Loading used to render nothing (blank flash while the H/β rank
        // settles). Quiet skeleton under disabled CTAs instead, the
        // FirstListLoadingSkeleton idiom. "Start blank" stays live as an exit.
        OnboardingScaffold(
            step = OnboardingStep.FirstList,
            onBack = null,
            primary = OnboardingAction(
                label = "Make this my first list",
                onClick = {},
                enabled = false,
            ),
            secondary = OnboardingAction(
                label = "Start blank",
                onClick = onSkip,
            ),
        ) {
            PreviewLoadingSkeleton()
        }
        return
    }

    if (ready.candidates.size < 3) return

    OnboardingPreviewContent(
        state = ready,
        onAccept = { selectedIds ->
            onAccept(ready.defaultName, selectedIds)
        },
        onSkip = onSkip,
    )
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

    OnboardingScaffold(
        step = OnboardingStep.FirstList, // logically still step 5
        onBack = null,
        primary = OnboardingAction(
            label = "Make this my first list",
            onClick = {
                onAccept(candidateIds.filter { it in selectedIds })
            },
            enabled = !noneSelected,
        ),
        secondary = OnboardingAction(
            label = "Start blank",
            onClick = onSkip,
        ),
    ) {
        Text(
            text = "Here are 5 to 10 people you've been in touch with",
            style = OrbitTheme.type.title.copy(color = OrbitTheme.colors.fg),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = "Untick anyone you'd rather not include. " +
                "You can edit anything before saving.",
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x4))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${selectedIds.size} selected",
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            )
            OrbitButton(
                text = if (allSelected) "Deselect all" else "Select all",
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
    // whole screen accent. Rows stay on the neutral
    // surface; the checkbox mark alone carries selection (accent budget,
    // project convention, design rule 5).
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .clip(OrbitTheme.shapes.md)
            .background(OrbitTheme.colors.surface)
            .clickable(onClick = onToggle)
            .padding(OrbitTheme.spacing.x3)
            .semantics { selected = isSelected },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.displayName,
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            )
            Text(
                text = candidate.lastCallRelative,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            )
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = OrbitTheme.colors.accent,
                checkmarkColor = OrbitTheme.colors.accentFg,
                uncheckedColor = OrbitTheme.colors.fgMuted,
            ),
        )
    }
}

/**
 * Quiet placeholder while [OnboardingPreviewUiState.Loading]:
 * a title-width muted bar plus a handful of row-height bars, mirroring
 * [OnboardingFirstListScreen]'s FirstListLoadingSkeleton idiom. No copy —
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
        OnboardingScaffold(
            step = OnboardingStep.FirstList,
            onBack = null,
            primary = OnboardingAction(
                label = "Make this my first list",
                onClick = {},
                enabled = false,
            ),
            secondary = OnboardingAction(label = "Start blank", onClick = {}),
        ) {
            PreviewLoadingSkeleton()
        }
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
                    PreviewCandidate(1L, "Sam", "Called 2 days ago"),
                    PreviewCandidate(2L, "Alex", "Called 4 days ago"),
                    PreviewCandidate(3L, "Jordan", "Called a week ago"),
                ),
            ),
            onAccept = {},
            onSkip = {},
        )
    }
}
