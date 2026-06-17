package app.orbit.domain.usecase

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.android.ContactsReader
import app.orbit.data.android.PhoneContact
import app.orbit.data.android.PhoneNumberRow
import app.orbit.data.dao.RecordingContactDao
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ContactPhoneEntity
import app.orbit.domain.clock.TestClock
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral coverage for the delta-sync rewrite of
 * [IngestPhoneContactsUseCase].
 *
 * Contract under test:
 *  - fresh device contacts insert WITH photoUri and a full contact_phones set
 *  - renames / photo changes propagate to existing rows
 *  - user-owned columns and the identity key are never clobbered
 *  - vanished device contacts get `isOrphaned = true` (never deleted)
 *  - returned device contacts get the flag cleared (`restored`)
 *  - an empty device read (permission revoked OR truly empty) is a no-op —
 *    it must NOT mass-orphan
 *  - a device contact matched via a SECONDARY number refreshes instead of
 *    duplicating
 *  - phone-set changes (new second number) sync to contact_phones
 *
 * Robolectric is needed only because [ContactsReader]'s constructor takes a
 * Context; the [FakeContactsReader] subclass never touches it.
 * @Config(application = Application::class) bypasses OrbitApp.onCreate (no
 * Hilt graph), @Config(sdk = [33]) per project convention.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class IngestPhoneContactsUseCaseTest {

    private val clock = TestClock(Instant.parse("2026-06-09T12:00:00Z"))

    private val passThruTx = object : TransactionRunner {
        override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    }

    /** Canned-rows reader — never touches the ContentResolver. */
    private class FakeContactsReader(
        context: Context,
        var rows: List<PhoneContact> = emptyList(),
    ) : ContactsReader(context) {
        override suspend fun readAll(): List<PhoneContact> = rows
    }

    /**
     * Stateful in-memory [app.orbit.data.dao.ContactDao] covering exactly the
     * surface the ingest delta-sync exercises. Mirrors the production
     * uniqueness rules: `contacts.normalizedPhone` and
     * `contact_phones.normalizedPhone` are unique with IGNORE semantics.
     */
    private class InMemoryContactDao : RecordingContactDao() {
        val contacts = mutableListOf<ContactEntity>()
        val phones = mutableListOf<ContactPhoneEntity>()
        private var nextContactId = 1L
        private var nextPhoneId = 1L

        fun seedContact(contact: ContactEntity): ContactEntity {
            val withId = contact.copy(id = nextContactId++)
            contacts += withId
            return withId
        }

        fun seedPhone(phone: ContactPhoneEntity): ContactPhoneEntity {
            val withId = phone.copy(id = nextPhoneId++)
            phones += withId
            return withId
        }

        override suspend fun getAllOnce(): List<ContactEntity> = contacts.toList()

        override suspend fun insertAll(contacts: List<ContactEntity>): List<Long> =
            contacts.map { c ->
                if (this.contacts.any { it.normalizedPhone == c.normalizedPhone }) {
                    -1L // unique-index IGNORE
                } else {
                    val withId = c.copy(id = nextContactId++)
                    this.contacts += withId
                    withId.id
                }
            }

        override suspend fun refreshMirrorFields(
            id: Long,
            displayName: String,
            photoUri: String?,
            phoneContactId: Long,
            isStarred: Boolean,
        ): Int {
            val idx = contacts.indexOfFirst { it.id == id }
            if (idx < 0) return 0
            contacts[idx] = contacts[idx].copy(
                displayName = displayName,
                photoUri = photoUri,
                phoneContactId = phoneContactId,
                isStarred = isStarred,
                isOrphaned = false,
            )
            return 1
        }

        override suspend fun setOrphanedBatch(ids: List<Long>, orphaned: Boolean): Int {
            var n = 0
            for (i in contacts.indices) {
                if (contacts[i].id in ids) {
                    contacts[i] = contacts[i].copy(isOrphaned = orphaned)
                    n++
                }
            }
            return n
        }

        override suspend fun getAllPhonesOnce(): List<ContactPhoneEntity> = phones.toList()

        override suspend fun insertPhones(phones: List<ContactPhoneEntity>): List<Long> =
            phones.map { p ->
                if (this.phones.any { it.normalizedPhone == p.normalizedPhone }) {
                    -1L // unique-index IGNORE
                } else {
                    val withId = p.copy(id = nextPhoneId++)
                    this.phones += withId
                    withId.id
                }
            }

        override suspend fun deletePhonesForContact(contactId: Long): Int {
            val before = phones.size
            phones.removeAll { it.contactId == contactId }
            return before - phones.size
        }
    }

    private fun build(
        deviceRows: List<PhoneContact> = emptyList(),
    ): Triple<IngestPhoneContactsUseCase, InMemoryContactDao, FakeContactsReader> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val reader = FakeContactsReader(context, deviceRows)
        val dao = InMemoryContactDao()
        val useCase = IngestPhoneContactsUseCase(reader, dao, passThruTx, clock)
        return Triple(useCase, dao, reader)
    }

    private fun deviceContact(
        contactId: Long,
        name: String,
        primary: String,
        photoUri: String? = null,
        extraNumbers: List<String> = emptyList(),
        isStarred: Boolean = false,
    ): PhoneContact {
        val all = (listOf(primary) + extraNumbers).map { PhoneNumberRow(it, it) }
        return PhoneContact(
            contactId = contactId,
            displayName = name,
            phone = primary,
            normalizedPhone = primary,
            photoUri = photoUri,
            phones = all,
            isStarred = isStarred,
        )
    }

    private fun mirroredContact(
        dao: InMemoryContactDao,
        phoneContactId: Long,
        name: String,
        phone: String,
        photoUri: String? = null,
        isOrphaned: Boolean = false,
        withPhoneRow: Boolean = true,
    ): ContactEntity {
        val seeded = dao.seedContact(
            ContactEntity(
                phoneContactId = phoneContactId,
                phoneNumber = phone,
                normalizedPhone = phone,
                displayName = name,
                photoUri = photoUri,
                firstSeenByAppAt = clock.now(),
                isOrphaned = isOrphaned,
            ),
        )
        if (withPhoneRow) {
            dao.seedPhone(
                ContactPhoneEntity(
                    contactId = seeded.id,
                    phoneNumber = phone,
                    normalizedPhone = phone,
                    isPrimary = true,
                ),
            )
        }
        return seeded
    }

    // ------------------------------------------------------------------
    // Insert path — photo + multi-number rows land
    // ------------------------------------------------------------------

    @Test
    fun fresh_contact_inserts_with_photo_and_all_phone_rows() = runTest {
        val (useCase, dao, _) = build(
            listOf(
                deviceContact(
                    contactId = 42L,
                    name = "Ada",
                    primary = "+14155551234",
                    photoUri = "content://contacts/photos/42",
                    extraNumbers = listOf("+14085550123"),
                ),
            ),
        )

        val summary = useCase()

        assertEquals(1, summary.inserted)
        assertEquals(IngestSummary(1, 0, 0, 0), summary)
        val row = dao.contacts.single()
        assertEquals("Ada", row.displayName)
        assertEquals("content://contacts/photos/42", row.photoUri, "photoUri must be ingested")
        assertEquals(42L, row.phoneContactId)
        val phoneRows = dao.phones.filter { it.contactId == row.id }
        assertEquals(2, phoneRows.size, "every device number lands in contact_phones")
        assertEquals(
            "+14155551234",
            phoneRows.single { it.isPrimary }.normalizedPhone,
            "exactly one primary row mirroring the contact's display number",
        )
    }

    // ------------------------------------------------------------------
    // Refresh path — rename + photo propagation, flags untouched
    // ------------------------------------------------------------------

    @Test
    fun rename_propagates_to_existing_row() = runTest {
        val (useCase, dao, _) = build(
            listOf(deviceContact(7L, "Ada Lovelace", "+14155551234")),
        )
        mirroredContact(dao, phoneContactId = 7L, name = "A. Lovelace", phone = "+14155551234")

        val summary = useCase()

        assertEquals(1, summary.refreshed)
        assertEquals(0, summary.inserted, "rename must refresh, not duplicate")
        assertEquals("Ada Lovelace", dao.contacts.single().displayName)
    }

    @Test
    fun photo_change_propagates_and_user_flags_survive() = runTest {
        val (useCase, dao, _) = build(
            listOf(deviceContact(7L, "Ada", "+14155551234", photoUri = "content://photo/new")),
        )
        val seeded = mirroredContact(dao, phoneContactId = 7L, name = "Ada", phone = "+14155551234")
        // Simulate user-owned state that the refresh must NOT clobber.
        dao.contacts[0] = dao.contacts[0].copy(isIgnored = true, pausedUntil = clock.now())

        val summary = useCase()

        assertEquals(1, summary.refreshed)
        val row = dao.contacts.single()
        assertEquals("content://photo/new", row.photoUri)
        assertTrue(row.isIgnored, "refresh must not clobber isIgnored")
        assertNotNull(row.pausedUntil, "refresh must not clobber pausedUntil")
        assertEquals(seeded.normalizedPhone, row.normalizedPhone, "identity key must not change")
    }

    @Test
    fun unchanged_contact_is_a_noop() = runTest {
        val (useCase, dao, _) = build(
            listOf(deviceContact(7L, "Ada", "+14155551234")),
        )
        mirroredContact(dao, phoneContactId = 7L, name = "Ada", phone = "+14155551234")

        val summary = useCase()

        assertEquals(IngestSummary.EMPTY, summary, "identical mirror must produce zero writes")
    }

    // ------------------------------------------------------------------
    // Orphan flag — set when device row vanishes, cleared when it returns
    // ------------------------------------------------------------------

    @Test
    fun vanished_device_contact_is_flagged_orphaned_not_deleted() = runTest {
        val (useCase, dao, _) = build(
            listOf(deviceContact(7L, "Ada", "+14155551234")),
        )
        mirroredContact(dao, phoneContactId = 7L, name = "Ada", phone = "+14155551234")
        mirroredContact(dao, phoneContactId = 8L, name = "Grace", phone = "+14155559999")

        val summary = useCase()

        assertEquals(1, summary.orphaned)
        assertEquals(2, dao.contacts.size, "orphaned rows are flagged, never deleted")
        val grace = dao.contacts.single { it.displayName == "Grace" }
        assertTrue(grace.isOrphaned, "vanished device contact must be flagged orphaned")
        val ada = dao.contacts.single { it.displayName == "Ada" }
        assertFalse(ada.isOrphaned)
    }

    @Test
    fun returned_device_contact_clears_orphan_flag() = runTest {
        val (useCase, dao, _) = build(
            listOf(deviceContact(8L, "Grace", "+14155559999")),
        )
        mirroredContact(
            dao, phoneContactId = 8L, name = "Grace", phone = "+14155559999",
            isOrphaned = true,
        )

        val summary = useCase()

        assertEquals(1, summary.restored)
        assertEquals(0, summary.orphaned)
        assertFalse(dao.contacts.single().isOrphaned, "returned contact must be un-orphaned")
    }

    @Test
    fun empty_device_read_never_orphans() = runTest {
        // Permission revoked and truly-empty address book are indistinguishable
        // here — both must be a clean no-op, not a mass-orphan.
        val (useCase, dao, _) = build(emptyList())
        mirroredContact(dao, phoneContactId = 7L, name = "Ada", phone = "+14155551234")

        val summary = useCase()

        assertEquals(IngestSummary.EMPTY, summary)
        assertFalse(dao.contacts.single().isOrphaned)
    }

    // ------------------------------------------------------------------
    // Starred seeding
    // ------------------------------------------------------------------

    @Test
    fun starred_device_contact_inserts_with_isStarred() = runTest {
        val (useCase, dao, _) = build(
            listOf(deviceContact(42L, "Ada", "+14155551234", isStarred = true)),
        )

        val summary = useCase()

        assertEquals(1, summary.inserted)
        assertTrue(dao.contacts.single().isStarred, "STARRED must be ingested on insert")
    }

    @Test
    fun starred_flip_on_device_refreshes_existing_row() = runTest {
        val (useCase, dao, _) = build(
            listOf(deviceContact(7L, "Ada", "+14155551234", isStarred = true)),
        )
        mirroredContact(dao, phoneContactId = 7L, name = "Ada", phone = "+14155551234")
        assertFalse(dao.contacts.single().isStarred, "fixture starts unstarred")

        val summary = useCase()

        assertEquals(1, summary.refreshed, "a starred flip alone must trigger the refresh")
        assertTrue(dao.contacts.single().isStarred, "starred flip must propagate like a rename")
    }

    @Test
    fun unstarring_on_device_clears_isStarred_and_keeps_user_flags() = runTest {
        val (useCase, dao, _) = build(
            listOf(deviceContact(7L, "Ada", "+14155551234", isStarred = false)),
        )
        mirroredContact(dao, phoneContactId = 7L, name = "Ada", phone = "+14155551234")
        dao.contacts[0] = dao.contacts[0].copy(isStarred = true, isIgnored = true)

        val summary = useCase()

        assertEquals(1, summary.refreshed)
        val row = dao.contacts.single()
        assertFalse(row.isStarred, "device unstar must propagate")
        assertTrue(row.isIgnored, "refresh must not clobber isIgnored")
    }

    // ------------------------------------------------------------------
    // Phone-set sync
    // ------------------------------------------------------------------

    @Test
    fun second_number_added_on_device_syncs_to_contact_phones() = runTest {
        val (useCase, dao, _) = build(
            listOf(
                deviceContact(
                    7L, "Ada", "+14155551234",
                    extraNumbers = listOf("+14085550123"),
                ),
            ),
        )
        val seeded = mirroredContact(dao, phoneContactId = 7L, name = "Ada", phone = "+14155551234")

        val summary = useCase()

        assertEquals(0, summary.inserted, "phone-set growth must not duplicate the contact")
        val phoneRows = dao.phones.filter { it.contactId == seeded.id }
        assertEquals(2, phoneRows.size, "new second number must land in contact_phones")
        assertTrue(phoneRows.any { it.normalizedPhone == "+14085550123" && !it.isPrimary })
        assertTrue(phoneRows.any { it.normalizedPhone == "+14155551234" && it.isPrimary })
    }

    @Test
    fun device_contact_matched_via_secondary_number_refreshes_instead_of_duplicating() = runTest {
        // Existing Room row keyed on +1415... ; the device contact's PRIMARY is
        // now a NEW number, but its second number still matches the stored row.
        val (useCase, dao, _) = build(
            listOf(
                deviceContact(
                    7L, "Ada (work)", "+16505550000",
                    extraNumbers = listOf("+14155551234"),
                ),
            ),
        )
        val seeded = mirroredContact(dao, phoneContactId = 7L, name = "Ada", phone = "+14155551234")

        val summary = useCase()

        assertEquals(0, summary.inserted, "secondary-number match must not create a duplicate")
        assertEquals(1, summary.refreshed)
        val row = dao.contacts.single()
        assertEquals("Ada (work)", row.displayName)
        assertEquals(seeded.normalizedPhone, row.normalizedPhone, "identity key stays stable")
        val phoneRows = dao.phones.filter { it.contactId == seeded.id }
        assertEquals(2, phoneRows.size)
        assertEquals(
            "+16505550000",
            phoneRows.single { it.isPrimary }.normalizedPhone,
            "isPrimary follows the device primary",
        )
        assertNull(
            dao.phones.firstOrNull { it.contactId != seeded.id },
            "no stray phone rows for other contacts",
        )
    }
}
