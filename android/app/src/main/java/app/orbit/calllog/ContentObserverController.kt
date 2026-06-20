package app.orbit.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.orbit.domain.CallLogResyncTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * ContentObserver + debounced-enqueue controller.
 *
 * Lifecycle: [start] is called once from OrbitApp.onCreate and from
 * Settings on permission grant. Idempotent via @Volatile [registered] guard.
 *
 * Permission policy:
 * - The OS enforces READ_CALL_LOG at `registerContentObserver` time on
 *   `CallLog.Calls.CONTENT_URI` (verified at runtime: a registration without
 *   permission throws SecurityException out of `IContentService` and crashes
 *   the process). This contradicts the "passive observer" assumption, so
 *   [start] short-circuits if the permission is revoked — registration is
 *   deferred until Settings calls [start] again on permission grant.
 * - The worker (CallLogSyncWorker) ALSO checks the permission and returns
 *   Result.success() on a revoked-mid-flight transition (CALL-06).
 *
 * Debounce (CALL-03): 10s setInitialDelay + ExistingWorkPolicy.REPLACE — a rapid
 * observer burst collapses into a single worker execution at the end of the window.
 *
 * Quota policy: RUN_AS_NON_EXPEDITED_WORK_REQUEST. Never DROP.
 *
 * ALSO observes `ContactsContract.Contacts.CONTENT_URI` and
 * enqueues [ContactsIngestWorker] on change. Registration is gated on
 * READ_CONTACTS the same way the call-log path is gated on READ_CALL_LOG —
 * a no-permission [start] short-circuits and the next call (post-grant) picks
 * up the registration. The contacts observer enqueues with KEEP instead of the
 * call-log-style debounce, so concurrent observer fires + permission-grant
 * fires collapse into a single worker run. Observer-triggered requests carry
 * [ContactsIngestWorker.KEY_FORCE] — the worker's 24h TTL gates
 * only the non-forced cold-start/grant path.
 */
@Singleton
open class ContentObserverController @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallLogResyncTrigger {

    private val handler = Handler(Looper.getMainLooper())
    private val callLogObserver: ContentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            Timber.tag(TAG).d("call_log_observer_fire")
            enqueueDebouncedSync()
        }
    }
    private val contactsObserver: ContentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            Timber.tag(TAG).d("contacts_observer_fire")
            enqueueContactsIngest()
        }
    }

    @Volatile private var callLogRegistered: Boolean = false
    @Volatile private var contactsRegistered: Boolean = false

    // Process-scoped guard for [enqueueResumeSyncIfStale]. Survives Activity
    // recreation (this controller is @Singleton, app-scoped) so config-change
    // ON_START churn (rotation, theme switch) does not re-enqueue; a fresh
    // process resets it to 0 so the first foreground always syncs.
    @Volatile private var lastResumeSyncAtMs: Long = 0L

    // WR-05 — distinct lock objects per observer so registration of one cannot
    // serialise behind the other. `start()` is called from OrbitApp.onCreate
    // AND from Settings on a permission grant; concurrent dispatches can race
    // the `!registered` check-then-act, double-registering an observer.
    // Synchronizing within each block makes the check-then-register-then-set
    // atomic under contention.
    private val callLogLock = Any()
    private val contactsLock = Any()

    /**
     * Register both content observers if not already registered AND the
     * matching permission is granted. Idempotent: a second call with both
     * already registered is a no-op. When called without one or both
     * permissions, the still-revoked observer is left dormant and the next
     * [start] call picks up registration after the grant.
     */
    open fun start() {
        synchronized(callLogLock) {
            if (!callLogRegistered && hasCallLogPermission()) {
                context.contentResolver.registerContentObserver(
                    CallLog.Calls.CONTENT_URI,
                    /* notifyForDescendants = */ false,
                    callLogObserver,
                )
                callLogRegistered = true
                Timber.tag(TAG).d("call_log_observer_registered")
            } else if (!callLogRegistered) {
                Timber.tag(TAG).d("call_log_observer_register_deferred_no_permission")
            }
        }

        synchronized(contactsLock) {
            if (!contactsRegistered && hasContactsPermission()) {
                context.contentResolver.registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI,
                    /* notifyForDescendants = */ true,
                    contactsObserver,
                )
                contactsRegistered = true
                Timber.tag(TAG).d("contacts_observer_registered")
            } else if (!contactsRegistered) {
                Timber.tag(TAG).d("contacts_observer_register_deferred_no_permission")
            }
        }
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    open fun stop() {
        // WR-05 — symmetric locking with start(); a concurrent start↔stop pair
        // would otherwise leave the registration flag in an inconsistent state.
        synchronized(callLogLock) {
            if (callLogRegistered) {
                context.contentResolver.unregisterContentObserver(callLogObserver)
                callLogRegistered = false
                Timber.tag(TAG).d("call_log_observer_unregistered")
            }
        }
        synchronized(contactsLock) {
            if (contactsRegistered) {
                context.contentResolver.unregisterContentObserver(contactsObserver)
                contactsRegistered = false
                Timber.tag(TAG).d("contacts_observer_unregistered")
            }
        }
    }

    private fun enqueueDebouncedSync() {
        // WorkManager rejects `setExpedited` together with `setInitialDelay`
        // at build time (`IllegalArgumentException: Expedited jobs cannot be
        // delayed`) — the two policies are mutually exclusive: expedited
        // work runs ASAP, delayed work waits. The debounced path is
        // explicitly the latter (10s wait to coalesce rapid observer
        // bursts), so the request stays non-expedited. The user-tapped
        // "Resync now" path keeps `setExpedited` because it's the
        // run-now case (see [enqueueImmediateSync] below).
        val request = OneTimeWorkRequestBuilder<CallLogSyncWorker>()
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_FULL_RESYNC to false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME_SYNC,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Timber.tag(TAG).d("enqueued_debounced_sync delay_s=%d", DEBOUNCE_SECONDS)
    }

    /**
     * Enqueue ContactsIngestWorker on observer fire. KEEP
     * coalesces rapid observer bursts (and concurrent grant fires) into one
     * pending request; the worker reads the address book at execution time,
     * so a burst still ingests the latest state.
     *
     * Observer fires mean "the address book changed RIGHT NOW",
     * so the request carries [ContactsIngestWorker.KEY_FORCE] to bypass the
     * worker's 24h TTL (which exists only for cold-start/grant dedup).
     * Without it, a newly added phone contact stayed invisible to the app
     * for up to a day.
     *
     * [expedited] is set only by the user-tapped manual path
     * ([enqueueImmediateContactsIngest]) so a "Sync contacts now" tap runs
     * promptly; the observer path stays non-expedited (background coalescing).
     */
    private fun enqueueContactsIngest(expedited: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<ContactsIngestWorker>()
            .setInputData(workDataOf(ContactsIngestWorker.KEY_FORCE to true))
        if (expedited) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            ContactsIngestWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            builder.build(),
        )
        Timber.tag(TAG).d("enqueued_contacts_ingest forced=true expedited=%b", expedited)
    }

    /**
     * Public manual "Sync contacts now" entry (Settings → Contacts). Forces a
     * ContactsIngestWorker run that bypasses the 24h TTL and runs expedited.
     * Reconcile-not-overwrite: the worker delegates to
     * [app.orbit.domain.usecase.IngestPhoneContactsUseCase], which inserts new
     * device contacts, refreshes drifted mirror fields, and flags vanished
     * contacts as orphaned — it NEVER deletes a contact, so call history,
     * notes, and ignore/pause flags survive an address-book change (e.g. a new
     * phone whose address book has not finished restoring yet).
     */
    fun enqueueImmediateContactsIngest() = enqueueContactsIngest(expedited = true)

    /**
     * Resume-triggered incremental call-log sync. Called from MainActivity's
     * ON_START observer to close the process-death gap: the content observer
     * only fires while a live process holds the registration, so a call that
     * completes while Orbit's process is dead is never observed. Re-syncing on
     * the next foreground catches it.
     *
     * Incremental (fullResync = false) — the worker reads from the last-sync
     * cursor, so this is cheap. [ExistingWorkPolicy.KEEP] yields to any pending
     * debounced observer sync (which runs within [DEBOUNCE_SECONDS]) rather
     * than cancelling it; when nothing is pending, this request runs promptly.
     *
     * Gated by an in-memory TTL so config-change ON_START churn does not
     * enqueue repeatedly. No-op without READ_CALL_LOG — the worker would
     * clean-exit anyway, but gating here avoids waking WorkManager needlessly.
     */
    fun enqueueResumeSyncIfStale() {
        if (!hasCallLogPermission()) return
        val now = System.currentTimeMillis()
        if (now - lastResumeSyncAtMs < RESUME_SYNC_TTL_MS) return
        lastResumeSyncAtMs = now
        val request = OneTimeWorkRequestBuilder<CallLogSyncWorker>()
            .setInputData(workDataOf(KEY_FULL_RESYNC to false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME_SYNC,
            ExistingWorkPolicy.KEEP,
            request,
        )
        Timber.tag(TAG).d("enqueued_resume_sync")
    }

    /**
     * Public helper used by SettingsViewModel for the "Resync now"
     * button AND by the first-run import path. REPLACE cancels any pending
     * debounced work.
     *
     * Also the production binding for [CallLogResyncTrigger]: the card view
     * calls this on return-from-dial (CORE-04) so a just-completed call advances
     * the deck immediately rather than waiting on the debounced observer / the
     * TTL-gated resume sync. Expedited + no debounce = runs now; incremental
     * (`fullResync = false`) so it only reads rows since the last-sync watermark.
     */
    override fun enqueueImmediateSync(fullResync: Boolean) {
        val request = OneTimeWorkRequestBuilder<CallLogSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(KEY_FULL_RESYNC to fullResync))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME_SYNC,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Timber.tag(TAG).d("enqueued_immediate_sync full=%b", fullResync)
    }

    companion object {
        const val UNIQUE_NAME_SYNC: String = "orbit.call_log_sync"
        const val KEY_FULL_RESYNC: String = "full_resync"
        const val DEBOUNCE_SECONDS: Long = 10L

        // In-memory dedup window for [enqueueResumeSyncIfStale]: a genuine
        // return-after-a-call (minutes/hours later) always passes; rapid
        // ON_START churn from a rotation / theme switch is absorbed.
        const val RESUME_SYNC_TTL_MS: Long = 60_000L
        private const val TAG: String = "calllog"
    }
}
