package app.orbit.domain.rule

import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.domain.JsonProvider
import app.orbit.domain.listFixture
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class OverrideResolverTest {

    private val json = JsonProvider.json

    // Minimal ContactEntity + RuleTemplateEntity fixtures for test isolation.
    // The factory helpers only set the fields resolveParamsFor actually reads —
    // other fields use arbitrary defaults.
    private fun contact(ruleOverrideJson: String? = null): ContactEntity =
        ContactEntity(
            id = 1L,
            phoneContactId = null,
            phoneNumber = "+15555550100",
            normalizedPhone = "+15555550100",
            displayName = "Test",
            photoUri = null,
            firstSeenByAppAt = Instant.parse("2026-01-01T00:00:00Z"),
            isIgnored = false,
            isOrphaned = false,
            pausedUntil = null,
            ruleOverrideJson = ruleOverrideJson,
        )

    private fun template(
        kind: RuleKind = RuleKind.KEEP_IN_TOUCH,
        params: RuleParams = RuleParams.KeepInTouch(),
    ): RuleTemplateEntity =
        RuleTemplateEntity(
            id = 1L,
            name = "Keep in touch",
            kind = kind,
            paramsJson = json.encodeToString(RuleParams.serializer(), params),
        )

    /** Default fixture list with no per-list override (column = null). */
    private fun list(ruleParamsOverrideJson: String? = null): ListEntity =
        listFixture(id = 10L, ruleTemplateId = 1L, ruleParamsOverrideJson = ruleParamsOverrideJson)

    @Test
    fun `no override returns list template params`() {
        val result = resolveParamsFor(
            contact = contact(ruleOverrideJson = null),
            list = list(),
            listTemplate = template(params = RuleParams.KeepInTouch(cooldownMinHours = 72)),
            json = json,
        )
        assertTrue(result is RuleParams.KeepInTouch, "expected KeepInTouch, got $result")
        assertEquals(72, (result as RuleParams.KeepInTouch).cooldownMinHours)
    }

    @Test
    fun `override returns decoded override params`() {
        val overrideJson = json.encodeToString(
            RuleParams.serializer(),
            RuleParams.KeepInTouch(cooldownMinHours = 999),
        )
        val result = resolveParamsFor(
            contact = contact(ruleOverrideJson = overrideJson),
            list = list(),
            listTemplate = template(params = RuleParams.KeepInTouch(cooldownMinHours = 48)),
            json = json,
        )
        assertTrue(result is RuleParams.KeepInTouch)
        assertEquals(999, (result as RuleParams.KeepInTouch).cooldownMinHours)
    }

    @Test
    fun `override with different subtype than template wins`() {
        val overrideJson = json.encodeToString(
            RuleParams.serializer(),
            RuleParams.LateNight(cooldownMinHours = 120),
        )
        val result = resolveParamsFor(
            contact = contact(ruleOverrideJson = overrideJson),
            list = list(),
            listTemplate = template(kind = RuleKind.KEEP_IN_TOUCH, params = RuleParams.KeepInTouch()),
            json = json,
        )
        assertTrue(result is RuleParams.LateNight, "override should override template subtype")
        assertEquals(120, (result as RuleParams.LateNight).cooldownMinHours)
    }

    // ─── LIST-04 — per-list override branch ─────────────────────────────────────────────

    @Test
    fun per_list_override_wins_when_no_per_contact_override() {
        val perListOverride = json.encodeToString(
            RuleParams.serializer(),
            RuleParams.KeepInTouch(cooldownMinHours = 720 /* 30d */),
        )
        val result = resolveParamsFor(
            contact = contact(ruleOverrideJson = null),
            list = list(ruleParamsOverrideJson = perListOverride),
            listTemplate = template(params = RuleParams.KeepInTouch(cooldownMinHours = 168 /* 7d */)),
            json = json,
        )
        assertTrue(result is RuleParams.KeepInTouch, "expected KeepInTouch, got $result")
        assertEquals(720, (result as RuleParams.KeepInTouch).cooldownMinHours)
    }

    @Test
    fun per_contact_override_wins_over_per_list_override() {
        val perListOverride = json.encodeToString(
            RuleParams.serializer(),
            RuleParams.KeepInTouch(cooldownMinHours = 720 /* 30d */),
        )
        val perContactOverride = json.encodeToString(
            RuleParams.serializer(),
            RuleParams.KeepInTouch(cooldownMinHours = 24 /* 1d */),
        )
        val result = resolveParamsFor(
            contact = contact(ruleOverrideJson = perContactOverride),
            list = list(ruleParamsOverrideJson = perListOverride),
            listTemplate = template(params = RuleParams.KeepInTouch(cooldownMinHours = 168 /* 7d */)),
            json = json,
        )
        assertTrue(result is RuleParams.KeepInTouch)
        // DOM-04 invariant: per-contact wins over per-list.
        assertEquals(24, (result as RuleParams.KeepInTouch).cooldownMinHours)
    }
}
