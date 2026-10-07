package app.orbit.data.repository

import android.content.Context
import androidx.work.WorkManager
import app.orbit.calllog.ContactsIngestWorker
import app.orbit.calllog.ContentObserverController
import app.orbit.data.AppPrefs
import app.orbit.data.db.OrbitDatabase
import app.orbit.notify.NudgeScheduler
import app.orbit.widget.WidgetUpdateScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * How a [ResetService.resetAll] ended. Held in [ResetService.outcome] until
 * whoever acts on it calls [ResetService.clearOutcome]: the restart into
 * onboarding for [Completed], the "Couldn't finish the reset" snackbar for
 * [Failed].
 */
sealed interface ResetOutcome {
    data object Completed : ResetOutcome
    data object Failed : ResetOutcome
}

/**
 * SET-06 — destructive reset implementing the full settings spec
 * (features/settings/README.md "Delete-all cancels all enqueued
 * WorkManager jobs..."): background machinery is shut down BEFORE the data
 * wipe so no worker fires against an empty database, then Room + DataStore
 * are cleared.
 *
 * Order matters:
 *   1. Cancel the unique works (call-log sync, contacts ingest, daily
 *      digest, nudge chains, widget updates) — a worker firing against
 *      empty tables is the documented crash gotcha in the settings spec;
 *      orphaned widget workers are a known pitfall.
 *   2. Stop the content observers — reuses the same
 *      [ContentObserverController.stop] cleanup the permission-revocation
 *      path runs (idempotent; unregisters call-log + contacts observers).
 *   3. Wipe Room (clearAllTables, FK cascades per schema).
 *   4. Wipe DataStore ([AppPrefs.resetAll] — every key including the
 *      onboarding flag).
 *   5. Run ONE final widget refresh right away (WIDGET-06,
 *      [WidgetUpdateScheduler.refreshNow]) so placed widgets re-render the
 *      empty state ("All quiet for now.") instead of showing the wiped
 *      person's name until the next cold start.
 *
 * After this returns someone must land the user somewhere honest. The
 * result is [outcome], sticky state rather than a one-shot event:
 * [MainActivity][app.orbit.MainActivity] reads it through
 * [app.orbit.AppViewModel] while resumed, restarts the task on
 * [ResetOutcome.Completed] (the onboarding flag was just cleared, so the
 * relaunch lands on Welcome) and shows the failure snackbar on
 * [ResetOutcome.Failed], then calls [clearOutcome]. It lives here, on the
 * app-scoped service, because the reset runs on the application scope and
 * must finish even if the ViewModel that started it is gone (rules.md
 * Code 6); until 2026-10-06 it was a `SharedFlow` without replay that only
 * the Settings screen collected, so a user who backed out or backgrounded
 * the app during the wipe stayed in a live app over an empty database with
 * the onboarding flag cleared, and a failure in that window reached no one
 * (rules.md Code 3). A `StateFlow` and not `replay = 1`: the process
 * survives the task restart, so a replayed completion would be delivered to
 * the next subscriber and restart the app a second time; the collector
 * clears the state before it acts.
 *
 * Caveats:
 *   - Phone contacts and call log are NOT touched; only Orbit's mirror
 *     tables. The user is told this in the ResetConfirmDialog body.
 *   - SQLCipher passphrase + Keystore wrapper key are deliberately LEFT
 *     IN PLACE, diverging from the privacy spec's "revoke the Keystore
 *     key" line. The Room connection stays open for the remainder of the
 *     process, and the encrypted DB file persists across the reset —
 *     deleting the Keystore key (or the wrapped passphrase in
 *     DatabaseKeyProvider's DataStore) would make that file permanently
 *     unreadable on the next launch: a bricked app, not a reset. Every
 *     table is empty after step 3, so the key protects nothing sensitive.
 *     Rotating key + passphrase + DB file together requires a
 *     close-and-reopen flow v1 does not have.
 *   - Onboarding flag flips back to false (DataStore wipe); the task
 *     restart after this call re-enters the welcome screen, consistent
 *     with F1 "reinstall = re-onboard".
 *   - Hilt provides this class with @Inject constructor + @Singleton;
 *     no module entry is required (a structural-anchor [ResetModule] is
 *     present for symmetry with other modules under `di/`).
 */
@Singleton
open class ResetService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: OrbitDatabase,
    private val appPrefs: AppPrefs,
    private val contentObserverController: ContentObserverController,
) {
    private val _outcome = MutableStateFlow<ResetOutcome?>(null)

    /**
     * The last [resetAll]'s result, or null once it has been acted on. Sticky,
     * so a collector that subscribes after the reset finished still sees it.
     */
    val outcome: StateFlow<ResetOutcome?> = _outcome.asStateFlow()

    /** Called by the collector once it has restarted the task or shown the failure. */
    fun clearOutcome() {
        _outcome.value = null
    }

    /**
     * Runs the reset and records its [outcome]. Does not throw: the outcome is
     * the report, read wherever the user is when it lands. Cancellation is the
     * one exception that passes through (rules.md Code 5). Not `open`: a test
     * double replaces [performReset] and keeps this bookkeeping.
     */
    suspend fun resetAll() {
        try {
            performReset()
            _outcome.value = ResetOutcome.Completed
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            _outcome.value = ResetOutcome.Failed
        }
    }

    /** The wipe itself, in the order the class KDoc gives. Test doubles override this. */
    protected open suspend fun performReset(): Unit = withContext(Dispatchers.IO) {
        // 1. Cancel scheduled work BEFORE the wipe so nothing fires against
        //    an empty DB (features/settings/README.md §Known gotchas).
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(ContentObserverController.UNIQUE_NAME_SYNC)
        workManager.cancelUniqueWork(ContactsIngestWorker.UNIQUE_NAME)
        // D-15: cancel the legacy digest by literal name "orbit.daily_digest"
        // (matches the old worker's UNIQUE_NAME so the stale WorkManager record on
        // existing installs is actually cancelled; worker removed in NOTIF-08).
        workManager.cancelUniqueWork("orbit.daily_digest")
        // NOTIF-11: also cancel all per-list nudge chains by tag so a full reset
        // kills every nudge_list_{id} self-re-enqueueing chain (orphan-chain
        // prevention). TAG_NUDGES is stable — all ListPromptWorker work items
        // carry this tag via NudgeScheduler.schedule.
        workManager.cancelAllWorkByTag(NudgeScheduler.TAG_NUDGES)
        // WR-06: cancel the widget update works (debounced one-time + hourly
        // sweep) so no orphaned widget worker fires against the freshly wiped DB.
        WidgetUpdateScheduler.cancelAll(context)

        // 2. Unregister the call-log + contacts content observers — same
        //    cleanup path as permission revocation. Idempotent.
        contentObserverController.stop()

        // 3. Wipe Room. clearAllTables() runs each entity's DELETE inside a
        //    transaction; FKs cascade per the schema (list_memberships,
        //    notes, call_events, contact_phones all hang off contacts).
        database.clearAllTables()

        // 4. Reset DataStore prefs. Deletes every key (onboarding flag,
        //    call-log import days, picker thresholds, etc); the next cold
        //    start re-derives defaults via the existing `?: defaultValue`
        //    reads in [AppPrefs].
        appPrefs.resetAll()

        // 5. One final widget refresh AFTER the wipe (WIDGET-06): placed
        //    widgets still show the last surfaced person's name, a privacy
        //    problem after an explicit reset. refreshNow, not the debounced
        //    scheduleImmediate: the 30 second debounce exists for bulk edits
        //    (WIDGET-05), and here it kept the wiped name on the home screen
        //    for half a minute after the user confirmed erasing everything.
        //    This re-renders "All quiet for now." at once. It MUST accompany
        //    cancelAll: cancel-only would leave the stale name until the next
        //    cold start re-registers the periodic sweep (OrbitApp.onCreate runs
        //    schedulePeriodic).
        WidgetUpdateScheduler.refreshNow(context)
    }
}
