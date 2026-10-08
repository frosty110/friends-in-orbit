package app.orbit.data.repository

import app.orbit.data.AppPrefs
import app.orbit.data.dao.WaitingCallRow
import app.orbit.domain.clock.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * NOTE-05: the calls waiting for a note, the one definition Home's stack
 * (HOME-14) and the post-call notification (NOTIF-16) both read, so the two
 * can never disagree about who is waiting.
 *
 * A call waits when it came from the call log, connected and lasted at least
 * [MIN_SECONDS], is with someone on a list who is not ignored, started within
 * [WINDOW], has no note about that person written since it started, and the
 * user has not dismissed it. One entry per person (their latest such call).
 * The database half of that is [CallEventRepository.observeWaitingForNote];
 * the dismissals are [AppPrefs.dismissedPostCallIds], combined here so a
 * dismissal on Home and a note saved anywhere both show up live.
 *
 * Until 2026-10-07 this was NOTE-02's banner: the latest OUTGOING call of the
 * last ten minutes, dismissed in an in-memory set that a new process forgot.
 */
@Singleton
class WaitingCalls @Inject constructor(
    private val callEventRepo: CallEventRepository,
    private val prefs: AppPrefs,
    private val clock: Clock,
) {

    /**
     * The waiting calls as of [now], newest first, re-emitted whenever a call,
     * a note, a list, a membership, a person or a dismissal changes.
     *
     * [now] fixes the start of the 24 hour window for the life of the flow, so
     * a call slides out of it only when the caller asks again with a later
     * [now] (Home does on every resume). The window is the outer bound; a
     * call never waits longer than the caller's own staleness.
     */
    fun observe(now: Instant = clock.now()): Flow<List<WaitingCallRow>> =
        combine(
            callEventRepo.observeWaitingForNote(since = now.minus(WINDOW), minSeconds = MIN_SECONDS),
            prefs.dismissedPostCallIds,
        ) { rows, dismissed -> rows.filterNot { it.callEventId in dismissed } }

    /** One read of [observe] at the clock's now. */
    suspend fun current(): List<WaitingCallRow> = observe(clock.now()).first()

    /**
     * The user closed these calls on Home ("Dismiss", "Dismiss all"). Kept
     * until [KEEP_DISMISSALS] has passed, which is longer than any call can
     * wait, so a dismissed call never comes back and the store never grows.
     */
    suspend fun dismiss(callEventIds: Collection<Long>) {
        val now = clock.now()
        prefs.addPostCallDismissals(callEventIds, at = now, forgetBefore = now.minus(KEEP_DISMISSALS))
    }

    /** Undo for [dismiss]. */
    suspend fun undoDismiss(callEventIds: Collection<Long>) = prefs.removePostCallDismissals(callEventIds)

    companion object {
        /** How long after it started a call can wait for a note. */
        val WINDOW: Duration = Duration.ofHours(24)

        /** The shortest call worth a note: a minute, either direction. */
        const val MIN_SECONDS: Int = 60

        /** How long a dismissal is remembered: twice [WINDOW], for margin. */
        val KEEP_DISMISSALS: Duration = Duration.ofHours(48)
    }
}

/** When the call ended: the call log records the start and the length. */
val WaitingCallRow.endedAt: Instant get() = occurredAt.plusSeconds(durationSeconds.toLong())
