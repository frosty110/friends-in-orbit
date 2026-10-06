package app.orbit.ui.screens.lists

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
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
 * a row opens the list's deck; the row menu's "List settings", the archived
 * row's settings control and a successful Create open List settings. Until
 * 2026-10-06 the screen had one callback and all three opened the deck, so a
 * brand-new list opened as an empty deck. The regression shipped with "nav
 * tests pass" because nothing asserted where each control went; this does.
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

    @Test
    fun new_list_opens_the_create_sheet() {
        setReadyContent()

        compose.onNodeWithText("New list").performClick()

        compose.runOnIdle { assertEquals(listOf("create"), fired) }
    }

    /**
     * The create flow's destination lives in the stateful screen, which
     * collects the VM's `createdListEvents`; a real VM over the fakes drives
     * it, as the `vm` parameter allows.
     */
    @Test
    fun a_created_list_opens_its_settings() {
        val repo = FakeListRepository(initialLists = listOf(listFixture(id = 1L, sortOrder = 0)))
        val vm = ListsManagerViewModel(
            listRepo = repo,
            ruleTemplateRepo = FakeRuleTemplateRepository(),
            nudgeScheduler = ScreenTestNudgeScheduler(),
        )
        compose.setContent {
            OrbitTheme {
                ListsManagerScreen(
                    onBack = {},
                    onOpenList = { fired += "deck:$it" },
                    onOpenListSettings = { fired += "settings:$it" },
                    vm = vm,
                )
            }
        }
        compose.waitForIdle()

        val blank = TemplateChoice.Catalog.first { it.id == "blank" }
        compose.runOnIdle { vm.createList(blank, "Night owls") }

        // FakeListRepository.create assigns max(id) + 1.
        compose.runOnIdle { assertEquals(listOf("settings:2"), fired) }
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
