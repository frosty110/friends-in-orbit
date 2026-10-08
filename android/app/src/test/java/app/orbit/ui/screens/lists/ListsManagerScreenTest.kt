package app.orbit.ui.screens.lists

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
                ruleSummary = UiText.plural(R.plurals.lists_interval_every_days, 7, 7),
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

    // LIST-07, amended 2026-10-08 (the owner, on the chip: "We don't need this
    // badge"). A smart list's row says what it is by its rule line, which
    // TalkBack reads with the name; the "Smart list" chip is gone. Fails with
    // the chip back: its text is found.
    @Test
    fun a_smart_lists_row_names_its_rule_and_carries_no_chip() {
        compose.setContent {
            OrbitTheme {
                ListsManagerContent(
                    state = ListsManagerUiState.Ready(
                        active = listOf(
                            ListTileState(
                                id = 3L,
                                name = "Recently added, not called",
                                memberCount = 4,
                                type = ListType.SMART,
                                ruleSummary = UiText.plural(R.plurals.lists_rule_summary_recently_added, 30, 30),
                            ),
                        ),
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

        compose.onNodeWithText("Recently added · 30 days").assertIsDisplayed()
        compose.onAllNodesWithText("Smart list", useUnmergedTree = true).assertCountEquals(0)
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
