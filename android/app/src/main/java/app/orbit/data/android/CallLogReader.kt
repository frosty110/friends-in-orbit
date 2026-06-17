package app.orbit.data.android

import android.content.Context
import android.provider.CallLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class CallRow(
    val normalizedPhone: String,
    val whenMs: Long,
    val durationSec: Int,
    val type: Int,              // CallLog.Calls.TYPE — INCOMING / OUTGOING / MISSED / VOICEMAIL
)

data class ContactStats(
    val totalCalls: Int,
    val lastCallMs: Long?,
    val avgDurationSec: Int,    // over connected (non-missed) calls only
    val pickupRate: Float,      // portion of calls that were connected (not missed)
    val heat: FloatArray,       // 24 entries, normalized 0..1 pickup-rate by hour-of-day
    val history: List<CallRow>, // most-recent-first, capped
) {
    // Correct structural equality — the synthesized version would use FloatArray
    // identity, which is never what callers want. Written manually because
    // data classes can't override equals cleanly while keeping FloatArray.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactStats) return false
        return totalCalls == other.totalCalls &&
            lastCallMs == other.lastCallMs &&
            avgDurationSec == other.avgDurationSec &&
            pickupRate == other.pickupRate &&
            heat.contentEquals(other.heat) &&
            history == other.history
    }

    override fun hashCode(): Int {
        var result = totalCalls
        result = 31 * result + (lastCallMs?.hashCode() ?: 0)
        result = 31 * result + avgDurationSec
        result = 31 * result + pickupRate.hashCode()
        result = 31 * result + heat.contentHashCode()
        result = 31 * result + history.hashCode()
        return result
    }
}

// Reads CallLog.Calls and derives per-contact stats. Only tracks calls within
// the lookback window (PRD §Call Detection: 90-day default import).
//
// Declared as `@Singleton open class @Inject constructor` so:
//   1. CallLogSyncWorker can inject this reader through Hilt's graph.
//   2. CallLogSyncWorkerTest can subclass with a FakeCallLogReader override.
@Singleton
open class CallLogReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    open fun readAll(lookbackDays: Int = 90): List<CallRow> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
        )
        val cutoff = System.currentTimeMillis() - lookbackDays * DAY_MS
        val selection = "${CallLog.Calls.DATE} >= ?"
        val args = arrayOf(cutoff.toString())
        val sort = CallLog.Calls.DATE + " DESC"

        val out = mutableListOf<CallRow>()
        resolver.query(CallLog.Calls.CONTENT_URI, projection, selection, args, sort)?.use { c ->
            val idxNum = c.getColumnIndexOrThrow(projection[0])
            val idxDate = c.getColumnIndexOrThrow(projection[1])
            val idxDur = c.getColumnIndexOrThrow(projection[2])
            val idxType = c.getColumnIndexOrThrow(projection[3])
            while (c.moveToNext()) {
                val num = c.getString(idxNum) ?: continue
                out += CallRow(
                    normalizedPhone = ContactsReader.normalizeForMatch(num),
                    whenMs = c.getLong(idxDate),
                    durationSec = c.getInt(idxDur),
                    type = c.getInt(idxType),
                )
            }
        }
        return out
    }

    companion object {
        const val DAY_MS = 24L * 3600L * 1000L

        // Groups raw call rows by normalized phone and derives per-contact
        // stats. Called once per ingestion; cheap enough to redo on refresh.
        fun statsByPhone(rows: List<CallRow>): Map<String, ContactStats> {
            val zone = ZoneId.systemDefault()
            val grouped = rows.groupBy { it.normalizedPhone }
            return grouped.mapValues { (_, calls) ->
                val total = calls.size
                val connected = calls.filter { it.type != CallLog.Calls.MISSED_TYPE && it.durationSec > 0 }
                val avg = if (connected.isEmpty()) 0
                    else connected.sumOf { it.durationSec } / connected.size
                val pickup = if (total == 0) 0f else connected.size.toFloat() / total
                val hourCounts = IntArray(24)
                val hourPickups = IntArray(24)
                calls.forEach { row ->
                    val hour = Instant.ofEpochMilli(row.whenMs).atZone(zone).hour
                    hourCounts[hour]++
                    if (row.type != CallLog.Calls.MISSED_TYPE && row.durationSec > 0) {
                        hourPickups[hour]++
                    }
                }
                val heat = FloatArray(24) { h ->
                    if (hourCounts[h] == 0) 0f
                    else hourPickups[h].toFloat() / hourCounts[h]
                }
                ContactStats(
                    totalCalls = total,
                    lastCallMs = calls.maxOfOrNull { it.whenMs },
                    avgDurationSec = avg,
                    pickupRate = pickup,
                    heat = heat,
                    history = calls.sortedByDescending { it.whenMs }.take(10),
                )
            }
        }

        fun relativeTime(whenMs: Long, now: Long = System.currentTimeMillis()): String {
            val deltaSec = abs(now - whenMs) / 1000
            val deltaDays = deltaSec / 86400
            return when {
                deltaSec < 60 -> "just now"
                deltaSec < 3600 -> "${deltaSec / 60} min ago"
                deltaSec < 86400 -> "${deltaSec / 3600} hr ago"
                deltaDays == 1L -> "1 day ago"
                deltaDays < 14 -> "$deltaDays days ago"
                deltaDays < 60 -> "${deltaDays / 7} weeks ago"
                else -> "${deltaDays / 30} months ago"
            }
        }

        fun durationLabel(seconds: Int): String {
            if (seconds < 60) return "${seconds}s"
            val mins = seconds / 60
            return "$mins min"
        }
    }
}
