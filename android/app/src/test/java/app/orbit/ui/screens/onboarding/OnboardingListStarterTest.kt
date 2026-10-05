package app.orbit.ui.screens.onboarding

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.AppPrefs
import app.orbit.domain.FakeListRepository
import app.orbit.domain.clock.TestClock
import app.orbit.domain.listFixture
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression: onboarding created a new list each time Preview's buttons ran,
 * and the user reaches Preview again after system back from the first-list
 * step or a cold-start resume. Each pass left a duplicate first list.
 *
 * Real DataStore under Robolectric with `runBlocking`, the same fixture as
 * OnboardingDoneViewModelTest (DataStore writes hop to a real IO dispatcher).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OnboardingListStarterTest {

    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun clearDataStore() {
        runBlocking { AppPrefs(context).setOnboardingListId(null) }
        File(context.filesDir.parentFile, "datastore").takeIf { it.exists() }?.deleteRecursively()
    }

    private fun starter(listRepo: FakeListRepository) =
        OnboardingListStarter(listRepo, AppPrefs(context), TestClock())

    @Test
    fun `returning to Preview reuses the list instead of creating a second`() = runBlocking {
        val repo = FakeListRepository()
        val starter = starter(repo)

        val first = starter.startOrResume(
            defaultName = "In touch",
            memberContactIds = listOf(1L, 2L)
        )
        val again = starter.startOrResume(
            defaultName = "In touch",
            memberContactIds = listOf(2L, 3L)
        )

        assertEquals(first, again)
        assertEquals(1, repo.createCalls.size, "one list, however often Preview runs")
        assertEquals(
            setOf(1L, 2L, 3L),
            repo.addMemberCalls.filter { it.listId == first }.map { it.contactId }.toSet(),
            "members are only ever added to the reused list"
        )
    }

    @Test
    fun `Sync can find the list being built, which is how a resume skips Preview`() = runBlocking {
        val repo = FakeListRepository()
        val starter = starter(repo)
        assertNull(starter.pendingListId(), "nothing pending before the first list exists")

        val id = starter.startOrResume(defaultName = "", memberContactIds = emptyList())

        assertEquals(id, starter.pendingListId())
    }

    @Test
    fun `a name the user typed is kept, and a blank one takes the default`() = runBlocking {
        val repo = FakeListRepository()
        val starter = starter(repo)

        val id = starter.startOrResume(defaultName = "", memberContactIds = emptyList())
        starter.startOrResume(defaultName = "In touch", memberContactIds = emptyList())
        assertEquals("In touch", repo.getById(id)?.name)

        repo.updateName(id, "Family")
        starter.startOrResume(defaultName = "In touch", memberContactIds = emptyList())
        assertEquals("Family", repo.getById(id)?.name)
    }

    @Test
    fun `Add another list is the one deliberate new list, and becomes the pending one`() =
        runBlocking {
            val repo = FakeListRepository()
            val starter = starter(repo)

            val first = starter.startOrResume(
                defaultName = "In touch",
                memberContactIds = emptyList()
            )
            val second = starter.startAnother()

            assertNotEquals(first, second)
            assertEquals(2, repo.createCalls.size)
            assertEquals(second, starter.pendingListId())
        }

    @Test
    fun `a stale pointer (list deleted or reset) reads as no list`() = runBlocking {
        val repo = FakeListRepository(initialLists = listOf(listFixture(id = 1L)))
        AppPrefs(context).setOnboardingListId(99L)
        assertNull(starter(repo).pendingListId())

        repo.updateLists { rows -> rows.map { it.copy(isArchived = true) } }
        AppPrefs(context).setOnboardingListId(1L)
        assertNull(starter(repo).pendingListId(), "an archived list is not resumed")
        assertEquals(1L, AppPrefs(context).onboardingListId.first())
    }
}
