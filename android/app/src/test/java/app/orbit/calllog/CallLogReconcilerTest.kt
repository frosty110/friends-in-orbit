package app.orbit.calllog

import android.provider.CallLog
import app.orbit.data.android.CallRow
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral coverage for [CallLogReconciler].
 *
 * Tests assert on the reconcile() contract (CALL-02, CALL-05, CALL-06):
 *  - first-run imports the full window (CALL-02)
 *  - type mapping: OUTGOING → OUTGOING; INCOMING / ANSWERED_EXTERNALLY → INCOMING
 *  - skip set: MISSED / REJECTED / VOICEMAIL / BLOCKED never insert
 *  - duration < 1s never inserts
 *  - unmatched phone (no contact) never inserts (FK guard)
 *  - dedup on repeat sync (existsAt-based)
 *  - IGNORE-09: ignored contacts record CallEvent BUT skip MarkCalledUseCase
 *  - empty rows is a clean no-op
 *  - sinceMs filter respected
 *
 * Fakes live in `Fakes.kt`. The `suspendAwareStub` constructed in
 * [buildReconciler] mirrors the real `MarkCalledUseCase` side-effect (an event
 * lands in the DAO via `markCalledAtomic`) by inserting directly into the
 * FakeCallEventDao after `super.invoke` records the invocation. This is the
 * pattern that makes the dedup test see cumulative state between
 * `reconcile()` passes — without it, the FakeDao would only see ignored-contact
 * inserts and the existsAt() guard would never trigger.
 */
class CallLogReconcilerTest {

    private val normalizer = PhoneNumberNormalizer()
    private val fixedNow: Instant = Instant.parse("2026-04-23T12:00:00Z")

    // ------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------

    private fun contact(id: Long, phone: String, isIgnored: Boolean = false): ContactEntity =
        ContactEntity(
            id = id,
            phoneNumber = phone,
            normalizedPhone = phone,
            displayName = "contact_$id",
            firstSeenByAppAt = fixedNow,
            isIgnored = isIgnored,
        )

    private fun row(
        phone: String,
        whenMs: Long,
        durationSec: Int = 30,
        type: Int = CallLog.Calls.OUTGOING_TYPE,
    ): CallRow = CallRow(
        normalizedPhone = phone,
        whenMs = whenMs,
        durationSec = durationSec,
        type = type,
    )

    /**
     * Build a reconciler whose `markCalledUseCase` is a [StubMarkCalledUseCase]
     * subclass that ALSO inserts into the provided [FakeCallEventDao] after
     * recording the invocation. Mirrors production: the real `MarkCalledUseCase`
     * fires `CallEventRepository.markCalledAtomic` which writes the CallEvent
     * inside its @Transaction. Without this insertion, `existsAt` would always
     * return zero on the second pass and the dedup test would falsely pass.
     */
    private fun buildReconciler(
        contacts: List<ContactEntity>,
        phones: List<ContactPhoneEntity> = emptyList(),
    ): Triple<CallLogReconciler, FakeCallEventDao, StubMarkCalledUseCase> {
        val contactRepo = FakeContactRepository(contacts, phones)
        val callEventDao = FakeCallEventDao()
        val suspendAwareStub = object : StubMarkCalledUseCase() {
            override suspend fun invoke(
                contactId: Long,
                callEvent: CallEventEntity,
            ): app.orbit.domain.usecase.MutationResult {
                val result = super.invoke(contactId, callEvent)
                callEventDao.insert(callEvent)
                return result
            }
        }
        val reconciler = CallLogReconciler(
            contactRepo = contactRepo,
            callEventDao = callEventDao,
            markCalledUseCase = suspendAwareStub,
            phoneNumberNormalizer = normalizer,
            clock = EpochClock,
        )
        return Triple(reconciler, callEventDao, suspendAwareStub)
    }

    // ------------------------------------------------------------------
    // CALL-02 — first-run full-window import
    // ------------------------------------------------------------------

    @Test
    fun first_run_imports_full_window() = runTest {
        val c = contact(1, "+14155551234")
        val (reconciler, dao, stub) = buildReconciler(listOf(c))
        val rows = List(10) { i ->
            row("+14155551234", fixedNow.toEpochMilli() - i * 60_000L)
        }

        val summary = reconciler.reconcile(sinceMs = 0L, rows = rows)

        assertEquals(10, summary.scanned)
        assertEquals(10, summary.inserted)
        assertEquals(0, summary.skipped)
        assertEquals(1, summary.contactsPropagated)
        assertEquals(10, dao.insertedCount(), "stub mirrored MarkCalled inserts into DAO")
        assertEquals(10, stub.invocations.size, "non-ignored contact propagated for every row")
    }

    // ------------------------------------------------------------------
    // CALL-05 — type mapping (positive)
    // ------------------------------------------------------------------

    @Test
    fun type_mapping_outgoing_and_incoming_are_inserted() = runTest {
        val c = contact(1, "+14155551234")
        val (reconciler, dao, _) = buildReconciler(listOf(c))
        val rows = listOf(
            row("+14155551234", 1_000L, type = CallLog.Calls.OUTGOING_TYPE),
            row("+14155551234", 2_000L, type = CallLog.Calls.INCOMING_TYPE),
            row("+14155551234", 3_000L, type = CallLog.Calls.ANSWERED_EXTERNALLY_TYPE),
        )

        val summary = reconciler.reconcile(0L, rows)

        assertEquals(3, summary.inserted)
        assertEquals(3, dao.insertedCount())
        // Spot-check direction mapping: OUTGOING_TYPE → OUTGOING; the other two → INCOMING.
        val byOccurredAt = dao.allEvents().associateBy { it.occurredAt }
        assertEquals(CallDirection.OUTGOING, byOccurredAt[Instant.ofEpochMilli(1_000L)]!!.direction)
        assertEquals(CallDirection.INCOMING, byOccurredAt[Instant.ofEpochMilli(2_000L)]!!.direction)
        assertEquals(CallDirection.INCOMING, byOccurredAt[Instant.ofEpochMilli(3_000L)]!!.direction)
    }

    // ------------------------------------------------------------------
    // CALL-05 — skip set (negative)
    // ------------------------------------------------------------------

    @Test
    fun type_mapping_skips_missed_rejected_voicemail_blocked() = runTest {
        val c = contact(1, "+14155551234")
        val (reconciler, dao, _) = buildReconciler(listOf(c))
        val rows = listOf(
            row("+14155551234", 1_000L, type = CallLog.Calls.MISSED_TYPE),
            row("+14155551234", 2_000L, type = CallLog.Calls.REJECTED_TYPE),
            row("+14155551234", 3_000L, type = CallLog.Calls.VOICEMAIL_TYPE),
            row("+14155551234", 4_000L, type = CallLog.Calls.BLOCKED_TYPE),
        )

        val summary = reconciler.reconcile(0L, rows)

        assertEquals(0, summary.inserted, "missed/rejected/voicemail/blocked must never insert")
        assertEquals(4, summary.skipped)
        assertEquals(0, dao.insertedCount())
    }

    // ------------------------------------------------------------------
    // duration<1s skip
    // ------------------------------------------------------------------

    @Test
    fun duration_under_1s_is_skipped() = runTest {
        val c = contact(1, "+14155551234")
        val (reconciler, dao, _) = buildReconciler(listOf(c))
        val rows = listOf(
            row("+14155551234", 1_000L, durationSec = 0),
            row("+14155551234", 2_000L, durationSec = 1), // boundary — included
        )

        val summary = reconciler.reconcile(0L, rows)

        assertEquals(1, summary.inserted)
        assertEquals(1, summary.skipped)
        assertEquals(1, dao.insertedCount())
    }

    // ------------------------------------------------------------------
    // Unmatched phone is skipped (FK guard)
    // ------------------------------------------------------------------

    @Test
    fun unmatched_number_is_skipped() = runTest {
        val c = contact(1, "+14155551234")
        val (reconciler, dao, stub) = buildReconciler(listOf(c))
        val rows = listOf(row("+15105550000", 1_000L)) // not in contacts

        val summary = reconciler.reconcile(0L, rows)

        assertEquals(0, summary.inserted)
        assertEquals(1, summary.skipped)
        assertEquals(0, dao.insertedCount())
        assertTrue(stub.invocations.isEmpty(), "no MarkCalled propagation for unmatched rows")
    }

    // ------------------------------------------------------------------
    // CALL-06 — dedup on repeat sync
    // ------------------------------------------------------------------

    @Test
    fun dedup_on_repeat_sync_inserts_zero_on_second_pass() = runTest {
        val c = contact(1, "+14155551234")
        val (reconciler, dao, _) = buildReconciler(listOf(c))
        val rows = List(5) { i -> row("+14155551234", 1_000L + i * 100L) }

        val first = reconciler.reconcile(0L, rows)
        assertEquals(5, first.inserted)
        assertEquals(5, dao.insertedCount())

        val second = reconciler.reconcile(0L, rows)
        assertEquals(0, second.inserted, "every row must be deduped via existsAt on the second pass")
        assertEquals(5, second.skipped)
        assertEquals(5, dao.insertedCount(), "DAO size must not grow after dedup pass")
    }

    // ------------------------------------------------------------------
    // IGNORE-09 — ignored contacts record event but skip propagation
    // ------------------------------------------------------------------

    @Test
    fun ignored_contact_records_event_and_skips_propagation() = runTest {
        val c = contact(1, "+14155551234", isIgnored = true)
        val (reconciler, dao, stub) = buildReconciler(listOf(c))
        val rows = listOf(row("+14155551234", 1_000L))

        val summary = reconciler.reconcile(0L, rows)

        // History is truth: the CallEvent IS inserted.
        assertEquals(1, summary.inserted)
        assertEquals(1, dao.insertedCount(), "ignored contacts must still record CallEvent")
        // But MarkCalledUseCase must NOT fire (no cross-list propagation).
        assertEquals(0, summary.contactsPropagated, "IGNORE-09: ignored contacts skip propagation")
        assertTrue(stub.invocations.isEmpty(), "MarkCalledUseCase must not be invoked for ignored contacts")
    }

    // ------------------------------------------------------------------
    // Empty rows — clean no-op
    // ------------------------------------------------------------------

    @Test
    fun empty_rows_is_clean_noop() = runTest {
        val c = contact(1, "+14155551234")
        val (reconciler, dao, _) = buildReconciler(listOf(c))

        val summary = reconciler.reconcile(0L, emptyList())

        assertEquals(ReconcileSummary.EMPTY, summary)
        assertEquals(0, dao.insertedCount())
    }

    // ------------------------------------------------------------------
    // sinceMs filter
    // ------------------------------------------------------------------

    @Test
    fun rows_older_than_sinceMs_are_skipped() = runTest {
        val c = contact(1, "+14155551234")
        val (reconciler, dao, _) = buildReconciler(listOf(c))
        val rows = listOf(
            row("+14155551234", whenMs = 500L),
            row("+14155551234", whenMs = 2_000L),
        )

        val summary = reconciler.reconcile(sinceMs = 1_000L, rows = rows)

        assertEquals(1, summary.inserted)
        assertEquals(1, summary.skipped)
        assertEquals(1, dao.insertedCount())
    }

    // ------------------------------------------------------------------
    // M-01 — phone collision detection
    //
    // Two ContactEntity rows whose stored phones normalize to the same E.164
    // (e.g. "+14155551234" and "(415) 555-1234"). The previous map-build
    // silently dropped one; now collisions are counted and reported via a
    // non-PII Timber.w. Behavioral expectations:
    //  - Exactly ONE event is inserted for a single matching CallRow.
    //  - The collision is detected — `buildNormalizedPhoneIndex` returns
    //    `(map of size 1, collisions = 1)`.
    // ------------------------------------------------------------------

    @Test
    fun phone_collision_logs_warning_and_keeps_one_mapping() = runTest {
        val c1 = contact(1, "+14155551234")
        val c2 = contact(2, "(415) 555-1234") // normalizes to +14155551234 in en_US
        val (reconciler, dao, _) = buildReconciler(listOf(c1, c2))

        // Drive the helper directly to lock in the collision contract
        // independently of reconcile() pipeline coverage. Empty phones list
        // exercises the contacts.phoneNumber fallback branch.
        val (map, collisions) = reconciler.buildNormalizedPhoneIndex(listOf(c1, c2), emptyList())
        assertEquals(1, map.size, "two colliding contacts collapse to one mapping")
        assertEquals(1, collisions, "exactly one collision must be reported")

        // End-to-end: a single CallRow yields a single insert (not two).
        val summary = reconciler.reconcile(0L, listOf(row("+14155551234", 1_000L)))
        assertEquals(1, summary.inserted)
        assertEquals(1, dao.insertedCount(), "one CallRow produces one CallEvent insert")
    }

    // ------------------------------------------------------------------
    // Second-number reconciliation via contact_phones
    // ------------------------------------------------------------------

    @Test
    fun call_to_second_number_reconciles_via_contact_phones() = runTest {
        val c = contact(1, "+14155551234")
        val phones = listOf(
            ContactPhoneEntity(
                id = 1, contactId = 1,
                phoneNumber = "+14155551234", normalizedPhone = "+14155551234",
                isPrimary = true,
            ),
            ContactPhoneEntity(
                id = 2, contactId = 1,
                phoneNumber = "(408) 555-0123", normalizedPhone = "+14085550123",
                isPrimary = false,
            ),
        )
        val (reconciler, dao, stub) = buildReconciler(listOf(c), phones)

        // A call to the SECOND number — previously this was skipped as
        // unmatched and the engine kept surfacing someone just called.
        val summary = reconciler.reconcile(0L, listOf(row("+14085550123", 1_000L)))

        assertEquals(1, summary.inserted, "second-number call must match via contact_phones")
        assertEquals(1, summary.contactsPropagated)
        assertEquals(1, dao.insertedCount())
        assertEquals(1, stub.invocations.size, "MarkCalled must propagate for the matched contact")
        assertEquals(1L, stub.invocations.first().first, "event must land on the owning contact")
    }

    @Test
    fun phone_rows_and_primary_fallback_for_same_contact_is_not_a_collision() = runTest {
        val c = contact(1, "+14155551234")
        val phones = listOf(
            ContactPhoneEntity(
                id = 1, contactId = 1,
                phoneNumber = "+14155551234", normalizedPhone = "+14155551234",
                isPrimary = true,
            ),
        )
        val (reconciler, _, _) = buildReconciler(listOf(c), phones)

        // The contact is indexed via its phone row AND via the
        // contacts.phoneNumber fallback — same id, so no collision.
        val (map, collisions) = reconciler.buildNormalizedPhoneIndex(listOf(c), phones)
        assertEquals(1, map.size)
        assertEquals(0, collisions, "same-contact dual indexing must not count as a collision")
    }
}
