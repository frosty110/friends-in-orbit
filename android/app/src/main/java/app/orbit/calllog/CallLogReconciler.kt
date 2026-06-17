package app.orbit.calllog

import android.provider.CallLog
import app.orbit.data.android.CallRow
import app.orbit.data.dao.CallEventDao
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.domain.clock.Clock
import app.orbit.domain.usecase.MarkCalledUseCase
import java.time.Instant
import androidx.annotation.VisibleForTesting
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import timber.log.Timber

private const val INCOMING_RECENCY_MS = 10L * 60 * 1_000 // 10 minutes (D-11)

/**
 * Call-log reconciliation orchestrator.
 *
 * Public API: [reconcile] — single entry point used by the
 * `CallLogSyncWorker`. The reconciler does NOT read `CallLog.Calls` itself; the
 * worker calls [app.orbit.data.android.CallLogReader.readAll] and passes the
 * resulting `List<CallRow>` in. Keeping the rows-as-input contract on the
 * reconciler makes it pure-JVM-testable (no Android `ContentResolver` mocking).
 *
 * Per-row pipeline:
 * 1. `whenMs < sinceMs` → skip (incremental-sync guard).
 * 2. Type ∈ {MISSED, REJECTED, VOICEMAIL, BLOCKED} or `durationSec < 1` → skip
 *    (CALL-05 — these are not "calls happened" in the user's mental model).
 * 3. Re-normalize `row.normalizedPhone` via [PhoneNumberNormalizer] (idempotent
 *    on already-E.164 input; corrects locale-dependent output from the legacy
 *    `ContactsReader.normalizeForMatch`).
 * 4. Look up matching [ContactEntity] in the in-memory `normalized → ContactEntity`
 *    map built once from `contactRepo.snapshotAllPhones()` (covers
 *    EVERY number a contact carries, so second-SIM / work-line calls match) with
 *    `contacts.phoneNumber` as a fallback for any row predating the
 *    `contact_phones` backfill. We do NOT use `ContactDao.getByPhoneNumber` —
 *    that's an exact-string-match against the raw stored phone, which would miss
 *    every row that needs normalization. The in-memory map is O(N) to build and
 *    O(1) to query, vs. O(M) DAO round-trips per row otherwise.
 * 5. Unmatched (no contact) → skip. Unknown-number CallEvent inserts would violate
 *    the `contactId` foreign key.
 * 6. Pre-check [CallEventDao.existsAt] for `(contactId, occurredAt)` — a
 *    workaround for the non-unique `@Index(["contactId", "occurredAt"])`. Skip
 *    duplicates.
 * 7. For non-ignored contacts: invoke [MarkCalledUseCase] which atomically inserts
 *    the CallEvent + recomputes per-list `nextDueAt` via
 *    `CallEventRepository.markCalledAtomic`. The reconciler does NOT depend on
 *    `CallEventRepository` directly — the atomic write path owns it.
 * 8. For ignored contacts (IGNORE-09): insert the CallEvent directly via
 *    [CallEventDao.insert] and SKIP [MarkCalledUseCase]. History is truth, but
 *    ignored contacts must not trigger cross-list surfacing.
 *
 * Returns a [ReconcileSummary] with non-PII counts suitable for Timber logging
 * (CALL-07 PII gate).
 */
@Singleton
open class CallLogReconciler @Inject constructor(
    private val contactRepo: ContactRepository,
    private val callEventDao: CallEventDao,
    private val markCalledUseCase: MarkCalledUseCase,
    private val phoneNumberNormalizer: PhoneNumberNormalizer,
    @Suppress("unused") private val clock: Clock,
) {

    /**
     * Reconcile the given rows into the `call_events` table.
     *
     * @param sinceMs rows with `whenMs < sinceMs` are skipped (incremental-sync
     *                guard; pass `0` for a full-window pass).
     * @param rows the call-log rows produced by the caller (worker).
     */
    open suspend fun reconcile(sinceMs: Long, rows: List<CallRow>): ReconcileSummary {
        if (rows.isEmpty()) {
            Timber.tag(TAG).d("reconcile: no rows scanned (sinceMs=%d)", sinceMs)
            return ReconcileSummary.EMPTY
        }

        // Build normalized-phone → ContactEntity map in a single pass over the
        // contact_phones snapshot (every number per contact) plus
        // a contacts.phoneNumber fallback. Empty normalizations (e.g. contacts
        // whose stored `phoneNumber` is garbage) are dropped from the map so
        // they can never accidentally match the empty-string output of a
        // malformed row normalization.
        //
        // Collisions: two contacts whose numbers normalize to the same E.164
        // (duplicated contacts, landline-and-mobile entered identically, bad
        // imports). The original `.associateBy { ... }` form silently dropped
        // all but the last contact — that contact would absorb every CallEvent for
        // the shared number and the others would be invisible to call-log sync.
        // Now: count collisions and emit a non-PII Timber.w so the issue surfaces.
        // The accumulator records the first contact wins; subsequent collisions
        // are counted, not overwritten — favouring stability across reconciles.
        val (byNormalizedPhone, collisions) = buildNormalizedPhoneIndex(
            contactRepo.observeAll().first(),
            contactRepo.snapshotAllPhones(),
        )
        if (collisions > 0) {
            // Counts only — no phone, no contact id, no name. PiiSanitizer-safe.
            Timber.tag(TAG).w("phone_collision_count=%d", collisions)
        }

        var scanned = 0
        var inserted = 0
        var skipped = 0
        val touchedContactIds = mutableSetOf<Long>()
        val ignoredContactIdsTouched = mutableSetOf<Long>()
        // D-11 / NOTIF-04: new INCOMING events from tracked, non-ignored, non-paused
        // contacts whose call ended within the last 10 minutes. De-duplicated via Set.
        // "Call ended at" = row.whenMs + row.durationSec * 1000L (whenMs is call start).
        val newIncomingContactIds = mutableSetOf<Long>()
        val nowMs = System.currentTimeMillis()

        for (row in rows) {
            scanned++
            if (row.whenMs < sinceMs) { skipped++; continue }
            if (!row.isIngestable()) { skipped++; continue }

            val normalizedPhone = phoneNumberNormalizer.normalize(row.normalizedPhone)
            if (normalizedPhone.isEmpty()) { skipped++; continue }

            val contact = byNormalizedPhone[normalizedPhone]
            if (contact == null) { skipped++; continue }

            val occurredAt = Instant.ofEpochMilli(row.whenMs)
            if (callEventDao.existsAt(contact.id, occurredAt) > 0) { skipped++; continue }

            val event = CallEventEntity(
                contactId = contact.id,
                occurredAt = occurredAt,
                direction = row.toDirection(),
                durationSeconds = row.durationSec,
                source = CallSource.CALL_LOG,
            )

            if (contact.isIgnored) {
                // IGNORE-09: history is truth — record the CallEvent — but skip
                // MarkCalledUseCase so cross-list nextDueAt recomputation is
                // not triggered for ignored contacts.
                callEventDao.insert(event)
                ignoredContactIdsTouched += contact.id
            } else {
                // The non-ignored path delegates to MarkCalledUseCase, which does
                // the CallEvent insert inside its atomic markCalledAtomic
                // transaction (insert + per-list nextDueAt update + skipCount reset).
                markCalledUseCase(contact.id, event)
                touchedContactIds += contact.id

                // D-11 / NOTIF-04: collect new INCOMING events for the follow-up enqueue.
                // Conditions (all must hold):
                //  1. Direction is INCOMING (INCOMING_TYPE + ANSWERED_EXTERNALLY_TYPE, per toDirection())
                //  2. Call ended within the last 10 minutes — "ended at" = whenMs + durationSec*1000
                //     (whenMs is the call start time; durationSec is the answered duration)
                //  3. Contact is non-paused (pausedUntil is null or already expired)
                val callEndedAtMs = row.whenMs + row.durationSec * 1_000L
                val isPaused = contact.pausedUntil != null && contact.pausedUntil.toEpochMilli() > nowMs
                if (row.toDirection() == CallDirection.INCOMING &&
                    callEndedAtMs >= nowMs - INCOMING_RECENCY_MS &&
                    !isPaused
                ) {
                    newIncomingContactIds += contact.id
                }
            }
            inserted++
        }

        Timber.tag(TAG).i(
            "reconcile_complete scanned=%d inserted=%d skipped=%d propagated=%d ignored_touched=%d",
            scanned, inserted, skipped, touchedContactIds.size, ignoredContactIdsTouched.size,
        )

        return ReconcileSummary(
            scanned = scanned,
            inserted = inserted,
            skipped = skipped,
            contactsPropagated = touchedContactIds.size,
            newIncomingContactIds = newIncomingContactIds.toList(),
        )
    }

    // ------------------------------------------------------------------
    // Private filtering / classification helpers
    // ------------------------------------------------------------------

    private fun CallRow.isIngestable(): Boolean = when (type) {
        CallLog.Calls.INCOMING_TYPE,
        CallLog.Calls.OUTGOING_TYPE,
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> durationSec >= 1

        // Missed / declined / voicemail / blocked are never counted
        // (they are not "calls happened" in the user's mental model).
        CallLog.Calls.MISSED_TYPE,
        CallLog.Calls.REJECTED_TYPE,
        CallLog.Calls.VOICEMAIL_TYPE,
        CallLog.Calls.BLOCKED_TYPE -> false

        else -> false
    }

    private fun CallRow.toDirection(): CallDirection = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
        // INCOMING_TYPE and ANSWERED_EXTERNALLY_TYPE both classify as incoming
        // for our domain — the user picked up a call (or another device on the
        // account did and we still want it on their history).
        else -> CallDirection.INCOMING
    }

    /**
     * Builds the `normalized E.164 → ContactEntity` lookup map and counts how
     * many cross-contact collisions occurred (two DIFFERENT contacts claiming
     * the same key — the same contact reachable via its phone row AND its
     * primary fallback is not a collision). First contact wins; later
     * collisions are tallied but not overwritten.
     *
     * `phones` rows are indexed first (they cover every number a
     * contact carries); each contact's primary `phoneNumber` is folded in as a
     * fallback for rows predating the contact_phones backfill. Phone rows are
     * re-normalized via [PhoneNumberNormalizer] — idempotent on E.164 input,
     * and it corrects legacy digit-only values written by MIGRATION_9_10's
     * backfill of MIGRATION_3_4-era keys.
     *
     * Visible for testing so the M-01 collision test can drive this directly
     * without spinning up the full `reconcile()` pipeline.
     */
    @VisibleForTesting
    internal fun buildNormalizedPhoneIndex(
        contacts: List<ContactEntity>,
        phones: List<ContactPhoneEntity>,
    ): Pair<Map<String, ContactEntity>, Int> {
        val byId = contacts.associateBy { it.id }
        val out = HashMap<String, ContactEntity>(contacts.size + phones.size)
        var collisions = 0
        for (p in phones) {
            val contact = byId[p.contactId] ?: continue
            val norm = phoneNumberNormalizer.normalize(p.normalizedPhone)
            if (norm.isEmpty()) continue
            val prior = out.putIfAbsent(norm, contact)
            if (prior != null && prior.id != contact.id) collisions++
        }
        for (c in contacts) {
            val norm = phoneNumberNormalizer.normalize(c.phoneNumber)
            if (norm.isEmpty()) continue
            val prior = out.putIfAbsent(norm, c)
            if (prior != null && prior.id != c.id) collisions++
        }
        return out to collisions
    }

    private companion object {
        const val TAG = "calllog"
    }
}
