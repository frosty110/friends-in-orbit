package app.orbit.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.orbit.calllog.ContactsIngestWorker
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.repository.CallEventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ONB-16/17/18: drives the blocking sync gate.
 *
 * Triggers an immediate full-resync on init when READ_CALL_LOG is granted
 * (ContentObserverController is idempotent: start() is a no-op if
 * already started; enqueueImmediateSync(fullResync=true) replaces any
 * pending debounced work). Observes:
 *
 *   - WorkManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME_SYNC)
 *       → maps to InProgress / Succeeded / Failed.
 *   - WorkManager.getWorkInfosForUniqueWorkFlow(ContactsIngestWorker.UNIQUE_NAME)
 *       → whether the contacts ingest is still on its first run. The gate
 *       stays InProgress while it is, because the ingest asks for a full
 *       call-log resync once it has inserted people (ContactsIngestWorker),
 *       and a Succeeded shown before that pass would count calls against an
 *       address book that was still being written. See [ingestPending] for
 *       why the wait is bounded.
 *   - CallEventRepository.observeAggregatesAll(), keyed by
 *     contactId. The VM derives `callCount = sum of count` and
 *     `contactCount = aggregate.size` so the UI gets a single Ready snapshot.
 *     A SUCCEEDED WorkInfo against an empty aggregate map after
 *     `lastCallLogSyncAt > 0L` classifies as Empty rather than InProgress
 *     (ONB-17 zero-rows path).
 *   - lastCallLogSyncAt → distinguishes "never synced" from "synced empty".
 *
 * Retry counter is held in a private MutableStateFlow so the screen can
 * see retryCount=1 → swap primary CTA to "Continue anyway" (ONB-18). It is
 * also the outer flow of a `flatMapLatest`, so [onRetry] re-subscribes the
 * whole pipeline: a thrown read (Room failing) lands in the `catch` as
 * `Failed(retries)` and uses the same Try again / Continue anyway UI as a
 * FAILED worker, instead of ending the first run with an uncaught exception.
 * A bare `catch` terminates the upstream, which is why the retry has to
 * start a new one. Without READ_CALL_LOG the same throw is Skipped, not
 * Failed: the Skipped precedence holds in the catch as in the transform, so
 * Continue stays available (ONB-18, rules.md Code 3).
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

    private val ingestPending: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(ContactsIngestWorker.UNIQUE_NAME)
            .map { infos -> ingestPending(infos) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<OnboardingSyncUiState> =
        _retryCount.flatMapLatest { retries ->
            combine(
                workState,
                ingestPending,
                callEventRepo.observeAggregatesAll(),
                appPrefs.lastCallLogSyncAt,
                appPrefs.callLogImportDays,
            ) { phase, ingesting, aggregate, lastSyncMs, importDays ->
                val totalCalls = aggregate.values.sumOf { it.count }
                val distinctContacts = aggregate.size

                val syncState = when {
                    // No READ_CALL_LOG → no sync was enqueued in init; Idle would
                    // otherwise map to InProgress and disable Continue forever
                    // (the "Continue without it" dead-end).
                    !hasCallLogPermission() -> SyncState.Skipped
                    // The ingest is still writing people; its resync request
                    // will supersede this sync, so the count is not final.
                    ingesting -> SyncState.InProgress
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
                    importDays = importDays,
                ) as OnboardingSyncUiState
            }.catch { t ->
                // rules.md Code 5: cancellation is structured concurrency, not a failure.
                if (t is CancellationException) throw t
                emit(
                    OnboardingSyncUiState.Ready(
                        // The same precedence as the transform above: without
                        // READ_CALL_LOG there is no sync to have failed, and
                        // the five flows here are subscribed either way, so a
                        // Room or DataStore throw on the denied path would
                        // otherwise read as Failed, whose Try again cannot
                        // enqueue anything and whose Continue needs a retry
                        // that never comes: the dead end ONB-18 and rules.md
                        // Code 3 forbid, with no back arrow on this step.
                        syncState = if (!hasCallLogPermission()) {
                            SyncState.Skipped
                        } else {
                            SyncState.Failed(retries)
                        },
                        callCount = 0,
                        contactCount = 0,
                        importDays = DEFAULT_IMPORT_DAYS,
                    ),
                )
            }
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

    /**
     * Try again. The counter advances first, whatever the permission says:
     * it is the outer flow of the `flatMapLatest`, so bumping it is what
     * re-subscribes a pipeline the `catch` has terminated. Only the sync
     * enqueue is gated on READ_CALL_LOG, since there is nothing to import
     * without it. Until 2026-10-06 the guard returned before the bump, so a
     * thrown read on the denied path could never be retried.
     */
    fun onRetry() {
        _retryCount.value = _retryCount.value + 1
        if (!hasCallLogPermission()) return
        controller.enqueueImmediateSync(fullResync = true)
    }

    /**
     * User picked a different look-back window. Persist it first (the worker
     * reads `callLogImportDays` at execution time), then re-run a full resync
     * so the wider/narrower window takes effect immediately. REPLACE policy on
     * the unique sync work means the in-flight import is superseded, not stacked.
     * No-op without READ_CALL_LOG: there's nothing to import.
     */
    fun onImportDaysSelected(days: Int) {
        viewModelScope.launch {
            appPrefs.setCallLogImportDays(days)
            if (hasCallLogPermission()) {
                controller.enqueueImmediateSync(fullResync = true)
            }
        }
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private enum class WorkPhase { Idle, Running, Succeeded, Failed }

    companion object {
        // Mirrors AppPrefs.callLogImportDays' default; only the catch branch
        // needs it, when the prefs read itself may be what failed.
        private const val DEFAULT_IMPORT_DAYS = 90

        /**
         * Whether the contacts ingest is still on its first run: RUNNING, or
         * ENQUEUED and never attempted. A retry backoff also reads as
         * ENQUEUED (ContactsIngestWorker returns `Result.retry()` on a failed
         * read, and WorkManager re-queues it with `runAttemptCount >= 1`),
         * and waiting through backoff would hold Continue disabled for as
         * long as the address book kept failing, the Skipped dead end again.
         * So the wait ends at the first attempt, whatever its outcome; the
         * worker's own resync request covers a later success.
         */
        internal fun ingestPending(infos: List<WorkInfo>): Boolean = infos.any {
            it.state == WorkInfo.State.RUNNING ||
                (it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0)
        }
    }
}
