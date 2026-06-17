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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 *   5. Schedule ONE final widget refresh (review WR-06) so placed widgets
 *      re-render "No one due" within ~30s instead of showing the wiped
 *      contact's name until the next cold start.
 *
 * After this returns the caller is responsible for landing the user
 * somewhere honest — [app.orbit.ui.screens.settings.SettingsViewModel]
 * emits a completion event and the screen restarts the task, so the next
 * cold start re-enters onboarding (the flag was just cleared).
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
    open suspend fun resetAll() = withContext(Dispatchers.IO) {
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

        // 5. One final widget refresh AFTER the wipe (review WR-06): placed
        //    widgets still show the last surfaced contact's name — a privacy
        //    problem after an explicit delete-all. This re-renders "No one
        //    due" within ~30s. It MUST accompany cancelAll: cancel-only would
        //    leave the stale name until the next cold start re-registers the
        //    periodic sweep (OrbitApp.onCreate runs schedulePeriodic).
        WidgetUpdateScheduler.scheduleImmediate(context)
    }
}
