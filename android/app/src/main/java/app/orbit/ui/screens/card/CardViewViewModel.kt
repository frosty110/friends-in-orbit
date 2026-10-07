package app.orbit.ui.screens.card

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.NoteRow
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.ListType
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
import app.orbit.ui.util.ComesUp
import app.orbit.ui.util.UiText
import app.orbit.ui.util.comesUp
import app.orbit.ui.util.formatAbsolute
import app.orbit.ui.util.formatAgo
import app.orbit.ui.util.formatRelative
import app.orbit.ui.util.formatSpan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

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
 * data-bound branch maps `SurfaceResult` → `Ready` / `EmptyNoMembers` /
 * `EmptyNothingEligible` with `Loading` as the structural pre-emission
 * placeholder (F-8).
 *
 * Card-view audit (2026-10-06):
 *   - **Error with Try again (CARD-07).** A failed read arrives from CardFeed
 *     as a snapshot with `error` set (the feed catches before its `stateIn`,
 *     see its KDoc); [onRetry] re-subscribes through
 *     `retryCount.flatMapLatest` (HOME-10 precedent). A malformed list id is
 *     the same deck with `canRetry = false`: there is no feed to re-subscribe,
 *     so it offers Go home alone. The malformed id used to render "All quiet
 *     for now." over a list that does not exist.
 *   - **Loading until the feed has answered.** The feed's placeholder is
 *     `loaded = false` and maps to Loading, so a cold first open never
 *     flashes an empty deck (F-8 in full; before, the placeholder looked
 *     like NothingEligible).
 *   - **CARD-03 by evidence.** "Called {name}" waits for a connected call at
 *     or after the dial, or for the deck to move past the person, not for
 *     motion alone (see [acknowledgeCallWhenConfirmed]).
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

    // listId arrives as a String. One that does not parse as Long (a bad deep
    // link) has no list to bind, so it is the Error deck (CARD-07, rules.md
    // Code 3: a path that cannot happen gets a loud guard, not a shrug). It
    // used to render "All quiet for now." with a Browse button into a list
    // that does not exist. The deck is a constant, so a retry could change
    // nothing: `canRetry = false` and the screen withholds Try again (until
    // 2026-10-06 it was the Primary action there, and did nothing).
    private val listId: Long? = savedStateHandle.get<String>("listId")?.toLongOrNull()

    // CARD-07: bumped by [onRetry] to re-subscribe after a failed read (the
    // HOME-10 precedent). CardFeed evicts a failed entry from its cache, so
    // the next forList call builds a fresh subscription instead of handing
    // back the memoized flow that already failed and will never emit again.
    private val retryCount = MutableStateFlow(0)

    /** The Error deck's Try again. */
    fun onRetry() {
        retryCount.update { it + 1 }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<CardViewUiState> =
        if (listId == null) {
            flowOf<CardViewUiState>(CardViewUiState.Error(canRetry = false))
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000L),
                    initialValue = CardViewUiState.Error(canRetry = false),
                )
        } else {
            // F-8 — initial value is Loading (rendered as a transparent
            // placeholder) so the screen doesn't flash an empty-state shell
            // before CardFeed's first emission.
            retryCount.flatMapLatest {
                cardFeed.forList(listId)
                    .map { snapshot -> snapshot.toUiState() }
                    // A failed upstream read arrives as a snapshot (CardFeed's
                    // own catch), so this one covers only toUiState itself.
                    .catch { t ->
                        if (t is CancellationException) throw t
                        emit(CardViewUiState.Error())
                    }
            }
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
    // Plain fields; no UI reads them. A non-null id just means "a dial happened";
    // the instant is what a logged call must be at or after to count (CARD-03).
    private var dialPendingContactId: Long? = null
    private var dialPendingName: String? = null
    private var dialPendingAt: Instant? = null

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
        runMutation(
            if (name != null) {
                UiText.res(R.string.card_later_failed_named, name)
            } else {
                UiText.res(R.string.card_later_failed_unnamed)
            },
        ) {
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
        runMutation(
            if (name != null) {
                UiText.res(R.string.card_sooner_failed_named, name)
            } else {
                UiText.res(R.string.card_sooner_failed_unnamed)
            },
        ) {
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
        dialPendingAt = clock.now()
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
        val dialedAt = dialPendingAt ?: clock.now()
        dialPendingContactId = null
        dialPendingName = null
        dialPendingAt = null
        callLogResync.enqueueImmediateSync(fullResync = false)
        acknowledgeCallWhenConfirmed(dialed, name, dialedAt)
    }

    /**
     * CARD-03: once the call log confirms the call, say so: "Called Avery",
     * with "Add a note" while the conversation is fresh. Confirmation is
     * evidence of the call, not motion of the deck: a Ready for the same
     * person whose newest connected call is at or after the dial, or the deck
     * moving past them (the call re-scheduled them behind someone else,
     * CORE-04). Motion alone was the old test, and on a one-member list, or
     * when everyone else is further out, the person stays at the head after
     * a real call, so nothing was ever said. An unanswered dial logs an
     * ATTEMPT, which is not a connection (Enums.kt), so it is never thanked
     * for. Nothing is said if the call is not confirmed within
     * [CALL_ACK_WAIT_MS] (it did not connect, or call-log access is off),
     * because thanking someone for a call that didn't happen is worse than
     * silence; and an Error deck is not evidence either way.
     */
    private fun acknowledgeCallWhenConfirmed(contactId: Long, name: String?, dialedAt: Instant) {
        callAckJob?.cancel()
        callAckJob = viewModelScope.launch {
            val confirmed = withTimeoutOrNull(CALL_ACK_WAIT_MS) {
                uiState.first { state -> state.confirmsCall(contactId, dialedAt) }
            }
            if (confirmed != null) {
                val text = if (name != null) {
                    UiText.res(R.string.card_called_named, name)
                } else {
                    UiText.res(R.string.card_call_logged)
                }
                _messages.tryEmit(CardMessage.Called(text = text, contactId = contactId))
            }
        }
    }

    /** CARD-03's evidence test; see [acknowledgeCallWhenConfirmed]. */
    private fun CardViewUiState.confirmsCall(contactId: Long, dialedAt: Instant): Boolean = when (this) {
        CardViewUiState.Loading, is CardViewUiState.Error -> false
        is CardViewUiState.Ready ->
            this.contactId != contactId || (lastCallAt != null && !lastCallAt.isBefore(dialedAt))
        // The deck emptied behind the person: the call moved them on.
        is CardViewUiState.EmptyNoMembers, is CardViewUiState.EmptyNothingEligible -> true
    }

    /**
     * Snackbar "Undo" tap. Replays the inverse only when [token] is the newest
     * action's; an older snackbar's Undo (one the screen failed to replace in
     * time) is ignored rather than reverting a different person.
     */
    fun onUndo(token: Long) = viewModelScope.launch {
        if (token != undoToken) return@launch
        runMutation(UiText.res(R.string.card_undo_failed)) { undoStack.take()?.inverse?.invoke() }
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
     *
     * [label] is the snackbar's text and travels as [UiText] in [CardMessage]
     * (`UndoStack.PendingUndo` carries only the inverse).
     */
    private fun stageUndo(prior: List<ListMembershipEntity>, label: UiText) {
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
        undoStack.put(UndoStack.PendingUndo(inverse))
        val token = ++undoToken
        _messages.tryEmit(CardMessage.Undoable(text = label, token = token))
    }

    /** The first name of the person on the card, read before a mutation moves it on. */
    private fun firstNameOf(contactId: Long): String? =
        (uiState.value as? CardViewUiState.Ready)
            ?.takeIf { it.contactId == contactId }
            ?.contact?.name?.trim()?.substringBefore(' ')?.ifBlank { null }

    // CARD-02: the snackbar names the person and says when they come back, in
    // the app's two verbs for this, Later and Sooner (voice.md glossary). A
    // separate sentence for an unknown name, so no language has to fit "They"
    // into a slot meant for a name.
    private fun laterMessage(name: String?, newDue: Instant?): UiText {
        val `when` = newDue?.let { futureDueLabel(it, clock.now()) }
        return when {
            name != null && `when` != null -> UiText.res(R.string.card_later_named_when, name, `when`)
            name != null -> UiText.res(R.string.card_later_named, name)
            `when` != null -> UiText.res(R.string.card_later_unnamed_when, `when`)
            else -> UiText.res(R.string.card_later_unnamed)
        }
    }

    private fun soonerMessage(name: String?, newDue: Instant?): UiText {
        val `when` = newDue?.let { futureDueLabel(it, clock.now()) }
        return when {
            name != null && `when` != null -> UiText.res(R.string.card_sooner_named_when, name, `when`)
            name != null -> UiText.res(R.string.card_sooner_named, name)
            `when` != null -> UiText.res(R.string.card_sooner_unnamed_when, `when`)
            else -> UiText.res(R.string.card_sooner_unnamed)
        }
    }

    /**
     * H4-style uniform mutation wrapper — surfaces failures on the snackbar
     * instead of silently dropping them inside `viewModelScope.launch`.
     * CancellationException is rethrown so structured concurrency stays intact.
     */
    private suspend fun runMutation(failureLabel: UiText, block: suspend () -> Unit) {
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
        val listName = listEntity?.name.orEmpty()
        val listType = listEntity?.type ?: ListType.STATIC
        // CARD-07 / F-8: a failed read and the pre-emission placeholder carry
        // no data (CardSnapshot KDoc), so they are decided before the surface
        // is read.
        if (error != null) return CardViewUiState.Error(listName = listName)
        if (!loaded) return CardViewUiState.Loading
        val now = clock.now()
        return when (val s = surface) {
            SurfaceResult.NoMembers -> CardViewUiState.EmptyNoMembers(listName = listName, listType = listType)
            SurfaceResult.NothingEligible -> CardViewUiState.EmptyNothingEligible(
                upNextName = upNext?.displayName,
                upNextLabel = upNext?.let { futureDueLabel(it.dueAt, now) },
                listName = listName,
                listType = listType,
            )
            is SurfaceResult.Found -> {
                // Snapshot the local hour ONCE per emission for the HeatStrip
                // highlight. WR-02 — injected ZoneId.
                val nowHour = now.atZone(zoneId).hour
                val isAhead = s.nextDueAt.isAfter(now)
                // Connections only for "when you last spoke" and for CARD-03's
                // evidence: an ATTEMPT is a reach-out that did not connect
                // (Enums.kt), the same filter withCallStats applies to "Last
                // call", so the face never says "You spoke today" over a
                // voicemail.
                val connections = recentCalls.filter { it.source != CallSource.ATTEMPT }
                CardViewUiState.Ready(
                    contactId = s.contact.id,
                    contact = s.contact.toUiContact()
                        .withCallStats(recentCalls, now)
                        .withCallPatterns(recentCalls, zoneId),
                    listContext = listName,
                    queueSize = queueSize,
                    recentNotes = recentNotes.map { it.toNoteRow(now) },
                    nowHour = nowHour,
                    isAheadOfToday = isAhead,
                    whyNowLine = whyNowLine(connections, now),
                    listType = listType,
                    lastCallAt = connections.maxOfOrNull { it.occurredAt },
                )
            }
        }
    }

    /**
     * Honest one-line framing from the most recent connected call (manual
     * marks count, the user told us they talked): "You spoke 3 weeks ago."
     * ([cardWhySince]; Home's words for the same gap, so one idea has one
     * wording). Null when there is no history at all; the face then shows
     * only the neutral "Not enough calls yet to see a pattern" panel.
     */
    private fun whyNowLine(recentCalls: List<CallEventEntity>, now: Instant): UiText? {
        val lastCallAt = recentCalls
            .maxByOrNull { it.occurredAt }
            ?.occurredAt
            ?: return null
        val since = cardWhySince(Duration.between(lastCallAt, now).toDays())
        // Two short lines read better than one that wraps mid-phrase.
        val rhythm = rhythmSentence(recentCalls) ?: return since
        return UiText.res(R.string.card_why_two_lines, since, rhythm)
    }

    /**
     * CARD-04: the pair's own rhythm, so "why now" is about the two of them
     * rather than a statistic: "You usually talk about every 2 weeks." The
     * median gap between calls (robust to one long silence), and only once
     * there are four calls (three gaps), because a rhythm from two calls is
     * a guess. Stated as a fact, never as a deadline (voice.md: no guilt).
     */
    private fun rhythmSentence(recentCalls: List<CallEventEntity>): UiText? {
        val times = recentCalls.map { it.occurredAt }.distinct().sorted()
        if (times.size < 4) return null
        val gaps = times.zipWithNext { a, b -> Duration.between(a, b).toDays() }.filter { it > 0 }.sorted()
        if (gaps.size < 3) return null
        val median = gaps[gaps.size / 2]
        return when {
            median <= 1L -> UiText.res(R.string.card_rhythm_daily)
            median < 7L -> median.toInt().let { UiText.plural(R.plurals.card_rhythm_days, it, it) }
            median < 11L -> UiText.res(R.string.card_rhythm_weekly)
            median < 60L -> ((median + 3) / 7).toInt().let { UiText.plural(R.plurals.card_rhythm_weeks, it, it) }
            else -> ((median + 15) / 30).toInt().let { UiText.plural(R.plurals.card_rhythm_months, it, it) }
        }
    }

    /**
     * Forward-looking phrase for snackbars and the up-next hint:
     * "later today" / "tomorrow" / "on Tuesday" / "in 12 days" / "in 3 weeks"
     * / "in 2 months". Lowercase fragment so it slots mid-sentence (a nested
     * [UiText] argument of the snackbar and up-next sentences). The buckets
     * are [comesUp]'s, shared with Browse's rows (BROWSE-07).
     */
    internal fun futureDueLabel(due: Instant, now: Instant): UiText =
        when (val bucket = comesUp(due, now, zoneId)) {
            ComesUp.LaterToday -> UiText.res(R.string.card_due_later_today)
            ComesUp.Tomorrow -> UiText.res(R.string.card_due_tomorrow)
            is ComesUp.OnDay -> UiText.res(
                R.string.card_due_on_day,
                bucket.day.getDisplayName(TextStyle.FULL, Locale.getDefault()),
            )
            is ComesUp.InDays -> UiText.res(R.string.card_due_in_span, formatSpan(bucket.days))
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

/**
 * The card's "when you last spoke" line for a gap of [days] whole days: "You
 * spoke today.", "You spoke yesterday.", then "You spoke 3 days ago." / "You
 * spoke 3 weeks ago." with [formatAgo]'s one wording as the argument. Active
 * voice, the form voice.md gives ("You spoke yesterday"). Until 2026-10-06 the
 * span filled "%1$s since you last spoke.", which for the most common gaps
 * read "3 days since you last spoke.", the shame framing voice.md never says
 * and `VoiceRules` forbids ("days since"); the string audit reads resource
 * text, where the span is a placeholder, so it never saw the rendered line.
 * Top-level (not VM-private), like `resolvePickerPhase`, so
 * `WhyLineVoiceTest` can render it for every bucket without the feed.
 */
internal fun cardWhySince(days: Long): UiText = when (days.coerceAtLeast(0L)) {
    0L -> UiText.res(R.string.card_why_today)
    1L -> UiText.res(R.string.card_why_yesterday)
    else -> UiText.res(R.string.card_why_ago, formatAgo(days))
}
