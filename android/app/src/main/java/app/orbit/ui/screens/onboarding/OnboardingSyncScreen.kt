package app.orbit.ui.screens.onboarding

import androidx.compose.foundation.background
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
    )
}

@Composable
private fun OnboardingSyncContent(
    state: OnboardingSyncUiState,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
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
            label = when {
                canContinue -> "Continue"
                retryFailed -> "Continue anyway"
                else -> "Continue"
            },
            onClick = onContinue,
            enabled = canContinue || retryFailed,
        ),
        secondary = if (ready?.syncState is SyncState.Failed) {
            OnboardingAction(
                label = if (retryFailed) "Try one more time" else "Try again",
                onClick = onRetry,
            )
        } else {
            null
        },
    ) {
        val skipped = ready?.syncState is SyncState.Skipped
        Text(
            text = if (skipped) "Starting fresh" else "Reading your call history",
            style = OrbitTheme.type.title.copy(color = OrbitTheme.colors.fg),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = if (skipped) {
                "Without call history, Orbit starts from what you tell it."
            } else {
                "Reading your last 90 days of calls — never leaves your device."
            },
            // Plain body copy reads fgMuted; `info` is reserved
            // for semantic emphasis, not paragraph text.
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x6))

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
                    text = "We'll learn as you go.",
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x1))
                Text(
                    text = "No calls found in the last 90 days. That's okay.",
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                )
            }
            SyncState.Skipped -> {
                Text(
                    text = "We'll learn as you go.",
                    style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
                )
                Spacer(Modifier.height(OrbitTheme.spacing.x1))
                Text(
                    text = "Orbit doesn't have call history access. You can grant it any time in Settings.",
                    style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
                )
            }
            is SyncState.Failed -> {
                val (title, sub) = if (state.retryCount >= 1) {
                    "Couldn't finish the sync." to "We'll try again later in the background."
                } else {
                    "Couldn't finish the sync. Try again?" to ""
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
        text = "Counted $callsFragment over $contactsFragment",
        style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
    )
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
            text = "Some phones have years of call history. We'll get there.",
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
            ),
            onContinue = {},
            onRetry = {},
        )
    }
}
