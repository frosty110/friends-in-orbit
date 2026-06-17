package app.orbit.ui.screens.lists

import app.cash.turbine.test
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.smart.SmartListEngine
import app.orbit.domain.smart.SmartListRule
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * SMART-06 integration test.
 *
 * Proves the rule-param-edit propagation contract at the engine boundary:
 * issuing a [SmartListEngine.membership] call with a rule that has different
 * params returns a different membership Flow whose first emission reflects
 * the new selection. The VM-level wire (flatMapLatest on the entity Flow in
 * [ListConfigViewModel.smartProjection]) is the natural counterpart — when a
 * rule-param edit lands via [ListConfigViewModel.setSmartRuleJson], the
 * `observeById` Flow re-emits the entity, `flatMapLatest` cancels the
 * previous engine subscription, and a fresh `engine.membership(newRule)`
 * starts.
 *
 * Pattern: real [SmartListEngine] over fake repositories, no mocking — same
 * shape as `SmartListEngineTest`. Turbine .test {} on the membership Flow.
 */
class SmartRuleEditIntegrationTest {

    @Test
    fun `param_change_propagates`() = runTest {
        val now = TestClock().now()
        // 10 contacts whose firstSeenByAppAt spans the last 10 days, one per
        // day starting from yesterday and going backward. With
        // RecentlyAddedNotCalled(daysWindow = N), the predicate keeps
        // contacts whose firstSeenByAppAt is within N days of now AND who
        // have zero call events.
        val contacts = (1..10).map { i ->
            contactFixture(
                id = i.toLong(),
                firstSeenByAppAt = now.minus(Duration.ofDays(i.toLong())),
            )
        }
        val contactRepo = FakeContactRepository(contacts)
        val callEventRepo = FakeCallEventRepository()
        val clock = TestClock(initial = now)
        val engine = SmartListEngine(contactRepo, callEventRepo, clock)

        // 5-day window — keeps contacts seen 1..5 days ago (5 contacts).
        engine.membership(SmartListRule.RecentlyAddedNotCalled(daysWindow = 5))
            .test(timeout = 2.seconds) {
                val first = awaitItem()
                assertEquals(5, first.size, "5-day window keeps 5 contacts")
                cancelAndIgnoreRemainingEvents()
            }

        // 8-day window — keeps contacts seen 1..8 days ago (8 contacts).
        // Proves rule param edits propagate through the engine: the same
        // underlying contact + event Flows produce a different membership
        // emission when the rule param changes. SMART-06.
        engine.membership(SmartListRule.RecentlyAddedNotCalled(daysWindow = 8))
            .test(timeout = 2.seconds) {
                val second = awaitItem()
                assertEquals(8, second.size, "8-day window keeps 8 contacts")
                cancelAndIgnoreRemainingEvents()
            }
    }
}
