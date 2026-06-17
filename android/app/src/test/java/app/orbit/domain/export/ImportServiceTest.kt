package app.orbit.domain.export

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.data.entity.NoteEntity
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeNoteRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import java.io.File
import java.time.Instant
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backup round-trip + refusal contracts.
 *
 * The round-trip test drives the REAL [ExportService] (against the
 * fake repositories) through the REAL crypto into the REAL [ImportService]
 * applying onto an in-memory Room build — the full export → import → state
 * pipeline with nothing mocked in the middle.
 *
 * Refusal tests exercise [ImportService.decode] directly with low PBKDF2
 * iterations (the iteration count is carried in the binary header, so a
 * 1,000-round test file decrypts exactly like a 120,000-round production
 * file — just faster).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ImportServiceTest {

    private val context: android.content.Context get() =
        ApplicationProvider.getApplicationContext()

    private lateinit var db: OrbitDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─── Fixture data ─────────────────────────────────────────────────────

    private val template = RuleTemplateEntity(
        id = 1L,
        name = "Keep in touch",
        kind = RuleKind.KEEP_IN_TOUCH,
        paramsJson = """{"type":"keep_in_touch"}""",
    )

    private val contactSam = ContactEntity(
        id = 1L,
        phoneNumber = "+1 555 123 4567",
        normalizedPhone = "+15551234567",
        displayName = "Sam",
        firstSeenByAppAt = Instant.ofEpochMilli(1_000L),
        pausedUntil = Instant.ofEpochMilli(99_999L),
        ruleOverrideJson = """{"type":"keep_in_touch"}""",
    )

    private val contactRiley = ContactEntity(
        id = 2L,
        phoneNumber = "+1 555 765 4321",
        normalizedPhone = "+15557654321",
        displayName = "Riley",
        firstSeenByAppAt = Instant.ofEpochMilli(2_000L),
        isIgnored = true,
        ignoredAt = Instant.ofEpochMilli(50_000L),
        isArchived = false,
    )

    private val innerOrbit = ListEntity(
        id = 1L,
        name = "Inner orbit",
        sortOrder = 0,
        type = ListType.STATIC,
        ruleTemplateId = 1L,
        activeHoursStart = LocalTime.of(9, 0),
        activeHoursEnd = LocalTime.of(21, 30),
        notificationsEnabled = true,
        dueCount = 0,
    )

    private val membershipSam = ListMembershipEntity(
        contactId = 1L,
        listId = 1L,
        addedAt = Instant.ofEpochMilli(10_000L),
        nextDueAt = Instant.ofEpochMilli(20_000L),   // far in the past → due
        skipCount = 2,
    )

    private val membershipRiley = ListMembershipEntity(
        contactId = 2L,
        listId = 1L,
        addedAt = Instant.ofEpochMilli(11_000L),
        nextDueAt = null,                            // null → due
        skipCount = 0,
    )

    private val callEvent = CallEventEntity(
        id = 1L,
        contactId = 1L,
        occurredAt = Instant.ofEpochMilli(30_000L),
        direction = CallDirection.OUTGOING,
        durationSeconds = 120,
        source = CallSource.CALL_LOG,
    )

    private val note = NoteEntity(
        id = 1L,
        contactId = 1L,
        createdAt = Instant.ofEpochMilli(40_000L),
        body = "asked about the move",
    )

    private fun buildExportService(): ExportService = ExportService(
        context = context,
        listRepo = FakeListRepository(
            initialLists = listOf(innerOrbit),
            initialMemberships = listOf(membershipSam, membershipRiley),
        ),
        contactRepo = FakeContactRepository(listOf(contactSam, contactRiley)),
        callEventRepo = FakeCallEventRepository(listOf(callEvent)),
        noteRepo = FakeNoteRepository(listOf(note)),
        ruleTemplateRepo = FakeRuleTemplateRepository(listOf(template)),
    )

    private fun envelopeFixture(): ExportEnvelope = ExportEnvelope(
        createdAt = 123_456L,
        lists = listOf(
            ListExport(
                id = 1L, name = "Inner orbit", sortOrder = 0, isArchived = false,
                type = "STATIC", ruleTemplateId = 1L,
                activeHoursStartSecondOfDay = 9 * 3600,
                activeHoursEndSecondOfDay = 21 * 3600 + 1800,
                notificationsEnabled = true,
            ),
        ),
        contacts = listOf(
            ContactExport(
                id = 1L, phoneNumber = "+1 555 123 4567",
                normalizedPhone = "+15551234567", displayName = "Sam",
                isIgnored = false, isArchived = false,
            ),
        ),
        memberships = listOf(
            MembershipExport(contactId = 1L, listId = 1L, addedAt = 10_000L, skipCount = 2),
        ),
        callEvents = emptyList(),
        notes = emptyList(),
        ruleTemplates = listOf(
            RuleTemplateExport(
                id = 1L, name = "Keep in touch", kind = "KEEP_IN_TOUCH",
                paramsJson = """{"type":"keep_in_touch"}""",
            ),
        ),
    )

    private suspend fun encrypt(envelope: ExportEnvelope, passphrase: CharArray): ByteArray {
        val plaintext = JsonProvider.json
            .encodeToString(ExportEnvelope.serializer(), envelope)
            .encodeToByteArray()
        return PassphraseEncryptor.encrypt(plaintext, passphrase, iterations = 1_000)
    }

    // ─── Round trip — export → import → state equal ───────────────────────

    @Test
    fun `export then import restores every table`() = runBlocking {
        // Junk pre-state proves REPLACE semantics (wipe before restore).
        db.ruleTemplateDao().insert(template.copy(id = 50L, name = "Stale template"))
        db.contactDao().insert(
            contactSam.copy(id = 99L, displayName = "Junk", normalizedPhone = "+10000000000"),
        )

        val file = File.createTempFile("orbit-export", ".bin")
        try {
            val passphrase = "correct horse battery".toCharArray()
            buildExportService().export(Uri.fromFile(file), passphrase.copyOf())

            val importService = ImportService(context, db)
            val payload = importService.read(Uri.fromFile(file), passphrase.copyOf())
            assertEquals(ExportEnvelope.CURRENT_VERSION, payload.envelopeVersion)
            importService.apply(payload)
        } finally {
            file.delete()
        }

        // Contacts — junk gone, both restored with portable fields intact.
        val contacts = db.contactDao().getAllOnce().sortedBy { it.id }
        assertEquals(listOf(1L, 2L), contacts.map { it.id })
        val sam = contacts[0]
        assertEquals("Sam", sam.displayName)
        assertEquals("+15551234567", sam.normalizedPhone)
        assertEquals(Instant.ofEpochMilli(99_999L), sam.pausedUntil)
        assertEquals("""{"type":"keep_in_touch"}""", sam.ruleOverrideJson)
        val riley = contacts[1]
        assertEquals(true, riley.isIgnored)
        assertEquals(Instant.ofEpochMilli(50_000L), riley.ignoredAt)

        // Lists — active hours survive the seconds-of-day round trip;
        // dueCount recomputed (both memberships due: one past, one null).
        val list = db.listDao().get(1L)
        assertEquals("Inner orbit", list?.name)
        assertEquals(1L, list?.ruleTemplateId)
        assertEquals(LocalTime.of(9, 0), list?.activeHoursStart)
        assertEquals(LocalTime.of(21, 30), list?.activeHoursEnd)
        assertEquals(2, list?.dueCount)

        // Memberships — skipCount + nextDueAt preserved.
        val members = db.listMembershipDao().getMembersOfList(1L).sortedBy { it.contactId }
        assertEquals(2, members.size)
        assertEquals(2, members[0].skipCount)
        assertEquals(Instant.ofEpochMilli(20_000L), members[0].nextDueAt)
        assertEquals(null, members[1].nextDueAt)

        // Call events + notes + templates.
        assertEquals(callEvent, db.callEventDao().get(1L))
        assertEquals(note, db.noteDao().get(1L))
        val restoredTemplate = db.ruleTemplateDao().get(1L)
        assertEquals(template, restoredTemplate)
        assertEquals(null, db.ruleTemplateDao().get(50L))

        // contact_phones — envelope v2 carries them (fake derives primaries).
        val phones = db.contactDao().getAllPhonesOnce().sortedBy { it.contactId }
        assertEquals(2, phones.size)
        assertEquals("+15551234567", phones[0].normalizedPhone)
        assertTrue(phones.all { it.isPrimary })
    }

    // ─── Refusals ─────────────────────────────────────────────────────────

    @Test
    fun `wrong passphrase is refused as unreadable`() = runBlocking {
        val bytes = encrypt(envelopeFixture(), "right password".toCharArray())
        assertFailsWith<ImportFormatException> {
            ImportService.decode(bytes, "wrong password".toCharArray())
        }
        Unit
    }

    @Test
    fun `corrupt file is refused as unreadable`() = runBlocking {
        val bytes = encrypt(envelopeFixture(), "right password".toCharArray())
        bytes[bytes.size - 5] = (bytes[bytes.size - 5] + 1).toByte()   // flip a ciphertext byte
        assertFailsWith<ImportFormatException> {
            ImportService.decode(bytes, "right password".toCharArray())
        }
        Unit
    }

    @Test
    fun `not-an-orbit-file is refused as unreadable`() = runBlocking {
        assertFailsWith<ImportFormatException> {
            ImportService.decode("definitely not a backup".encodeToByteArray(), "x".toCharArray())
        }
        Unit
    }

    @Test
    fun `newer envelope version is refused`() = runBlocking {
        val newer = envelopeFixture().copy(version = ExportEnvelope.CURRENT_VERSION + 1)
        val bytes = encrypt(newer, "pass".toCharArray())
        val e = assertFailsWith<ImportVersionTooNewException> {
            ImportService.decode(bytes, "pass".toCharArray())
        }
        assertEquals(ExportEnvelope.CURRENT_VERSION + 1, e.found)
        assertEquals(ExportEnvelope.CURRENT_VERSION, e.supported)
    }

    // ─── v1 envelope tolerance — contact_phones self-heal ─────────────────

    @Test
    fun `v1 envelope without contactPhones derives primary phone rows`() = runBlocking {
        val v1 = envelopeFixture().copy(version = 1, contactPhones = emptyList())
        val bytes = encrypt(v1, "pass".toCharArray())
        val payload = ImportService.decode(bytes, "pass".toCharArray())

        assertEquals(1, payload.contactPhones.size)
        val phone = payload.contactPhones.first()
        assertEquals(1L, phone.contactId)
        assertEquals("+15551234567", phone.normalizedPhone)
        assertEquals(true, phone.isPrimary)
    }
}
