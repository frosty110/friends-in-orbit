package app.orbit.domain.usecase

import app.cash.turbine.test
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * SurfaceNextUseCase contract amendment retest.
 *
 * The original surface excluded only `isIgnored == true`. The amendment
 * (CONTACT-06) introduces `isArchived` as a separate hide mechanism — archived
 * contacts must NOT surface, but their `ListMembership` rows are preserved and
 * their call history stays in the call log.
 *
 * Mirrors the existing IGNORE-05 ignored-exclusion test shape in
 * [SurfaceNextUseCaseTest] (which is unchanged — the amendment is additive).
 *
 * Tests:
 *   1. Archived contact does NOT surface.
 *   2. Toggling archived true → false re-surfaces the contact (membership-
 *      preservation invariant — same shape as IGNORE-07).
 */
class SurfaceNextUseCaseArchivedTest {

    private val T0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun fixture(
        contacts: List<ContactEntity>,
        lists: List<ListEntity>,
        memberships: List<ListMembershipEntity>,
        callEvents: List<CallEventEntity> = emptyList(),
        templates: List<RuleTemplateEntity> = listOf(ruleTemplateFixture(id = 1L)),
        clockAt: Instant = T0,
    ): Triple<SurfaceNextUseCase, FakeContactRepository, FakeListRepository> {
        val contactRepo = FakeContactRepository(contacts)
        val listRepo = FakeListRepository(lists)
        val callEventRepo = FakeCallEventRepository(callEvents)
        val templateRepo = FakeRuleTemplateRepository(templates)
        listRepo.seedMemberships(memberships)
        val useCase = SurfaceNextUseCase(
            contactRepo = contactRepo,
            listRepo = listRepo,
            callEventRepo = callEventRepo,
            ruleTemplateRepo = templateRepo,
            clock = TestClock(clockAt),
            json = JsonProvider.json,
        )
        return Triple(useCase, contactRepo, listRepo)
    }

    @Test
    fun `archived contact is excluded from surfacing`() = runTest {
        val (useCase, _, _) = fixture(
            contacts = listOf(contactFixture(id = 1L, isArchived = true)),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            assertEquals(
                SurfaceResult.NoMembers,
                awaitItem(),
                "archived contact must NOT surface (CONTACT-06); list has zero visible members",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `non-archived contact still surfaces normally`() = runTest {
        val (useCase, contactRepo, _) = fixture(
            contacts = listOf(contactFixture(id = 1L, isArchived = false)),
            lists = listOf(listFixture(id = 1L, ruleTemplateId = 1L)),
            memberships = listOf(membershipFixture(contactId = 1L, listId = 1L, nextDueAt = null)),
        )
        useCase(listId = 1L).test(timeout = 2.seconds) {
            val first = awaitItem()
            assertTrue(first is SurfaceResult.Found, "non-archived contact surfaces; got $first")
            assertEquals(1L, first.contact.id)
            // Flip to archived → must drop on next emission (contract amendment).
            contactRepo.update { list -> list.map { it.copy(isArchived = true) } }
            assertEquals(
                SurfaceResult.NoMembers,
                awaitItem(),
                "newly-archived contact must NOT surface; now zero visible members",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
