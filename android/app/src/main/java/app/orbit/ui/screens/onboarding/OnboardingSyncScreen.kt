package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.ui.components.ImportRangeChipGroup
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import kotlinx.coroutines.delay

/**
 * ONB-16/17/18: blocking call-log sync gate. Sits between the
 * permission rationale screens and the preview / first-list step. Continue is
 * gated on WorkInfo.SUCCEEDED OR a single retry-failed-once "Continue anyway"
 * override.
 *
 * Voice: calm copy; sentence case; no exclamation. Progress renders as an
 * indeterminate LinearProgressIndicator plus a live "Counted N calls over M
 * people" line fed by the VM's count flows; a determinate
 * WorkInfo.progress switch was considered but the live counts carry the
 * feels-alive signal instead.
 */
@Composable
fun OnboardingSyncScreen(
    onContinue: () -> Unit,
    vm: OnboardingSyncViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    OnboardingSyncContent(
        state = state,
        onContinue = onContinue,
        onRetry = vm::onRetry,
        onImportDaysSelected = vm::onImportDaysSelected,
    )
}

// Internal, not private: OnboardingSyncChipsSemanticsTest renders this
// stateless layer to read the chip row's semantics.
@Composable
internal fun OnboardingSyncContent(
    state: OnboardingSyncUiState,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onImportDaysSelected: (Int) -> Unit,
) {
    val ready = state as? OnboardingSyncUiState.Ready

    val canContinue = ready?.syncState is SyncState.Succeeded ||
        ready?.syncState is SyncState.Empty ||
        ready?.syncState is SyncState.Skipped
    val retryFailed = (ready?.syncState as? SyncState.Failed)?.retryCount?.let { it >= 1 } ?: false
    val skipped = ready?.syncState is SyncState.Skipped
    val title = stringResource(if (skipped) R.string.onb_sync_title_skipped else R.string.onb_sync_title)

    OnboardingScaffold(
        title = title,
        step = OnboardingStep.Sync,
        onBack = null,
        primary = OnboardingAction(
            label = stringResource(
                when {
                    canContinue -> R.string.components_action_continue
                    retryFailed -> R.string.onb_sync_continue_anyway
                    else -> R.string.components_action_continue
                },
            ),
            onClick = onContinue,
            enabled = canContinue || retryFailed,
        ),
        secondary = if (ready?.syncState is SyncState.Failed) {
            OnboardingAction(
                // The shared "Try again" (strings_components.xml), the same
                // words every error state uses; the second attempt says so.
                label = stringResource(if (retryFailed) R.string.onb_sync_try_once_more else R.string.components_error_retry),
                onClick = onRetry,
            )
        } else {
            null
        },
    ) {
        Text(
            text = title,
            style = OrbitTheme.type.title.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = if (skipped) {
                stringResource(R.string.onb_sync_body_skipped)
            } else {
                val days = ready?.importDays ?: 90
                pluralStringResource(R.plurals.onb_sync_body, days, days)
            },
            // Plain body copy reads fgMuted; `info` is reserved
            // for semantic emphasis, not paragraph text.
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x6))

        if (!skipped) {
            ImportRangeChips(
                selectedDays = ready?.importDays ?: 90,
                onSelect = onImportDaysSelected,
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x4))
        }

        SyncProgressCard(state = ready?.syncState ?: SyncState.InProgress, ready = ready)

        var showSlowTip by remember { mutableStateOf(false) }
        LaunchedEffect(ready?.syncState) {
            if (ready?.syncState is SyncState.InProgress) {
                delay(10_000L)
                showSlowTip = true
            } else {
                showSlowTip = false
            }
        }
        if (showSlowTip) {
            Spacer(Modifier.height(OrbitTheme.spacing.x4))
            SlowTipCard()
        }
    }
}

@Composable
private fun SyncProgressCard(state: SyncState, ready: OnboardingSyncUiState.Ready?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .orbitCardShadow(OrbitTheme.shapes.lg, OrbitTheme.colors.isDark)
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.surface)
            .padding(OrbitTheme.spacing.x4),
    ) {
        when (state) {
            SyncState.InProgress -> {
                // Ink, not accent: the bar is not an action, and the footer's
                // Continue is this screen's one accent element (rules.md
                // §Design 5). Until 2026-10-06 the bar and the button were
                // both terracotta for the whole reading phase.
                LinearProgressIndicator(
                    color = OrbitTheme.colors.fg,
                    trackColor = OrbitTheme.colors.bgSubtle,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x3))
                FriendlyCount(callCount = ready?.callCount ?: 0, contactCount = ready?.contactCount ?: 0)
            }
            SyncState.Succeeded -> {
                FriendlyCount(callCount = ready?.callCount ?: 0, contactCount = ready?.contactCount ?: 0)
            }
            SyncState.Empty -> {
                Text(
                    text = stringResource(R.string.onb_sync_learn),
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x1))
                val days = ready?.importDays ?: 90
                Text(
                    text = pluralStringResource(R.plurals.onb_sync_empty, days, days),
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                )
            }
            SyncState.Skipped -> {
                Text(
                    text = stringResource(R.string.onb_sync_learn),
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x1))
                Text(
                    text = stringResource(R.string.onb_sync_no_access),
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                )
            }
            is SyncState.Failed -> {
                val (title, sub) = if (state.retryCount >= 1) {
                    stringResource(R.string.onb_sync_failed_final) to stringResource(R.string.onb_sync_failed_final_sub)
                } else {
                    stringResource(R.string.onb_sync_failed_retry) to ""
                }
                Text(text = title, style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg))
                if (sub.isNotEmpty()) {
                    Spacer(Modifier.height(OrbitTheme.spacing.x1))
                    Text(text = sub, style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted))
                }
            }
        }
    }
}

@Composable
private fun FriendlyCount(callCount: Int, contactCount: Int) {
    val callsFragment = pluralStringResource(R.plurals.onb_sync_calls, callCount, callCount)
    // "people", not "contacts": the glossary keeps "contacts" for the phone's
    // address book (voice.md).
    val peopleFragment = pluralStringResource(R.plurals.onb_sync_contacts, contactCount, contactCount)
    Text(
        text = stringResource(R.string.onb_sync_counted, callsFragment, peopleFragment),
        style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
    )
}

/**
 * Look-back window selector: the question over [ImportRangeChipGroup], the
 * same radio group Settings' import range row draws, so the same setting
 * looks, reads and announces the same on both screens. Before 2026-10-06 this
 * was a Material FilterChip with a colour override that offered three windows
 * and said "90 days" where Settings offered four and said "3 months" (onb-9);
 * until 2026-10-07 Settings still drew checkboxes.
 *
 * Selecting a chip persists `callLogImportDays` and re-runs the import for the
 * new window (VM.onImportDaysSelected); the default (90) is pre-selected and
 * already importing, so the common path stays friction-free.
 */
@Composable
private fun ImportRangeChips(
    selectedDays: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.onb_sync_range_question),
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
        )
        ImportRangeChipGroup(
            selectedDays = selectedDays,
            onSelect = onSelect,
            modifier = Modifier.padding(top = OrbitTheme.spacing.x2),
        )
    }
}

@Composable
private fun SlowTipCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitTheme.shapes.md)
            .background(OrbitTheme.colors.bgSubtle)
            .padding(OrbitTheme.spacing.x3),
    ) {
        Text(
            text = stringResource(R.string.onb_sync_slow_tip),
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
        )
    }
}

// One preview per state, so each cell of the state matrix renders in the
// screenshot gallery and its audits (rubric D6). Until 2026-10-06 only
// InProgress had one.
@Composable
private fun OnboardingSyncPreviewBody(syncState: SyncState, callCount: Int = 142, contactCount: Int = 32) {
    OrbitTheme {
        OnboardingSyncContent(
            state = OnboardingSyncUiState.Ready(
                syncState = syncState,
                callCount = callCount,
                contactCount = contactCount,
                importDays = 90,
            ),
            onContinue = {},
            onRetry = {},
            onImportDaysSelected = {},
        )
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingSyncScreenPreview() {
    OnboardingSyncPreviewBody(SyncState.InProgress)
}

@PreviewLightDark
@Composable
private fun OnboardingSyncSucceededPreview() {
    OnboardingSyncPreviewBody(SyncState.Succeeded)
}

@PreviewLightDark
@Composable
private fun OnboardingSyncEmptyPreview() {
    OnboardingSyncPreviewBody(SyncState.Empty, callCount = 0, contactCount = 0)
}

@PreviewLightDark
@Composable
private fun OnboardingSyncSkippedPreview() {
    OnboardingSyncPreviewBody(SyncState.Skipped, callCount = 0, contactCount = 0)
}

@PreviewLightDark
@Composable
private fun OnboardingSyncFailedPreview() {
    OnboardingSyncPreviewBody(SyncState.Failed(retryCount = 0), callCount = 0, contactCount = 0)
}

// After one failed retry: "Try one more time" and "Continue anyway" (ONB-18).
@PreviewLightDark
@Composable
private fun OnboardingSyncFailedTwicePreview() {
    OnboardingSyncPreviewBody(SyncState.Failed(retryCount = 1), callCount = 0, contactCount = 0)
}
