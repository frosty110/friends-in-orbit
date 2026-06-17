package app.orbit.domain.rule

import app.orbit.domain.JsonProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import org.junit.Test

class RuleParamsSerializationTest {

    private val json = JsonProvider.json

    @Test
    fun `keepInTouch roundtrips via kotlinx-serialization`() {
        val original: RuleParams = RuleParams.KeepInTouch(
            cooldownMinHours = 48,
            cooldownMaxHours = 336,
            escalationFactor = 1.5,
            skipPenaltyHours = 24,
            shortCallThresholdSeconds = 60,
            shortCallResetPct = 25,
            incomingCallResetPct = 50,
        )
        val encoded = json.encodeToString(RuleParams.serializer(), original)
        val decoded = json.decodeFromString(RuleParams.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `lateNight roundtrips via kotlinx-serialization`() {
        val original: RuleParams = RuleParams.LateNight()
        val encoded = json.encodeToString(RuleParams.serializer(), original)
        val decoded = json.decodeFromString(RuleParams.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `energize roundtrips via kotlinx-serialization`() {
        val original: RuleParams = RuleParams.Energize()
        val encoded = json.encodeToString(RuleParams.serializer(), original)
        val decoded = json.decodeFromString(RuleParams.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `encoded keepInTouch carries type discriminator`() {
        val encoded = json.encodeToString(
            RuleParams.serializer(),
            RuleParams.KeepInTouch(),
        )
        assertTrue(
            encoded.contains("\"type\":\"keepInTouch\""),
            "expected type=keepInTouch discriminator in $encoded",
        )
    }

    @Test
    fun `unknown discriminator throws SerializationException`() {
        val bogus = """{"type":"bogusRuleType","cooldownMinHours":99}"""
        assertFailsWith<SerializationException> {
            json.decodeFromString(RuleParams.serializer(), bogus)
        }
    }
}
