package app.orbit.ui.screens.lists.newlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.entity.ContactEntity
import app.orbit.data.repository.ContactRepository
import app.orbit.domain.usecase.CreateListUseCase
import app.orbit.ui.screens.lists.ListConfigContactSnapshot
import app.orbit.ui.screens.lists.TemplateChoice
import app.orbit.ui.screens.picker.PickerCommitBus
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * LIST-28: New list, step by step. Start with (LIST-29), Name, How often,
 * then Add people, and "Create list"; a list that fills itself stops at How
 * often, whose button is "Create list".
 *
 * Everything the user enters is kept in [SavedStateHandle]: the step, the
 * template, the name, the interval and the people. Stepping back changes
 * only the step, so nothing entered is lost going back and forth, and a
 * process death returns to the same step with the same entries.
 *
 * The name field is the screen's (rules.md Code 7): it starts from [NewListUiState.name]
 * each time the Name step opens and hands every change here one way
 * ([onNameChange]); nothing here writes the name while that step is open.
 * The one write that is not the field's is a template's starting name
 * ([selectTemplate]), which happens on Start with, where the field is not.
 *
 * A template fills in what the user has not: its name replaces the name
 * only while that is blank or still the previous template's name, and its
 * rhythm sets How often only until the wheel has been turned. So going back
 * to Start with and picking another template moves the defaults along and
 * keeps what was typed or chosen.
 *
 * [create] writes through [CreateListUseCase], one transaction for the list,
 * its rhythm and its people. On success "Created {name}." goes to the app's
 * snackbar bus, shown on the screen the flow returns to (the note page's
 * precedent), and [NewListUiState.createdListId] tells the screen to leave.
 * On failure the flow stays on its step with everything entered and says
 * "Couldn't save your change" (rules.md Code 3). The write runs on
 * viewModelScope: the screen leaves only once it has landed, so nothing
 * navigates away mid-flight (rules.md Code 6 is about work that must outlive
 * the screen, which this does not).
 *
 * No logging here (rules.md Code 4): this class holds names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NewListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val createList: CreateListUseCase,
    private val contactRepo: ContactRepository,
    private val commitBus: PickerCommitBus,
) : ViewModel() {

    private val creating = MutableStateFlow(false)
    private val createdListId = MutableStateFlow<Long?>(null)

    private val _events = MutableSharedFlow<UiText>(extraBufferCapacity = 4)

    /** One-shot snackbars shown in the flow itself: only a failed Create. */
    val events: SharedFlow<UiText> = _events.asSharedFlow()

    private data class Entries(
        val step: NewListStep,
        val template: TemplateChoice?,
        val name: String,
        val intervalHours: Int?,
    )

    private val entries: Flow<Entries> = combine(
        savedStateHandle.getStateFlow(KEY_STEP, NewListStep.StartWith.name),
        savedStateHandle.getStateFlow<String?>(KEY_TEMPLATE, null),
        savedStateHandle.getStateFlow(KEY_NAME, ""),
        savedStateHandle.getStateFlow<Int?>(KEY_INTERVAL, null),
    ) { step, templateId, name, interval ->
        Entries(stepNamed(step), TemplateChoice.byId(templateId), name, interval)
    }

    /**
     * The chosen people, by name, in name order, as the People section of
     * List settings shows them. Read from the contacts the picker offered
     * them from, so someone who has since gone from the phone's contacts
     * drops out here rather than failing Create.
     */
    private val people: Flow<List<ListConfigContactSnapshot>> =
        savedStateHandle.getStateFlow(KEY_PEOPLE, LongArray(0))
            .flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val chosen = ids.toSet()
                    contactRepo.observeAll().map { contacts -> contacts.chosen(chosen) }
                }
            }

    val uiState: StateFlow<NewListUiState> =
        combine(entries, people, creating, createdListId) { e, people, creating, created ->
            NewListUiState(
                step = e.step,
                template = e.template,
                name = e.name,
                intervalHours = e.intervalHours ?: e.template?.startingIntervalHours ?: TemplateChoice.DEFAULT_INTERVAL_HOURS,
                people = people,
                creating = creating,
                createdListId = created,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), NewListUiState())

    // ── Reads of what is entered, straight from the handle ──────────────────
    // The actions below decide from these, not from uiState.value: with
    // WhileSubscribed the state may not be collected (a test, a moment with
    // no screen), and the handle is the one place the entries live.

    private val step: NewListStep get() = stepNamed(savedStateHandle[KEY_STEP])
    private val template: TemplateChoice? get() = TemplateChoice.byId(savedStateHandle[KEY_TEMPLATE])
    private val name: String get() = savedStateHandle[KEY_NAME] ?: ""
    private val intervalHours: Int
        get() = savedStateHandle[KEY_INTERVAL] ?: template?.startingIntervalHours ?: TemplateChoice.DEFAULT_INTERVAL_HOURS
    private val chosenIds: LongArray get() = savedStateHandle[KEY_PEOPLE] ?: LongArray(0)

    private fun steps(): List<NewListStep> = NewListStep.stepsFor(template)

    /**
     * Start with: [templateId] picked, [defaultName] its name in the user's
     * language (the screen resolves it; empty for "Start from blank"). The
     * name follows the template while it is blank or still the last
     * template's name; How often follows it until the wheel has been turned.
     */
    fun selectTemplate(templateId: String, defaultName: String) {
        // Only on Start with, which keeps the Name step's field the name's
        // one writer while that step is open (see the class KDoc).
        if (step != NewListStep.StartWith || creating.value || TemplateChoice.byId(templateId) == null) return
        savedStateHandle[KEY_TEMPLATE] = templateId
        val prefilled: String = savedStateHandle[KEY_PREFILLED_NAME] ?: ""
        if (name.isBlank() || name == prefilled) {
            savedStateHandle[KEY_NAME] = defaultName
            savedStateHandle[KEY_PREFILLED_NAME] = defaultName
        }
    }

    /** The Name step's field, every change (one way; see the class KDoc). */
    fun onNameChange(text: String) {
        savedStateHandle[KEY_NAME] = text
    }

    /**
     * How often's wheel settled at [hours]. From here on the template no
     * longer sets it. Refused while Create's write is in flight, as every
     * entry change is: the write has already read the interval, so a change
     * then would not be the list that is made (the screen holds the wheel
     * still too).
     */
    fun onIntervalCommit(hours: Int) {
        if (creating.value) return
        savedStateHandle[KEY_INTERVAL] = hours
    }

    /**
     * Next. Moves on only when the step is answered: a template on Start
     * with, a name that is not blank on Name. The last step has no Next; its
     * button is [create].
     */
    fun next() {
        if (creating.value) return
        val answered = when (step) {
            NewListStep.StartWith -> template != null
            NewListStep.Name -> name.isNotBlank()
            NewListStep.HowOften, NewListStep.People -> true
        }
        if (!answered) return
        val steps = steps()
        val at = steps.indexOf(step)
        if (at in 0 until steps.lastIndex) savedStateHandle[KEY_STEP] = steps[at + 1].name
    }

    /**
     * Back within the flow: the previous step, keeping everything entered.
     * Returns false on the first step, where Back leaves the flow (the
     * screen asks first when something is entered).
     */
    fun previousStep(): Boolean {
        if (creating.value) return true
        val steps = steps()
        val at = steps.indexOf(step)
        if (at <= 0) return false
        savedStateHandle[KEY_STEP] = steps[at - 1].name
        return true
    }

    /** The picker's selection for the People step, replacing what was chosen before. */
    fun onPeopleChosen(contactIds: List<Long>) {
        savedStateHandle[KEY_PEOPLE] = contactIds.distinct().toLongArray()
    }

    /**
     * "Remove {name} from list" on the People step. Refused while Create's
     * write is in flight: the write has already read who is chosen, so the
     * person would leave the screen and still be on the list (the screen
     * disables the control too). Until 2026-10-08 it was not refused.
     */
    fun removePerson(contactId: Long) {
        if (creating.value) return
        savedStateHandle[KEY_PEOPLE] = chosenIds.filterNot { it == contactId }.toLongArray()
    }

    /** The ids the picker opens with, so it shows who is already chosen. */
    fun chosenPeople(): List<Long> = chosenIds.toList()

    /**
     * "Create list" (and "Create without people"). Only from the last step,
     * with a template and a name, once at a time.
     */
    fun create() {
        val chosenTemplate = template ?: return
        val listName = name.trim()
        if (step != steps().last() || listName.isEmpty() || creating.value || createdListId.value != null) return
        val hours = intervalHours
        creating.value = true
        viewModelScope.launch {
            val created: Long? = try {
                val people = if (chosenTemplate.isSmart) emptyList() else existingChosen()
                createList(
                    CreateListUseCase.Request(
                        name = listName,
                        smartRule = chosenTemplate.smartRule,
                        intervalHours = hours,
                        memberContactIds = people,
                    ),
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t // rules.md Code 5
                null
            }
            // The state settles before anything is said, so whoever hears
            // the outcome sees the flow as it now is.
            creating.value = false
            if (created == null) {
                _events.tryEmit(UiText.res(R.string.components_snackbar_save_failed))
            } else {
                createdListId.value = created
                commitBus.publish(SnackbarEvent(UiText.res(R.string.lists_snackbar_created, listName)))
            }
        }
    }

    /** The chosen people who are still among the phone's contacts, as the People step shows them. */
    private suspend fun existingChosen(): List<Long> {
        val ids = chosenIds
        if (ids.isEmpty()) return emptyList()
        val chosen = ids.toSet()
        val existing = contactRepo.observeAll().first().filter { it.id in chosen }.map { it.id }.toSet()
        return ids.filter { it in existing }
    }

    private fun stepNamed(name: String?): NewListStep =
        NewListStep.entries.firstOrNull { it.name == name } ?: NewListStep.StartWith

    private fun List<ContactEntity>.chosen(ids: Set<Long>): List<ListConfigContactSnapshot> =
        filter { it.id in ids }
            .sortedBy { it.displayName.lowercase() }
            .map { ListConfigContactSnapshot(id = it.id, displayName = it.displayName, photoUri = it.photoUri) }

    companion object {
        // Not copy (voice.md "Where copy lives"): SavedStateHandle keys.
        internal const val KEY_STEP = "new_list_step"
        internal const val KEY_TEMPLATE = "new_list_template"
        internal const val KEY_NAME = "new_list_name"
        internal const val KEY_PREFILLED_NAME = "new_list_prefilled_name"
        internal const val KEY_INTERVAL = "new_list_interval_hours"
        internal const val KEY_PEOPLE = "new_list_people"
    }
}
