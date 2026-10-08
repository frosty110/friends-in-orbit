package app.orbit.ui.screens.lists.newlist

import androidx.compose.runtime.Immutable
import app.orbit.ui.screens.lists.ListConfigContactSnapshot
import app.orbit.ui.screens.lists.TemplateChoice

/**
 * LIST-28: the steps of New list, one decision each, in the owner's order
 * (vision/flows/owner-review-2026-10-07.md, decision 14).
 */
enum class NewListStep {
    /** LIST-29: "Start from blank", the three rhythm templates, the list that fills itself. */
    StartWith,

    /** One text field, filled from the template's name. */
    Name,

    /** The How often day wheel List settings uses, set from the template. */
    HowOften,

    /** The people, chosen in the contact picker; never for a list that fills itself. */
    People,
    ;

    companion object {
        private val Regular = listOf(StartWith, Name, HowOften, People)

        // A list that fills itself takes its people from its rule, so How
        // often is its last step and its button is "Create list".
        private val Smart = listOf(StartWith, Name, HowOften)

        /** The steps [template] goes through; a regular list's until a template is chosen. */
        fun stepsFor(template: TemplateChoice?): List<NewListStep> =
            if (template?.isSmart == true) Smart else Regular
    }
}

/**
 * New list's one state contract (ARCH-02). Everything entered lives in the
 * ViewModel's SavedStateHandle and arrives here, so stepping back loses
 * nothing and neither does a process death; the derived values below are
 * computed once, at construction.
 *
 * [createdListId] is set once Create's write has landed: the screen leaves
 * on it (state, not an event, so a rotation during the write cannot leave
 * the flow open over a list that already exists; the note page's precedent).
 */
@Immutable
data class NewListUiState(
    val step: NewListStep = NewListStep.StartWith,
    val template: TemplateChoice? = null,
    val name: String = "",
    val intervalHours: Int = TemplateChoice.DEFAULT_INTERVAL_HOURS,
    /** The people chosen so far, by name; those who have left the phone's contacts drop out. */
    val people: List<ListConfigContactSnapshot> = emptyList(),
    val creating: Boolean = false,
    val createdListId: Long? = null,
) {
    val steps: List<NewListStep> = NewListStep.stepsFor(template)

    /** 1-based, for "Step 2 of 4". */
    val stepNumber: Int = steps.indexOf(step) + 1

    val stepCount: Int = steps.size

    /** The step whose button is "Create list": People, or How often for a list that fills itself. */
    val isLastStep: Boolean = step == steps.last()

    /**
     * Whether the step's Next may go on: a template chosen on Start with, a
     * name that is not blank on Name. How often always has a value. Nothing
     * moves while Create's write is in flight.
     */
    val canGoOn: Boolean = !creating && when (step) {
        NewListStep.StartWith -> template != null
        NewListStep.Name -> name.isNotBlank()
        NewListStep.HowOften, NewListStep.People -> true
    }

    /**
     * Whether leaving now would throw something away, so Back on the first
     * step or the close control asks "Discard this list?" first. A template
     * picked counts: it is the first thing entered.
     */
    val hasEntries: Boolean = template != null || name.isNotBlank() || people.isNotEmpty()
}
