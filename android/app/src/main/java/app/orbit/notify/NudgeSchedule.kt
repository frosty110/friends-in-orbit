package app.orbit.notify

import app.orbit.domain.JsonProvider
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
 *
 * This is the stored format, not copy: nothing here is shown to a person (the
 * schedule editor and summary format times with
 * [app.orbit.ui.util.formatClockTime], in the phone's 12 or 24 hour style).
 * [Locale.ROOT] pins it so the stored text never depends on the phone's
 * language.
 */
object LocalTimeSerializer : KSerializer<LocalTime> {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

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
    val days: Set<
        @Serializable(with = DayOfWeekSerializer::class)
        DayOfWeek
        >,
    val times: List<
        @Serializable(with = LocalTimeSerializer::class)
        LocalTime
        >
) {
    companion object {
        /** Default schedule: all 7 days at 10:00. Sealed by D-03 (default-ON). */
        val DEFAULT = NudgeSchedule(
            days = DayOfWeek.values().toSet(),
            times = listOf(LocalTime.of(10, 0))
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

        /**
         * The schedule a list nudges on, from its stored
         * [app.orbit.data.entity.ListEntity.nudgeScheduleJson]: [DEFAULT] when
         * the column is null, blank or unreadable, as the nudge chain has always
         * treated it. One reading for the chain
         * ([NudgeScheduler.scheduleFromEntity]) and for
         * [app.orbit.data.db.MIGRATION_13_14], which must fold a window into
         * the schedule the chain was really running.
         */
        fun fromStoredJson(json: String?): NudgeSchedule =
            json?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JsonProvider.json.decodeFromString(serializer(), it) }.getOrNull() }
                ?: DEFAULT
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

// ─── Active-hours window ─────────────────────────────────────────────────────

/** True when the window [start]..[end] crosses midnight (22:00 to 02:00, or the old Nights part). */
internal fun spansMidnight(start: LocalTime, end: LocalTime): Boolean = end < start

/**
 * True when [time] falls inside the active-hours window that runs from
 * [start] up to [end]: the start is in, the end is not, wrapping past midnight
 * when [end] is before [start] (e.g. 22:00 to 02:00).
 *
 * The end is out because the gate asks this at the moment the worker runs,
 * which is always a little after its slot: a time chosen on the end (9pm
 * under Evenings, 5pm to 9pm) was "inside" to the scheduler and the plan, so
 * nothing was added, and outside to the gate a moment later, so that list
 * went silent every day with nothing said (until 2026-10-08). With the end
 * out, such a time is held back like any other outside time, the scheduler
 * adds the start, and "When to nudge" says so. It also stops the parts of
 * the day overlapping: noon is Afternoons, not Mornings too.
 *
 * The single definition shared by the fire-time gate ([ListPromptWorker]) and
 * the scheduler ([NudgeScheduler.effectiveSchedule]). The scheduler decides
 * whether a chosen time can ever post by asking exactly the question the gate
 * will ask at fire time, so the two cannot drift apart.
 */
fun isInActiveWindow(time: LocalTime, start: LocalTime, end: LocalTime): Boolean =
    if (spansMidnight(start, end)) {
        // Midnight-spanning: inside if time >= start OR time < end
        time >= start || time < end
    } else {
        // Normal range: inside if start <= time < end
        time >= start && time < end
    }

/**
 * LIST-25: this schedule with a list's active-hours window folded into its
 * times, so that the times alone say when the nudge comes.
 *
 * Until 2026-10-08 a list had two answers to "when": its nudge times (When to
 * nudge) and a window they had to fall in (Time of day, before that "Active
 * hours"). The window only ever narrowed the times, so the owner's review
 * found two sections deciding one thing, and three bugs had come from how the
 * two combined (B4, a summary naming 10am for a 5pm nudge, and a time on the
 * window's end never posting). Time of day went; this keeps every list
 * nudging exactly when it did:
 *
 * - The times inside the window are the ones that posted (the fire-time gate
 *   held the rest), so they are kept and the rest dropped.
 * - With none inside, the scheduler added the window's start (D-09) and that
 *   was the one nudge, so the start becomes the only time.
 * - No window (either end null), or no times (nudges off): unchanged.
 * - A window that starts where it ends (the older start and end pickers
 *   allowed it) let nothing through: every slot, its own start included, was
 *   held back by the gate. It folds to no time at all, so the list stays as
 *   silent as it was, and When to nudge says "No time set".
 *
 * Days are never touched: the window never changed them. Folding a schedule
 * with no days still folds its times, so the list nudges as before if days
 * are chosen again. Pure; used by [app.orbit.data.db.MIGRATION_13_14] for
 * every stored window and by the importer for a window in an older backup.
 */
fun NudgeSchedule.foldActiveWindow(start: LocalTime?, end: LocalTime?): NudgeSchedule {
    if (start == null || end == null || times.isEmpty()) return this
    if (start == end) return copy(times = emptyList())
    val inside = times.filter { isInActiveWindow(it, start, end) }.distinct().sorted()
    return copy(times = inside.ifEmpty { listOf(start) })
}
