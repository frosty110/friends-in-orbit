package app.orbit.ui.util

import app.orbit.R
import app.orbit.domain.model.PauseDuration

/**
 * How a pause reads in the snackbar that confirms it, single or bulk:
 * "Paused Sam for 1 week", "Paused 3 people indefinitely". The one owner of
 * that sentence, so Contact detail and Browse (single row and bulk) cannot
 * word it differently. A whole sentence per duration in strings_components.xml
 * rather than "Paused {name} {phrase}": "indefinitely" takes no preposition in
 * English, and building "for {label}" at a call site once produced "Paused 3
 * contacts for indefinitely".
 *
 * [name] is the person's name, or a [UiText] stand-in when the screen has no
 * name to hand.
 */
fun pausedSnackbar(name: Any, duration: PauseDuration): UiText = UiText.res(
    when (duration) {
        PauseDuration.OneWeek -> R.string.components_snackbar_paused_week
        PauseDuration.OneMonth -> R.string.components_snackbar_paused_month
        PauseDuration.Indefinite -> R.string.components_snackbar_paused_indefinitely
    },
    name,
)

/** The bulk form of [pausedSnackbar]: "Paused 3 people for 1 month". */
fun pausedPeopleSnackbar(count: Int, duration: PauseDuration): UiText = UiText.plural(
    when (duration) {
        PauseDuration.OneWeek -> R.plurals.components_snackbar_paused_people_week
        PauseDuration.OneMonth -> R.plurals.components_snackbar_paused_people_month
        PauseDuration.Indefinite -> R.plurals.components_snackbar_paused_people_indefinitely
    },
    count,
    count,
)
