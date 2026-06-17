package app.orbit.data.serialization

import app.orbit.domain.JsonProvider
import app.orbit.domain.rule.RuleParams
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers CONTACT-03 RuleParams JSON round-trip + ignoreUnknownKeys
 * forward-compat. Both tests run against the existing `JsonProvider.json`
 * configuration and the `RuleParams` sealed class.
 *
 * This test guards a contract that already exists on HEAD.
 * `ContactRepository.setRuleOverrideJson` writes the same JSON shape exercised
 * here, but the serialization round-trip itself is testable without that
 * wiring. The test means a regression in JsonProvider config (e.g.,
 * `ignoreUnknownKeys = false`) gets caught immediately, not when the column is
 * first read from a stored row.
 */
class RuleOverrideRoundTripTest {

    @Test
    fun `JsonProvider json round-trips RuleParams KeepInTouch cleanly`() {
        val original: RuleParams = RuleParams.KeepInTouch(
            cooldownMinHours = 60,
            cooldownMaxHours = 400,
            escalationFactor = 1.75,
            skipPenaltyHours = 36,
            shortCallThresholdSeconds = 90,
            shortCallResetPct = 30,
            incomingCallResetPct = 55,
        )
        val json = JsonProvider.json.encodeToString(original)
        val decoded = JsonProvider.json.decodeFromString<RuleParams>(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `JsonProvider json ignoreUnknownKeys decodes future shape with extra fields`() {
        // Simulates a forward-compat scenario: a future build ships a new field
        // on KeepInTouch (here, "futureFieldFromVNext"). Stored rows from that
        // future schema must remain decodable by today's binary so a downgrade
        // path (or a stale cache hit) doesn't crash the app.
        val futureJson = """
            {
              "type": "keepInTouch",
              "cooldownMinHours": 48,
              "cooldownMaxHours": 336,
              "futureFieldFromVNext": "ignored-by-decoder"
            }
        """.trimIndent()
        val decoded = JsonProvider.json.decodeFromString<RuleParams>(futureJson)
        val keep = decoded as RuleParams.KeepInTouch
        assertEquals(48, keep.cooldownMinHours)
        assertEquals(336, keep.cooldownMaxHours)
    }

    /**
     * Malformed JSON in `Contact.ruleOverrideJson` MUST raise a
     * [SerializationException] so [ContactDetailViewModel.deriveOverrideDisplay]
     * can catch it and flip `currentParams` to null + `currentTemplateName`
     * to "Custom schedule (recovering)". The VM-side test
     * `corrupted ruleOverrideJson recovers via try-catch and flips to
     * recovering copy` exercises the VM-side catch; this test pins the
     * library-side throw contract.
     */
    @Test
    fun `decodeFromString raises SerializationException on malformed json`() {
        assertFailsWith<SerializationException> {
            JsonProvider.json.decodeFromString<RuleParams>("not-valid-json")
        }
    }
}
