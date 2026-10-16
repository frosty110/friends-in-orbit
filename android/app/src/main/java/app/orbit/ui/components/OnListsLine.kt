package app.orbit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import app.orbit.R
import app.orbit.ui.util.UiText
import app.orbit.ui.util.asString

/**
 * The lists a person is on, said in words on one line: "On Inner orbit, Late
 * night"; past three names, "On Inner orbit, Late night, Family and 2 more";
 * under the privacy curtain only how many, "On 2 lists", so no list name is
 * ever shown, masked or not (PRIV-03). Null when the person is on no list:
 * each caller says that its own way (Search keeps "Not on any list", the Add
 * people picker says nothing, since most of an address book is on no list).
 *
 * One definition for the two places that say this fact about a person in a
 * list of people: the Add people picker's rows (PICK-04), where it began, and
 * Search's results (BROWSE-03), which said it as a row of Stone chips until
 * 2026-10-08. A read-only chip is a badge, and rules.md Design 9 retires
 * them: the fact is said in words on a line the row already has.
 *
 * Contact detail does not use it: that page is the person's own, so it names
 * every list without "and N more", and its heading ("On these lists") already
 * says "On".
 *
 * Reads [LocalPrivacyCurtain] itself, so a caller cannot forget the mask.
 * The words come from [onListsLineText], which tests resolve directly.
 */
@Composable
@ReadOnlyComposable
fun onListsLine(listNames: List<String>): String? =
    onListsLineText(listNames, curtain = LocalPrivacyCurtain.current)?.asString()

/**
 * The words behind [onListsLine], as [UiText] (voice.md, "Where copy lives":
 * formatters return UiText and hold no Context). Names are joined with ", "
 * in the user's own list order; at most [ON_LISTS_SHOWN] are named.
 */
fun onListsLineText(listNames: List<String>, curtain: Boolean): UiText? = when {
    listNames.isEmpty() -> null
    curtain -> UiText.plural(R.plurals.components_on_lists_count, listNames.size, listNames.size)
    listNames.size <= ON_LISTS_SHOWN -> UiText.res(R.string.components_on_lists, listNames.joinToString(", "))
    else -> {
        val rest = listNames.size - ON_LISTS_SHOWN
        UiText.plural(
            R.plurals.components_on_lists_more,
            rest,
            listNames.take(ON_LISTS_SHOWN).joinToString(", "),
            rest,
        )
    }
}

/** How many list names the line says before "and N more". */
private const val ON_LISTS_SHOWN = 3
