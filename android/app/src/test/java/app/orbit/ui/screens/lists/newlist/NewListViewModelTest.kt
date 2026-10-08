package app.orbit.ui.screens.lists.newlist

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.orbit.data.db.OrbitDatabase
import app.orbit.data.db.RoomTransactionRunner
import app.orbit.data.db.TransactionRunner
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.usecase.CreateListUseCase
import app.orbit.testutil.MainDispatcherRule
import app.orbit.ui.screens.lists.TemplateChoice
import app.orbit.ui.screens.picker.PickerCommitBus
import app.orbit.ui.util.UiText
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LIST-28: New list's ViewModel. The steps in order for a regular and a
 * smart template, Next refusing an unanswered step (no template, a blank
 * name), Back keeping every entry, a template filling in only what the user
 * has not, and Create: the list, its rhythm and its people in one write
 * with "Created {name}." on success, and on failure nothing left behind,
 * the user kept on the step with everything entered and "Couldn't save your
 * change".
 *
 * Create runs through the real [CreateListUseCase] over in-memory Room, so
 * "nothing left behind" is the database's word, not a fake's. The failure is
 * a real one: a chosen person the contacts table no longer holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NewListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: OrbitDatabase
    private var keepInTouchId = 0L
    private val contacts = FakeContactRepository()
    private val bus = PickerCommitBus()
    private lateinit var savedState: SavedStateHandle
    private lateinit var vm: NewListViewModel

    private val innerOrbit = TemplateChoice.Catalog.first { it.id == "inner_orbit" }
    private val family = TemplateChoice.Catalog.first { it.id == "family" }
    private val drifted = TemplateChoice.Catalog.first { it.id == "drifted" }
    private val blank = TemplateChoice.Catalog.first { it.id == "blank" }
    private val smart = TemplateChoice.Catalog.first { it.isSmart }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OrbitDatabase::class.java).allowMainThreadQueries().build()
        val people = listOf(
            contactFixture(id = 1L, displayName = "Kai Tanaka"),
            contactFixture(id = 2L, displayName = "Maya Okafor"),
        )
        runBlocking {
            keepInTouchId = db.ruleTemplateDao().insert(ruleTemplateFixture(id = 0L, kind = RuleKind.KEEP_IN_TOUCH))
            db.contactDao().insertAll(people)
        }
        contacts.seed(people)
        savedState = SavedStateHandle()
        vm = newViewModel(savedState)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newViewModel(
        handle: SavedStateHandle,
        txRunner: TransactionRunner = RoomTransactionRunner(db),
    ) = NewListViewModel(
        savedStateHandle = handle,
        createList = CreateListUseCase(
            txRunner = txRunner,
            listDao = db.listDao(),
            listMembershipDao = db.listMembershipDao(),
            ruleTemplateRepo = FakeRuleTemplateRepository(
                listOf(ruleTemplateFixture(id = keepInTouchId, kind = RuleKind.KEEP_IN_TOUCH)),
            ),
            clock = TestClock(Instant.parse("2026-10-07T18:00:00Z")),
        ),
        contactRepo = contacts,
        commitBus = bus,
    )

    /** Keeps the WhileSubscribed state live for the test, as the screen does. */
    private fun TestScope.watch(viewModel: NewListViewModel = vm) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    }

    private val state: NewListUiState get() = vm.uiState.value

    /**
     * The state once Create has landed. The "Created" message and the state
     * are two flows: Room finishes the write on its own thread, where the
     * ViewModel (on the unconfined test Main) sets the id and publishes, and
     * the state's combine recomputes on that thread too. So the message can
     * reach the test before the state has caught up, and reading [state] the
     * instant it lands failed about one run in a full suite (2026-10-08).
     * This waits for the state instead.
     */
    private suspend fun created(): NewListUiState = vm.uiState.first { it.createdListId != null }

    private fun select(template: TemplateChoice) =
        vm.selectTemplate(template.id, template.defaultNameRes?.let { context.getString(it) }.orEmpty())

    private fun UiText.text(): String = asString(context)

    // ── The steps ──────────────────────────────────────────────────────────

    @Test
    fun `a regular template goes Start with, Name, How often, People`() = runTest {
        watch()
        assertEquals(NewListStep.StartWith, state.step)
        assertEquals(1 to 4, state.stepNumber to state.stepCount)

        select(innerOrbit)
        vm.next()
        assertEquals(NewListStep.Name, state.step)
        assertEquals(2 to 4, state.stepNumber to state.stepCount)
        vm.next()
        assertEquals(NewListStep.HowOften, state.step)
        assertFalse(state.isLastStep)
        vm.next()
        assertEquals(NewListStep.People, state.step)
        assertEquals(4 to 4, state.stepNumber to state.stepCount)
        assertTrue(state.isLastStep, "People is the last step: its button is Create list")

        vm.next()
        assertEquals(NewListStep.People, state.step, "there is no step after People")
    }

    @Test
    fun `the list that fills itself stops at How often`() = runTest {
        watch()
        select(smart)
        assertEquals(3, state.stepCount)

        vm.next()
        vm.next()

        assertEquals(NewListStep.HowOften, state.step)
        assertEquals(3 to 3, state.stepNumber to state.stepCount)
        assertTrue(state.isLastStep, "How often is its last step: its button is Create list")
        vm.next()
        assertEquals(NewListStep.HowOften, state.step, "no People step for a list whose rule chooses its people")
    }

    @Test
    fun `Next waits for a template`() = runTest {
        watch()
        assertFalse(state.canGoOn)

        vm.next()

        assertEquals(NewListStep.StartWith, state.step)
    }

    @Test
    fun `a blank name blocks Next`() = runTest {
        watch()
        select(blank)
        vm.next()
        assertEquals(NewListStep.Name, state.step)
        assertEquals("", state.name, "Start from blank starts with no name")
        assertFalse(state.canGoOn)

        vm.next()
        assertEquals(NewListStep.Name, state.step)
        vm.onNameChange("   ")
        assertFalse(state.canGoOn)
        vm.next()
        assertEquals(NewListStep.Name, state.step, "spaces are not a name")

        vm.onNameChange("Night owls")
        assertTrue(state.canGoOn)
        vm.next()
        assertEquals(NewListStep.HowOften, state.step)
    }

    @Test
    fun `How often starts at the template's rhythm`() = runTest {
        watch()
        select(innerOrbit)
        assertEquals(7 * 24, state.intervalHours)
        assertEquals("Inner orbit", state.name)
    }

    // ── Back keeps everything ──────────────────────────────────────────────

    @Test
    fun `Back steps back and keeps every entry`() = runTest {
        watch()
        select(innerOrbit)
        vm.next()
        vm.onNameChange("Close friends")
        vm.next()
        vm.onIntervalCommit(10 * 24)
        vm.next()
        vm.onPeopleChosen(listOf(1L, 2L))

        assertTrue(vm.previousStep())
        assertEquals(NewListStep.HowOften, state.step)
        assertTrue(vm.previousStep())
        assertEquals(NewListStep.Name, state.step)
        assertTrue(vm.previousStep())
        assertEquals(NewListStep.StartWith, state.step)
        assertFalse(vm.previousStep(), "Back on the first step leaves the flow; the screen asks first")

        assertEquals(innerOrbit, state.template)
        assertEquals("Close friends", state.name)
        assertEquals(10 * 24, state.intervalHours)
        assertEquals(listOf(1L, 2L), state.people.map { it.id })

        vm.next()
        vm.next()
        vm.next()
        assertEquals(NewListStep.People, state.step)
        assertEquals("Close friends", state.name)
    }

    @Test
    fun `everything entered survives a process death`() = runTest {
        watch()
        select(family)
        vm.next()
        vm.onNameChange("Cousins")
        vm.next()
        vm.onIntervalCommit(21 * 24)

        // A new ViewModel over what the handle saved.
        val restored = newViewModel(SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) }))
        watch(restored)

        val again = restored.uiState.value
        assertEquals(NewListStep.HowOften, again.step)
        assertEquals(family, again.template)
        assertEquals("Cousins", again.name)
        assertEquals(21 * 24, again.intervalHours)
    }

    @Test
    fun `a template fills in only what the user has not`() = runTest {
        watch()
        select(innerOrbit)
        select(family)
        assertEquals("Family", state.name, "the name follows the template while it is the template's")
        assertEquals(14 * 24, state.intervalHours, "How often follows it until the slider moves")

        vm.next()
        vm.onNameChange("Cousins")
        vm.previousStep()
        select(drifted)
        assertEquals("Cousins", state.name, "a typed name stays")
        assertEquals(30 * 24, state.intervalHours)

        vm.next()
        vm.next()
        vm.onIntervalCommit(10 * 24)
        vm.previousStep()
        vm.previousStep()
        select(innerOrbit)
        assertEquals(10 * 24, state.intervalHours, "a moved slider stays")

        // Cleared by hand: an empty name takes the next template's again.
        vm.next()
        vm.onNameChange("")
        vm.previousStep()
        select(family)
        assertEquals("Family", state.name)
    }

    @Test
    fun `a template can only be picked on the first step`() = runTest {
        watch()
        select(innerOrbit)
        vm.next()

        select(family)

        assertEquals(innerOrbit, state.template, "the Name step's field is the name's one writer while it is open")
        assertEquals("Inner orbit", state.name)
    }

    @Test
    fun `discard asks once something is entered`() = runTest {
        watch()
        assertFalse(state.hasEntries, "nothing to lose: Back just leaves")

        select(blank)

        assertTrue(state.hasEntries, "a template picked is something entered")
    }

    // ── People ─────────────────────────────────────────────────────────────

    @Test
    fun `chosen people show by name, and one can be removed`() = runTest {
        watch()
        vm.onPeopleChosen(listOf(2L, 1L, 2L))

        assertEquals(listOf("Kai Tanaka", "Maya Okafor"), state.people.map { it.displayName })
        assertEquals(listOf(2L, 1L), vm.chosenPeople(), "the picker reopens with them ticked")

        vm.removePerson(2L)

        assertEquals(listOf(1L), state.people.map { it.id })
    }

    // ── Create ─────────────────────────────────────────────────────────────

    private fun goToPeople(name: String = "Close friends", interval: Int = 10 * 24) {
        select(innerOrbit)
        vm.next()
        vm.onNameChange(name)
        vm.next()
        vm.onIntervalCommit(interval)
        vm.next()
    }

    @Test
    fun `Create writes the list, its rhythm and its people in one go, then says so and leaves`() = runTest {
        watch()
        goToPeople()
        vm.onPeopleChosen(listOf(1L, 2L))

        bus.events.test {
            vm.create()
            assertEquals("Created Close friends.", awaitItem().message.text())
        }

        val done = created()
        val id = assertNotNull(done.createdListId, "the screen leaves on this")
        assertFalse(done.creating)
        val list = checkNotNull(db.listDao().get(id))
        assertEquals("Close friends", list.name)
        assertEquals(ListType.STATIC, list.type)
        assertEquals(keepInTouchId, list.ruleTemplateId)
        // The slider's interval, through the single entry point.
        assertEquals(
            RuleParams.KeepInTouch().withIntervalHours(10 * 24),
            JsonProvider.json.decodeFromString(RuleParams.serializer(), checkNotNull(list.ruleParamsOverrideJson)),
        )
        assertEquals(setOf(1L, 2L), db.listMembershipDao().getMembersOfList(id).map { it.contactId }.toSet())
    }

    @Test
    fun `Create without people makes an empty list`() = runTest {
        watch()
        goToPeople()

        bus.events.test {
            vm.create()
            awaitItem()
        }

        val id = assertNotNull(created().createdListId)
        assertTrue(db.listMembershipDao().getMembersOfList(id).isEmpty())
    }

    @Test
    fun `the list that fills itself is created from How often, with no people`() = runTest {
        watch()
        // People chosen for a regular list first, then the smart one picked.
        goToPeople()
        vm.onPeopleChosen(listOf(1L))
        vm.previousStep()
        vm.previousStep()
        vm.previousStep()
        select(smart)
        vm.next()
        vm.onNameChange("New faces")
        vm.next()
        assertEquals(NewListStep.HowOften, state.step)

        bus.events.test {
            vm.create()
            assertEquals("Created New faces.", awaitItem().message.text())
        }

        val list = checkNotNull(db.listDao().get(checkNotNull(created().createdListId)))
        assertEquals(ListType.SMART, list.type)
        assertTrue(db.listMembershipDao().getMembersOfList(list.id).isEmpty(), "its rule chooses its people")
    }

    @Test
    fun `a person who has left the contacts drops out of the step and of Create`() = runTest {
        watch()
        goToPeople()
        contacts.seed(listOf(contactFixture(id = 1L, displayName = "Kai Tanaka")))
        vm.onPeopleChosen(listOf(1L, 2L))
        assertEquals(listOf(1L), state.people.map { it.id }, "what the step shows")

        bus.events.test {
            vm.create()
            awaitItem()
        }

        val id = checkNotNull(created().createdListId)
        assertEquals(listOf(1L), db.listMembershipDao().getMembersOfList(id).map { it.contactId }, "is what is made")
    }

    @Test
    fun `a failed Create leaves nothing behind and keeps the user on the step with everything entered`() = runTest {
        watch()
        goToPeople()
        // Someone the picker offered who is no longer in the contacts table:
        // their membership breaks its foreign key after the list row is
        // written, part way through the transaction.
        contacts.seed(contacts.observeAll().first() + contactFixture(id = 99L, displayName = "Sam Lee"))
        vm.onPeopleChosen(listOf(1L, 99L))

        bus.events.test {
            vm.events.test {
                vm.create()
                assertEquals("Couldn't save your change", awaitItem().text())
            }
            expectNoEvents()
        }

        assertTrue(db.listDao().observeAll().first().isEmpty(), "no half-made list")
        assertTrue(db.listMembershipDao().observeAll().first().isEmpty())
        assertNull(state.createdListId, "the flow stays open")
        assertFalse(state.creating)
        assertEquals(NewListStep.People, state.step)
        assertEquals("Close friends", state.name)
        assertEquals(10 * 24, state.intervalHours)
        assertEquals(listOf(1L, 99L), state.people.map { it.id })
    }

    @Test
    fun `while Create is in flight the people and How often hold still`() = runTest {
        // The write is held at its transaction, after it has read who is
        // chosen and the interval. Until 2026-10-08 Remove still took the
        // person off the screen here, and they were made a member anyway.
        val writeMayStart = CompletableDeferred<Unit>()
        vm = newViewModel(savedState, txRunner = HeldTransactionRunner(RoomTransactionRunner(db), writeMayStart))
        watch()
        goToPeople()
        vm.onPeopleChosen(listOf(1L, 2L))

        vm.create()
        assertTrue(state.creating)
        vm.removePerson(2L)
        vm.onIntervalCommit(3 * 24)

        assertEquals(listOf(1L, 2L), state.people.map { it.id }, "what the screen shows")
        assertEquals(10 * 24, state.intervalHours)

        writeMayStart.complete(Unit)
        val id = checkNotNull(created().createdListId)
        assertEquals(
            setOf(1L, 2L),
            db.listMembershipDao().getMembersOfList(id).map { it.contactId }.toSet(),
            "is what is made",
        )
    }

    @Test
    fun `Create only runs from the last step`() = runTest {
        watch()
        select(innerOrbit)
        vm.next()

        vm.create()

        assertNull(state.createdListId)
        assertFalse(state.creating)
        assertTrue(db.listDao().observeAll().first().isEmpty())
    }
}

/** Holds the write at its transaction until [mayStart] completes, so a test can act while Create is in flight. */
private class HeldTransactionRunner(
    private val inner: TransactionRunner,
    private val mayStart: CompletableDeferred<Unit>,
) : TransactionRunner {
    override suspend fun <T> withTransaction(block: suspend () -> T): T {
        mayStart.await()
        return inner.withTransaction(block)
    }
}
