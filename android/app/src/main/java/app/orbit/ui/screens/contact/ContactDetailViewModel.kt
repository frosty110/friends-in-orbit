package app.orbit.ui.screens.contact

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.data.CallEntry
import app.orbit.data.NoteRow
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.data.entity.RuleKind
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.data.mappers.toUiContact
import app.orbit.data.mappers.withCallStats
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.NoteRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.clock.Clock
import app.orbit.domain.model.PauseDuration
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.AddNoteUseCase
import app.orbit.domain.usecase.AddRetroactiveNoteUseCase
import app.orbit.domain.usecase.ArchiveContactUseCase
import app.orbit.domain.usecase.DeleteNoteUseCase
import app.orbit.domain.usecase.EditNoteUseCase
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.MarkCalledUseCase
import app.orbit.domain.usecase.PauseContactUseCase
import kotlinx.serialization.encodeToString
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.formatAbsolute
import app.orbit.ui.util.formatDuration
import app.orbit.ui.util.formatRelative
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ContactDetail ViewModel, with the Notes journaling surface (NOTE-01).
 *
 * Core surface:
 *   - injects [ListRepository] + [CallEventRepository] for memberships + history.
 *   - resolves `listsOn` (list-name strings) by joining membership.listId →
 *     [ListEntity.name].
 *   - emits up to 50 [CallEntry] rows from
 *     [CallEventRepository.observeForContact] — the push-down replaces
 *     the legacy `observeAll().filter { ... }.take(50)` shape.
 *   - branches on [ContactEntity.isOrphaned] → `Orphaned` vs. `Ready`.
 *
 * Notes widenings:
 *   - injects [AddNoteUseCase], [EditNoteUseCase], [DeleteNoteUseCase], and the
 *     [UndoStack] singleton so the snackbar's "Undo" tap can replay a
 *     deletion.
 *   - exposes a [draft] StateFlow so the input field's text lives on the VM
 *     (preserves across rotation).
 *   - exposes a [snackbarEvents] SharedFlow for the screen's
 *     `LaunchedEffect`/`collect` snackbar host.
 *   - replaces the inline [addNote] body with a call into [AddNoteUseCase];
 *     successful insert clears the draft.
 *
 * **B3 — DOM-01 Clock-injection invariant.** [toNoteRow] takes `now: Instant`
 * sourced from the injected [Clock] inside the combine block; the composable
 * (NotesSection) receives pre-formatted `relativeTimestamp` + `absoluteTimestamp`
 * String fields so it never touches the JVM time API.
 *
 * **B4 — chained typed combine.** The kotlinx.coroutines `combine` overload
 * tops out at five typed flow inputs. With 6+ flows, vararg `combine(*flows)`
 * forces an `Array<Any?>` cast that erases types; instead, we group the first
 * five flows into a typed tuple and chain a second `.combine(_draft)` to fold
 * in the draft state.
 *
 * Mapper [toUiCallEntry] continues to use [formatRelative] + [formatDuration]
 * from `app.orbit.ui.util.RelativeTime` — single source of truth shared with
 * the screen's CallHistoryRow render path.
 */
@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val callEventRepo: CallEventRepository,
    private val noteRepo: NoteRepository,
    private val pauseContact: PauseContactUseCase,
    private val addNoteUseCase: AddNoteUseCase,
    private val editNoteUseCase: EditNoteUseCase,
    private val deleteNoteUseCase: DeleteNoteUseCase,
    private val ignoreContactUseCase: IgnoreContactUseCase,
    private val archiveContactUseCase: ArchiveContactUseCase,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val addRetroactiveNoteUseCase: AddRetroactiveNoteUseCase,
    private val markCalledUseCase: MarkCalledUseCase,
    private val undoStack: UndoStack,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val contactIdString: String? = savedStateHandle["contactId"]
    private val contactId: Long? = contactIdString?.removePrefix("c-")?.toLongOrNull()

    // NOTE-02 — PostCallBanner deep-link: when true, the Notes input
    // should claim focus on first composition. The Routes.contactWithFocus
    // helper encodes the boolean as "1"; nullable / unset / "0" all read as
    // false. Owned by VM so rotation doesn't re-fire focus (init-only emit).
    private val focusNoteOnFirstShow: Boolean =
        savedStateHandle.get<String?>("focusNote") == "1"

    // LOG-03 — CallLog deep-link: when set, the screen scrolls its
    // body LazyColumn to the matching CallEvent row and renders an inline
    // "Add note to this call" Primary button below it. Encoded as a String
    // by the Routes.contactWithFocus helper to keep the Navigation Compose
    // arg surface uniform with focusNote; parsed back to Long here.
    private val scrollToCallEventId: Long? =
        savedStateHandle.get<String?>("scrollToCallEventId")?.toLongOrNull()

    private val _focusNoteEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val focusNoteEvent: SharedFlow<Unit> = _focusNoteEvent.asSharedFlow()

    private val contactSource: Flow<ContactEntity?> =
        if (contactId == null) flowOf(null) else contactRepo.observeById(contactId)

    private val membershipsSource: Flow<List<ListMembershipEntity>> =
        if (contactId == null) flowOf(emptyList())
        else listRepo.observeMembershipsForContact(contactId)

    // Contact-scoped recent events with explicit limit (50).
    // The DAO already filters by contactId and applies the LIMIT, so this read
    // is bounded to at most 50 rows for the focused contact. Replaces the
    // legacy `callEventRepo.observeAll().map { it.filter { ... }.take(50) }`
    // shape that pulled the entire `call_events` table on every emission.
    private val recentEventsSource: Flow<List<CallEventEntity>> =
        if (contactId == null) flowOf(emptyList())
        else callEventRepo.observeForContact(contactId, limit = RECENT_EVENTS_LIMIT)

    private val notesSource: Flow<List<NoteEntity>> =
        if (contactId == null) flowOf(emptyList()) else noteRepo.observeByContactId(contactId)

    // Notes input draft + snackbar event surface.
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    // Ephemeral "override editor open" flag. Opening the editor
    // is a read-only peek; nothing persists until the user actually changes a
    // value ([onSaveOverride]). Previously [onOpenOverride] wrote a default
    // RuleParams to `Contact.ruleOverrideJson` on open — peek was a mutation
    // with no undo. VM-scoped (not SavedStateHandle): an open-but-untouched
    // editor is not worth resurrecting across process death.
    private val _overrideEditorOpen = MutableStateFlow(false)

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /**
     * CONTACT-06 — one-shot navigation events. The screen's `LaunchedEffect`
     * collects this flow and routes [NavEvent.RelinkPicker] to
     * `Routes.pickContacts(contactId, mode = "relink")`. SharedFlow with
     * `extraBufferCapacity = 1` mirrors [_focusNoteEvent] semantics so a
     * late-collecting screen still receives the event.
     */
    sealed interface NavEvent {
        /** Open the contact picker filtered to phone-contact relink candidates. */
        data class RelinkPicker(val contactId: Long) : NavEvent
    }
    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    init {
        // NOTE-02 — emit the focus signal exactly once at VM construction
        // when the deep-link arg is set. SharedFlow's replay = 0 + extra
        // buffer = 1 means a late-collecting screen (compose-then-collect
        // ordering) still receives the event; rotation creates a new VM
        // instance only if SavedStateHandle still carries the arg, which is
        // intentional (the deep link is a one-shot intent — re-rotating the
        // screen 5 minutes later shouldn't re-focus the input).
        if (focusNoteOnFirstShow) _focusNoteEvent.tryEmit(Unit)
    }

    /**
     * B4 — typed five-tuple folded by the first combine; the chained
     * `.combine(_draft)` lifts the draft into the same recomposition family
     * without Array<Any?> casts.
     */
    private data class FiveTuple(
        val entity: ContactEntity?,
        val memberships: List<ListMembershipEntity>,
        val events: List<CallEventEntity>,
        val allLists: List<ListEntity>,
        val noteEntities: List<NoteEntity>,
    )

    /**
     * Intermediate fold of the FiveTuple + draft so a
     * second `.combine(ruleTemplateRepo.observeAll())` can lift the templates
     * list into the same recomposition family without a 7-arg combine call
     * (which would force `Array<Any?>` casts — see [FiveTuple] KDoc).
     * Also folds the ephemeral [_overrideEditorOpen] flag in the same
     * chained-combine shape.
     */
    private data class SixTuple(
        val tuple: FiveTuple,
        val draft: String,
        val overrideEditorOpen: Boolean = false,
    )

    val uiState: StateFlow<ContactDetailUiState> =
        combine(
            contactSource,
            membershipsSource,
            recentEventsSource,
            listRepo.observeAll(),
            notesSource,
        ) { entity, memberships, events, allLists, noteEntities ->
            FiveTuple(entity, memberships, events, allLists, noteEntities)
        }.combine(_draft) { tuple, draftStr ->
            SixTuple(tuple, draftStr)
        }.combine(_overrideEditorOpen) { six, editorOpen ->
            six.copy(overrideEditorOpen = editorOpen)
        }.combine(ruleTemplateRepo.observeAll()) { six, templates ->
            val tuple = six.tuple
            val draftStr = six.draft
            val entity = tuple.entity ?: return@combine ContactDetailUiState.NotFound

            // B3 — single Clock read per emission; passed into both the call
            // mapper and the note mapper so derivations share one "now".
            val now = clock.now()

            val listsOn = tuple.memberships
                .mapNotNull { m -> tuple.allLists.firstOrNull { it.id == m.listId }?.name }
            val recentCalls = tuple.events.map { it.toUiCallEntry(now) }
            // LOG-03 — parallel-indexed call-event ids for the screen's
            // scroll-to + retro-note affordance lookup. Same order as
            // `recentCalls` above (DESC by occurredAt).
            val recentCallEventIds = tuple.events.map { it.id }
            // Manual-log surface — parallel-indexed MANUAL flags so the
            // history row can render "Logged" + a distinct icon without
            // widening the CallEntry shape (same rationale as
            // recentCallEventIds above).
            val recentCallIsManual = tuple.events.map { it.source == CallSource.MANUAL }
            // Attempt surface — parallel-indexed with recentCalls; reach-outs
            // that didn't connect render "Attempted" + a phone-slash icon.
            val recentCallIsAttempt = tuple.events.map { it.source == CallSource.ATTEMPT }
            val longestGapLabel = computeLongestGap(tuple.events)
            val noteRows = tuple.noteEntities.map { it.toNoteRow(now) }

            // CONTACT-05 — derive UnpauseBanner visibility.
            // True iff pausedUntil has lapsed AND it's NOT the indefinite
            // sentinel. Indefinite pauses ("until I unpause") are user-explicit
            // and never auto-expire; clearing them goes through the overflow
            // sheet flow, not the banner.
            val unpausePromptVisible = entity.pausedUntil?.let { pu ->
                pu <= now && !PauseContactUseCase.isIndefinite(pu)
            } ?: false

            // CONTACT-03 — derive RuleOverrideSection
            // inputs. Corrupted-JSON recovery is the try/catch
            // around decodeFromString; failed decode flips currentParams to
            // null and currentTemplateName to "Custom schedule (recovering)".
            val customScheduleVisible = listsOn.size >= 2
            // The editor branch renders when an override is
            // PERSISTED or the user peeked the editor open this session.
            // Opening alone persists nothing (see onOpenOverride).
            val hasOverride = entity.ruleOverrideJson != null || six.overrideEditorOpen
            val (currentTemplateName, currentParams) = deriveOverrideDisplay(
                ruleOverrideJson = entity.ruleOverrideJson,
                memberships = tuple.memberships,
                allLists = tuple.allLists,
                templates = templates,
            )
            val primaryListName = tuple.memberships.firstOrNull()?.listId?.let { lid ->
                tuple.allLists.firstOrNull { it.id == lid }?.name
            } ?: ""

            // Overlay call-derived stats — `lastCalledLabel`, `totalCalls`,
            // `avgLengthLabel` — onto the placeholder mapper output. Without
            // this overlay the Stats panel reads "Never called / 0 / —" even
            // for contacts with a populated call history (CallEventEntity
            // rows existed for the contact but the bare toUiContact mapper
            // emitted placeholders).
            val contactWithStats = entity.toUiContact().withCallStats(tuple.events, now)

            if (entity.isOrphaned) {
                ContactDetailUiState.Orphaned(
                    contact = contactWithStats,
                    listsOn = listsOn,
                    recentCalls = recentCalls,
                    longestGapLabel = longestGapLabel,
                    recentCallIsManual = recentCallIsManual,
                    recentCallIsAttempt = recentCallIsAttempt,
                )
            } else {
                ContactDetailUiState.Ready(
                    contact = contactWithStats,
                    notes = noteRows,
                    listsOn = listsOn,
                    recentCalls = recentCalls,
                    longestGapLabel = longestGapLabel,
                    draft = draftStr,
                    unpausePromptVisible = unpausePromptVisible,
                    customScheduleVisible = customScheduleVisible,
                    currentTemplateName = currentTemplateName,
                    primaryListName = primaryListName,
                    hasOverride = hasOverride,
                    currentParams = currentParams,
                    // LOG-03 — CallLog deep-link surface. Both fields carry the
                    // same id by default — scrolling and showing the affordance
                    // are coupled signals. A future variant could decouple them
                    // (e.g., scroll without affordance) but the user
                    // story is single-purpose: tap row → arrive scrolled with
                    // a one-tap retro-note path.
                    scrollToCallEventId = scrollToCallEventId,
                    retroNoteAffordanceFor = scrollToCallEventId,
                    recentCallEventIds = recentCallEventIds,
                    recentCallIsManual = recentCallIsManual,
                    recentCallIsAttempt = recentCallIsAttempt,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ContactDetailUiState.Loading,
        )

    /**
     * CONTACT-03 — resolves the (template-name, RuleParams) pair driving the
     * RuleOverrideSection copy.
     *
     * Three branches:
     *   1. Override exists + decodes cleanly → ("Keep in touch" / "Late
     *      night" / "Energize" via [labelForKind], decoded RuleParams).
     *   2. Override exists + decode throws (corrupted JSON) →
     *      ("Custom schedule (recovering)", null). The screen passes a fresh
     *      default RuleParams so the editor still renders.
     *   3. No override → (template name from primary list, null params).
     */
    private fun deriveOverrideDisplay(
        ruleOverrideJson: String?,
        memberships: List<ListMembershipEntity>,
        allLists: List<ListEntity>,
        templates: List<RuleTemplateEntity>,
    ): Pair<String, RuleParams?> {
        if (ruleOverrideJson != null) {
            return try {
                val params = JsonProvider.json.decodeFromString<RuleParams>(ruleOverrideJson)
                Pair(labelForKind(params.toRuleKind()), params)
            } catch (_: Throwable) {
                Pair("Custom schedule (recovering)", null)
            }
        }
        // No per-contact override — show the template inherited from the
        // primary list. The "primary" list is the first membership row;
        // the RoundRobinEngine treats memberships as ordered so this
        // matches the surfacing path's notion of "first".
        val primaryListId = memberships.firstOrNull()?.listId
        val primaryList = primaryListId?.let { lid -> allLists.firstOrNull { it.id == lid } }
        val templateId = primaryList?.ruleTemplateId
        val templateKind = templateId?.let { tid -> templates.firstOrNull { it.id == tid } }?.kind
        val name = templateKind?.let(::labelForKind) ?: "Keep in touch"
        return Pair(name, null)
    }

    private fun RuleParams.toRuleKind(): RuleKind = when (this) {
        is RuleParams.KeepInTouch -> RuleKind.KEEP_IN_TOUCH
        is RuleParams.LateNight -> RuleKind.LATE_NIGHT
        is RuleParams.Energize -> RuleKind.ENERGIZE
    }

    private fun labelForKind(kind: RuleKind): String = when (kind) {
        RuleKind.KEEP_IN_TOUCH -> "Keep in touch"
        RuleKind.LATE_NIGHT -> "Late night"
        RuleKind.ENERGIZE -> "Energize"
    }

    /** Longest gap between consecutive call events. "—" if < 2 events. */
    private fun computeLongestGap(events: List<CallEventEntity>): String {
        if (events.size < 2) return "—"
        val sorted = events.sortedBy { it.occurredAt }
        var maxDays = 0L
        for (i in 1 until sorted.size) {
            val days = Duration.between(sorted[i - 1].occurredAt, sorted[i].occurredAt).toDays()
            if (days > maxDays) maxDays = days
        }
        return if (maxDays == 0L) "—" else "$maxDays days"
    }

    /**
     * Concrete CallEventEntity → UI CallEntry mapper. Ground truth (Model.kt:39):
     *   `data class CallEntry(direction, relativeWhen, lengthLabel)`
     * Earlier drafts used `kotlin.TODO()` here — that crashed the moment a user
     * opened a contact with any history. Lifted formatters live in
     * `app.orbit.ui.util.RelativeTime` so VM and screen share one source.
     */
    private fun CallEventEntity.toUiCallEntry(now: Instant = Instant.now()): CallEntry =
        CallEntry(
            // Entity enum (OUTGOING / INCOMING) → UI enum (Outgoing / Incoming).
            // Two enums are deliberate: entity layer follows SQL-style upper case,
            // UI layer follows Kotlin convention. Map at the seam.
            direction = when (this.direction) {
                app.orbit.data.entity.CallDirection.OUTGOING -> app.orbit.data.CallDirection.Outgoing
                app.orbit.data.entity.CallDirection.INCOMING -> app.orbit.data.CallDirection.Incoming
            },
            relativeWhen = formatRelative(this.occurredAt, now),
            lengthLabel = formatDuration(this.durationSeconds),
        )

    /**
     * NOTE-01 — adds a note via [AddNoteUseCase]; clears the draft on a
     * successful insert (use case returns null for blank/empty bodies).
     */
    fun addNote(body: String) {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation("Couldn't add note") {
                val rowId = addNoteUseCase(cid, body)
                if (rowId != null) _draft.value = ""
            }
        }
    }

    /** NOTE-01 — input draft change. */
    fun onDraftChange(newDraft: String) {
        _draft.value = newDraft
    }

    /**
     * LOG-03 — back-dated retroactive note. The user lands here from
     * CallLogScreen via [Routes.contactWithFocus] with `scrollToCallEventId`
     * set; the inline "Add note to this call" button hands a body string to
     * this method along with the event id.
     *
     * **O(1) primary-key lookup via [CallEventRepository.byId].** The byId
     * surface exists specifically for this access pattern; an
     * earlier draft routed through a snapshot of the call-event table to
     * resolve `callEventId`, which scaled O(N) with call-event count. The
     * bulk `observeAll()` sentinel was deleted entirely — byId is now
     * the only correct path for single-row lookup.
     *
     * The use case silently back-dates `createdAt` to `event.occurredAt`
     * (locked, no confirmation dialog). The note
     * shows up in NotesSection with a relative timestamp matching the
     * call's age ("called 14 min ago") rather than "just now" — that's the
     * visual cue that disambiguates retroactive vs live notes.
     */
    fun onAddRetroactiveNote(callEventId: Long, body: String) {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation("Couldn't add note") {
                // O(1) primary-key lookup. NOT observeAll().first().
                val event = callEventRepo.byId(callEventId) ?: return@runMutation
                val rowId = addRetroactiveNoteUseCase(cid, body, event.occurredAt)
                if (rowId != null) _snackbarEvents.tryEmit(SnackbarEvent("Note saved", null))
            }
        }
    }

    /**
     * Manual connection / attempt log — records something Orbit's call-log sync
     * can't see as a [CallEventEntity] with `durationSeconds = 0`.
     *
     * When [isAttempt] is false the event is a connection (`source = MANUAL`) —
     * another app, in person — and resets the full template cadence. When true
     * it is a reach-out that didn't connect (`source = ATTEMPT` — voicemail / no
     * answer) and advances the rotation by only the flat [app.orbit.domain.rule.AttemptCooldown]
     * window, without claiming you actually talked (it never sets "last
     * contacted" or feeds heat — see ContactMapper.withCallStats).
     *
     * Routes through [MarkCalledUseCase] — the same atomic path the call-log
     * reconciler uses — so per-list `nextDueAt` recomputes for every list the
     * contact is on (DOM-06 cross-list propagation). The engines treat MANUAL
     * as "not a real call" for the short-call/incoming *adjustments* only;
     * the base cooldown still keys off `occurredAt`, so the contact stops
     * surfacing as due.
     *
     * `whenChoice` time resolution (single `clock.now()` read — B3):
     *   - [LogConnectionWhen.Today] → now.
     *   - [LogConnectionWhen.Yesterday] → now minus 24h.
     *   - [LogConnectionWhen.OnDate] → the picker hands back UTC midnight of
     *     the chosen calendar day; we pin to local noon so the event lands on
     *     the chosen day in every timezone, then clamp to now (authoritative
     *     no-future-events guard — the sheet's date bound is advisory).
     *
     * A non-blank note is attached via [AddRetroactiveNoteUseCase] back-dated
     * to the same `occurredAt`, mirroring the LOG-03 retro-note convention.
     * The screen's state refreshes by itself — [recentEventsSource] is a Room
     * flow that re-emits on insert.
     */
    fun onLogConnection(whenChoice: LogConnectionWhen, note: String, isAttempt: Boolean = false) {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation("Couldn't log that") {
                val now = clock.now()
                val occurredAt: Instant = when (whenChoice) {
                    LogConnectionWhen.Today -> now
                    LogConnectionWhen.Yesterday -> now.minus(Duration.ofDays(1))
                    is LogConnectionWhen.OnDate -> Instant
                        .ofEpochMilli(whenChoice.utcMidnightMillis)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .atTime(12, 0)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .coerceAtMost(now)
                }
                val event = CallEventEntity(
                    contactId = cid,
                    occurredAt = occurredAt,
                    // OUTGOING is the closest fit — the user reached out (or
                    // met up). Engines ignore direction for MANUAL/ATTEMPT
                    // anyway (isRealCall gate / attempt short-circuit).
                    direction = app.orbit.data.entity.CallDirection.OUTGOING,
                    durationSeconds = 0,
                    source = if (isAttempt) CallSource.ATTEMPT else CallSource.MANUAL,
                )
                markCalledUseCase(cid, event)
                if (note.isNotBlank()) {
                    addRetroactiveNoteUseCase(cid, note.trim(), occurredAt)
                }
                _snackbarEvents.tryEmit(SnackbarEvent(if (isAttempt) "Attempt logged." else "Logged."))
            }
        }
    }

    /**
     * NOTE-01 — swipe-to-delete with snackbar undo. Pushes the use case's
     * inverse onto [UndoStack] (depth-1) and emits a SnackbarEvent so the
     * screen can show "Note deleted · Undo".
     */
    fun onDeleteNote(noteRow: NoteRow) {
        val noteEntity = NoteEntity(
            id = noteRow.id,
            contactId = noteRow.contactId,
            body = noteRow.body,
            createdAt = Instant.ofEpochMilli(noteRow.createdAtMs),
        )
        viewModelScope.launch {
            runMutation("Couldn't delete note") {
                val result = deleteNoteUseCase(noteEntity)
                undoStack.put(UndoStack.PendingUndo(result.inverse, result.label))
                _snackbarEvents.tryEmit(SnackbarEvent(result.label, "Undo"))
            }
        }
    }

    /** NOTE-01 — long-press inline edit commit. */
    fun onEditNote(noteRow: NoteRow, newBody: String) {
        val updated = NoteEntity(
            id = noteRow.id,
            contactId = noteRow.contactId,
            body = newBody.trim(),
            createdAt = Instant.ofEpochMilli(noteRow.createdAtMs),
        )
        viewModelScope.launch {
            runMutation("Couldn't update note") { editNoteUseCase(updated) }
        }
    }

    /** Snackbar "Undo" tap — pops UndoStack and runs the inverse closure. */
    fun onUndo() = viewModelScope.launch {
        runMutation("Couldn't undo") { undoStack.take()?.inverse?.invoke() }
    }

    /**
     * IGNORE-02 — single-contact ignore via [ignoreContactUseCase] with the
     * 4-second snackbar undo affordance. The use case returns its
     * inverse closure (clears the four ignore columns); pushing it onto
     * [UndoStack] is what makes the snackbar's Undo tap atomic — see
     * [onUndo] for the inverse playback path.
     */
    fun onIgnore(contactName: String) {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation("Couldn't ignore $contactName") {
                val result = ignoreContactUseCase(cid, contactName)
                undoStack.put(UndoStack.PendingUndo(result.inverse, result.label))
                _snackbarEvents.tryEmit(SnackbarEvent(result.label, "Undo"))
            }
        }
    }

    /**
     * CONTACT-04 — commits a pause via [pauseContact] and emits a snackbar
     * with Undo. The earlier hook only wrote `pausedUntil`; this wraps
     * that with the snackbar event the screen's `LaunchedEffect` collector
     * needs. The inverse closure clears `pausedUntil` (mirrors
     * [onUnpauseContact] semantics — there is no companion UnpauseUseCase
     * for the single-contact path; the repo setter is the inverse).
     */
    fun onPauseContact(duration: PauseDuration) {
        val cid = contactId ?: return
        val displayName = (uiState.value as? ContactDetailUiState.Ready)?.contact?.name ?: "Contact"
        val labelSuffix = when (duration) {
            PauseDuration.OneWeek -> "for 1 week"
            PauseDuration.OneMonth -> "for 1 month"
            PauseDuration.Indefinite -> "indefinitely"
        }
        viewModelScope.launch {
            runMutation("Couldn't pause $displayName") {
                pauseContact(cid, duration)
                val inverse: suspend () -> Unit = { contactRepo.setPausedUntil(cid, null) }
                val label = "Paused $displayName $labelSuffix"
                undoStack.put(UndoStack.PendingUndo(inverse, label))
                _snackbarEvents.tryEmit(SnackbarEvent(label, "Undo"))
            }
        }
    }

    fun onUnpauseContact() {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation("Couldn't unpause") { contactRepo.setPausedUntil(cid, null) }
        }
    }

    /**
     * CONTACT-06 — Re-link tap on the OrphanBanner. Emits a one-shot
     * [NavEvent.RelinkPicker] so the screen's NavHost-side caller can navigate
     * to `Routes.pickContacts(contactId, mode = "relink")`.
     *
     * **Deferral:** the picker treats `mode=relink` as the
     * default `Add` fall-through today; future work extends
     * [app.orbit.ui.screens.picker.ContactPickerViewModel] with relink-mode
     * filter behavior (e.g., hide already-tracked contacts). The
     * navigation-layer wiring is in place; the picker filter is the
     * cosmetic deferral.
     */
    fun onRelink() {
        val cid = contactId ?: return
        _navEvents.tryEmit(NavEvent.RelinkPicker(cid))
    }

    /**
     * CONTACT-06 — Archive tap on the OrphanBanner. Calls
     * [archiveContactUseCase], pushes the inverse closure onto [undoStack],
     * and emits a SnackbarEvent so the screen renders "Archived {Name} ·
     * Undo".
     *
     * The archive invariant lives in the use case, not here — this VM method
     * just dispatches and stages the inverse for the snackbar Undo tap.
     */
    fun onArchive(contactName: String) {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation("Couldn't archive $contactName") {
                val result = archiveContactUseCase(cid, contactName)
                undoStack.put(UndoStack.PendingUndo(result.inverse, result.label))
                _snackbarEvents.tryEmit(SnackbarEvent(result.label, "Undo"))
            }
        }
    }

    /**
     * CONTACT-03 — opens the per-contact override editor. Opening
     * is READ-ONLY. The flag flips `Ready.hasOverride` so the screen renders
     * the editor branch on default values, but nothing touches
     * `Contact.ruleOverrideJson` until the user actually changes a value —
     * an actual change fires [onSaveOverride] (kind switch or slider commit
     * through [RuleParams.KeepInTouch.withIntervalHours], see
     * RuleOverrideSection). Previously this method persisted a default
     * override on open: a peek was a mutation with no undo.
     */
    fun onOpenOverride() {
        _overrideEditorOpen.value = true
    }

    /**
     * CONTACT-03 — save-on-change handler from [RuleOverrideSection]. Encodes
     * the new RuleParams via [JsonProvider.json] (which uses
     * `classDiscriminator = "type"` so the OverrideResolver decode in
     * [app.orbit.domain.rule.resolveParamsFor] can reconstruct the subtype
     * without an envelope).
     */
    fun onSaveOverride(newParams: RuleParams) {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation {
                val json = JsonProvider.json.encodeToString(newParams)
                contactRepo.setRuleOverrideJson(cid, json)
            }
        }
    }

    /**
     * CONTACT-03 — clears the per-contact override. Passing null to the
     * setter wipes the column; on the next emission the section flips back
     * to the no-override branch ("Inherits {template} from {primary list}").
     *
     * Also covers the corrupted-JSON recovery path: when decode
     * fails the user sees "Custom schedule (recovering)" + the editor
     * primed with default RuleParams; tapping Reset to default here clears
     * the corrupted column without forcing the user to overwrite it.
     */
    fun onClearOverride() {
        val cid = contactId ?: return
        // Also close a peeked-open editor so the section flips
        // back to the inherit branch even when nothing was ever persisted.
        _overrideEditorOpen.value = false
        viewModelScope.launch {
            runMutation { contactRepo.setRuleOverrideJson(cid, null) }
        }
    }

    /**
     * H4 fix — wraps a mutation block with a uniform try/catch + snackbar
     * surface. Without this, an exception inside `viewModelScope.launch` is
     * silently dropped and the UI shows stale optimistic state.
     * `CancellationException` is rethrown so structured concurrency
     * cancellation still propagates correctly when the screen leaves the back
     * stack.
     */
    private suspend fun runMutation(
        failureLabel: String = "Couldn't save your change",
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snackbarEvents.tryEmit(SnackbarEvent(failureLabel))
        }
    }

    /**
     * B3 — Clock-aware mapper. `now` flows in from the combine's `clock.now()`
     * call so the relative timestamp is deterministic under TestClock and the
     * NotesSection composable never touches the JVM time API.
     */
    private fun NoteEntity.toNoteRow(now: Instant): NoteRow = NoteRow(
        id = id,
        contactId = contactId,
        body = body,
        createdAtMs = createdAt.toEpochMilli(),
        relativeTimestamp = formatRelative(createdAt, now),
        absoluteTimestamp = formatAbsolute(createdAt),
    )

    private companion object {
        /**
         * At most 50 recent events for the focused contact. The
         * value matches the historical client-side `.take(50)` cap that the
         * legacy `observeAll().filter { ... }.take(50)` shape applied; the
         * difference is that the LIMIT now lives in the DAO @Query so we
         * read 50 rows, not the whole table.
         */
        private const val RECENT_EVENTS_LIMIT: Int = 50
    }
}

/**
 * "When did you connect?" choice handed from the Log-connection sheet to
 * [ContactDetailViewModel.onLogConnection]. Plain data — lives beside the VM
 * (not under sections/) so the VM never imports from a composable package.
 *
 * [OnDate.utcMidnightMillis] is the raw Material DatePicker selection — UTC
 * midnight of the chosen calendar day. The VM converts it to a local-noon
 * Instant; the sheet never touches java.time "now".
 */
sealed interface LogConnectionWhen {
    data object Today : LogConnectionWhen
    data object Yesterday : LogConnectionWhen
    data class OnDate(val utcMidnightMillis: Long) : LogConnectionWhen
}
