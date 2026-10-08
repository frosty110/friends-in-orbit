package app.orbit.ui.screens.lists.newlist

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.R
import app.orbit.ui.components.CurtainMask
import app.orbit.ui.components.IntervalDaysPicker
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitAppBar
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitScreen
import app.orbit.ui.components.OrbitSnackbarHost
import app.orbit.ui.components.OrbitTextField
import app.orbit.ui.screens.lists.ListConfigContactSnapshot
import app.orbit.ui.screens.lists.MembersPreview
import app.orbit.ui.screens.lists.TemplateChoice
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow
import app.orbit.ui.util.asString

/**
 * LIST-28: New list, step by step, a screen of its own (`Routes.NewList`)
 * opened by Home's "New list" and "Create your first list" and by the Lists
 * screen's "New list". The owner's order: choose how to start, Next, type the
 * name, Next, set how often, Next, choose the people, "Create list"
 * (vision/flows/owner-review-2026-10-07.md, decision 14).
 *
 * This wrapper resolves the ViewModel and does the screen's three jobs that
 * are not drawing: it hands the Collect picker's result to the ViewModel once
 * ([chosenPeople], then [onChosenPeopleTaken]), it shows a failed Create, and
 * it leaves once the list exists ([NewListUiState.createdListId]), which is
 * when "Created {name}." shows on the screen underneath. Everything drawn is
 * [NewListContent].
 */
@Composable
fun NewListScreen(
    chosenPeople: List<Long>?,
    onChosenPeopleTaken: () -> Unit,
    onChoosePeople: (selectedContactIds: List<Long>) -> Unit,
    onLeave: () -> Unit,
    vm: NewListViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.events.collect { message -> snackbarHostState.showSnackbar(message.asString(context)) }
    }
    // The picker's selection arrives through the nav entry; take it once.
    LaunchedEffect(chosenPeople) {
        if (chosenPeople != null) {
            vm.onPeopleChosen(chosenPeople)
            onChosenPeopleTaken()
        }
    }
    // The write landed: leave. State, not an event, so a rotation during the
    // write cannot leave the flow open over a list that already exists.
    val currentOnLeave by rememberUpdatedState(onLeave)
    LaunchedEffect(state.createdListId) { if (state.createdListId != null) currentOnLeave() }

    NewListContent(
        state = state,
        onSelectTemplate = { template, defaultName -> vm.selectTemplate(template.id, defaultName) },
        onNameChange = vm::onNameChange,
        onNext = vm::next,
        onPreviousStep = { vm.previousStep() },
        onIntervalCommit = vm::onIntervalCommit,
        onAddPeople = { onChoosePeople(vm.chosenPeople()) },
        onRemovePerson = vm::removePerson,
        onCreate = vm::create,
        onLeave = onLeave,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * The stateless flow (THEME-04): one step at a time under one app bar.
 *
 * - App bar: Back, "New list" (the pane title), then "Step 2 of 4" and, from
 *   the second step on, a close control. Back steps back and keeps
 *   everything; on the first step it leaves, as the close control does from
 *   any step. Leaving with something entered asks "Discard this list?"
 *   ("Keep going" or "Discard"); with nothing entered it just leaves. System
 *   Back does the same as the arrow.
 * - The step's heading, a heading for TalkBack and a polite live region, so
 *   moving to the next step is heard ("Name your list") without a new
 *   screen. Then the step's one decision.
 * - The footer: the step's one accent element (rules.md Design 5), "Next",
 *   or "Create list" on the last step; on the People step with nobody chosen
 *   yet, "Add people" with "Create without people" (quiet) above it.
 *
 * Whether "Discard this list?" is showing is this composable's own state
 * (rules.md Code 7); what is entered is the ViewModel's, through [state].
 *
 * While Create's write is in flight ([NewListUiState.creating]) nothing on the
 * screen is pressable: the footer, the close control (and so "Discard" behind
 * it), the People step's "Add people" and remove controls, and the How often
 * wheel. The write has already read the people and the interval, so a change
 * made then would show on the screen and not in the list that is made, and a
 * Discard would leave the flow while the list was still being written. Back
 * steps nowhere then either (the ViewModel refuses it). Until 2026-10-08 only
 * the footer waited.
 */
@Composable
internal fun NewListContent(
    state: NewListUiState,
    onSelectTemplate: (template: TemplateChoice, defaultName: String) -> Unit,
    onNameChange: (String) -> Unit,
    onNext: () -> Unit,
    onPreviousStep: () -> Unit,
    onIntervalCommit: (hours: Int) -> Unit,
    onAddPeople: () -> Unit,
    onRemovePerson: (contactId: Long) -> Unit,
    onCreate: () -> Unit,
    onLeave: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    autoFocus: Boolean = true,
    askingDiscardAtStart: Boolean = false,
) {
    var askingDiscard by rememberSaveable { mutableStateOf(askingDiscardAtStart) }
    val requestLeave: () -> Unit = {
        when {
            state.creating -> Unit
            state.hasEntries -> askingDiscard = true
            else -> onLeave()
        }
    }
    val back: () -> Unit = { if (state.stepNumber > 1) onPreviousStep() else requestLeave() }
    // On the first step with nothing entered there is nothing to ask about
    // or step back to, so Back is the system's: the flow simply closes.
    BackHandler(enabled = state.stepNumber > 1 || state.hasEntries) { back() }

    OrbitScreen {
        OrbitAppBar(
            title = stringResource(R.string.lists_new_title),
            leading = {
                OrbitIconButton(
                    icon = "arrow-left",
                    onClick = back,
                    contentDescription = stringResource(R.string.components_action_back),
                )
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.lists_new_progress,
                            state.stepCount,
                            state.stepNumber,
                            state.stepCount,
                        ),
                        style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgSubtle),
                        modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x2),
                    )
                    // On the first step the arrow already leaves; a second
                    // control for the same thing would be one job with two
                    // ways in (rubric D2).
                    if (state.stepNumber > 1) {
                        OrbitIconButton(
                            icon = "x",
                            onClick = requestLeave,
                            contentDescription = stringResource(R.string.lists_new_close),
                            enabled = !state.creating,
                        )
                    }
                }
            },
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OrbitTheme.spacing.x5)
                    .padding(top = OrbitTheme.spacing.x2, bottom = OrbitTheme.spacing.x5),
            ) {
                StepHeading(text = stringResource(headingFor(state.step)))
                when (state.step) {
                    NewListStep.StartWith -> StartWithStep(
                        selected = state.template,
                        onSelect = onSelectTemplate,
                    )
                    NewListStep.Name -> NameStep(
                        initialName = state.name,
                        onNameChange = onNameChange,
                        onNext = onNext,
                        autoFocus = autoFocus,
                    )
                    NewListStep.HowOften -> HowOftenStep(
                        intervalHours = state.intervalHours,
                        onIntervalCommit = onIntervalCommit,
                        enabled = !state.creating,
                    )
                    NewListStep.People -> PeopleStep(
                        people = state.people,
                        onAddPeople = onAddPeople,
                        onRemovePerson = onRemovePerson,
                        enabled = !state.creating,
                    )
                }
            }
            OrbitSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        NewListFooter(state = state, onNext = onNext, onAddPeople = onAddPeople, onCreate = onCreate)
    }

    if (askingDiscard) {
        DiscardDialog(
            onKeepGoing = { askingDiscard = false },
            onDiscard = {
                askingDiscard = false
                onLeave()
            },
        )
    }
}

private fun headingFor(step: NewListStep): Int = when (step) {
    NewListStep.StartWith -> R.string.lists_new_start_heading
    NewListStep.Name -> R.string.lists_new_name_heading
    NewListStep.HowOften -> R.string.lists_new_how_often_heading
    NewListStep.People -> R.string.lists_new_people_heading
}

/**
 * The step's question. One node whose words change with the step, so its
 * polite live region tells TalkBack where the user is now; the app bar's
 * "New list" stays the pane title throughout.
 */
@Composable
private fun StepHeading(text: String) {
    Text(
        text = text,
        style = OrbitTheme.type.h2.copy(color = OrbitTheme.colors.fg),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = OrbitTheme.spacing.x2, bottom = OrbitTheme.spacing.x4)
            .semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * Name: one field, filled from the template's name (empty for "Start from
 * blank"), focused as the step opens so the keyboard is up, and the
 * keyboard's action is Next. The field owns its text while the step is open
 * (rules.md Code 7): it starts from [initialName], the ViewModel's copy, and
 * hands every change one way to [onNameChange]. Nothing writes the
 * ViewModel's name while this step is open, so the two never disagree, and
 * coming back to the step starts from what was typed. Under the privacy
 * curtain it draws "List" over the buffer and leaves the buffer alone
 * (CurtainMask, PRIV-03), as every list name field does.
 */
@Composable
private fun NameStep(
    initialName: String,
    onNameChange: (String) -> Unit,
    onNext: () -> Unit,
    autoFocus: Boolean,
) {
    var nameText by rememberSaveable { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    val curtainList = stringResource(R.string.components_curtain_list)
    // Focus may not be available on the first pass on a slow device; the
    // ListConfigScreen rename field's pattern.
    LaunchedEffect(Unit) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }
    OrbitTextField(
        value = nameText,
        onValueChange = {
            nameText = it
            onNameChange(it)
        },
        label = stringResource(R.string.lists_name_field),
        visualTransformation = if (LocalPrivacyCurtain.current) CurtainMask(curtainList) else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Next,
        ),
        // A blank name goes nowhere: the ViewModel's Next refuses it, as the
        // disabled button does.
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        modifier = Modifier.focusRequester(focusRequester),
    )
}

/**
 * How often: the day wheel List settings uses ([IntervalDaysPicker], LIST-30),
 * starting at the template's rhythm (every 2 days for "Start from blank" and
 * the list that fills itself), on a card as it sits in List settings.
 */
@Composable
private fun HowOftenStep(intervalHours: Int, onIntervalCommit: (Int) -> Unit, enabled: Boolean) {
    Text(
        text = stringResource(R.string.lists_new_how_often_hint),
        style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        modifier = Modifier.padding(bottom = OrbitTheme.spacing.x4),
    )
    StepCard {
        // Whole days from the wheel; the ViewModel keeps hours.
        IntervalDaysPicker(
            currentHours = intervalHours,
            onCommit = { days -> onIntervalCommit(days * 24) },
            enabled = enabled,
        )
    }
}

/**
 * Add people. With nobody chosen yet, one line and the footer's "Add people"
 * (and "Create without people"); once people are chosen, the People section
 * List settings shows ([MembersPreview], LIST-27): the count with "Add
 * people" on its right, which reopens the picker with them ticked, and each
 * person with "Remove {name} from list". Names and faces mask under the
 * curtain there.
 */
@Composable
private fun PeopleStep(
    people: List<ListConfigContactSnapshot>,
    onAddPeople: () -> Unit,
    onRemovePerson: (Long) -> Unit,
    enabled: Boolean,
) {
    if (people.isEmpty()) {
        Text(
            text = stringResource(R.string.lists_new_people_hint),
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        )
    } else {
        StepCard {
            MembersPreview(
                members = people,
                isSmart = false,
                onRemoveMember = { id, _ -> onRemovePerson(id) },
                onAddContacts = onAddPeople,
                enabled = enabled,
            )
        }
    }
}

/** The surface a step's control sits on, as a section of List settings does. */
@Composable
private fun StepCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .orbitCardShadow(OrbitTheme.shapes.lg, OrbitTheme.colors.isDark)
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.surface),
    ) { content() }
}

/**
 * The step's buttons. Primary is the step's one accent: "Next", "Create
 * list" on the last step, or "Add people" on the People step while nobody
 * is chosen, with "Create without people" (Ghost) above it. None of them is
 * pressable while Create's write is in flight, nor is anything else on the
 * screen (see [NewListContent]).
 */
@Composable
private fun NewListFooter(
    state: NewListUiState,
    onNext: () -> Unit,
    onAddPeople: () -> Unit,
    onCreate: () -> Unit,
) {
    val choosingPeople = state.step == NewListStep.People && state.people.isEmpty()
    Column(
        verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
        modifier = Modifier
            .fillMaxWidth()
            .background(OrbitTheme.colors.bg)
            .padding(horizontal = OrbitTheme.spacing.x5, vertical = OrbitTheme.spacing.x3),
    ) {
        if (choosingPeople) {
            OrbitButton(
                text = stringResource(R.string.lists_new_create_empty),
                onClick = onCreate,
                variant = OrbitButtonVariant.Ghost,
                enabled = state.canGoOn,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val (label, action) = when {
            choosingPeople -> R.string.lists_new_add_people to onAddPeople
            state.isLastStep -> R.string.lists_new_create to onCreate
            else -> R.string.lists_new_next to onNext
        }
        OrbitButton(
            text = stringResource(label),
            onClick = action,
            enabled = state.canGoOn,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Discard this list?": "Keep going" or "Discard". The note page's dialog shell. */
@Composable
private fun DiscardDialog(onKeepGoing: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepGoing,
        containerColor = OrbitTheme.colors.surface,
        title = {
            Text(
                text = stringResource(R.string.lists_new_discard_title),
                style = OrbitTheme.type.h3.copy(color = OrbitTheme.colors.fg),
            )
        },
        confirmButton = {
            OrbitButton(
                text = stringResource(R.string.lists_new_discard_confirm),
                onClick = onDiscard,
                variant = OrbitButtonVariant.Destructive,
            )
        },
        dismissButton = {
            OrbitButton(
                text = stringResource(R.string.lists_new_discard_keep),
                onClick = onKeepGoing,
                variant = OrbitButtonVariant.Ghost,
            )
        },
    )
}

// ── Previews (THEME-05: one per step and state; light, dark, 200%, the curtain) ──
// The first step's previews live in NewListStartWith.kt: its tiles show
// template names, which are copy, and that file is exempt from the
// gallery's curtain pass for it. Every step here shows the user's own words
// or people, and is audited.

private val innerOrbit = TemplateChoice.Catalog.first { it.id == "inner_orbit" }
private val recentlyAdded = TemplateChoice.Catalog.first { it.isSmart }

internal val previewPeople = listOf(
    ListConfigContactSnapshot(1L, "Kai Tanaka", null),
    ListConfigContactSnapshot(2L, "Maya Okafor", null),
    ListConfigContactSnapshot(3L, "Priya Nair", null),
)

@Composable
internal fun NewListPreviewHost(
    state: NewListUiState,
    curtain: Boolean = false,
    askingDiscard: Boolean = false,
) {
    OrbitTheme {
        // Only ever turns the curtain on, so the gallery's curtain pass, which
        // provides it from outside, still sees through this host.
        CompositionLocalProvider(LocalPrivacyCurtain provides (curtain || LocalPrivacyCurtain.current)) {
            NewListContent(
                state = state,
                onSelectTemplate = { _, _ -> },
                onNameChange = {},
                onNext = {},
                onPreviousStep = {},
                onIntervalCommit = {},
                onAddPeople = {},
                onRemovePerson = {},
                onCreate = {},
                onLeave = {},
                autoFocus = false,
                askingDiscardAtStart = askingDiscard,
            )
        }
    }
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun NewListNamePreview() {
    NewListPreviewHost(NewListUiState(step = NewListStep.Name, template = innerOrbit, name = "Inner orbit"))
}

// "Start from blank": an empty field, so Next is disabled until a name is typed.
@PreviewLightDark
@Composable
private fun NewListNameBlankPreview() {
    NewListPreviewHost(NewListUiState(step = NewListStep.Name, template = TemplateChoice.Catalog.first()))
}

@Preview(name = "curtain")
@Preview(name = "curtain, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewListNameCurtainPreview() {
    NewListPreviewHost(
        NewListUiState(step = NewListStep.Name, template = innerOrbit, name = "Inner orbit"),
        curtain = true,
    )
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun NewListHowOftenPreview() {
    NewListPreviewHost(
        NewListUiState(step = NewListStep.HowOften, template = innerOrbit, name = "Inner orbit", intervalHours = 7 * 24),
    )
}

// The list that fills itself: How often is its last step, so its button is
// "Create list" and the progress reads "Step 3 of 3".
@PreviewLightDark
@Composable
private fun NewListHowOftenSmartPreview() {
    NewListPreviewHost(
        NewListUiState(step = NewListStep.HowOften, template = recentlyAdded, name = "Recently added, not called"),
    )
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun NewListPeopleEmptyPreview() {
    NewListPreviewHost(NewListUiState(step = NewListStep.People, template = innerOrbit, name = "Inner orbit"))
}

@PreviewLightDark
@Preview(name = "200%", fontScale = 2f)
@Composable
private fun NewListPeoplePreview() {
    NewListPreviewHost(
        NewListUiState(step = NewListStep.People, template = innerOrbit, name = "Inner orbit", people = previewPeople),
    )
}

@Preview(name = "curtain")
@Preview(name = "curtain, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewListPeopleCurtainPreview() {
    NewListPreviewHost(
        NewListUiState(step = NewListStep.People, template = innerOrbit, name = "Inner orbit", people = previewPeople),
        curtain = true,
    )
}

// Create's write in flight: nothing that changes the list can be pressed
// until it lands or fails, the close control and the People section's Add
// people and remove controls included ("Show all" stays live: it only shows
// more).
@PreviewLightDark
@Composable
private fun NewListCreatingPreview() {
    NewListPreviewHost(
        NewListUiState(
            step = NewListStep.People,
            template = innerOrbit,
            name = "Inner orbit",
            people = previewPeople,
            creating = true,
        ),
    )
}

@PreviewLightDark
@Composable
private fun NewListDiscardPreview() {
    NewListPreviewHost(
        NewListUiState(step = NewListStep.HowOften, template = innerOrbit, name = "Inner orbit"),
        askingDiscard = true,
    )
}
