package app.orbit.ui.screens.note

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDuration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * NOTE-04's page, from the semantics tree under Robolectric: the timer and
 * what TalkBack hears of it, Save only with words written, "Not now" and
 * Back asking "Discard this note?" only when there is something to lose, the
 * curtain, and the not-found message.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class PostCallNoteContentTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = compose.activity

    private val ready = PostCallNoteUiState.Ready(
        contactId = 7L,
        firstName = "Kai",
        call = NoteCall(
            direction = CallDirection.OUTGOING,
            durationLabel = formatDuration(14 * 60),
            whenLabel = UiText.res(R.string.note_call_when, UiText.res(R.string.time_day_today), "4:30pm"),
        ),
    )

    private var seconds by mutableLongStateOf(134L)
    private var left = 0
    private var discarded = 0
    private var saves = 0
    private val drafts = mutableListOf<String>()

    private fun setPage(state: PostCallNoteUiState = ready, draft: String = "", curtain: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme {
                    PostCallNoteContent(
                        state = state,
                        elapsedSeconds = { seconds },
                        initialDraft = draft,
                        onDraftChange = { drafts += it },
                        onSave = { saves++ },
                        onLeave = { left++ },
                        onDiscard = { discarded++ },
                        onRetry = {},
                        autoFocus = false,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    // ── what the page shows ───────────────────────────────────────────────────

    @Test
    fun the_page_names_the_call_in_the_title_and_in_one_line() {
        setPage()

        compose.onNodeWithText(string(R.string.note_title_named, "Kai")).assertExists()
        compose.onNodeWithText("You called Kai · 14 min · Today at 4:30pm").assertExists()
        compose.onNodeWithText(string(R.string.note_prompt)).assertExists()
    }

    @Test
    fun the_timer_counts_in_m_ss_and_TalkBack_hears_it_only_by_the_minute() {
        setPage()

        // TalkBack hears the minute in words; the ticking digits are drawn
        // but cleared from what accessibility reads (the merged tree, where
        // clearAndSetSemantics applies), so they are never read second by second.
        compose.onNodeWithContentDescription(context.resources.getQuantityString(R.plurals.note_timer_minutes, 2, 2))
            .assertExists()
        compose.onNodeWithText("2:14").assertDoesNotExist()
        // No live region: a ticking clock is never announced by itself.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)

        seconds = 30L
        compose.waitForIdle()
        compose.onNodeWithContentDescription(string(R.string.note_timer_under_a_minute)).assertExists()
    }

    @Test
    fun elapsed_time_reads_m_ss_then_h_mm_ss() {
        assertEquals("0:00", formatElapsed(0L))
        assertEquals("0:08", formatElapsed(8L))
        assertEquals("2:14", formatElapsed(134L))
        assertEquals("59:59", formatElapsed(3_599L))
        assertEquals("1:00:00", formatElapsed(3_600L))
        assertEquals("1:02:05", formatElapsed(3_725L))
        assertEquals("0:00", formatElapsed(-4L))
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    @Test
    fun save_waits_for_words_and_every_change_reaches_the_view_model() {
        setPage()
        val save = compose.onNodeWithText(string(R.string.note_save))
        save.assertIsNotEnabled()

        compose.onNodeWithText(string(R.string.note_prompt)).performTextInput("Went well")
        compose.waitForIdle()

        save.assertIsEnabled().performClick()
        assertEquals(1, saves)
        assertEquals("Went well", drafts.last())
    }

    @Test
    fun save_is_held_while_the_note_is_being_written() {
        setPage(state = ready.copy(saving = true), draft = "Went well")

        compose.onNodeWithText(string(R.string.note_save)).assertIsNotEnabled()
    }

    // ── leaving ───────────────────────────────────────────────────────────────

    @Test
    fun not_now_with_nothing_written_just_leaves() {
        setPage()

        compose.onNodeWithText(string(R.string.note_not_now)).performClick()

        assertEquals(1, left)
        compose.onAllNodesWithText(string(R.string.note_discard_title)).assertCountEquals(0)
    }

    @Test
    fun not_now_with_words_asks_first_and_keep_writing_stays() {
        setPage(draft = "Went well")

        compose.onNodeWithText(string(R.string.note_not_now)).performClick()
        compose.onNodeWithText(string(R.string.note_discard_title)).assertExists()

        compose.onNodeWithText(string(R.string.note_discard_keep)).performClick()
        compose.waitForIdle()

        assertEquals(0, left)
        assertEquals(0, discarded)
        compose.onAllNodesWithText(string(R.string.note_discard_title)).assertCountEquals(0)
    }

    @Test
    fun discard_forgets_the_words_and_leaves() {
        setPage(draft = "Went well")

        compose.onNodeWithText(string(R.string.note_not_now)).performClick()
        compose.onNodeWithText(string(R.string.note_discard_confirm)).performClick()

        assertEquals(1, discarded)
        assertEquals(1, left)
    }

    @Test
    fun back_with_words_asks_too() {
        setPage(draft = "Went well")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.note_discard_title)).assertExists()
        assertEquals(0, left)
    }

    // ── the curtain (PRIV-03) and the person being gone ───────────────────────

    @Test
    fun under_the_curtain_the_page_names_no_one_and_hides_the_words() {
        setPage(draft = "Kai sounded tired", curtain = true)

        compose.onNodeWithText(string(R.string.note_title)).assertExists()
        compose.onNodeWithText("You called · 14 min · Today at 4:30pm").assertExists()
        compose.onNodeWithText(string(R.string.note_hidden)).assertExists()
        // What is drawn and spoken: text, the field's EditableText, labels.
        // Not InputText, the buffer kept for autofill: CurtainMask draws over
        // the words rather than replacing them, so a typed note is never
        // saved as "Note hidden" (the gallery's curtain audit reads the same way).
        compose.onAllNodes(namesShown("Kai"), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun namesShown(name: String) = SemanticsMatcher("$name drawn or spoken") { node ->
        val c = node.config
        (
            c.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                c.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } +
                listOfNotNull(c.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text)
            ).any { name in it }
    }

    @Test
    fun a_person_who_is_gone_gets_the_not_found_message_and_go_back() {
        setPage(state = PostCallNoteUiState.NotFound)

        compose.onNodeWithText(string(R.string.contact_not_found_title)).assertExists()
        compose.onNodeWithText(string(R.string.components_action_go_back)).performClick()

        assertEquals(1, left)
        assertTrue(compose.onAllNodesWithText(string(R.string.note_save)).fetchSemanticsNodes().isEmpty())
    }
}
