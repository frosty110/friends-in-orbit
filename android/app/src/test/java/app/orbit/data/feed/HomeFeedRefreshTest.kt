package app.orbit.data.feed

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.AppPrefs
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListType
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.usecase.SurfaceNextUseCase
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural coverage for the 5-minute TTL gate around
 * [HomeFeed.refreshDueCountsIfStale].
 *
 * The gate is the only thing standing between [androidx.lifecycle.Lifecycle.Event.ON_START]
 * and N `recomputeDueCountForList` calls per active list. Rotations, theme
 * switches, and brief backgrounding all fire ON_START — recomputing on every
 * one would be a DoS on the user's DB.
 *
 * Robolectric supplies a real [android.content.Context] so the production
 * [AppPrefs] runs unchanged (its DataStore extension property is
 * Context-coupled). Same pattern as [app.orbit.data.AppPrefsTest].
 *
 * `recomputeDueCountForActiveCalls` on [FakeListRepository] is the assertion
 * target — tests verify the bulk recompute fires (or doesn't) per the gate's
 * decision. HomeFeed dispatches one bulk recompute per ON_START past
 * the TTL, not N per-list calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HomeFeedRefreshTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private lateinit var appPrefs: AppPrefs
    private lateinit var listRepo: FakeListRepository
    private lateinit var clock: TestClock

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        appPrefs = AppPrefs(ctx)
        clock = TestClock(T0)
        // Three active lists + one archived. Active set drives the recompute
        // count; archived must not appear in the dispatched recompute calls.
        listRepo = FakeListRepository(
            initialLists = listOf(
                ListEntity(id = 1L, name = "Inner orbit", sortOrder = 0, type = ListType.STATIC),
                ListEntity(id = 2L, name = "Late night", sortOrder = 1, type = ListType.STATIC),
                ListEntity(id = 3L, name = "Recently added", sortOrder = 2, type = ListType.SMART),
                ListEntity(id = 4L, name = "Archived", sortOrder = 3, isArchived = true),
            ),
        )
    }

    @After
    fun clearDataStore() {
        runBlocking {
            // Reset every key this class writes so subsequent classes in the
            // same JVM see fresh defaults.
            appPrefs.setLastDueCountRecomputeAt(0L)
        }
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val prefsDir = java.io.File(ctx.filesDir.parentFile, "datastore")
        if (prefsDir.exists()) prefsDir.deleteRecursively()
    }

    private fun feed(): HomeFeed = HomeFeed(
        listRepo = listRepo,
        clock = clock,
        appPrefs = appPrefs,
        // Enrichment deps — these tests exercise the staleness gate only; the
        // empty fakes make the per-list enrichment a no-op.
        surfaceNext = SurfaceNextUseCase(
            contactRepo = FakeContactRepository(),
            listRepo = FakeListRepository(),
            callEventRepo = FakeCallEventRepository(),
            ruleTemplateRepo = FakeRuleTemplateRepository(),
            clock = clock,
            json = JsonProvider.json,
        ),
        callEventRepo = FakeCallEventRepository(),
        scope = CoroutineScope(UnconfinedTestDispatcher()),
    )

    // ------------------------------------------------------------------
    // Within-TTL no-op
    // ------------------------------------------------------------------

    @Test
    fun within_5_minutes_no_op() = runTest {
        // Last refresh 4 minutes ago — under the 5-minute window.
        appPrefs.setLastDueCountRecomputeAt(T0.toEpochMilli())
        clock.advance(Duration.ofMinutes(4))
        val feed = feed()

        feed.refreshDueCountsIfStale()

        assertTrue(
            listRepo.recomputeDueCountForActiveCalls.isEmpty(),
            "TTL gate must short-circuit before dispatching recompute; got ${listRepo.recomputeDueCountForActiveCalls}",
        )
    }

    // ------------------------------------------------------------------
    // Beyond-TTL recomputes every active list (single bulk call)
    // ------------------------------------------------------------------

    @Test
    fun beyond_5_minutes_recomputes_each_active_list() = runTest {
        // Last refresh 6 minutes ago — past the 5-minute window.
        appPrefs.setLastDueCountRecomputeAt(T0.toEpochMilli())
        clock.advance(Duration.ofMinutes(6))
        val nowAfter = clock.now()
        val feed = feed()

        feed.refreshDueCountsIfStale()

        // WR-02 — exactly one bulk recompute call (covers every active list
        // in a single SQL statement), not N per-list calls.
        assertEquals(1, listRepo.recomputeDueCountForActiveCalls.size)
        assertEquals(nowAfter, listRepo.recomputeDueCountForActiveCalls.single())
        // Pref updated to the new now (epoch millis).
        assertEquals(nowAfter.toEpochMilli(), appPrefs.lastDueCountRecomputeAt.first())
    }

    // ------------------------------------------------------------------
    // First foreground after install (lastRecomputeAt == 0L) recomputes
    // ------------------------------------------------------------------

    @Test
    fun first_foreground_after_install_recomputes() = runTest {
        // DataStore default is 0L; nowMs - 0L is always > 5 minutes.
        // Do not touch appPrefs to keep the default in play.
        val feed = feed()

        feed.refreshDueCountsIfStale()

        assertEquals(1, listRepo.recomputeDueCountForActiveCalls.size)
        assertEquals(T0.toEpochMilli(), appPrefs.lastDueCountRecomputeAt.first())
    }

    // ------------------------------------------------------------------
    // Idempotence — calling twice in rapid succession is one recompute pass
    // ------------------------------------------------------------------

    @Test
    fun second_call_within_ttl_is_no_op() = runTest {
        val feed = feed()

        // First call: 0L last → past TTL → dispatches.
        feed.refreshDueCountsIfStale()
        assertEquals(1, listRepo.recomputeDueCountForActiveCalls.size)

        // Second call ~immediately: nowMs - lastMs ≪ 5 min → no-op.
        feed.refreshDueCountsIfStale()
        assertEquals(1, listRepo.recomputeDueCountForActiveCalls.size)
    }
}
