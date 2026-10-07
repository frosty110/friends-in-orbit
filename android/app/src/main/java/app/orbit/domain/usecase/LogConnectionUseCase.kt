package app.orbit.domain.usecase

import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.domain.clock.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * "Log a connection": records a conversation Orbit's call-log sync can't see
 * (another app, a visit), or with "Couldn't reach them" an attempt that did
 * not connect, as a [CallEventEntity] with `durationSeconds = 0`.
 *
 * The one way to log, called by Contact detail (CONTACT-09) and by the card
 * (CARD-10). Until 2026-10-07 the write lived in
 * `ContactDetailViewModel.onLogConnection`; the card needed the same thing, and
 * two copies of a write is how two screens come to disagree about what
 * "logged" means, so the body moved here unchanged and both ViewModels call it.
 *
 * - A connection (`isAttempt = false`) is `CallSource.MANUAL` and resets the
 *   full rule cadence. An attempt is `CallSource.ATTEMPT` (a voicemail, no
 *   answer) and advances the rotation only by the flat
 *   [app.orbit.domain.rule.AttemptCooldown] window, without claiming you
 *   talked: it never sets "last contacted" or feeds the heat strip
 *   (`ContactMapper.withCallStats`).
 * - It writes through [MarkCalledUseCase], the same atomic path the call-log
 *   reconciler uses, so `nextDueAt` recomputes on every list the person is on
 *   (DOM-06) and they go back into the rhythm. The engines treat MANUAL as
 *   "not a real call" only for the short-call and incoming adjustments; the
 *   base cooldown still keys off `occurredAt`, so the person stops surfacing
 *   as due.
 * - A non-blank note is attached through [AddRetroactiveNoteUseCase],
 *   back-dated to the same `occurredAt` (the LOG-03 convention).
 *
 * When ([LogConnectionWhen]) resolves against one read of the injected clock:
 * Today is now, Yesterday is now minus 24 hours, and a picked date (the
 * Material date picker's UTC midnight of the chosen day) is pinned to local
 * noon in the injected zone, so it lands on the chosen day in every time
 * zone, then clamped to now, the authoritative no-future-events guard (the
 * sheet's date bound is advisory).
 *
 * Throws what the writes throw; the caller says "Couldn't save your change"
 * or its own words for a failure (rules.md Code 3).
 */
class LogConnectionUseCase @Inject constructor(
    private val markCalled: MarkCalledUseCase,
    private val addRetroactiveNote: AddRetroactiveNoteUseCase,
    private val clock: Clock,
    private val zoneId: ZoneId,
) {

    /**
     * Logs the connection (or attempt) for [contactId] and returns what
     * [MarkCalledUseCase] returned: [MutationResult.MembershipMissing] when
     * the person was on no list, so nothing was rescheduled (the event is
     * still written).
     */
    suspend operator fun invoke(
        contactId: Long,
        whenChoice: LogConnectionWhen,
        note: String,
        isAttempt: Boolean,
    ): MutationResult {
        val now = clock.now()
        val occurredAt: Instant = when (whenChoice) {
            LogConnectionWhen.Today -> now
            LogConnectionWhen.Yesterday -> now.minus(Duration.ofDays(1))
            is LogConnectionWhen.OnDate ->
                Instant
                    .ofEpochMilli(whenChoice.utcMidnightMillis)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .atTime(12, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .coerceAtMost(now)
        }
        val event = CallEventEntity(
            contactId = contactId,
            occurredAt = occurredAt,
            // OUTGOING is the closest fit: the user reached out (or met up).
            // The engines ignore direction for MANUAL and ATTEMPT anyway (the
            // isRealCall gate, the attempt short-circuit).
            direction = CallDirection.OUTGOING,
            durationSeconds = 0,
            source = if (isAttempt) CallSource.ATTEMPT else CallSource.MANUAL,
        )
        val result = markCalled(contactId, event)
        if (note.isNotBlank()) {
            addRetroactiveNote(contactId, note.trim(), occurredAt)
        }
        return result
    }
}

/**
 * "When did you connect?", handed from the shared Log a connection sheet
 * (`ui/components/LogConnectionSheet`) to [LogConnectionUseCase]. Plain data,
 * so the domain never imports from a composable package. It lived beside
 * `ContactDetailViewModel` until the card logged connections too (CARD-10).
 *
 * [OnDate.utcMidnightMillis] is the raw Material DatePicker selection, UTC
 * midnight of the chosen calendar day; the use case turns it into local noon.
 * The sheet never reads the clock.
 */
sealed interface LogConnectionWhen {
    data object Today : LogConnectionWhen
    data object Yesterday : LogConnectionWhen
    data class OnDate(val utcMidnightMillis: Long) : LogConnectionWhen
}
