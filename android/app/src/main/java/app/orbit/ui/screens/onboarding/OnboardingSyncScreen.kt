package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import kotlinx.coroutines.delay

/**
 * ONB-16/17/18 — blocking call-log sync gate. Sits between the
 * permission rationale screens and the preview / first-list step. Continue is
 * gated on WorkInfo.SUCCEEDED OR a single retry-failed-once "Continue anyway"
 * override.
 *
 * Voice: calm copy; sentence case; no exclamation. Progress renders as an
 * indeterminate LinearProgressIndicator plus a live "Counted N calls over M
 * contacts" line fed by the VM's count flows — a determinate
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

@Composable
private fun OnboardingSyncContent(
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

    OnboardingScaffold(
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
                label = stringResource(if (retryFailed) R.string.onb_sync_try_once_more else R.string.onb_sync_try_again),
                onClick = onRetry,
            )
        } else {
            null
        },
    ) {
        val skipped = ready?.syncState is SyncState.Skipped
        Text(
            text = stringResource(if (skipped) R.string.onb_sync_title_skipped else R.string.onb_sync_title),
            style = OrbitTheme.type.title.copy(color = OrbitTheme.colors.fg),
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
                LinearProgressIndicator(
                    color = OrbitTheme.colors.accent,
                    trackColor = OrbitTheme.colors.accentTint,
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
    val contactsFragment = pluralStringResource(R.plurals.onb_sync_contacts, contactCount, contactCount)
    Text(
        text = stringResource(R.string.onb_sync_counted, callsFragment, contactsFragment),
        style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
    )
}

/**
 * Look-back window selector. Mirrors Settings' `ImportRangeRow` idiom
 * (accentTint selected container, 48dp tap floor) so the two surfaces agree.
 * Selecting a chip persists `callLogImportDays` and re-runs the import for the
 * new window (VM.onImportDaysSelected) — the default (90) is pre-selected and
 * already importing, so the common path stays friction-free.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        Row(
            modifier = Modifier.padding(top = OrbitTheme.spacing.x2),
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        ) {
            IMPORT_DAY_OPTIONS.forEach { days ->
                FilterChip(
                    selected = selectedDays == days,
                    onClick = { onSelect(days) },
                    label = { Text(pluralStringResource(R.plurals.onb_sync_range_days, days, days)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OrbitTheme.colors.accentTint,
                        selectedLabelColor = OrbitTheme.colors.fg,
                    ),
                    modifier = Modifier.defaultMinSize(minHeight = OrbitTheme.spacing.tapMin),
                )
            }
        }
    }
}

private val IMPORT_DAY_OPTIONS: List<Int> = listOf(90, 180, 365)

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

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingSyncScreenPreview() {
    OrbitTheme {
        OnboardingSyncContent(
            state = OnboardingSyncUiState.Ready(
                syncState = SyncState.InProgress,
                callCount = 142,
                contactCount = 32,
                importDays = 90,
            ),
            onContinue = {},
            onRetry = {},
            onImportDaysSelected = {},
        )
    }
}
