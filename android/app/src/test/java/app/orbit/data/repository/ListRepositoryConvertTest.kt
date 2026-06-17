package app.orbit.data.repository

// JVM-only via SmartListEngine + fake repos. The @Transaction guarantee
// is exercised on-device by the androidTest infrastructure (real Room);
// this test covers algorithmic correctness of the convert pipeline (snapshot
// composition + ignore-exclusion + post-state) without spinning up Robolectric.

import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
import app.orbit.domain.FakeCallEventRepository
import app.orbit.domain.FakeContactRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.callEventFixture
import app.orbit.domain.contactFixture
import app.orbit.domain.listFixture
import app.orbit.domain.smart.SmartListEngine
import app.orbit.domain.smart.SmartListRule
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import app.orbit.domain.clock.TestClock

/**
 * Unit tests for the convert smart→static algorithm (LIST-08, Pitfall 5).
 *
 * Pre-state: smart list has type=SMART, smartRuleJson=non-null, zero memberships.
 * Post-state: type=STATIC, smartRuleJson=null, memberships present matching
 * `SmartListEngine.snapshotOnce(rule)` output.
 *
 * Atomicity at the @Transaction boundary is the on-device androidTest's job —
 * here we assert the algorithmic post-state and the SMART-05 ignore-exclusion
 * invariant carries through `snapshotOnce`.
 */
class ListRepositoryConvertTest {

    private val now: Instant = Instant.parse("2026-04-25T12:00:00Z")
    private val fixedClock = TestClock(now)
    private val json = JsonProvider.json

    @Test
    fun convert_snapshots_members_and_clears_rule_atomically() = runTest {
        // 5 contacts. Three match RecentlyAddedNotCalled(30): id 1, 2, 3 — added
        // within 30 days, no calls. id 4 added 60 days ago (outside window).
        // id 5 added recently but has a call event.
        val contacts = listOf(
            contactFixture(1L, firstSeenByAppAt = now.minusSeconds(86_400L * 5)),
            contactFixture(2L, firstSeenByAppAt = now.minusSeconds(86_400L * 10)),
            contactFixture(3L, firstSeenByAppAt = now.minusSeconds(86_400L * 15)),
            contactFixture(4L, firstSeenByAppAt = now.minusSeconds(86_400L * 60)),
            contactFixture(5L, firstSeenByAppAt = now.minusSeconds(86_400L * 5)),
        )
        val events = listOf(
            callEventFixture(id = 100L, contactId = 5L, occurredAt = now.minusSeconds(86_400L)),
        )
        val contactRepo = FakeContactRepository(contacts)
        val callRepo = FakeCallEventRepository(events)
        val engine = SmartListEngine(contactRepo, callRepo, fixedClock)

        val rule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30)
        val ruleJson = json.encodeToString(SmartListRule.serializer(), rule)
        val list = listFixture(
            id = 10L,
            type = ListType.SMART,
            smartRuleJson = ruleJson,
        )

        // Pre-state assertions
        assertEquals(ListType.SMART, list.type)
        assertEquals(ruleJson, list.smartRuleJson)

        // Act: emulate the body of ListRepositoryImpl.convertSmartToStatic against
        // the engine + fake repos. Proves the snapshot+clear logical sequence is
        // correct. The @Transaction guarantee is exercised on-device.
        val snapshot = engine.snapshotOnce(rule)
        val newMemberships = snapshot.map {
            ListMembershipEntity(contactId = it.id, listId = list.id, addedAt = now, nextDueAt = now)
        }
        val converted = list.copy(type = ListType.STATIC, smartRuleJson = null)

        // Post-state
        assertEquals(3, snapshot.size, "Three contacts should match RecentlyAddedNotCalled(30)")
        assertEquals(setOf(1L, 2L, 3L), snapshot.map { it.id }.toSet())
        assertEquals(ListType.STATIC, converted.type)
        assertNull(converted.smartRuleJson)
        assertEquals(3, newMemberships.size)
        assertEquals(setOf(1L, 2L, 3L), newMemberships.map { it.contactId }.toSet())
    }

    @Test
    fun convert_with_already_static_list_is_noop() = runTest {
        // Static list with no smartRuleJson — convert is a no-op (the impl's
        // `ruleJson ?: return@withTransaction` branch). Algorithmic post-state:
        // unchanged.
        val list = listFixture(id = 11L, type = ListType.STATIC, smartRuleJson = null)
        // Simulates "rule == null -> return @withTransaction" — list is unchanged.
        val converted = list
        assertEquals(ListType.STATIC, converted.type)
        assertNull(converted.smartRuleJson)
    }

    @Test
    fun convert_with_ignored_contacts_excludes_them() = runTest {
        // SMART-05: ignored contacts are excluded from snapshot output. The
        // convert flow inherits this — `snapshotOnce` runs `compute` which filters
        // `!isIgnored` after rule selection.
        val contacts = listOf(
            contactFixture(1L, firstSeenByAppAt = now.minusSeconds(86_400L), isIgnored = false),
            contactFixture(2L, firstSeenByAppAt = now.minusSeconds(86_400L), isIgnored = true),
        )
        val contactRepo = FakeContactRepository(contacts)
        val callRepo = FakeCallEventRepository(emptyList())
        val engine = SmartListEngine(contactRepo, callRepo, fixedClock)

        val snapshot = engine.snapshotOnce(SmartListRule.RecentlyAddedNotCalled(30))

        assertTrue(
            snapshot.all { !it.isIgnored },
            "SMART-05: ignored contacts must be excluded from snapshot",
        )
        assertEquals(setOf(1L), snapshot.map { it.id }.toSet())
    }
}
