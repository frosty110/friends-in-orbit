package app.orbit.ui.screens.note

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.NoteRepository
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeNoteRepository
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.usecase.AddNoteUseCase
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.screens.picker.PickerCommitBus
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDuration
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * NOTE-04's ViewModel: the timer's start survives recreation, Save writes
 * one ordinary note and leaves, a failed save keeps the words and says so,
 * Discard forgets the draft, and a person who is gone is NotFound.
 *
 * Recreation is a second ViewModel over the same [SavedStateHandle], which
 * is what Android hands a ViewModel rebuilt after process death (the handle
 * restored from the saved bundle).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostCallNoteViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val now: Instant = Instant.parse("2026-10-07T18:00:00Z")
    private val clock = TestClock(now)
    private val kai = contactFixture(id = 7L, displayName = "Kai Mensah")
    private val contacts = FakeContactRepository(listOf(kai))
    private val notes = FakeNoteRepository()
    private val bus = PickerCommitBus()
    private val call = callEventFixture(
        id = 41L,
        contactId = 7L,
        occurredAt = now.minus(Duration.ofMinutes(30)),
        direction = CallDirection.INCOMING,
        durationSeconds = 14 * 60,
    )
    private val calls = FakeCallEventRepository(listOf(call))

    private fun handle(contactId: String = "7", callEventId: String? = "41") = SavedStateHandle(
        buildMap {
            put(PostCallNoteViewModel.ARG_CONTACT_ID, contactId)
            if (callEventId != null) put(PostCallNoteViewModel.ARG_CALL_EVENT_ID, callEventId)
        },
    )

    private fun vm(
        handle: SavedStateHandle = handle(),
        noteRepo: NoteRepository = notes,
        contactRepo: ContactRepository = contacts,
    ) = PostCallNoteViewModel(handle, contactRepo, calls, AddNoteUseCase(noteRepo, clock), bus, clock)

    private suspend fun PostCallNoteViewModel.ready(): PostCallNoteUiState.Ready =
        uiState.first { it is PostCallNoteUiState.Ready } as PostCallNoteUiState.Ready

    // ── the timer ─────────────────────────────────────────────────────────────

    @Test
    fun `the timer starts when the page first opens and survives recreation`() {
        val saved = handle()
        val first = vm(saved)
        assertEquals(now, first.startedAt)

        clock.advance(Duration.ofMinutes(5))
        val recreated = vm(saved)

        assertEquals(now, recreated.startedAt, "a rotation or process death must not restart the count")
    }

    @Test
    fun `a new page starts its own timer`() {
        vm(handle())
        clock.advance(Duration.ofMinutes(5))

        assertEquals(now.plus(Duration.ofMinutes(5)), vm(handle()).startedAt)
    }

    // ── what the page shows ───────────────────────────────────────────────────

    @Test
    fun `the page names the person by first name and describes the named call`() = runTest {
        val ready = vm().ready()

        assertEquals("Kai", ready.firstName)
        assertEquals(CallDirection.INCOMING, ready.call?.direction)
        assertEquals(formatDuration(14 * 60), ready.call?.durationLabel)
        assertIs<UiText.Res>(ready.call?.whenLabel)
        assertEquals(R.string.note_call_when, (ready.call?.whenLabel as UiText.Res).id)
    }

    @Test
    fun `without a named call the page describes the latest connected call, never a logged connection`() = runTest {
        calls.seed(
            listOf(
                callEventFixture(id = 1L, contactId = 7L, occurredAt = now.minus(Duration.ofDays(2)), durationSeconds = 600),
                callEventFixture(
                    id = 2L,
                    contactId = 7L,
                    occurredAt = now.minus(Duration.ofHours(1)),
                    durationSeconds = 0,
                    source = CallSource.MANUAL,
                ),
            ),
        )

        val ready = vm(handle(contactId = "c-7", callEventId = null)).ready()

        assertEquals(formatDuration(600), ready.call?.durationLabel)
    }

    @Test
    fun `a named call that is someone else's is not described as this person's`() = runTest {
        calls.seed(listOf(callEventFixture(id = 41L, contactId = 99L, occurredAt = now, durationSeconds = 300)))

        val ready = vm().ready()

        assertNull(ready.call, "Kai has no connected call of their own, so there is no call line")
    }

    @Test
    fun `a person who is not in Orbit is NotFound, and so is a malformed id`() = runTest {
        assertEquals(PostCallNoteUiState.NotFound, vm(handle(contactId = "8")).uiState.first { it != PostCallNoteUiState.Loading })
        assertEquals(PostCallNoteUiState.NotFound, vm(handle(contactId = "nope")).uiState.first { it != PostCallNoteUiState.Loading })
    }

    @Test
    fun `a failed read is an Error, and Try again recovers`() = runTest {
        var fail = true
        val flaky = object : ContactRepository by contacts {
            override fun observeById(id: Long): Flow<ContactEntity?> =
                if (fail) flow { throw IOException("database locked") } else contacts.observeById(id)
        }
        val model = vm(contactRepo = flaky)

        model.uiState.test {
            assertEquals(PostCallNoteUiState.Error, awaitItemNot(PostCallNoteUiState.Loading))
            fail = false
            model.onRetry()
            assertIs<PostCallNoteUiState.Ready>(awaitItemNot(PostCallNoteUiState.Error))
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── the draft ─────────────────────────────────────────────────────────────

    @Test
    fun `the draft survives recreation, and Discard forgets it`() {
        val saved = handle()
        vm(saved).onDraftChange("Kai sounded tired but happy")

        val recreated = vm(saved)
        assertEquals("Kai sounded tired but happy", recreated.initialDraft)

        recreated.onDiscard()
        assertEquals("", vm(saved).initialDraft, "a discarded note does not come back after process death")
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    @Test
    fun `Save writes one ordinary note, says Note saved where the page returns, and leaves`() = runTest {
        val saved = handle()
        val model = vm(saved)
        model.ready()

        bus.events.test {
            model.onDraftChange("  Ask about the flat viewing.  ")
            model.save()
            assertEquals(SnackbarEvent(UiText.res(R.string.note_snackbar_saved)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            listOf(NoteEntity(contactId = 7L, createdAt = now, body = "Ask about the flat viewing.")),
            notes.insertCalls,
            "the same shape Contact detail's note field writes (AddNoteUseCase)",
        )
        assertTrue(model.ready().saved, "saved is what makes the page leave")
        assertEquals("", vm(saved).initialDraft, "a saved note is not a draft any more")
    }

    @Test
    fun `Save twice writes one note`() = runTest {
        val model = vm()
        model.ready()
        model.onDraftChange("Went well")

        model.save()
        model.save()

        assertEquals(1, notes.insertCalls.size)
    }

    @Test
    fun `a blank draft saves nothing`() = runTest {
        val model = vm()
        model.ready()
        model.onDraftChange("   ")

        model.save()

        assertTrue(notes.insertCalls.isEmpty())
    }

    @Test
    fun `a failed save says so, keeps every word, and stays on the page`() = runTest {
        val failing = object : NoteRepository by FakeNoteRepository() {
            override suspend fun insert(note: NoteEntity): Long = throw IOException("disk full")
        }
        val saved = handle()
        val model = vm(saved, noteRepo = failing)
        model.ready()

        model.events.test {
            model.onDraftChange("Kai sounded tired but happy")
            model.save()
            assertEquals(UiText.res(R.string.note_snackbar_save_failed), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val after = model.ready()
        assertFalse(after.saved, "a failed save must not leave the page")
        assertFalse(after.saving, "Save is offered again")
        assertEquals("Kai sounded tired but happy", vm(saved).initialDraft, "the words are kept")
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<PostCallNoteUiState>.awaitItemNot(
        skip: PostCallNoteUiState,
    ): PostCallNoteUiState {
        var item = awaitItem()
        while (item == skip) item = awaitItem()
        return item
    }
}
