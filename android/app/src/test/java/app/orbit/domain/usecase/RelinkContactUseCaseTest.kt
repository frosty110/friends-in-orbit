package app.orbit.domain.usecase

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.android.ContactsReader
import app.orbit.data.android.PhoneContact
import app.orbit.data.android.PhoneNumberRow
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.db.RoomTransactionRunner
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.domain.FakeListRepository
import app.orbit.domain.clock.TestClock
import app.orbit.domain.listFixture
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CONTACT-07 merge + undo against a real in-memory Room build, so the foreign
 * keys, the unique `normalizedPhone` indexes and the cascade deletes are the
 * production ones. A fake would happily let a child row be orphaned or a
 * number be held twice; this is exactly where those mistakes would show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RelinkContactUseCaseTest {

    private lateinit var db: OrbitDatabase
    private lateinit var listRepo: FakeListRepository
    private lateinit var useCase: RelinkContactUseCase
    private val clock = TestClock(Instant.parse("2026-10-05T12:00:00Z"))

    private val orphan = ContactEntity(
        id = 10L,
        phoneContactId = 100L,
        phoneNumber = "+1 555 000 0010",
        normalizedPhone = "+15550000010",
        displayName = "Mum (old)",
        firstSeenByAppAt = Instant.parse("2025-01-01T00:00:00Z"),
        deviceUpdatedAt = Instant.parse("2024-06-01T00:00:00Z"),
        isOrphaned = true,
        ruleOverrideJson = """{"type":"keep_in_touch"}"""
    )
    private val live = ContactEntity(
        id = 20L,
        phoneContactId = 200L,
        phoneNumber = "+1 555 000 0020",
        normalizedPhone = "+15550000020",
        displayName = "Mum",
        photoUri = "content://photo/20",
        isStarred = true,
        firstSeenByAppAt = Instant.parse("2026-09-01T00:00:00Z"),
        deviceUpdatedAt = Instant.parse("2026-08-30T00:00:00Z"),
        pausedUntil = Instant.parse("2026-11-01T00:00:00Z")
    )

    // List 1 is shared; list 2 is orphan-only; list 3 is live-only.
    private val orphanOnShared = ListMembershipEntity(
        contactId = 10L,
        listId = 1L,
        addedAt = Instant.parse("2025-02-01T00:00:00Z"),
        nextDueAt = Instant.parse("2026-09-10T00:00:00Z"),
        skipCount = 0
    )
    private val orphanOnly = ListMembershipEntity(
        contactId = 10L,
        listId = 2L,
        addedAt = Instant.parse("2025-03-01T00:00:00Z")
    )
    private val liveOnShared = ListMembershipEntity(
        contactId = 20L,
        listId = 1L,
        addedAt = Instant.parse("2026-09-02T00:00:00Z"),
        nextDueAt = Instant.parse("2026-10-20T00:00:00Z"),
        skipCount = 1
    )
    private val liveOnly = ListMembershipEntity(
        contactId = 20L,
        listId = 3L,
        addedAt = Instant.parse("2026-09-03T00:00:00Z")
    )

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listRepo = FakeListRepository()
        useCase = RelinkContactUseCase(
            txRunner = RoomTransactionRunner(db),
            contactDao = db.contactDao(),
            listMembershipDao = db.listMembershipDao(),
            listRepo = listRepo,
            clock = clock
        )

        listOf(1L, 2L, 3L).forEach {
            db.listDao().insert(listFixture(id = it, name = "List $it", ruleTemplateId = null))
        }
        db.contactDao().insert(orphan)
        db.contactDao().insert(live)
        db.contactDao().insertPhones(
            listOf(
                ContactPhoneEntity(
                    contactId = 10L,
                    phoneNumber = orphan.phoneNumber,
                    normalizedPhone = orphan.normalizedPhone,
                    isPrimary = true
                ),
                ContactPhoneEntity(
                    contactId = 20L,
                    phoneNumber = live.phoneNumber,
                    normalizedPhone = live.normalizedPhone,
                    isPrimary = true
                ),
                ContactPhoneEntity(
                    contactId = 20L,
                    phoneNumber = "+1 555 000 0021",
                    normalizedPhone = "+15550000021"
                )
            )
        )
        db.callEventDao().insert(call(contactId = 10L, at = "2026-01-01T10:00:00Z"))
        db.callEventDao().insert(call(contactId = 20L, at = "2026-09-05T10:00:00Z"))
        db.callEventDao().insert(call(contactId = 20L, at = "2026-09-20T10:00:00Z"))
        db.noteDao().insert(
            NoteEntity(
                contactId = 10L,
                createdAt = Instant.parse("2026-01-01T11:00:00Z"),
                body = "orphan note"
            )
        )
        db.noteDao().insert(
            NoteEntity(
                contactId = 20L,
                createdAt = Instant.parse("2026-09-05T11:00:00Z"),
                body = "live note"
            )
        )
        db.listMembershipDao().insertAll(listOf(orphanOnShared, orphanOnly, liveOnShared, liveOnly))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun call(contactId: Long, at: String) = CallEventEntity(
        contactId = contactId,
        occurredAt = Instant.parse(at),
        direction = CallDirection.OUTGOING,
        durationSeconds = 60,
        source = CallSource.CALL_LOG
    )

    // ─── Forward merge ───────────────────────────────────────────────────

    @Test
    fun `the orphan survives with the phone contact's identity and is no longer orphaned`() =
        runTest {
            val result = assertNotNull(useCase(orphanId = 10L, liveId = 20L))

            // The snackbar's "Re-linked to Mum" is built from this name
            // (strings_picker.xml; SnackbarCopyTest).
            assertEquals("Mum", result.linkedName)
            assertNull(db.contactDao().get(20L), "the emptied live row is deleted")
            val merged = assertNotNull(db.contactDao().get(10L))
            assertFalse(merged.isOrphaned)
            assertEquals(200L, merged.phoneContactId)
            assertEquals(live.phoneNumber, merged.phoneNumber)
            assertEquals(live.normalizedPhone, merged.normalizedPhone)
            assertEquals("Mum", merged.displayName)
            assertEquals(live.photoUri, merged.photoUri)
            assertTrue(merged.isStarred)
            assertEquals(live.deviceUpdatedAt, merged.deviceUpdatedAt)
        }

    @Test
    fun `user-owned state stays the orphan's, and the live row fills only the gaps`() = runTest {
        useCase(orphanId = 10L, liveId = 20L)

        val merged = assertNotNull(db.contactDao().get(10L))
        assertEquals(
            orphan.firstSeenByAppAt,
            merged.firstSeenByAppAt,
            "not new to Orbit, so not 'recently added'"
        )
        assertEquals(orphan.ruleOverrideJson, merged.ruleOverrideJson)
        assertEquals(
            live.pausedUntil,
            merged.pausedUntil,
            "the orphan had no pause, so the live one carries over"
        )
    }

    @Test
    fun `every call, note and number moves onto the orphan`() = runTest {
        useCase(orphanId = 10L, liveId = 20L)

        assertEquals(3, db.callEventDao().observeByContactId(10L).first().size)
        assertEquals(
            setOf("orphan note", "live note"),
            db.noteDao().observeByContactId(10L).first().map { it.body }.toSet()
        )
        assertEquals(
            setOf("+15550000020", "+15550000021"),
            db.contactDao().getPhonesForContact(10L).map { it.normalizedPhone }.toSet(),
            "the orphan's dead number is dropped; the phone contact's numbers come over"
        )
    }

    @Test
    fun `memberships union, and a shared list keeps the first add and the later due date`() =
        runTest {
            useCase(orphanId = 10L, liveId = 20L)

            val rows = db.listMembershipDao().getMembershipsForContact(
                10L
            ).associateBy { it.listId }
            assertEquals(setOf(1L, 2L, 3L), rows.keys)
            val shared = rows.getValue(1L)
            assertEquals(orphanOnShared.addedAt, shared.addedAt)
            assertEquals(
                liveOnShared.nextDueAt,
                shared.nextDueAt,
                "a call under the new contact pushed it out"
            )
            assertEquals(liveOnShared.skipCount, shared.skipCount)
            assertEquals(
                setOf(1L, 2L, 3L),
                listRepo.recomputeDueCountCalls.map { it.listId }.toSet()
            )
        }

    @Test
    fun `the next contacts sync keeps the re-linked contact linked`() = runTest {
        useCase(orphanId = 10L, liveId = 20L)

        val reader = object : ContactsReader(ApplicationProvider.getApplicationContext()) {
            override suspend fun readAll(): List<PhoneContact> = listOf(
                PhoneContact(
                    contactId = 200L,
                    displayName = "Mum",
                    phone = live.phoneNumber,
                    normalizedPhone = live.normalizedPhone,
                    photoUri = live.photoUri,
                    phones = listOf(
                        PhoneNumberRow(live.phoneNumber, live.normalizedPhone),
                        PhoneNumberRow("+1 555 000 0021", "+15550000021")
                    ),
                    isStarred = true
                )
            )
        }
        val summary =
            IngestPhoneContactsUseCase(reader, db.contactDao(), RoomTransactionRunner(db), clock)()

        assertEquals(0, summary.inserted, "no duplicate row for the phone contact")
        assertEquals(0, summary.orphaned)
        assertEquals(1, db.contactDao().getAllOnce().size)
        assertFalse(assertNotNull(db.contactDao().get(10L)).isOrphaned)
    }

    // ─── Undo ────────────────────────────────────────────────────────────

    @Test
    fun `undo splits the two contacts back exactly as they were`() = runTest {
        val phonesBefore = db.contactDao().getAllPhonesOnce().toSet()
        val result = assertNotNull(useCase(orphanId = 10L, liveId = 20L))

        result.inverse()

        assertEquals(orphan, db.contactDao().get(10L))
        assertEquals(live, db.contactDao().get(20L))
        assertEquals(phonesBefore, db.contactDao().getAllPhonesOnce().toSet())
        assertEquals(1, db.callEventDao().observeByContactId(10L).first().size)
        assertEquals(2, db.callEventDao().observeByContactId(20L).first().size)
        assertEquals(
            listOf("orphan note"),
            db.noteDao().observeByContactId(10L).first().map { it.body }
        )
        assertEquals(
            listOf("live note"),
            db.noteDao().observeByContactId(20L).first().map { it.body }
        )
        assertEquals(
            setOf(orphanOnShared, orphanOnly),
            db.listMembershipDao().getMembershipsForContact(10L).toSet()
        )
        assertEquals(
            setOf(liveOnShared, liveOnly),
            db.listMembershipDao().getMembershipsForContact(20L).toSet()
        )
    }

    // ─── Refusals: nothing written ───────────────────────────────────────

    @Test
    fun `refuses when the orphan was restored by a sync in the meantime`() = runTest {
        db.contactDao().update(orphan.copy(isOrphaned = false))
        assertRefusedUnchanged(orphanId = 10L, liveId = 20L)
    }

    @Test
    fun `refuses a pick that is not a live phone contact`() = runTest {
        // Call-log-only row: no device contact to link to.
        db.contactDao().update(live.copy(phoneContactId = null))
        assertRefusedUnchanged(orphanId = 10L, liveId = 20L)
        db.contactDao().update(live.copy(isIgnored = true))
        assertRefusedUnchanged(orphanId = 10L, liveId = 20L)
        db.contactDao().update(live.copy(isArchived = true))
        assertRefusedUnchanged(orphanId = 10L, liveId = 20L)
        db.contactDao().update(live.copy(isOrphaned = true))
        assertRefusedUnchanged(orphanId = 10L, liveId = 20L)
    }

    @Test
    fun `refuses itself and missing rows`() = runTest {
        assertRefusedUnchanged(orphanId = 10L, liveId = 10L)
        assertRefusedUnchanged(orphanId = 10L, liveId = 999L)
        assertRefusedUnchanged(orphanId = 999L, liveId = 20L)
    }

    private suspend fun assertRefusedUnchanged(orphanId: Long, liveId: Long) {
        val contactsBefore = db.contactDao().getAllOnce()
        val membershipsBefore = db.listMembershipDao().getMembershipsForContact(10L) +
            db.listMembershipDao().getMembershipsForContact(20L)

        assertNull(useCase(orphanId = orphanId, liveId = liveId))

        assertEquals(contactsBefore, db.contactDao().getAllOnce())
        assertEquals(
            membershipsBefore,
            db.listMembershipDao().getMembershipsForContact(10L) +
                db.listMembershipDao().getMembershipsForContact(20L)
        )
    }

    // ─── Pure merge rules ────────────────────────────────────────────────

    @Test
    fun `a null due date means due now, so a scheduled one is later`() {
        val dueNow = orphanOnShared.copy(nextDueAt = null, skipCount = 0)
        val scheduled = liveOnShared.copy(
            nextDueAt = Instant.parse("2026-10-20T00:00:00Z"),
            skipCount = 2
        )

        val merged = mergeMemberships(10L, listOf(dueNow), listOf(scheduled)).single()

        assertEquals(10L, merged.contactId)
        assertEquals(scheduled.nextDueAt, merged.nextDueAt)
        assertEquals(2, merged.skipCount)
    }

    @Test
    fun `isRelinkTarget accepts only another live phone contact`() {
        assertTrue(RelinkContactUseCase.isRelinkTarget(live, orphanId = 10L))
        assertFalse(RelinkContactUseCase.isRelinkTarget(live, orphanId = 20L))
        assertFalse(
            RelinkContactUseCase.isRelinkTarget(live.copy(phoneContactId = null), orphanId = 10L)
        )
        assertFalse(
            RelinkContactUseCase.isRelinkTarget(live.copy(isOrphaned = true), orphanId = 10L)
        )
        assertFalse(
            RelinkContactUseCase.isRelinkTarget(live.copy(isIgnored = true), orphanId = 10L)
        )
        assertFalse(
            RelinkContactUseCase.isRelinkTarget(live.copy(isArchived = true), orphanId = 10L)
        )
    }
}
