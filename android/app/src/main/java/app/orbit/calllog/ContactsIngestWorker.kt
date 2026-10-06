package app.orbit.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.orbit.data.AppPrefs
import app.orbit.domain.CallLogResyncTrigger
import app.orbit.domain.clock.Clock
import app.orbit.domain.usecase.IngestPhoneContactsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Moves [IngestPhoneContactsUseCase] off the cold-start critical path.
 *
 * Triggers (both enqueue with [ExistingWorkPolicy.KEEP] under the same
 * [UNIQUE_NAME] — concurrent grant/observer events do not stack):
 * 1. `READ_CONTACTS` permission grant — observed by
 *    [app.orbit.ui.screens.onboarding.OnboardingPermissionsViewModel]
 *    on the false → true transition.
 * 2. `ContactsContract.Contacts.CONTENT_URI` change — observed by
 *    [ContentObserverController] (registered when permission is held).
 *
 * Re-run gating: a 24h TTL persisted in [AppPrefs.lastContactsIngestedAt]
 * skips work when the address book was recently ingested. The use case
 * itself is idempotent (`OnConflictStrategy.IGNORE`) so the TTL is a perf
 * optimisation, not a correctness gate. The TTL is classified as `accept`
 * in the threat register — a user with adb could clear DataStore to force
 * re-ingest, but the use case is permission-safe and has no abuse vector.
 *
 * TTL bypass via [KEY_FORCE] input data. The TTL exists for cold-start
 * dedup (trigger 1: concurrent permission-grant fires), but trigger 2's
 * whole purpose is "the address book changed RIGHT NOW" — gating an observer
 * fire on a 24h TTL made new contacts invisible for up to a day.
 * The observer enqueue path ([ContentObserverController.enqueueContactsIngest])
 * sets `force = true` and skips the TTL check; scheduled/grant triggers keep
 * the default `false`. A forced run still refreshes
 * [AppPrefs.lastContactsIngestedAt] on success, so the TTL window restarts.
 *
 * Ordering with the call-log sync: an ingest that inserted people asks for a
 * full call-log resync ([CallLogResyncTrigger], the controller's expedited
 * REPLACE enqueue) when READ_CALL_LOG is held. The reconciler matches each
 * call to a person by phone number in Room and skips calls whose person is
 * not there yet (CallLogReconciler), so any call that landed before the
 * person did was dropped for good: onboarding's first sync could run while
 * the ingest was still writing a large address book, and every later sync is
 * incremental from the watermark, so the gap never closed (onb-2). The same
 * holds for a contacts grant in Settings and for the address-book observer.
 */
@HiltWorker
class ContactsIngestWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val ingestPhoneContacts: IngestPhoneContactsUseCase,
    private val clock: Clock,
    private val appPrefs: AppPrefs,
    private val callLogResync: CallLogResyncTrigger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = clock.now()
        val force = inputData.getBoolean(KEY_FORCE, false)
        val last = appPrefs.lastContactsIngestedAt.first()
        val ttl = Duration.ofHours(TTL_HOURS)
        // WR-04 — clock-rollback guard. `Duration.between(last, now)` is
        // signed: when `now < last` (NTP backwards correction, manual time
        // change, time-zone migration) the result is negative and a naive
        // `< ttl` check fires open, permanently skipping ingestion. The
        // `!now.isBefore(last)` guard funnels the rollback case through the
        // work block, which re-runs ingestion AND refreshes
        // `lastContactsIngestedAt = now` — also healing the drift.
        //
        // `force` (observer-triggered: the address book just changed)
        // bypasses the TTL entirely; the TTL gates only the
        // cold-start/grant dedup path.
        if (!force && last != null && !now.isBefore(last) && Duration.between(last, now) < ttl) {
            Timber.tag(TAG).d("ingest_skipped_within_ttl")
            return Result.success()
        }
        return runCatching {
            val summary = ingestPhoneContacts()
            appPrefs.setLastContactsIngestedAt(now)
            Timber.tag(TAG).i(
                "ingest_complete forced=%b inserted=%d refreshed=%d orphaned=%d restored=%d",
                force, summary.inserted, summary.refreshed, summary.orphaned, summary.restored,
            )
            if (summary.inserted > 0 && hasCallLogPermission()) {
                // New people may own calls already in the log that the last
                // sync skipped as unmatched; only a full pass re-reads them.
                callLogResync.enqueueImmediateSync(fullResync = true)
                Timber.tag(TAG).d("ingest_requested_full_resync inserted=%d", summary.inserted)
            }
            Result.success()
        }.getOrElse { e ->
            Timber.tag(TAG).w(e, "ingest_failed_will_retry")
            Result.retry()
        }
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val UNIQUE_NAME: String = "orbit.contacts_ingest"
        const val TTL_HOURS = 24L

        /**
         * Boolean input flag set by the contacts-observer enqueue path.
         * `true` = the address book just changed; skip the 24h TTL.
         */
        const val KEY_FORCE: String = "force_ingest"
        private const val TAG: String = "contacts_ingest"
    }
}
