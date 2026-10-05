package app.orbit.ui.screens.card

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.NoteRow
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.data.feed.CardFeed
import app.orbit.data.feed.CardSnapshot
import app.orbit.data.mappers.toUiContact
import app.orbit.data.mappers.withCallPatterns
import app.orbit.data.mappers.withCallStats
import app.orbit.data.repository.ListRepository
import app.orbit.domain.CallLogResyncTrigger
import app.orbit.domain.clock.Clock
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.SkipContactUseCase
import app.orbit.domain.usecase.SurfaceResult
import app.orbit.domain.usecase.SurfaceSoonerUseCase
import app.orbit.ui.util.formatAbsolute
import app.orbit.ui.util.formatRelative
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Card View VM — thin subscriber to [CardFeed] plus the card-loop interaction
 * surface (2026-06-09 revision):
 *
 *   - **Hydrated card.** `Ready.contact` carries the `withCallStats` overlay
 *     (last called / avg length / total calls) and the `withCallPatterns`
 *     hour histogram, both derived from the snapshot's `recentCalls`. The
 *     pre-revision card mapped a bare `toUiContact()` and showed placeholder
 *     stats + an all-zero heat strip on every contact.
 *   - **Swipe undo.** `onSwipeLeft` / `onSwipeRight` capture the prior
 *     membership schedule, run the mutation, then emit a snackbar with Undo
 *     that restores `nextDueAt` + `skipCount` via [UndoStack] (same pattern
 *     as SettingsIgnoredViewModel).
 *   - **Silent call advance (CORE-04).** `onCall` records that a dial left for
 *     the dialer; on return ([onReturnedFromDial]) the VM kicks an immediate
 *     incremental call-log sync so the detected call advances the deck on its
 *     own within ~1-2s. There is no "did you talk?" confirmation — the call log
 *     is the source of truth; off-log connections use "Log a connection" on the
 *     contact screen.
 *
 * The VM keeps the `nowHour` snapshot, the `NoteEntity → NoteRow` mapping
 * (Option B layering), and the `WhileSubscribed(5_000L)` per-screen cache
 * policy over the Eagerly-started singleton feed (ARCH-02 config-change
 * survival).
 *
 * Tide marker (2026-05-08) — the terminal `AllCaughtUp` state is gone; the
 * unparseable-listId branch routes to `EmptyNothingEligible`, and the
 * data-bound branch maps `SurfaceResult` → `Ready` / `EmptyNoMembers` /
 * `EmptyNothingEligible` with `Loading` as the structural pre-emission
 * placeholder (F-8).
 */
@HiltViewModel
class CardViewViewModel @Inject constructor(
    cardFeed: CardFeed,
    private val skipContact: SkipContactUseCase,
    private val surfaceSooner: SurfaceSoonerUseCase,
    private val listRepo: ListRepository,
    private val undoStack: UndoStack,
    private val callLogResync: CallLogResyncTrigger,
    private val clock: Clock,
    private val zoneId: ZoneId,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // listId arrives as a String. If it doesn't parse as Long (e.g. demo
    // sentinels "inner" / "drifted"), we route to EmptyNothingEligible —
    // there's no list to bind, so the surface is neutrally empty.
    private val listId: Long? = savedStateHandle.get<String>("listId")?.toLongOrNull()

    val uiState: StateFlow<CardViewUiState> =
        if (listId == null) {
            flowOf<CardViewUiState>(CardViewUiState.EmptyNothingEligible())
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000L),
                    initialValue = CardViewUiState.EmptyNothingEligible(),
                )
        } else {
            // F-8 — initial value is Loading (rendered as a transparent
            // placeholder) so the screen doesn't flash an empty-state shell
            // before CardFeed's first emission.
            cardFeed.forList(listId)
                .map { snapshot -> snapshot.toUiState() }
                .catch { t -> emit(CardViewUiState.Error(t.message ?: "unknown")) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000L),
                    initialValue = CardViewUiState.Loading,
                )
        }

    /** Later / Sooner, call acknowledgements and failures; the screen hosts the snackbar. */
    private val _messages = MutableSharedFlow<CardMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<CardMessage> = _messages.asSharedFlow()

    // Dial-in-flight marker: the contact id set on tap-to-call, consumed on the
    // next ON_RESUME to trigger an immediate call-log resync ([onReturnedFromDial]).
    // Plain field; no UI reads it. A non-null value just means "a dial happened".
    private var dialPendingContactId: Long? = null
    private var dialPendingName: String? = null

    // The newest undoable action. Older snackbars are replaced on screen, and a
    // stale token can never replay an inverse that belongs to someone else.
    private var undoToken = 0L

    // Waits for the call log to confirm a call placed from this card, so the
    // acknowledgement names a call that happened. Cancelled by any swipe, so a
    // deck that moved for another reason is never credited as a call.
    private var callAckJob: Job? = null

    /** CORE-04: Later (left swipe or the Later button) defers this contact on the current list. */
    fun onSwipeLeft(contactId: Long) = viewModelScope.launch {
        callAckJob?.cancel()
        val name = firstNameOf(contactId)
        runMutation("Couldn't move ${name ?: "them"} to later. Try again.") {
            val prior = captureSchedule(contactId)
            skipContact(contactId = contactId, listId = listId)
            val newDue = focusedDueAfterMutation(contactId)
            stageUndo(prior, label = laterMessage(name, newDue))
        }
    }

    /** CORE-03: Sooner (right swipe or the Sooner button) brings this contact forward on the current list. */
    fun onSwipeRight(contactId: Long) = viewModelScope.launch {
        callAckJob?.cancel()
        val name = firstNameOf(contactId)
        runMutation("Couldn't move ${name ?: "them"} sooner. Try again.") {
            val prior = captureSchedule(contactId)
            surfaceSooner(contactId = contactId, listId = listId)
            val newDue = focusedDueAfterMutation(contactId)
            stageUndo(prior, label = soonerMessage(name, newDue))
        }
    }

    /**
     * CORE-04 — the screen dials, then tells us a dial left for the dialer.
     * Recorded here and consumed on the next [onReturnedFromDial] to trigger an
     * immediate call-log resync; nothing changes on the card until the detected
     * call advances it on its own.
     */
    fun onCall(contactId: Long) {
        dialPendingContactId = contactId
        dialPendingName = firstNameOf(contactId)
    }

    /**
     * Screen-side ON_RESUME hook. When a dial is pending (the user just left for
     * the dialer and came back — the single most likely moment a new completed
     * call exists) kick an immediate INCREMENTAL call-log sync (expedited, no
     * debounce) so the deck advances on its own within ~1-2s rather than waiting
     * on the 10s-debounced content observer or the TTL-gated resume sync (the
     * latter is suppressed precisely in this flow — foreground → dial → return
     * all happen inside its 60s window).
     *
     * No "did you talk?" confirmation: the call log is the source of truth, so a
     * connected call advances the deck silently and an unconnected one correctly
     * does not. The sync is cheap (reads only rows since the last-sync watermark)
     * and idempotent; a still-in-progress call (no call-log row yet) is a no-op
     * and the observer picks it up on hang-up. Off-log connections (WhatsApp,
     * landline, in person) use "Log a connection" on the contact screen.
     *
     * Resumes with nothing pending (including first composition) are no-ops.
     */
    fun onReturnedFromDial() {
        val dialed = dialPendingContactId ?: return
        val name = dialPendingName
        dialPendingContactId = null
        dialPendingName = null
        callLogResync.enqueueImmediateSync(fullResync = false)
        acknowledgeCallWhenConfirmed(dialed, name)
    }

    /**
     * CARD-03: once the call log confirms the call (the deck moves past the
     * person, CORE-04), say so: "Called Avery", with "Add a note" while the
     * conversation is fresh. Nothing is said if the call is not confirmed
     * within [CALL_ACK_WAIT_MS] (it did not connect, or call-log access is
     * off), because thanking someone for a call that didn't happen is worse
     * than silence.
     */
    private fun acknowledgeCallWhenConfirmed(contactId: Long, name: String?) {
        callAckJob?.cancel()
        callAckJob = viewModelScope.launch {
            val moved = withTimeoutOrNull(CALL_ACK_WAIT_MS) {
                uiState.first { state -> state !is CardViewUiState.Loading && (state as? CardViewUiState.Ready)?.contactId != contactId }
            }
            if (moved != null) {
                _messages.tryEmit(CardMessage.Called(text = if (name != null) "Called $name" else "Call logged", contactId = contactId))
            }
        }
    }

    /**
     * Snackbar "Undo" tap. Replays the inverse only when [token] is the newest
     * action's; an older snackbar's Undo (one the screen failed to replace in
     * time) is ignored rather than reverting a different person.
     */
    fun onUndo(token: Long) = viewModelScope.launch {
        if (token != undoToken) return@launch
        runMutation("Couldn't undo that. Try again.") { undoStack.take()?.inverse?.invoke() }
    }

    // ─── Swipe-undo internals ────────────────────────────────────────────────

    /**
     * Memberships the upcoming mutation will touch — the focused list when
     * `listId` parsed, every membership otherwise (mirrors the use cases'
     * null-listId fan-out).
     */
    private suspend fun captureSchedule(contactId: Long): List<ListMembershipEntity> {
        val memberships = listRepo.observeMembershipsForContact(contactId).first()
        return if (listId == null) memberships else memberships.filter { it.listId == listId }
    }

    /** Post-mutation persisted nextDueAt for the focused list (soonest when unfocused). */
    private suspend fun focusedDueAfterMutation(contactId: Long): Instant? {
        val after = listRepo.observeMembershipsForContact(contactId).first()
        val focused = if (listId == null) after else after.filter { it.listId == listId }
        return focused.mapNotNull { it.nextDueAt }.minOrNull()
    }

    /**
     * Stage the inverse (exact `nextDueAt` + `skipCount` restore per touched
     * membership) on the depth-1 [UndoStack] and emit the snackbar. The
     * recompute keeps `lists.dueCount` consistent after the restore.
     */
    private fun stageUndo(prior: List<ListMembershipEntity>, label: String) {
        val inverse: suspend () -> Unit = {
            prior.forEach { membership ->
                listRepo.restoreMembershipSchedule(
                    contactId = membership.contactId,
                    listId = membership.listId,
                    nextDueAt = membership.nextDueAt,
                    skipCount = membership.skipCount,
                )
                listRepo.recomputeDueCountForList(membership.listId, clock.now())
            }
        }
        undoStack.put(UndoStack.PendingUndo(inverse, label))
        val token = ++undoToken
        _messages.tryEmit(CardMessage.Undoable(text = label, token = token))
    }

    /** The first name of the person on the card, read before a mutation moves it on. */
    private fun firstNameOf(contactId: Long): String? =
        (uiState.value as? CardViewUiState.Ready)
            ?.takeIf { it.contactId == contactId }
            ?.contact?.name?.trim()?.substringBefore(' ')?.ifBlank { null }

    // CARD-02: the snackbar names the person and says when they come back, in
    // the app's two verbs for this, Later and Sooner (voice.md glossary).
    private fun laterMessage(name: String?, newDue: Instant?): String {
        val who = name ?: "They"
        return newDue?.let { "$who will come up again ${futureDueLabel(it, clock.now())}." } ?: "$who moved to later."
    }

    private fun soonerMessage(name: String?, newDue: Instant?): String {
        val who = name ?: "They"
        return newDue?.let { "$who is now due ${futureDueLabel(it, clock.now())}." } ?: "$who moved sooner."
    }

    /**
     * H4-style uniform mutation wrapper — surfaces failures on the snackbar
     * instead of silently dropping them inside `viewModelScope.launch`.
     * CancellationException is rethrown so structured concurrency stays intact.
     */
    private suspend fun runMutation(failureLabel: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _messages.tryEmit(CardMessage.Failed(failureLabel))
        }
    }

    // ─── Snapshot → UI state ─────────────────────────────────────────────────

    /**
     * Maps the singleton snapshot + per-emission `now` to a [CardViewUiState].
     * Note rows are formatted here (Option B layering); the surfaced contact
     * is hydrated with `withCallStats` + `withCallPatterns` from the
     * snapshot's `recentCalls`.
     */
    private fun CardSnapshot.toUiState(): CardViewUiState {
        val now = clock.now()
        return when (val s = surface) {
            SurfaceResult.NoMembers -> CardViewUiState.EmptyNoMembers
            SurfaceResult.NothingEligible -> CardViewUiState.EmptyNothingEligible(
                upNextName = upNext?.displayName,
                upNextLabel = upNext?.let { futureDueLabel(it.dueAt, now) },
            )
            is SurfaceResult.Found -> {
                // Snapshot the local hour ONCE per emission for the HeatStrip
                // highlight. WR-02 — injected ZoneId.
                val nowHour = now.atZone(zoneId).hour
                val isAhead = s.nextDueAt.isAfter(now)
                CardViewUiState.Ready(
                    contactId = s.contact.id,
                    contact = s.contact.toUiContact()
                        .withCallStats(recentCalls, now)
                        .withCallPatterns(recentCalls, zoneId),
                    listContext = listEntity?.name.orEmpty(),
                    queueSize = queueSize,
                    recentNotes = recentNotes.map { it.toNoteRow(now) },
                    nowHour = nowHour,
                    isAheadOfToday = isAhead,
                    whyNowLine = whyNowLine(recentCalls, now),
                )
            }
        }
    }

    /**
     * Honest one-line framing from the most recent call event (manual marks
     * count — the user told us they talked): "It's been 3 weeks." Empty when
     * there is no history at all — the screen's neutral "No call history yet"
     * panel covers that case instead.
     */
    private fun whyNowLine(recentCalls: List<CallEventEntity>, now: Instant): String {
        val lastCallAt = recentCalls
            .maxByOrNull { it.occurredAt }
            ?.occurredAt
            ?: return ""
        val days = Duration.between(lastCallAt, now).toDays().coerceAtLeast(0L)
        val since = when {
            days == 0L -> "You talked today."
            days == 1L -> "You talked yesterday."
            days < 14L -> "It's been $days days."
            days < 60L -> "It's been ${days / 7} weeks."
            else -> "It's been ${days / 30} months."
        }
        // Two short lines read better than one that wraps mid-phrase.
        return listOfNotNull(since, rhythmSentence(recentCalls)).joinToString("\n")
    }

    /**
     * CARD-04: the pair's own rhythm, so "why now" is about the two of them
     * rather than a statistic: "You usually talk about every 2 weeks." The
     * median gap between calls (robust to one long silence), and only once
     * there are four calls (three gaps), because a rhythm from two calls is
     * a guess. Stated as a fact, never as a deadline (voice.md: no guilt).
     */
    private fun rhythmSentence(recentCalls: List<CallEventEntity>): String? {
        val times = recentCalls.map { it.occurredAt }.distinct().sorted()
        if (times.size < 4) return null
        val gaps = times.zipWithNext { a, b -> Duration.between(a, b).toDays() }.filter { it > 0 }.sorted()
        if (gaps.size < 3) return null
        val median = gaps[gaps.size / 2]
        val every = when {
            median <= 1L -> "every day"
            median < 7L -> "every $median days"
            median < 11L -> "every week"
            median < 60L -> "every ${(median + 3) / 7} weeks"
            else -> "every ${(median + 15) / 30} months"
        }
        return "You usually talk about $every."
    }

    /**
     * Forward-looking phrase for snackbars and the up-next hint:
     * "later today" / "tomorrow" / "on Tuesday" / "in 12 days" / "in 3 weeks"
     * / "in 2 months". Lowercase fragment so it slots mid-sentence.
     */
    private fun futureDueLabel(due: Instant, now: Instant): String {
        val days = Duration.between(now, due).toDays()
        return when {
            days <= 0L -> "later today"
            days == 1L -> "tomorrow"
            days < 7L -> "on " + due.atZone(zoneId).dayOfWeek
                .getDisplayName(TextStyle.FULL, Locale.getDefault())
            days < 14L -> "in $days days"
            days < 60L -> "in ${days / 7} weeks"
            else -> "in ${days / 30} months"
        }
    }

    /**
     * B3 — Clock-aware mapper. `now` is the same snapshot used elsewhere in
     * `toUiState` so the rendered relative timestamps are consistent.
     */
    private fun NoteEntity.toNoteRow(now: Instant): NoteRow = NoteRow(
        id = id,
        contactId = contactId,
        body = body,
        createdAtMs = createdAt.toEpochMilli(),
        relativeTimestamp = formatRelative(createdAt, now),
        absoluteTimestamp = formatAbsolute(createdAt),
    )
}

/** How long to wait for the call log to confirm a call before saying nothing. */
private const val CALL_ACK_WAIT_MS = 15_000L
