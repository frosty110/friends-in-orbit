package app.orbit.notify

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * DataStore-backed dedup store for incoming-call follow-up notifications — D-13.
 *
 * Stores a per-contact `lastFollowUpAtMs` timestamp so that
 * [IncomingFollowUpWorker] can suppress a follow-up when one already fired for
 * the same contact within the last 30 minutes.
 *
 * ### DataStore file
 * Uses a **dedicated** file (`orbit_followup_state`) separate from `orbit_prefs`.
 * Follow-up dedup state is high-frequency write (every incoming call) whereas
 * `orbit_prefs` holds low-frequency flags — keeping them separate avoids
 * spurious fan-out reads to unrelated preference flows.
 *
 * ### Per-contact key format
 * `longPreferencesKey("fu_{contactId}")` — one key per tracked contact.
 *
 * ### Security note (T-10-08 — accepted)
 * This store holds only `contactId → epoch-millis`. No display names, no phone
 * numbers, no note bodies. The file is app-private and excluded from cloud backup
 * by the `data_extraction_rules` where applicable.
 */
private val Context.followUpDataStore by preferencesDataStore(name = "orbit_followup_state")

@Singleton
open class FollowUpDedupStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Returns the epoch-millis timestamp of the last follow-up posted for
     * [contactId], or `null` if no follow-up has been posted yet.
     */
    open suspend fun lastFollowUpAtMs(contactId: Long): Long? {
        val prefs = context.followUpDataStore.data.first()
        return prefs[key(contactId)]
    }

    /**
     * Records [atMs] as the most recent follow-up timestamp for [contactId].
     *
     * Overwrites any existing value for the same contact — only the latest
     * fire time matters for the 30-minute dedup window.
     */
    open suspend fun record(contactId: Long, atMs: Long) {
        context.followUpDataStore.edit { prefs ->
            prefs[key(contactId)] = atMs
        }
    }

    private fun key(contactId: Long) = longPreferencesKey("fu_$contactId")

    companion object {

        /**
         * Pure dedup window predicate — JVM-testable from [IncomingFollowUpWorkerTest].
         *
         * Returns `true` when [lastMs] is non-null and the elapsed time since
         * [lastMs] is strictly less than [windowMs] (default 30 minutes per D-13).
         *
         * Usage:
         * ```kotlin
         * if (FollowUpDedupStore.isWithinDedupWindow(lastMs, System.currentTimeMillis())) {
         *     return Result.success() // already fired recently — skip
         * }
         * ```
         *
         * @param lastMs Epoch-millis of the previous follow-up, or `null` if never fired.
         * @param nowMs  Epoch-millis representing "now" (injectable for testing).
         * @param windowMs Dedup window in millis; defaults to 30 minutes.
         * @return `true` if a follow-up was fired within [windowMs] of [nowMs].
         */
        fun isWithinDedupWindow(
            lastMs: Long?,
            nowMs: Long,
            windowMs: Long = 30L * 60L * 1_000L,
        ): Boolean = lastMs != null && (nowMs - lastMs) < windowMs
    }
}
