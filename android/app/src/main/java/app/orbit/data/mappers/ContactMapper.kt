package app.orbit.data.mappers

import app.orbit.R
import app.orbit.data.Contact
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatDuration
import app.orbit.ui.util.formatRelative
import java.time.Instant
import java.time.ZoneId

/**
 * Minimal-safe [ContactEntity] → UI-domain [Contact] projection used by the
 * Card / Browse / ContactDetail view models.
 *
 * Only `id`, `name`, and `phone` are derived from the entity directly — the
 * call-derived fields (`lastCalledLabel`, `totalCalls`, `avgLengthLabel`) are
 * defaults that should be overlaid via [withCallStats] when the caller has
 * the contact's [CallEventEntity] list in scope. ContactDetail uses the
 * overlay; Card View / Browse have their own per-screen relative-time
 * overlays that pre-date this helper.
 *
 * `bestWindowLabel` and `heat` are overlaid via [withCallPatterns] (Card View).
 * Fields that remain placeholders (`history`, `notes`, `patternNote`, `due`,
 * `listIds`) are filled either by the screen's other state branches (e.g.
 * ContactDetail composes `recentCalls` and `notes` separately) or deferred
 * until a consumer needs the full hydrated shape.
 */
fun ContactEntity.toUiContact(): Contact = Contact(
    id = "c-$id",
    name = displayName,
    phone = phoneNumber,
    lastCalledLabel = null,
    avgLengthLabel = null,
    pickupRateLabel = "",
    totalCalls = 0,
    due = false,
    listIds = emptyList(),
    bestWindowLabel = null,
    heat = FloatArray(24) { 0f },
    history = emptyList(),
    notes = emptyList(),
    patternNote = "",
    photoUri = photoUri
)

/**
 * Overlay the call-derived stats on a [Contact] using its raw event list.
 * Computes:
 *   - `lastCalledLabel` — relative-time formatting of the most recent
 *     CONNECTION (e.g. "today", "7 days ago"). ATTEMPT events (reach-outs
 *     that didn't connect — voicemail / no answer) are excluded: a voicemail
 *     must never read as "you talked today". Null when there is no connection
 *     so [ContactDetailScreen] falls through to the "Never called" rendering.
 *   - `totalCalls` — count of CONNECTIONS (CALL_LOG + MANUAL; ATTEMPT excluded —
 *     an attempt is not a call that happened).
 *   - `avgLengthLabel` — mean of `durationSeconds` over MEASURED calls only
 *     (`durationSeconds > 0`), formatted via [formatDuration]. Zero-duration
 *     events are unverified manual marks, not measured calls — averaging
 *     them in dragged a 22-min average to 19 after one logged connection.
 *     When no measured calls exist the label is null: there is no average to
 *     state, and each screen says so in its own words (it used to be the em
 *     dash [formatDuration] returned for zero).
 *   - `measuredCalls`: how many connections fed that mean. Contact detail
 *     gates "Average length" on `measuredCalls >= 3`, not `totalCalls >= 3`:
 *     the two counts differ by the zero-length MANUAL rows, so gating on the
 *     total let one measured call plus two logged connections show that
 *     single call's length as an average (CONTACT-02). The label is still
 *     populated below three so other consumers can decide their own gate
 *     (Card view reads it ungated).
 *
 * Per-direction breakdowns (`pickupRateLabel`) are out of scope here —
 * `call_events` stores connected calls only (the reconciler drops MISSED /
 * REJECTED / VOICEMAIL rows), so a pickup *rate* is not computable from the
 * encrypted store. Heat-map hydration lives in [withCallPatterns].
 */
fun Contact.withCallStats(events: List<CallEventEntity>, now: Instant): Contact {
    if (events.isEmpty()) return this
    // Connections only — ATTEMPT events (reach-outs that didn't connect) must
    // not set "last contacted", inflate the call count, or feed the average.
    // They still surface in the history list with their own "Attempted"
    // treatment; they just don't masquerade as connections in the stats.
    val connections = events.filter { it.source != CallSource.ATTEMPT }
    val mostRecent = connections.maxByOrNull { it.occurredAt }
    // Zero-duration events (MANUAL marks) are excluded from the
    // average; they still count toward totalCalls and lastCalledLabel.
    val measured = connections.filter { it.durationSeconds > 0 }
    val avgSeconds: Int? =
        if (measured.isEmpty()) null else measured.map { it.durationSeconds }.average().toInt()
    return copy(
        lastCalledLabel = mostRecent?.let { formatRelative(it.occurredAt, now) },
        totalCalls = connections.size,
        measuredCalls = measured.size,
        avgLengthLabel = avgSeconds?.let(::formatDuration)
    )
}

/**
 * Card hydration (2026-06-09) — overlay the 24-hour call-pattern histogram
 * (`heat`) and the derived `bestWindowLabel` from the contact's raw event
 * list. Only connected device-log calls feed the histogram: manual marks
 * carry `durationSeconds == 0` and say nothing about when this person
 * actually talks, so they are excluded naturally by the duration filter.
 *
 * `heat[h]` is the share of connected calls that landed in local hour `h`,
 * normalized so the busiest hour reads 1.0 (frequency, not pickup rate —
 * `call_events` has no missed-call rows to rate against; the Card's tooltip
 * copy "when you usually answer or call" stays honest for this signal).
 *
 * Below [MIN_CALLS_FOR_PATTERNS] connected calls the overlay is a no-op —
 * `heat` stays all-zero and `bestWindowLabel` stays empty, which the Card
 * View reads as "not enough history" and renders a neutral line instead of
 * the pattern panel.
 */
fun Contact.withCallPatterns(events: List<CallEventEntity>, zoneId: ZoneId): Contact {
    val connected = events.filter { it.durationSeconds > 0 }
    if (connected.size < MIN_CALLS_FOR_PATTERNS) return this
    val hourCounts = IntArray(24)
    connected.forEach { event ->
        hourCounts[event.occurredAt.atZone(zoneId).hour]++
    }
    val peak = hourCounts.max()
    if (peak == 0) return this
    return copy(
        heat = FloatArray(24) { h -> hourCounts[h].toFloat() / peak },
        bestWindowLabel = bestWindowLabel(hourCounts)
    )
}

/** Connected-call floor below which call-pattern overlays stay unhydrated. */
private const val MIN_CALLS_FOR_PATTERNS: Int = 3

/**
 * Coarse day-part label for the stat slot next to the heat strip. Buckets the
 * hour histogram into four windows and names the heaviest one. Sentence case
 * plural ("Evenings") matches the legacy Card View register; the words live
 * in strings_time.xml.
 */
private fun bestWindowLabel(hourCounts: IntArray): UiText {
    val windows: List<Pair<Int, List<Int>>> = listOf(
        R.string.time_daypart_mornings to (5..11).toList(),
        R.string.time_daypart_afternoons to (12..16).toList(),
        R.string.time_daypart_evenings to (17..21).toList(),
        R.string.time_daypart_late_nights to listOf(22, 23, 0, 1, 2, 3, 4)
    )
    return UiText.res(windows.maxBy { (_, hours) -> hours.sumOf { hourCounts[it] } }.first)
}
