package app.orbit.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.repository.CallEventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ONB-16/17/18 — drives the blocking sync gate.
 *
 * Triggers an immediate full-resync on init when READ_CALL_LOG is granted
 * (ContentObserverController is idempotent — start() is a no-op if
 * already started; enqueueImmediateSync(fullResync=true) replaces any
 * pending debounced work). Observes:
 *
 *   - WorkManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME_SYNC)
 *       → maps to InProgress / Succeeded / Failed.
 *   - CallEventRepository.observeAggregatesAll() — keyed by
 *     contactId. The VM derives `callCount = sum of count` and
 *     `contactCount = aggregate.size` so the UI gets a single Ready snapshot.
 *     A SUCCEEDED WorkInfo against an empty aggregate map after
 *     `lastCallLogSyncAt > 0L` classifies as Empty rather than InProgress
 *     (ONB-17 zero-rows path).
 *   - lastCallLogSyncAt → distinguishes "never synced" from "synced empty".
 *
 * Retry counter is held in a private MutableStateFlow so the screen can
 * see retryCount=1 → swap primary CTA to "Continue anyway" (ONB-18).
 */
@HiltViewModel
class OnboardingSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPrefs: AppPrefs,
    private val controller: ContentObserverController,
    private val callEventRepo: CallEventRepository,
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val _retryCount = MutableStateFlow(0)

    private val workState: Flow<WorkPhase> =
        workManager.getWorkInfosForUniqueWorkFlow(ContentObserverController.UNIQUE_NAME_SYNC)
            .map { infos ->
                when {
                    infos.isEmpty() -> WorkPhase.Idle
                    infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } -> WorkPhase.Running
                    infos.any { it.state == WorkInfo.State.SUCCEEDED } -> WorkPhase.Succeeded
                    infos.any { it.state == WorkInfo.State.FAILED } -> WorkPhase.Failed
                    else -> WorkPhase.Idle
                }
            }

    val uiState: StateFlow<OnboardingSyncUiState> =
        combine(
            workState,
            callEventRepo.observeAggregatesAll(),
            appPrefs.lastCallLogSyncAt,
            _retryCount,
        ) { phase, aggregate, lastSyncMs, retries ->
            val totalCalls = aggregate.values.sumOf { it.count }
            val distinctContacts = aggregate.size

            val syncState = when {
                // No READ_CALL_LOG → no sync was enqueued in init; Idle would
                // otherwise map to InProgress and disable Continue forever
                // (the "Continue without it" dead-end).
                !hasCallLogPermission() -> SyncState.Skipped
                phase == WorkPhase.Succeeded && totalCalls == 0 && lastSyncMs > 0L -> SyncState.Empty
                phase == WorkPhase.Succeeded -> SyncState.Succeeded
                phase == WorkPhase.Failed -> SyncState.Failed(retries)
                phase == WorkPhase.Running -> SyncState.InProgress
                else -> SyncState.InProgress
            }
            OnboardingSyncUiState.Ready(
                syncState = syncState,
                callCount = totalCalls,
                contactCount = distinctContacts,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = OnboardingSyncUiState.Loading,
        )

    init {
        if (hasCallLogPermission()) {
            controller.start()
            controller.enqueueImmediateSync(fullResync = true)
        }
    }

    fun onRetry() {
        if (!hasCallLogPermission()) return
        _retryCount.value = _retryCount.value + 1
        controller.enqueueImmediateSync(fullResync = true)
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private enum class WorkPhase { Idle, Running, Succeeded, Failed }
}
