package app.orbit.data.feed

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.orbit.R
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.FakeListRepository
import app.orbit.domain.FakeRuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.TestClock
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.membershipFixture
import app.orbit.domain.ruleTemplateFixture
import app.orbit.domain.usecase.SurfaceNextUseCase
import app.orbit.testutil.newPrefs
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HOME-8, `HomeFeed`'s rhythm projection, and HOME-3's "Next up".
 *
 * The strip's bars are now tappable, so each [app.orbit.ui.screens.home.RhythmCall]
 * has to carry who the call was with and which way it went, not just a duration.
 * These tests pin that hydration: names resolved from the list's members,
 * direction carried through from the call event, the 3-minute qualifying floor
 * and the trailing-7-day window still applied, and the "Someone" fallback for a
 * contact who has since left the list. The "Next up" case pins what the feed
 * hands the ViewModel for the card's person: name, number and the latest call.
 *
 * Each test owns its DataStore (`tmp.newPrefs`), see testutil/TestDataStore.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HomeFeedRhythmTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    // Midday UTC so the trailing-7-day local-date bucketing is unambiguous
    // regardless of the JVM's default zone.
    private val now: Instant = Instant.parse("2026-01-08T12:00:00Z")
    private val clock = TestClock(now)

    private fun callEvent(
        id: Long,
        contactId: Long,
        daysAgo: Long,
        seconds: Int,
        direction: CallDirection,
        // Shifts the event earlier within its own day, so same-day events can be
        // ordered deterministically without crossing a date boundary.
        minutesEarlier: Long = 0L,
    ) = CallEventEntity(
        id = id,
        contactId = contactId,
        occurredAt = now.minusSeconds(daysAgo * 86_400L + minutesEarlier * 60L),
        direction = direction,
        durationSeconds = seconds,
        source = CallSource.CALL_LOG,
    )

    private fun feed(
        contacts: List<ContactEntity>,
        events: List<CallEventEntity>,
        // Needed only for "Next up": SurfaceNextUseCase surfaces a due member
        // of a list that has a rule template. The rhythm tests leave both
        // empty, so their head of queue is nobody.
        memberships: List<ListMembershipEntity> = emptyList(),
        templates: List<RuleTemplateEntity> = emptyList(),
    ): HomeFeed {
        val listRepo = FakeListRepository(
            initialLists = listOf(listFixture(id = 1L)),
            initialMemberships = memberships,
        )
        return HomeFeed(
            listRepo = listRepo,
            clock = clock,
            appPrefs = tmp.newPrefs(storeScope),
            surfaceNext = SurfaceNextUseCase(
                contactRepo = FakeContactRepository(contacts),
                listRepo = listRepo,
                callEventRepo = FakeCallEventRepository(events),
                ruleTemplateRepo = FakeRuleTemplateRepository(templates),
                clock = clock,
                json = JsonProvider.json,
            ),
            callEventRepo = FakeCallEventRepository(events),
            contactRepo = FakeContactRepository(contacts),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
    }

    private suspend fun rhythmOf(feed: HomeFeed) =
        feed.enrichment.first { it.containsKey(1L) }.getValue(1L).rhythm

    @Test
    fun `bars carry the contact name, direction, and formatted labels`() = runTest {
        val rhythm = rhythmOf(
            feed(
                contacts = listOf(contactFixture(id = 7L, displayName = "Kai Mensah")),
                events = listOf(
                    callEvent(1L, 7L, daysAgo = 0, seconds = 14 * 60, CallDirection.OUTGOING),
                ),
            ),
        )

        // index 6 = today
        val today = rhythm[6].calls
        assertEquals(1, today.size)
        val bar = today.single()
        assertEquals(7L, bar.contactId)
        assertEquals(1L, bar.callEventId)
        assertEquals("Kai Mensah", bar.contactName)
        assertEquals(CallDirection.OUTGOING, bar.direction)
        assertEquals("14 min", bar.durationLabel.asString(ApplicationProvider.getApplicationContext<Application>()))
        // Wall-clock formatting is zone-dependent; assert the shape, not the hour.
        assertTrue(bar.timeLabel.endsWith("am") || bar.timeLabel.endsWith("pm"), bar.timeLabel)
        assertNull(bar.photoUri)
    }

    @Test
    fun `incoming and outgoing calls both survive into the same day`() = runTest {
        val rhythm = rhythmOf(
            feed(
                contacts = listOf(
                    contactFixture(id = 1L, displayName = "Kai"),
                    contactFixture(id = 2L, displayName = "Mara"),
                ),
                events = listOf(
                    // Seeded newest-first to prove the projection sorts rather
                    // than inheriting the repository's order.
                    callEvent(2L, 2L, daysAgo = 2, seconds = 20 * 60, CallDirection.INCOMING),
                    callEvent(
                        1L, 1L, daysAgo = 2, seconds = 10 * 60, CallDirection.OUTGOING,
                        minutesEarlier = 90L,
                    ),
                ),
            ),
        )

        val day = rhythm[4].calls // two days ago
        assertEquals(2, day.size)
        assertEquals(
            listOf(CallDirection.OUTGOING, CallDirection.INCOMING),
            day.map { it.direction },
        )
        // Oldest-first within the day, so the stacked bars read top-down in the
        // same order the day sheet lists them.
        assertEquals(listOf(1L, 2L), day.map { it.callEventId })
        assertEquals(listOf("Kai", "Mara"), day.map { it.contactName })
    }

    @Test
    fun `a contact no longer on the list still renders its call as Someone`() = runTest {
        val rhythm = rhythmOf(
            feed(
                // Contact 9 called, but is not among the list's current members.
                contacts = emptyList(),
                events = listOf(
                    callEvent(1L, 9L, daysAgo = 1, seconds = 5 * 60, CallDirection.INCOMING),
                ),
            ),
        )

        val yesterday = rhythm[5].calls
        assertEquals(1, yesterday.size)
        // No name rides on the bar; the day sheet says "Someone" in its place
        // (strings_home.xml).
        assertNull(yesterday.single().contactName)
        assertEquals(
            "Someone",
            ApplicationProvider.getApplicationContext<Application>().getString(R.string.home_rhythm_someone),
        )
        assertEquals(9L, yesterday.single().contactId)
    }

    @Test
    fun `sub-three-minute calls stay out of the strip`() = runTest {
        val rhythm = rhythmOf(
            feed(
                contacts = listOf(contactFixture(id = 1L)),
                events = listOf(
                    callEvent(1L, 1L, daysAgo = 0, seconds = 179, CallDirection.OUTGOING),
                    // A manual "Logged" connection is written with 0 seconds and
                    // must never reach the strip — its direction is a stand-in,
                    // not a carrier observation.
                    callEvent(2L, 1L, daysAgo = 0, seconds = 0, CallDirection.OUTGOING),
                    callEvent(3L, 1L, daysAgo = 0, seconds = 180, CallDirection.INCOMING),
                ),
            ),
        )

        val today = rhythm[6].calls
        assertEquals(1, today.size)
        assertEquals(3L, today.single().callEventId)
    }

    @Test
    fun `calls older than the window are dropped and the strip stays seven days`() = runTest {
        val rhythm = rhythmOf(
            feed(
                contacts = listOf(contactFixture(id = 1L)),
                events = listOf(
                    callEvent(1L, 1L, daysAgo = 30, seconds = 30 * 60, CallDirection.OUTGOING),
                ),
            ),
        )

        assertEquals(7, rhythm.size)
        assertTrue(rhythm.all { it.calls.isEmpty() })
    }

    // HOME-3 / HOME-9: the card's person is the list's head of queue (the same
    // SurfaceNextUseCase answer Card view shows first), with the number the
    // call button dials and the latest call, from which the VM words the why
    // line.
    @Test
    fun `next up is the list's head of queue with their number and latest call`() = runTest {
        val kai = contactFixture(id = 7L, displayName = "Kai Mensah", phoneNumber = "+1 555 0100")
        val latest = callEvent(2L, 7L, daysAgo = 1, seconds = 10 * 60, CallDirection.INCOMING)
        val feed = feed(
            contacts = listOf(kai),
            events = listOf(
                // Seeded older-first to prove "latest" is by time, not by order.
                callEvent(1L, 7L, daysAgo = 3, seconds = 20 * 60, CallDirection.OUTGOING),
                latest,
                // Someone else's call must not become Kai's last call.
                callEvent(3L, 8L, daysAgo = 0, seconds = 5 * 60, CallDirection.OUTGOING),
            ),
            memberships = listOf(membershipFixture(contactId = 7L, listId = 1L, nextDueAt = now.minusSeconds(3_600L))),
            templates = listOf(ruleTemplateFixture(id = 1L)),
        )

        val nextUp = assertNotNull(feed.enrichment.first { it.containsKey(1L) }.getValue(1L).nextUp)
        assertEquals(7L, nextUp.contactId)
        assertEquals("Kai Mensah", nextUp.name)
        assertEquals("+1 555 0100", nextUp.phone)
        assertEquals(latest.occurredAt, nextUp.lastCalledAt)
        assertNull(nextUp.photoUri)
    }

    @Test
    fun `a list with nobody surfaceable has no next up`() = runTest {
        val feed = feed(contacts = emptyList(), events = emptyList())
        assertNull(feed.enrichment.first { it.containsKey(1L) }.getValue(1L).nextUp)
    }

    // HOME-12: the strip buckets by clock.now() when a Room flow re-emits, so
    // with nothing changing overnight the last column kept meaning yesterday.
    // Home reports each resume's date; the same date twice is a no-op, a new
    // one re-buckets.
    @Test
    fun `the strip re-buckets when told the day has changed`() = runTest {
        val feed = feed(
            contacts = listOf(contactFixture(id = 1L)),
            events = listOf(callEvent(1L, 1L, daysAgo = 0, seconds = 10 * 60, CallDirection.OUTGOING)),
        )
        val zone = ZoneId.systemDefault()
        assertEquals(listOf(1L), rhythmOf(feed)[6].calls.map { it.callEventId })

        // The same day again: nothing to do.
        feed.noteToday(now.atZone(zone).toLocalDate())
        assertEquals(listOf(1L), rhythmOf(feed)[6].calls.map { it.callEventId })

        // Midnight passed in the background; Home resumes on the next day.
        clock.advance(Duration.ofDays(1))
        feed.noteToday(clock.now().atZone(zone).toLocalDate())
        val rhythm = rhythmOf(feed)
        assertTrue(rhythm[6].calls.isEmpty(), "today has no calls yet")
        assertEquals(listOf(1L), rhythm[5].calls.map { it.callEventId }, "yesterday's call moved one column left")
    }
}
