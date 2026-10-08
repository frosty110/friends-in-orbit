package app.orbit.notify

import app.orbit.domain.JsonProvider
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies NOTIF-10: NudgeSchedule JSON round-trip and DEFAULT serialization contract.
 */
class NudgeScheduleTest {

    @Test
    fun default_hasAllSevenDaysAndSingleTenOClockSlot() {
        assertEquals(7, NudgeSchedule.DEFAULT.days.size, "DEFAULT should have all 7 DayOfWeek values")
        assertTrue(NudgeSchedule.DEFAULT.days.containsAll(DayOfWeek.values().toList()))
        assertEquals(1, NudgeSchedule.DEFAULT.times.size, "DEFAULT should have exactly one time slot")
        assertEquals(LocalTime.of(10, 0), NudgeSchedule.DEFAULT.times.first())
    }

    @Test
    fun default_jsonRoundTrip() {
        val encoded = JsonProvider.json.encodeToString(NudgeSchedule.DEFAULT)
        val decoded = JsonProvider.json.decodeFromString<NudgeSchedule>(encoded)
        assertEquals(NudgeSchedule.DEFAULT, decoded)
    }

    @Test
    fun default_serializedJsonMatchesConstant() {
        // Pitfall-3 guard — the migration literal must match the serializer output.
        val encoded = JsonProvider.json.encodeToString(NudgeSchedule.DEFAULT)
        assertEquals(
            NudgeSchedule.DEFAULT_JSON,
            encoded,
            "Migration DEFAULT_JSON must match JsonProvider.json.encodeToString(NudgeSchedule.DEFAULT)",
        )
    }

    @Test
    fun fromStoredJson_readsAScheduleAndFallsBackToTheDefaultTheChainUses() {
        // One reading for the nudge chain and for MIGRATION_13_14 (LIST-25):
        // the migration must fold a window into the schedule that really ran.
        val weekdaysAtSix = NudgeSchedule(
            days = setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
            ),
            times = listOf(LocalTime.of(18, 0))
        )
        assertEquals(
            weekdaysAtSix,
            NudgeSchedule.fromStoredJson(JsonProvider.json.encodeToString(weekdaysAtSix))
        )
        listOf(null, "", "   ", "{not json").forEach { stored ->
            assertEquals(
                NudgeSchedule.DEFAULT,
                NudgeSchedule.fromStoredJson(stored),
                "stored=$stored"
            )
        }
    }
}
