package app.orbit.ui.screens.card

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.NoteRow
import app.orbit.data.entity.CallDirection
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
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
import app.orbit.domain.usecase.MarkCalledUseCase
import app.orbit.domain.usecase.SkipContactUseCase
import app.orbit.domain.usecase.SurfaceResult
import app.orbit.domain.usecase.SurfaceSoonerUseCase
import app.orbit.ui.screens.picker.SnackbarEvent
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *   - **Call follow-through.** `onCall` remembers the dialed contact; when
 *     the screen resumes from the dialer, [callPrompt] surfaces a one-tap
 *     "Talked to {name}? Mark it" affordance that records a manual call via
 *     [MarkCalledUseCase] (CallSource.MANUAL, zero duration — the engines
 *     deliberately treat manual marks as full-cooldown calls).
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
    private val markCalled: MarkCalledUseCase,
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

    /** Swipe-commit + mark-called acknowledgments; screen hosts the snackbar. */
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /**
     * "Talked to {firstName}? Mark it" affordance state. Non-null only after
     * the user dialed from this screen AND the screen has resumed from the
     * dialer ([onReturnedFromDial]). Dismiss / mark both clear it.
     */
    data class CallPrompt(val contactId: Long, val firstName: String)

    private val _callPrompt = MutableStateFlow<CallPrompt?>(null)
    val callPrompt: StateFlow<CallPrompt?> = _callPrompt.asStateFlow()

    // Dial-in-flight marker: set on tap-to-call, promoted to [_callPrompt]
    // on the next ON_RESUME. Plain field — no UI reads it directly.
    private var pendingDial: CallPrompt? = null

    /** CORE-04 — left swipe defers this contact on the current list. */
    fun onSwipeLeft(contactId: Long) = viewModelScope.launch {
        runMutation("Couldn't defer that") {
            val prior = captureSchedule(contactId)
            skipContact(contactId = contactId, listId = listId)
            val newDue = focusedDueAfterMutation(contactId)
            stageUndo(prior, label = deferredMessage(newDue))
        }
    }

    /** CORE-03 — right swipe brings this contact forward on the current list. */
    fun onSwipeRight(contactId: Long) = viewModelScope.launch {
        runMutation("Couldn't move that up") {
            val prior = captureSchedule(contactId)
            surfaceSooner(contactId = contactId, listId = listId)
            val newDue = focusedDueAfterMutation(contactId)
            stageUndo(prior, label = movedUpMessage(newDue))
        }
    }

    /**
     * CORE-02 follow-through — the screen dials, then tells us which contact
     * left for the dialer. The prompt itself only appears once the screen
     * resumes ([onReturnedFromDial]); until then nothing changes on the card.
     */
    fun onCall(contactId: Long) {
        val name = (uiState.value as? CardViewUiState.Ready)
            ?.takeIf { it.contactId == contactId }
            ?.contact?.name
            ?: return
        pendingDial = CallPrompt(contactId, name.substringBefore(' '))
    }

    /**
     * Screen-side ON_RESUME hook. The first resume after a dial promotes the
     * pending marker to a visible prompt; resumes with nothing pending are
     * no-ops (including the screen's very first composition).
     *
     * CORE-04 (call-detection latency) — returning here means the user just
     * left for the dialer and came back, the single most likely moment a new
     * completed call exists. Kick an immediate INCREMENTAL call-log sync
     * (expedited, no debounce) so the deck advances within ~1-2s rather than
     * waiting on the 10s-debounced content observer or the TTL-gated resume
     * sync — the latter is suppressed precisely in this flow (foreground →
     * dial → return all happen inside its 60s window). The sync is cheap (reads
     * only rows since the last-sync watermark) and idempotent; if the call is
     * still in progress (no call-log row yet) it is a no-op and the observer
     * picks it up on hang-up. The "Mark it" prompt remains as the manual path.
     */
    fun onReturnedFromDial() {
        pendingDial?.let {
            _callPrompt.value = it
            pendingDial = null
            callLogResync.enqueueImmediateSync(fullResync = false)
        }
    }

    /** Quiet dismiss — the user didn't talk, or doesn't want to mark it. */
    fun onDismissCallPrompt() {
        _callPrompt.value = null
    }

    /**
     * One-tap manual mark from the post-dial prompt. CallSource.MANUAL +
     * zero duration is the engines' designed shape for an unverified mark —
     * full cooldown applies, no short-call reduction (KeepInTouchEngine
     * `isRealCall` filter). The card advances on the resulting emission.
     */
    fun onMarkTalked() {
        val prompt = _callPrompt.value ?: return
        _callPrompt.value = null
        viewModelScope.launch {
            runMutation("Couldn't mark that call") {
                markCalled(
                    contactId = prompt.contactId,
                    callEvent = CallEventEntity(
                        contactId = prompt.contactId,
                        occurredAt = clock.now(),
                        direction = CallDirection.OUTGOING,
                        durationSeconds = 0,
                        source = CallSource.MANUAL,
                    ),
                )
                _snackbarEvents.tryEmit(SnackbarEvent("Marked — talked to ${prompt.firstName}"))
            }
        }
    }

    /** Snackbar "Undo" tap — pop [UndoStack] and replay the inverse closure. */
    fun onUndo() = viewModelScope.launch {
        runMutation("Couldn't undo") { undoStack.take()?.inverse?.invoke() }
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
        _snackbarEvents.tryEmit(SnackbarEvent(label, "Undo"))
    }

    private fun deferredMessage(newDue: Instant?): String =
        newDue?.let { "Deferred — back ${futureDueLabel(it, clock.now())}" } ?: "Deferred"

    private fun movedUpMessage(newDue: Instant?): String =
        newDue?.let { "Moved up — due ${futureDueLabel(it, clock.now())}" } ?: "Moved up"

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
            _snackbarEvents.tryEmit(SnackbarEvent(failureLabel))
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
        return when {
            days == 0L -> "You talked today."
            days == 1L -> "You talked yesterday."
            days < 14L -> "It's been $days days."
            days < 60L -> "It's been ${days / 7} weeks."
            else -> "It's been ${days / 30} months."
        }
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
