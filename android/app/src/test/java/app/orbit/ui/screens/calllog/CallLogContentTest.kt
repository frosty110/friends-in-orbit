package app.orbit.ui.screens.calllog

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What TalkBack hears from Call history, read from the semantics tree on the
 * JVM (the `ContactDetailCurtainTest` precedent):
 *
 *  - the pane title in every state of one person's log. Until 2026-10-06 the
 *    bar was blank whenever the person's name had not loaded, which is
 *    momentary while Loading but permanent in Error (a read that failed
 *    before its first emission, the case Retry exists for), so TalkBack
 *    announced no screen at all;
 *  - the row's "More actions for {name}" button, named for the person it acts
 *    on and masked under the privacy curtain like every other name (PRIV-03).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class CallLogContentTest {

    @get:Rule val compose = createComposeRule()

    private val paneTitle = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)

    private fun titled(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    private fun show(state: CallLogUiState, curtain: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    CallLogContent(
                        state = state,
                        onBack = {},
                        onOpenContact = { _, _ -> },
                        onCallAgain = {},
                        onFilterChange = {},
                        onShowMore = {},
                        onOpenSettings = {},
                        onRetry = {},
                    )
                }
            }
        }
    }

    @Test
    fun everyone_is_call_history() {
        show(CallLogUiState.Loading())
        compose.onNode(titled("Call history")).assertExists()
    }

    @Test
    fun one_person_still_loading_has_no_title_yet() {
        // The moment the person's row takes to arrive: blank rather than a
        // false "Call history" for everyone, and no empty pane title.
        show(CallLogUiState.Loading(CallLogScope.Person(contactId = 1L)))
        compose.onAllNodes(paneTitle).assertCountEquals(0)
    }

    @Test
    fun one_person_whose_read_failed_before_the_name_loaded_is_titled_call_history() {
        show(CallLogUiState.Error(CallLogScope.Person(contactId = 1L)))
        compose.onNode(titled("Call history")).assertExists()
    }

    @Test
    fun one_person_denied_before_the_name_loaded_is_titled_call_history() {
        show(CallLogUiState.PermissionDenied(CallLogScope.Person(contactId = 1L)))
        compose.onNode(titled("Call history")).assertExists()
    }

    @Test
    fun one_person_with_a_name_is_titled_calls_with_them() {
        show(CallLogUiState.Error(CallLogScope.Person(contactId = 1L, name = "Avery Quinn")))
        compose.onNode(titled("Calls with Avery Quinn")).assertExists()
    }

    @Test
    fun the_more_button_is_named_for_the_person() {
        show(ready())
        compose.onNodeWithContentDescription("More actions for Jordan Lee").assertExists()
    }

    @Test
    fun under_the_curtain_the_more_button_names_no_one() {
        show(ready(), curtain = true)
        compose.onNodeWithContentDescription("More actions for Contact").assertExists()
        compose.onAllNodes(SemanticsMatcher("names Jordan") { node ->
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { "Jordan" in it }
        }).assertCountEquals(0)
    }

    private fun ready(): CallLogUiState = CallLogUiState.Ready(
        sections = listOf(
            CallLogDaySection(
                epochDay = 20_500L,
                label = UiText.res(R.string.time_day_today),
                rows = listOf(
                    CallLogRow(
                        callEventId = 1L,
                        contactId = 1L,
                        name = "Jordan Lee",
                        phone = "+15550001",
                        photoUri = null,
                        listName = "Inner orbit",
                        durationLabel = null,
                        directionIconName = "phone-outgoing",
                        timeLabel = "4:30pm",
                        isIgnored = false,
                        kind = CallLogKind.Outgoing,
                    ),
                ),
            ),
        ),
    )
}
