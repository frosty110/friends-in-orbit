package app.orbit.data.db

import androidx.room.TypeConverter
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ListType
import app.orbit.data.entity.RuleKind
import java.time.Instant
import java.time.LocalTime

class OrbitTypeConverters {

    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    // M9 — seconds-of-day Int for LocalTime preserves sub-minute precision through
    // round trip. The earlier "HH:mm" formatter truncated seconds and nanos.
    @TypeConverter
    fun fromLocalTime(value: LocalTime?): Int? = value?.toSecondOfDay()

    @TypeConverter
    fun toLocalTime(value: Int?): LocalTime? = value?.let { LocalTime.ofSecondOfDay(it.toLong()) }

    @TypeConverter
    fun fromListType(value: ListType?): String? = value?.name

    // Migration safety: a row written by a future build may carry an enum
    // constant this build doesn't know about. `valueOf` would throw and crash
    // the query — `runCatching { … }.getOrNull()` returns null, letting the
    // Room layer / consumer treat the row's enum as missing rather than fatal.
    @TypeConverter
    fun toListType(value: String?): ListType? =
        value?.let { runCatching { ListType.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromCallDirection(value: CallDirection?): String? = value?.name

    @TypeConverter
    fun toCallDirection(value: String?): CallDirection? =
        value?.let { runCatching { CallDirection.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromCallSource(value: CallSource?): String? = value?.name

    @TypeConverter
    fun toCallSource(value: String?): CallSource? =
        value?.let { runCatching { CallSource.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromRuleKind(value: RuleKind?): String? = value?.name

    @TypeConverter
    fun toRuleKind(value: String?): RuleKind? =
        value?.let { runCatching { RuleKind.valueOf(it) }.getOrNull() }
}
