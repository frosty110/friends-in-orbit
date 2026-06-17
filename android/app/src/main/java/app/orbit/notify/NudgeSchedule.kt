package app.orbit.notify

import app.orbit.domain.JsonProvider
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// ─── Custom serializers for java.time types ──────────────────────────────────

/**
 * Serializes [DayOfWeek] as its `.name` string ("MONDAY".."SUNDAY").
 * kotlinx-serialization has no built-in java.time serializers; this provides a
 * deterministic, human-readable encoding that matches the migration DEFAULT_JSON.
 */
object DayOfWeekSerializer : KSerializer<DayOfWeek> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DayOfWeek", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DayOfWeek) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): DayOfWeek =
        DayOfWeek.valueOf(decoder.decodeString())
}

/**
 * Serializes [LocalTime] as "HH:mm" (e.g. "10:00").
 * The format is intentionally truncated to minutes — schedule precision is
 * minute-granular and the 4-char string matches the migration DEFAULT_JSON literal.
 */
object LocalTimeSerializer : KSerializer<LocalTime> {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm")

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) {
        encoder.encodeString(value.format(formatter))
    }

    override fun deserialize(decoder: Decoder): LocalTime =
        LocalTime.parse(decoder.decodeString(), formatter)
}

// ─── NudgeSchedule model ─────────────────────────────────────────────────────

/**
 * Per-list nudge schedule: the set of days and times at which a nudge fires.
 *
 * Stored as JSON in [app.orbit.data.entity.ListEntity.nudgeScheduleJson] (D-01,
 * NOTIF-10/11). Serialized via [JsonProvider.json] — `encodeDefaults = false`,
 * so fields equal to their default value are omitted. [DEFAULT_JSON] is the
 * canonical migration literal and MUST be kept in sync with the serializer
 * output (verified by [app.orbit.notify.NudgeScheduleTest.default_serializedJsonMatchesConstant]).
 *
 * Encoding contracts:
 *  - [days] elements encode as their [DayOfWeek.name] ("MONDAY".."SUNDAY").
 *  - [times] elements encode as "HH:mm" (e.g. "10:00").
 *
 * DST note (Pitfall 2): [nextSlot] always assembles candidate datetimes via
 * [ZonedDateTime.of] with a [LocalTime], letting java.time resolve the wall-clock
 * interpretation for the target zone. A 10:00 slot can never fall in the
 * 02:00–03:00 DST gap, so no special DST handling is required for this schedule.
 */
@Serializable
data class NudgeSchedule(
    val days: Set<@Serializable(with = DayOfWeekSerializer::class) DayOfWeek>,
    val times: List<@Serializable(with = LocalTimeSerializer::class) LocalTime>,
) {
    companion object {
        /** Default schedule: all 7 days at 10:00. Sealed by D-03 (default-ON). */
        val DEFAULT = NudgeSchedule(
            days = DayOfWeek.values().toSet(),
            times = listOf(LocalTime.of(10, 0)),
        )

        /**
         * Byte-exact JSON representation of [DEFAULT] as produced by
         * [JsonProvider.json].encodeToString([DEFAULT]).
         *
         * CRITICAL (RESEARCH Pitfall 3): [MIGRATION_11_12] in OrbitDatabase.kt
         * uses this constant verbatim. If the serializer output ever diverges from
         * this literal, workers will throw [kotlinx.serialization.SerializationException]
         * when decoding migration-seeded rows. The equality is asserted in
         * [app.orbit.notify.NudgeScheduleTest.default_serializedJsonMatchesConstant].
         */
        const val DEFAULT_JSON =
            """{"days":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"],"times":["10:00"]}"""
    }
}

// ─── nextSlot extension ──────────────────────────────────────────────────────

/**
 * Returns the next [ZonedDateTime] at which this schedule would fire, relative
 * to [now], or null if [days] or [times] is empty.
 *
 * Algorithm (RESEARCH Pattern 1):
 *  1. Collect the sorted times for today (if today is a scheduled day and any
 *     time is strictly after [now]).
 *  2. If none found today, advance one day at a time (up to 7 days), collecting
 *     the first slot of the first scheduled day reached.
 *
 * DST (Pitfall 2): candidate datetimes are assembled via
 * [java.time.ZonedDateTime.of(date, time, zone)] which resolves the wall-clock
 * interpretation correctly. A 10:00 slot never falls in the 02:00–03:00 spring-
 * forward gap, so no gap-bridging is required for typical schedules.
 *
 * @param now the reference point (zone determines which calendar day "today" is)
 * @return the next scheduled [ZonedDateTime], or null if the schedule is empty
 */
fun NudgeSchedule.nextSlot(now: ZonedDateTime): ZonedDateTime? {
    if (days.isEmpty() || times.isEmpty()) return null
    val sortedTimes = times.sorted()
    val zone = now.zone
    val nowTime = now.toLocalTime()
    // Check each offset day 0..6 (today first, then successive days).
    for (offset in 0..6) {
        val candidate = now.toLocalDate().plusDays(offset.toLong())
        if (candidate.dayOfWeek !in days) continue
        // On offset=0 (today), only slots strictly after now qualify.
        val effectiveTimes = if (offset == 0) {
            sortedTimes.filter { it.isAfter(nowTime) }
        } else {
            sortedTimes
        }
        if (effectiveTimes.isNotEmpty()) {
            return ZonedDateTime.of(candidate, effectiveTimes.first(), zone)
        }
    }
    return null
}
