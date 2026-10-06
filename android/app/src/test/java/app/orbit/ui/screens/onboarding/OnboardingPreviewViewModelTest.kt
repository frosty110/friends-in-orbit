package app.orbit.ui.screens.onboarding

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.data.repository.CallAgg
import app.orbit.data.repository.CallEventRepository
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.callEventFixture
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.testutil.MainDispatcherRule
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [OnboardingPreviewViewModel] (ONB-19).
 *
 * The VM ranks contacts by the locked recency × frequency formula:
 *
 *   score = count + max(0, 30 − daysSinceLastCall) / 30.0
 *
 * and emits [OnboardingPreviewUiState.Ready] with the top ≤10 candidates,
 * or an EMPTY candidate list when fewer than 3 contacts score > 0. A failed
 * read is [OnboardingPreviewUiState.Error], and `onRetry` re-subscribes.
 *
 * The row meta line is `UiText` built on `formatRelative` (the glossary's one
 * "time since" formatter), resolved here against Robolectric's resources.
 * Time comes from an injected [TestClock], so every fixture event sits at a
 * whole-day offset before a fixed `now` and the scoring is reproducible.
 *
 * Fixture pattern mirrors [OnboardingPermissionsViewModelTest] /
 * SettingsViewModelTest:
 *   - `@Config(application = Application::class)` bypasses `OrbitApp.onCreate`.
 *   - Hand-rolled fakes ([FakeContactRepository], [FakeCallEventRepository]);
 *     no mockk.
 *   - The `combine` upstream emits `Loading` (stateIn initialValue) first, so
 *     tests skip to the terminal Ready via `filterIsInstance<Ready>().first()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OnboardingPreviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // The VM's clock. Fixture events sit at whole-day offsets before it.
    private val clock = TestClock(Instant.parse("2026-10-06T12:00:00Z"))
    private val now: Instant get() = clock.now()

    // Copy is UiText (strings_onboarding.xml); resolve it against real resources.
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun daysAgo(days: Long): Instant = now.minus(days, ChronoUnit.DAYS)

    private fun buildVm(
        contactRepo: FakeContactRepository,
        callEventRepo: CallEventRepository,
    ): OnboardingPreviewViewModel =
        OnboardingPreviewViewModel(
            contactRepo = contactRepo,
            callEventRepo = callEventRepo,
            clock = clock,
        )

    /** The aggregates read fails on its first subscription, then passes. */
    private class FlakyAggregates(private val inner: CallEventRepository) : CallEventRepository by inner {
        var failuresLeft: Int = 1
        override fun observeAggregatesAll(): Flow<Map<Long, CallAgg>> = flow {
            if (failuresLeft > 0) {
                failuresLeft--
                throw IllegalStateException("disk")
            }
            emitAll(inner.observeAggregatesAll())
        }
    }

    private suspend fun StateFlow<OnboardingPreviewUiState>.awaitReady(): OnboardingPreviewUiState.Ready =
        (this as Flow<OnboardingPreviewUiState>)
            .filterIsInstance<OnboardingPreviewUiState.Ready>()
            .first()

    // ============================================================================
    // Test 1 — fewer than 3 scored candidates collapses to an empty list (the
    // screen routes to the manual first-list path in that case, ONB-19).
    // ============================================================================

    @Test
    fun `fewer than three scored candidates emits empty list`() = runTest {
        val contacts = FakeContactRepository(
            listOf(contactFixture(id = 1L), contactFixture(id = 2L)),
        )
        val events = FakeCallEventRepository(
            listOf(
                callEventFixture(id = 1L, contactId = 1L, occurredAt = daysAgo(1)),
                callEventFixture(id = 2L, contactId = 2L, occurredAt = daysAgo(1)),
            ),
        )
        val vm = buildVm(contacts, events)

        val ready = vm.uiState.awaitReady()
        assertTrue(ready.candidates.isEmpty(), "expected empty candidates, got ${ready.candidates}")
    }

    // ============================================================================
    // Test 2 — exactly 3 scored candidates surfaces all three (>= 3 gate met).
    // ============================================================================

    @Test
    fun `three scored candidates surface all three`() = runTest {
        val contacts = FakeContactRepository(
            (1L..3L).map { contactFixture(id = it) },
        )
        val events = FakeCallEventRepository(
            (1L..3L).map { callEventFixture(id = it, contactId = it, occurredAt = daysAgo(1)) },
        )
        val vm = buildVm(contacts, events)

        val ready = vm.uiState.awaitReady()
        assertEquals(3, ready.candidates.size)
        assertEquals(setOf(1L, 2L, 3L), ready.candidates.map { it.contactId }.toSet())
    }

    // ============================================================================
    // Test 3 — contacts with no recorded call (absent from the aggregate) and
    // contacts whose only event has a null lastAt score 0 and are dropped. Here
    // 4 contacts exist but only 3 have calls, so the gate is met by exactly the
    // called set.
    // ============================================================================

    @Test
    fun `never-called contacts are dropped from candidates`() = runTest {
        val contacts = FakeContactRepository(
            (1L..4L).map { contactFixture(id = it) },
        )
        // Contact 4 has no call event → absent from observeAggregatesAll → dropped.
        val events = FakeCallEventRepository(
            (1L..3L).map { callEventFixture(id = it, contactId = it, occurredAt = daysAgo(2)) },
        )
        val vm = buildVm(contacts, events)

        val ready = vm.uiState.awaitReady()
        assertEquals(3, ready.candidates.size)
        assertTrue(
            ready.candidates.none { it.contactId == 4L },
            "never-called contact 4 must not appear",
        )
    }

    // ============================================================================
    // Test 4 — ignored and archived contacts are filtered out before scoring.
    // Three "live" contacts remain so the >= 3 gate is met by the live set only.
    // ============================================================================

    @Test
    fun `ignored and archived contacts are excluded`() = runTest {
        val contacts = FakeContactRepository(
            listOf(
                contactFixture(id = 1L),
                contactFixture(id = 2L),
                contactFixture(id = 3L),
                contactFixture(id = 4L, isIgnored = true),
                contactFixture(id = 5L, isArchived = true),
            ),
        )
        val events = FakeCallEventRepository(
            (1L..5L).map { callEventFixture(id = it, contactId = it, occurredAt = daysAgo(1)) },
        )
        val vm = buildVm(contacts, events)

        val ready = vm.uiState.awaitReady()
        assertEquals(setOf(1L, 2L, 3L), ready.candidates.map { it.contactId }.toSet())
    }

    // ============================================================================
    // Test 5 — ranking order: score DESC. Higher call count outranks recency;
    // contact 1 (3 calls) > contact 2 (2 calls) > contact 3 (1 call), all called
    // recently so the recency arm is roughly equal and the count term dominates.
    // ============================================================================

    @Test
    fun `candidates are ranked by score descending`() = runTest {
        val contacts = FakeContactRepository(
            (1L..3L).map { contactFixture(id = it) },
        )
        val events = FakeCallEventRepository(
            buildList {
                // Contact 1 — 3 calls.
                add(callEventFixture(id = 1L, contactId = 1L, occurredAt = daysAgo(1)))
                add(callEventFixture(id = 2L, contactId = 1L, occurredAt = daysAgo(2)))
                add(callEventFixture(id = 3L, contactId = 1L, occurredAt = daysAgo(3)))
                // Contact 2 — 2 calls.
                add(callEventFixture(id = 4L, contactId = 2L, occurredAt = daysAgo(1)))
                add(callEventFixture(id = 5L, contactId = 2L, occurredAt = daysAgo(2)))
                // Contact 3 — 1 call.
                add(callEventFixture(id = 6L, contactId = 3L, occurredAt = daysAgo(1)))
            },
        )
        val vm = buildVm(contacts, events)

        val ready = vm.uiState.awaitReady()
        assertEquals(listOf(1L, 2L, 3L), ready.candidates.map { it.contactId })
    }

    // ============================================================================
    // Test 6 — displayName ASC tiebreak when scores are equal. Three contacts,
    // each with a single call on the same day → identical score; the names
    // "Anna" / "Bob" / "Cara" decide the order.
    // ============================================================================

    @Test
    fun `equal scores break ties by display name ascending`() = runTest {
        val contacts = FakeContactRepository(
            listOf(
                contactFixture(id = 1L, displayName = "Cara"),
                contactFixture(id = 2L, displayName = "Anna"),
                contactFixture(id = 3L, displayName = "Bob"),
            ),
        )
        val events = FakeCallEventRepository(
            (1L..3L).map { callEventFixture(id = it, contactId = it, occurredAt = daysAgo(1)) },
        )
        val vm = buildVm(contacts, events)

        val ready = vm.uiState.awaitReady()
        // Anna (2) < Bob (3) < Cara (1) by name.
        assertEquals(listOf(2L, 3L, 1L), ready.candidates.map { it.contactId })
    }

    // ============================================================================
    // Test 7 — at most 10 candidates are surfaced even when more qualify. Seed
    // 12 contacts each with a distinct call count so the order is total and the
    // take(10) boundary is exercised.
    // ============================================================================

    @Test
    fun `candidate list is capped at ten`() = runTest {
        val ids = (1L..12L).toList()
        val contacts = FakeContactRepository(ids.map { contactFixture(id = it) })
        // Contact i gets i calls so score is strictly increasing with id; the
        // top 10 by score are ids 12..3.
        val events = FakeCallEventRepository(
            buildList {
                var eventId = 1L
                ids.forEach { contactId ->
                    repeat(contactId.toInt()) {
                        add(
                            callEventFixture(
                                id = eventId++,
                                contactId = contactId,
                                occurredAt = daysAgo(1),
                            ),
                        )
                    }
                }
            },
        )
        val vm = buildVm(contacts, events)

        val ready = vm.uiState.awaitReady()
        assertEquals(10, ready.candidates.size)
        // Lowest-scoring contacts (ids 1 and 2) fall outside the top 10.
        assertTrue(ready.candidates.none { it.contactId == 1L || it.contactId == 2L })
        // Highest-scoring contact (id 12) leads.
        assertEquals(12L, ready.candidates.first().contactId)
    }

    // ============================================================================
    // Test 8 — the meta line is the VM's pre-formatted relative-time string
    // ("Called …") rather than a raw timestamp, worded by formatRelative like
    // every other "time since" in the app (voice.md glossary).
    // ============================================================================

    @Test
    fun `candidate meta line carries the Called prefix`() = runTest {
        val contacts = FakeContactRepository(
            (1L..3L).map { contactFixture(id = it) },
        )
        val events = FakeCallEventRepository(
            (1L..3L).map { callEventFixture(id = it, contactId = it, occurredAt = daysAgo(4)) },
        )
        val vm = buildVm(contacts, events)

        val ready = vm.uiState.awaitReady()
        assertTrue(
            ready.candidates.all { it.lastCallRelative.asString(context).startsWith("Called ") },
            "every meta line must lead with the 'Called ' voice prefix",
        )
    }

    @Test
    fun `an older call reads in weeks, like every other screen`() = runTest {
        // DateUtils (used here before) switched to a date past a week
        // ("Called Sep 12"); formatRelative keeps the span in words.
        val contacts = FakeContactRepository(
            (1L..3L).map { contactFixture(id = it) },
        )
        val events = FakeCallEventRepository(
            (1L..3L).map { callEventFixture(id = it, contactId = it, occurredAt = daysAgo(21)) },
        )
        val vm = buildVm(contacts, events)

        val ready = vm.uiState.awaitReady()
        assertEquals(
            listOf("Called 3 weeks ago"),
            ready.candidates.map { it.lastCallRelative.asString(context) }.distinct(),
        )
    }

    // ============================================================================
    // Test 9 — default list name surfaced on Ready is the locked "In touch"
    // copy (OnboardingPreviewUiState.Ready.defaultName).
    // ============================================================================

    @Test
    fun `ready exposes the default first-list name`() = runTest {
        val contacts = FakeContactRepository(
            (1L..3L).map { contactFixture(id = it) },
        )
        val events = FakeCallEventRepository(
            (1L..3L).map { callEventFixture(id = it, contactId = it, occurredAt = daysAgo(1)) },
        )
        val vm = buildVm(contacts, events)

        val ready = vm.uiState.awaitReady()
        assertEquals("In touch", ready.defaultName.asString(context))
    }

    // ============================================================================
    // Test 10: a failing read is Error, not a crash, and Try again
    // re-subscribes so the next read can succeed. Fails without the
    // `retryCount.flatMapLatest { ... .catch { } }` shape in the VM: a bare
    // `catch` terminates the pipeline and a retry could never recover.
    // ============================================================================

    @Test
    fun `a failing source shows Error, and Try again recovers`() = runTest {
        val contacts = FakeContactRepository(
            (1L..3L).map { contactFixture(id = it) },
        )
        val events = FlakyAggregates(
            FakeCallEventRepository(
                (1L..3L).map { callEventFixture(id = it, contactId = it, occurredAt = daysAgo(1)) },
            ),
        )
        val vm = buildVm(contacts, events)

        val first = (vm.uiState as Flow<OnboardingPreviewUiState>)
            .filterIsInstance<OnboardingPreviewUiState.Error>()
            .first()
        assertEquals(OnboardingPreviewUiState.Error, first)

        vm.onRetry()

        val ready = vm.uiState.awaitReady()
        assertEquals(3, ready.candidates.size)
        assertEquals(0, events.failuresLeft)
    }
}
