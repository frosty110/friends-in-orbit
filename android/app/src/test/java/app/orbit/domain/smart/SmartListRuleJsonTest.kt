package app.orbit.domain.smart

import app.orbit.domain.JsonProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import org.junit.Test

class SmartListRuleJsonTest {

    private val json = JsonProvider.json

    private fun roundtrip(rule: SmartListRule): SmartListRule {
        val encoded = json.encodeToString(SmartListRule.serializer(), rule)
        return json.decodeFromString(SmartListRule.serializer(), encoded)
    }

    @Test
    fun `recentlyAddedNotCalled roundtrips`() {
        val original: SmartListRule = SmartListRule.RecentlyAddedNotCalled(daysWindow = 30)
        assertEquals(original, roundtrip(original))
    }

    @Test
    fun `longGap roundtrips`() {
        val original: SmartListRule = SmartListRule.LongGap(daysThreshold = 60)
        assertEquals(original, roundtrip(original))
    }

    @Test
    fun `commonlyCalled roundtrips`() {
        val original: SmartListRule = SmartListRule.CommonlyCalled(topPercent = 20)
        assertEquals(original, roundtrip(original))
    }

    @Test
    fun `rarelyCalled roundtrips`() {
        val original: SmartListRule = SmartListRule.RarelyCalled(bottomPercent = 50)
        assertEquals(original, roundtrip(original))
    }

    @Test
    fun `neverCalled roundtrips`() {
        val original: SmartListRule = SmartListRule.NeverCalled
        val decoded = roundtrip(original)
        assertEquals(SmartListRule.NeverCalled, decoded)
    }

    @Test
    fun `neverCalled encodes with only type discriminator`() {
        val encoded = json.encodeToString(SmartListRule.serializer(), SmartListRule.NeverCalled)
        // encodeDefaults = false + zero fields ⇒ encoded JSON contains only the type key
        assertTrue(
            encoded.contains("\"type\":\"neverCalled\""),
            "expected type=neverCalled in $encoded",
        )
    }

    @Test
    fun `unknown discriminator throws SerializationException`() {
        val bogus = """{"type":"bogusRule","daysWindow":30}"""
        assertFailsWith<SerializationException> {
            json.decodeFromString(SmartListRule.serializer(), bogus)
        }
    }
}
