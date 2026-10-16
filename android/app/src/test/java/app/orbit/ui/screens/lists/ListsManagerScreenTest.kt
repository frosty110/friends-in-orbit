package app.orbit.ui.screens.lists

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.orbit.R
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.listFixture
import app.orbit.notify.NudgeScheduler
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.UiText
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * LIST-23 wiring (lists-1): the Lists screen leaves for two places. A tap on
 * a row opens the list's deck; the row menu's "List settings" and the
 * archived row's settings control open List settings. Until 2026-10-06 the
 * screen had one callback and all of them opened the deck. The regression
 * shipped with "nav tests pass" because nothing asserted where each control
 * went; this does. "New list" opens the New list flow (LIST-28), a screen of
 * its own since 2026-10-07; where that flow returns is OrbitNavHostTest's.
 * A list that was not there before (Create's new list, last in the order) is
 * scrolled into view; the lists first shown and a restored one are not news.
 * Each row says its member count in words at the end of its second line
 * (rules.md Design 9, 2026-10-08), never after the unreadable-rhythm error.
 *
 * Runs on the JVM under Robolectric: it reads the semantics tree and fires
 * click actions, which needs no emulator (development-cycle.md, Verify).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ListsManagerScreenTest {

    @get:Rule val compose = createComposeRule()

    private val ready = ListsManagerUiState.Ready(
        active = listOf(
            ListTileState(
                id = 1L,
                name = "Inner orbit",
                memberCount = 3,
                type = ListType.STATIC,
                ruleSummary = ListRowSummary.Readable(UiText.plural(R.plurals.lists_interval_every_days, 7, 7)),
            ),
        ),
        archived = listOf(
            ListTileState(
                id = 9L,
                name = "Drifted",
                memberCount = 0,
                type = ListType.STATIC,
                ruleSummary = null,
            ),
        ),
        archivedExpanded = true,
    )

    private val fired = mutableListOf<String>()

    private fun setReadyContent() {
        compose.setContent {
            OrbitTheme {
                ListsManagerContent(
                    state = ready,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onOpenList = { fired += "deck:$it" },
                    onOpenListSettings = { fired += "settings:$it" },
                    onAddContacts = {},
                    onCreate = { fired += "create" },
                    onMove = { _, _ -> },
                    onArchive = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onRestore = {},
                    onToggleNudges = {},
                    onToggleArchived = {},
                )
            }
        }
    }

    @Test
    fun a_row_tap_opens_the_deck() {
        setReadyContent()

        compose.onNodeWithText("Inner orbit").performClick()

        compose.runOnIdle { assertEquals(listOf("deck:1"), fired) }
    }

    @Test
    fun the_menus_list_settings_opens_settings_not_the_deck() {
        setReadyContent()

        compose.onNodeWithContentDescription("More actions for Inner orbit").performClick()
        compose.onNodeWithText("List settings").performClick()

        compose.runOnIdle { assertEquals(listOf("settings:1"), fired) }
    }

    @Test
    fun the_archived_rows_settings_control_opens_settings() {
        setReadyContent()

        compose.onNodeWithContentDescription("List settings for Drifted").performClick()

        compose.runOnIdle { assertEquals(listOf("settings:9"), fired) }
    }

    private fun setRows(vararg rows: ListTileState) {
        compose.setContent {
            OrbitTheme {
                ListsManagerContent(
                    state = ListsManagerUiState.Ready(
                        active = rows.toList(),
                        archived = emptyList(),
                        archivedExpanded = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onOpenList = {},
                    onOpenListSettings = {},
                    onAddContacts = {},
                    onCreate = {},
                    onMove = { _, _ -> },
                    onArchive = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onRestore = {},
                    onToggleNudges = {},
                    onToggleArchived = {},
                )
            }
        }
    }

    private fun row(
        id: Long,
        name: String,
        memberCount: Int,
        summary: ListRowSummary?,
        type: ListType = ListType.STATIC,
    ) = ListTileState(id = id, name = name, memberCount = memberCount, type = type, ruleSummary = summary)

    private fun every(days: Int) =
        ListRowSummary.Readable(UiText.plural(R.plurals.lists_interval_every_days, days, days))

    /** The text TalkBack reads for the row named [name]: the row is one merged, clickable node. */
    private fun rowTexts(name: String): List<String> =
        compose.onNode(hasText(name) and hasClickAction()).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    // LIST-07, amended 2026-10-08 (the owner, on the chip: "We don't need this
    // badge"). A smart list's row says what it is by its rule line, which
    // TalkBack reads with the name; the "Smart list" chip is gone. Fails with
    // the chip back: its text is found. The same day the count joined that
    // line in words (rules.md Design 9), so the line ends with the people.
    @Test
    fun a_smart_lists_row_names_its_rule_and_carries_no_chip() {
        setRows(
            row(
                id = 3L,
                name = "Recently added, not called",
                memberCount = 4,
                type = ListType.SMART,
                summary = ListRowSummary.Readable(UiText.plural(R.plurals.lists_rule_summary_recently_added, 30, 30)),
            ),
        )

        compose.onNodeWithText("Recently added · 30 days · 4 people").assertIsDisplayed()
        compose.onAllNodesWithText("Smart list", useUnmergedTree = true).assertCountEquals(0)
    }

    // ── Design 9: the member count is said in words, not in a pill ─────────

    // Until 2026-10-08 the count was a bare number in an accent pill after
    // the name, also a second accent beside New list (Design 5). Fails with
    // the pill back: its bare "12" is found, and TalkBack hears it after the
    // line.
    @Test
    fun the_count_ends_the_rhythm_line_and_no_pill_shows_it() {
        setRows(row(id = 1L, name = "Inner orbit", memberCount = 12, summary = every(14)))

        compose.onNodeWithText("Every 14 days · 12 people").assertIsDisplayed()
        compose.onAllNodesWithText("12", useUnmergedTree = true).assertCountEquals(0)
        // One merged node: the name, then the line, once each.
        assertEquals(listOf("Inner orbit", "Every 14 days · 12 people"), rowTexts("Inner orbit"))
    }

    @Test
    fun one_person_is_singular() {
        setRows(row(id = 1L, name = "Inner orbit", memberCount = 1, summary = every(7)))

        compose.onNodeWithText("Every 7 days · 1 person").assertIsDisplayed()
    }

    // The pill drew nothing at zero; the line says what Home's card says of
    // an empty list, so the two screens agree.
    @Test
    fun an_empty_list_says_no_one_yet() {
        setRows(row(id = 1L, name = "Inner orbit", memberCount = 0, summary = every(14)))

        compose.onNodeWithText("Every 14 days · No one yet").assertIsDisplayed()
    }

    // A list with no rhythm to say (no template, no override) had no second
    // line; now the count is the line.
    @Test
    fun with_no_rhythm_the_count_is_the_line() {
        setRows(
            row(id = 1L, name = "Inner orbit", memberCount = 12, summary = null),
            row(id = 2L, name = "Book club", memberCount = 0, summary = null),
        )

        assertEquals(listOf("Inner orbit", "12 people"), rowTexts("Inner orbit"))
        assertEquals(listOf("Book club", "No one yet"), rowTexts("Book club"))
    }

    // The error stays alone: a count after it would read as part of what
    // could not be read (rules.md Code 3 keeps the error itself).
    @Test
    fun an_unreadable_rhythm_says_so_alone_with_no_count() {
        setRows(row(id = 1L, name = "Inner orbit", memberCount = 5, summary = ListRowSummary.Unreadable))

        assertEquals(listOf("Inner orbit", "Couldn't read this list's rhythm"), rowTexts("Inner orbit"))
        compose.onAllNodesWithText("5 people", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun the_line_builder_never_joins_the_count_to_the_error() {
        assertEquals(ListRowSummary.Unreadable.text, listRowMeta(ListRowSummary.Unreadable, memberCount = 5))
        assertEquals(
            UiText.res(R.string.lists_row_meta, every(14).text, UiText.plural(R.plurals.lists_members_count, 12, 12)),
            listRowMeta(every(14), memberCount = 12),
        )
        assertEquals(UiText.res(R.string.lists_row_members_none), listRowMeta(null, memberCount = 0))
    }

    @Test
    fun new_list_opens_the_new_list_flow() {
        setReadyContent()

        compose.onNodeWithText("New list").performClick()

        compose.runOnIdle { assertEquals(listOf("create"), fired) }
    }

    // ── LIST-28: the new list is brought into view ─────────────────────────

    private fun tile(id: Long, name: String) = ListTileState(
        id = id,
        name = name,
        memberCount = 0,
        type = ListType.STATIC,
        ruleSummary = null,
    )

    /** More lists than fit on the test window, so the last ones start below the fold. */
    private val manyLists = (1L..20L).map { tile(it, "List $it") }

    private var shown by mutableStateOf<ListsManagerUiState>(ListsManagerUiState.Loading)

    private fun setChangingContent(initial: ListsManagerUiState) {
        shown = initial
        compose.setContent {
            OrbitTheme {
                ListsManagerContent(
                    state = shown,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onOpenList = {},
                    onOpenListSettings = {},
                    onAddContacts = {},
                    onCreate = {},
                    onMove = { _, _ -> },
                    onArchive = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onRestore = {},
                    onToggleNudges = {},
                    onToggleArchived = {},
                )
            }
        }
    }

    @Test
    fun a_new_list_last_in_the_order_is_scrolled_into_view() {
        // Until 2026-10-08 the screen kept its scroll position, so with more
        // lists than fit, the list just made was below the fold.
        setChangingContent(
            ListsManagerUiState.Ready(active = manyLists, archived = emptyList(), archivedExpanded = false),
        )
        compose.onNodeWithText("List 1").assertIsDisplayed()

        compose.runOnIdle {
            shown = ListsManagerUiState.Ready(
                active = manyLists + tile(21L, "Book club"),
                archived = emptyList(),
                archivedExpanded = false,
            )
        }

        compose.onNodeWithText("Book club").assertIsDisplayed()
    }

    @Test
    fun the_lists_first_shown_and_a_restored_one_leave_the_scroll_where_it_is() {
        // The first lists are the baseline: opening Lists starts at the top.
        setChangingContent(
            ListsManagerUiState.Ready(
                active = manyLists,
                archived = listOf(tile(30L, "Old friends")),
                archivedExpanded = false,
            ),
        )
        compose.onNodeWithText("List 1").assertIsDisplayed()

        // Restore puts a list it has shown before last among the active ones.
        compose.runOnIdle {
            shown = ListsManagerUiState.Ready(
                active = manyLists + tile(30L, "Old friends"),
                archived = emptyList(),
                archivedExpanded = false,
            )
        }

        compose.onNodeWithText("List 1").assertIsDisplayed()
    }

    @Test
    fun the_stateful_screen_hands_new_list_to_its_caller() {
        val vm = ListsManagerViewModel(
            listRepo = FakeListRepository(initialLists = listOf(listFixture(id = 1L, sortOrder = 0))),
            ruleTemplateRepo = FakeRuleTemplateRepository(),
            nudgeScheduler = ScreenTestNudgeScheduler(),
        )
        compose.setContent {
            OrbitTheme {
                ListsManagerScreen(
                    onBack = {},
                    onOpenList = { fired += "deck:$it" },
                    onOpenListSettings = { fired += "settings:$it" },
                    onCreateList = { fired += "new list" },
                    vm = vm,
                )
            }
        }

        compose.onNodeWithText("New list").performClick()

        // The sheet is gone (LIST-28): nothing opens on this screen, the
        // caller (the nav graph) opens the flow.
        compose.runOnIdle { assertEquals(listOf("new list"), fired) }
    }
}

/** Keeps WorkManager out of the test; the scheduler is not what is under test here. */
private class ScreenTestNudgeScheduler : NudgeScheduler(
    context = ApplicationProvider.getApplicationContext<Context>(),
    listRepo = FakeListRepository(),
) {
    override fun cancel(listId: Long) = Unit
    override suspend fun scheduleFromEntity(list: ListEntity) = Unit
}
