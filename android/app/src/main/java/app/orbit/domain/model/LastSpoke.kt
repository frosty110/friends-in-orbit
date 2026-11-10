package app.orbit.domain.model

import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import java.time.Instant

/**
 * A conversation happened: a call that connected ([CallSource.CALL_LOG]) or
 * a connection the user logged by hand ([CallSource.MANUAL]). An
 * [CallSource.ATTEMPT] (no answer, a voicemail, "Couldn't reach them") is a
 * reach-out, not a conversation: nobody talked (Enums.kt, voice.md's
 * "Attempt").
 */
val CallEventEntity.isConnection: Boolean
    get() = source != CallSource.ATTEMPT

/**
 * When you last spoke: the newest [isConnection] event among these, never an
 * attempt. Null when nobody has spoken, which includes a history of attempts
 * alone.
 *
 * One definition for every "Spoke ..." line. Until 2026-11-10 Home's Next up
 * took the newest event of any kind (`HomeFeed.enrichOne`), so an unanswered
 * dial read "Spoke today" there while Card view, which filtered attempts out
 * itself, still said "Spoke 3 weeks ago." about the same person. Card view
 * (`CardViewViewModel`) and Contact detail's "Last call"
 * (`ContactMapper.withCallStats`) filtered attempts out by hand until the same
 * day; all three read [isConnection] now.
 */
fun Iterable<CallEventEntity>.lastSpokeAt(): Instant? =
    asSequence().filter { it.isConnection }.maxOfOrNull { it.occurredAt }
