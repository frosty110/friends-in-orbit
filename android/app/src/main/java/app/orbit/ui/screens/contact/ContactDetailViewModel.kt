package app.orbit.ui.screens.contact

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.CallEntry
import app.orbit.data.NoteRow
import app.orbit.data.entity.CallEventEntity
import app.orbit.data.entity.CallSource
import app.orbit.data.entity.ContactEntity
import app.orbit.data.entity.ListEntity
import app.orbit.data.entity.ListMembershipEntity
import app.orbit.data.entity.NoteEntity
import app.orbit.data.entity.RuleTemplateEntity
import app.orbit.data.mappers.toUiContact
import app.orbit.data.mappers.withCallPatterns
import app.orbit.data.mappers.withCallStats
import app.orbit.data.repository.CallEventRepository
import app.orbit.data.repository.ContactRepository
import app.orbit.data.repository.ListRepository
import app.orbit.data.repository.NoteRepository
import app.orbit.data.repository.RuleTemplateRepository
import app.orbit.domain.JsonProvider
import app.orbit.domain.WidgetRefreshTrigger
import app.orbit.domain.clock.Clock
import app.orbit.domain.model.PauseDuration
import app.orbit.domain.rule.RuleParams
import app.orbit.domain.rule.baseIntervalHours
import app.orbit.domain.undo.UndoStack
import app.orbit.domain.usecase.AddNoteUseCase
import app.orbit.domain.usecase.AddRetroactiveNoteUseCase
import app.orbit.domain.usecase.ArchiveContactUseCase
import app.orbit.domain.usecase.DeleteNoteUseCase
import app.orbit.domain.usecase.EditNoteUseCase
import app.orbit.domain.usecase.IgnoreContactUseCase
import app.orbit.domain.usecase.LogConnectionUseCase
import app.orbit.domain.usecase.LogConnectionWhen
import app.orbit.domain.usecase.PauseContactUseCase
import app.orbit.domain.usecase.UnignoreContactUseCase
import app.orbit.ui.screens.lists.RuleParamsResolution
import app.orbit.ui.screens.lists.resolveRuleParams
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.UiText
import app.orbit.ui.util.formatAbsolute
import app.orbit.ui.util.formatDuration
import app.orbit.ui.util.formatRelative
import app.orbit.ui.util.pausedSnackbar
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

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
 *
 * Every word this VM produces (labels in [ContactDetailUiState], snackbar
 * events) is [UiText] from strings_contact.xml or a shared resource: the VM
 * holds no Context, the screen resolves (UX rubric 3.4).
 *
 * READ_CALL_LOG is read by the screen on every resume and pushed in through
 * [onCallLogPermissionChanged] (ARCH-04: no permission seam in the VM, the
 * Browse and Call history precedent), so the JVM tests drive the denied state
 * directly.
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
    private val unignoreContactUseCase: UnignoreContactUseCase,
    private val archiveContactUseCase: ArchiveContactUseCase,
    private val ruleTemplateRepo: RuleTemplateRepository,
    private val addRetroactiveNoteUseCase: AddRetroactiveNoteUseCase,
    // CONTACT-09 / CARD-10: the one way to log a connection, shared with the
    // card (it was MarkCalledUseCase plus this VM's own body until 2026-10-07).
    private val logConnection: LogConnectionUseCase,
    private val undoStack: UndoStack,
    private val clock: Clock,
    // Buckets call times into day-parts for the "Usually" stat; injected (as on
    // CardViewViewModel) so tests pin the zone instead of inheriting the host's.
    private val zoneId: ZoneId,
    savedStateHandle: SavedStateHandle,
    // WIDGET-06: the widget must stop offering someone the user just paused,
    // and offer them again once unpaused, before the hourly sweep. Trailing
    // with a no-op default so JVM fixtures construct the VM unchanged (the
    // IgnoreContactUseCase precedent).
    private val widgetRefreshTrigger: WidgetRefreshTrigger = WidgetRefreshTrigger { }
) : ViewModel() {

    private val contactIdString: String? = savedStateHandle["contactId"]
    private val contactId: Long? = contactIdString?.removePrefix("c-")?.toLongOrNull()

    // NOTE-02: the focusNote deep-link (a Call history row; Home's post-call
    // banner used it until 2026-10-07, NOTE-04): when true, the Notes input
    // should claim focus on first composition. The Routes.contactWithFocus
    // helper encodes the boolean as "1"; nullable / unset / "0" all read as
    // false. Owned by VM so rotation doesn't re-fire focus: cleared by the
    // first delivery below.
    private var focusNotePending: Boolean =
        savedStateHandle.get<String?>("focusNote") == "1"

    // LOG-03 — CallLog deep-link: when set, the screen scrolls its
    // body LazyColumn to the matching CallEvent row and renders an inline
    // "Add note to this call" Primary button below it. Encoded as a String
    // by the Routes.contactWithFocus helper to keep the Navigation Compose
    // arg surface uniform with focusNote; parsed back to Long here.
    private val scrollToCallEventId: Long? =
        savedStateHandle.get<String?>("scrollToCallEventId")?.toLongOrNull()

    /**
     * NOTE-02: the one-shot focus signal, delivered once per VM instance to
     * the first collector. A cold flow, not a SharedFlow: a SharedFlow with
     * replay 0 forgets a `tryEmit` made before anyone collects, and the
     * screen's `LaunchedEffect` always collects after `hiltViewModel()` has
     * run this VM's init, so the old emit-in-init never reached the screen
     * (the VM test written for it on 2026-10-06 failed first). Replay 1 would
     * re-fire on every re-collect (a rotation re-runs the LaunchedEffect on
     * the same VM) and refocus the field; the pending flag does not.
     */
    val focusNoteEvent: Flow<Unit> = flow {
        if (focusNotePending) {
            focusNotePending = false
            emit(Unit)
        }
    }

    private val contactSource: Flow<ContactEntity?> =
        if (contactId == null) flowOf(null) else contactRepo.observeById(contactId)

    private val membershipsSource: Flow<List<ListMembershipEntity>> =
        if (contactId == null) {
            flowOf(emptyList())
        } else {
            listRepo.observeMembershipsForContact(contactId)
        }

    // Contact-scoped recent events with explicit limit (50).
    // The DAO already filters by contactId and applies the LIMIT, so this read
    // is bounded to at most 50 rows for the focused contact. Replaces the
    // legacy `callEventRepo.observeAll().map { it.filter { ... }.take(50) }`
    // shape that pulled the entire `call_events` table on every emission.
    private val recentEventsSource: Flow<List<CallEventEntity>> =
        if (contactId == null) {
            flowOf(emptyList())
        } else {
            callEventRepo.observeForContact(contactId, limit = RECENT_EVENTS_LIMIT)
        }

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

    // READ_CALL_LOG, pushed from the screen on every ON_RESUME (ARCH-04), so
    // coming back from Settings with access granted clears the notice without
    // a restart. While denied the stats say so instead of "Never called".
    private val _callLogDenied = MutableStateFlow(false)

    fun onCallLogPermissionChanged(denied: Boolean) {
        _callLogDenied.value = denied
    }

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /**
     * CONTACT-06 — one-shot navigation events. The screen's `LaunchedEffect`
     * collects this flow and routes [NavEvent.RelinkPicker] to
     * `Routes.relinkContact(contactId)`. A SharedFlow is right here, unlike
     * for [focusNoteEvent]: the event is emitted on a tap while the screen is
     * collecting, never before it exists.
     */
    sealed interface NavEvent {
        /** Open the contact picker filtered to phone-contact relink candidates. */
        data class RelinkPicker(val contactId: Long) : NavEvent
    }
    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

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
        val noteEntities: List<NoteEntity>
    )

    /**
     * Intermediate fold of the FiveTuple + draft so a
     * second `.combine(ruleTemplateRepo.observeAll())` can lift the templates
     * list into the same recomposition family without a 7-arg combine call
     * (which would force `Array<Any?>` casts — see [FiveTuple] KDoc).
     * Also folds the ephemeral [_overrideEditorOpen] flag and the screen's
     * READ_CALL_LOG report in the same chained-combine shape.
     */
    private data class SixTuple(
        val tuple: FiveTuple,
        val draft: String,
        val overrideEditorOpen: Boolean = false,
        val callLogDenied: Boolean = false
    )

    // CONTACT-08: bumped by [onRetry]; flatMapLatest re-subscribes every source.
    private val retryCount = MutableStateFlow(0)

    /** The Error state's Retry. */
    fun onRetry() {
        retryCount.update { it + 1 }
    }

    /**
     * CONTACT-08: a failure in any source becomes [ContactDetailUiState.Error]
     * (with Retry) instead of an uncaught exception in viewModelScope, which
     * took the whole app down. No logging here (rules.md Code 4).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ContactDetailUiState> = retryCount.flatMapLatest { detailState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ContactDetailUiState.Loading
        )

    private fun detailState(): Flow<ContactDetailUiState> = combine(
        contactSource,
        membershipsSource,
        recentEventsSource,
        listRepo.observeAll(),
        notesSource
    ) { entity, memberships, events, allLists, noteEntities ->
        FiveTuple(entity, memberships, events, allLists, noteEntities)
    }.combine(_draft) { tuple, draftStr ->
        SixTuple(tuple, draftStr)
    }.combine(_overrideEditorOpen) { six, editorOpen ->
        six.copy(overrideEditorOpen = editorOpen)
    }.combine(_callLogDenied) { six, denied ->
        six.copy(callLogDenied = denied)
    }.combine(ruleTemplateRepo.observeAll()) { six, templates ->
        val tuple = six.tuple
        val draftStr = six.draft
        val entity = tuple.entity ?: return@combine ContactDetailUiState.NotFound

        // B3: single Clock read per emission; passed into both the call
        // mapper and the note mapper so derivations share one "now".
        val now = clock.now()

        val listsOn = tuple.memberships
            .mapNotNull { m -> tuple.allLists.firstOrNull { it.id == m.listId }?.name }
        val recentCalls = tuple.events.map { it.toUiCallEntry(now) }
        // LOG-03: parallel-indexed call-event ids for the screen's
        // scroll-to + retro-note affordance lookup. Same order as
        // `recentCalls` above (DESC by occurredAt).
        val recentCallEventIds = tuple.events.map { it.id }
        // Manual-log surface: parallel-indexed MANUAL flags so the
        // history row can render "Logged" + a distinct icon without
        // widening the CallEntry shape (same rationale as
        // recentCallEventIds above).
        val recentCallIsManual = tuple.events.map { it.source == CallSource.MANUAL }
        // Attempt surface: parallel-indexed with recentCalls; reach-outs
        // that didn't connect render "Attempted" + a phone-slash icon.
        val recentCallIsAttempt = tuple.events.map { it.source == CallSource.ATTEMPT }
        val longestGapLabel = computeLongestGap(tuple.events)
        val noteRows = tuple.noteEntities.map { it.toNoteRow(now) }

        // CONTACT-05: derive UnpauseBanner visibility.
        // True iff pausedUntil has lapsed AND it's NOT the indefinite
        // sentinel. Indefinite pauses ("until I unpause") are user-explicit
        // and never auto-expire; clearing them goes through the overflow
        // sheet flow, not the banner.
        val unpausePromptVisible = entity.pausedUntil?.let { pu ->
            pu <= now && !PauseContactUseCase.isIndefinite(pu)
        } ?: false
        val pausedLabel = entity.pausedUntil
            ?.takeIf { it.isAfter(now) }
            ?.let { pu ->
                if (PauseContactUseCase.isIndefinite(pu)) {
                    UiText.res(R.string.contact_paused_indefinitely)
                } else {
                    UiText.res(
                        R.string.contact_paused_until,
                        pu.atZone(zoneId).format(PAUSED_UNTIL_FORMAT)
                    )
                }
            }

        // CONTACT-03: derive RuleOverrideSection
        // inputs. Corrupted-JSON recovery is the try/catch
        // around decodeFromString; failed decode flips currentParams and
        // inheritedRhythm to null (the section shows the editor).
        val customScheduleVisible = listsOn.size >= 2
        // The editor branch renders when an override is
        // PERSISTED or the user peeked the editor open this session.
        // Opening alone persists nothing (see onOpenOverride).
        val hasOverride = entity.ruleOverrideJson != null || six.overrideEditorOpen
        val (inheritedRhythm, currentParams) = deriveOverrideDisplay(
            ruleOverrideJson = entity.ruleOverrideJson,
            memberships = tuple.memberships,
            allLists = tuple.allLists,
            templates = templates
        )
        val primaryListName = tuple.memberships.firstOrNull()?.listId?.let { lid ->
            tuple.allLists.firstOrNull { it.id == lid }?.name
        } ?: ""

        // Overlay call-derived stats (`lastCalledLabel`, `totalCalls`,
        // `avgLengthLabel`) onto the placeholder mapper output. Without
        // this overlay the Stats panel reads "Never called / 0 / a dash" even
        // for contacts with a populated call history (CallEventEntity
        // rows existed for the contact but the bare toUiContact mapper
        // emitted placeholders).
        //
        // The "Usually" stat reads `bestWindowLabel`, which only the
        // call-pattern overlay computes. Card View applied it and this
        // screen did not, so the same person showed "Mornings" on the card
        // and a blank here.
        val contactWithStats = entity.toUiContact()
            .withCallStats(tuple.events, now)
            .withCallPatterns(tuple.events, zoneId)

        if (entity.isOrphaned) {
            ContactDetailUiState.Orphaned(
                contact = contactWithStats,
                listsOn = listsOn,
                recentCalls = recentCalls,
                longestGapLabel = longestGapLabel,
                // Notes are Orbit's own data: they outlive the phone
                // contact and stay readable on the orphaned page.
                notes = noteRows,
                recentCallEventIds = recentCallEventIds,
                recentCallIsManual = recentCallIsManual,
                recentCallIsAttempt = recentCallIsAttempt,
                callLogDenied = six.callLogDenied
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
                pausedLabel = pausedLabel,
                isIgnored = entity.isIgnored,
                isArchived = entity.isArchived,
                phoneContactId = entity.phoneContactId,
                callLogDenied = six.callLogDenied,
                customScheduleVisible = customScheduleVisible,
                inheritedRhythm = inheritedRhythm,
                primaryListName = primaryListName,
                hasOverride = hasOverride,
                currentParams = currentParams,
                // LOG-03: CallLog deep-link surface. Both fields carry the
                // same id by default; scrolling and showing the affordance
                // are coupled signals. A future variant could decouple them
                // (e.g., scroll without affordance) but the user
                // story is single-purpose: tap row → arrive scrolled with
                // a one-tap retro-note path.
                scrollToCallEventId = scrollToCallEventId,
                retroNoteAffordanceFor = scrollToCallEventId,
                recentCallEventIds = recentCallEventIds,
                recentCallIsManual = recentCallIsManual,
                recentCallIsAttempt = recentCallIsAttempt
            )
        }
    }.catch { emit(ContactDetailUiState.Error) }

    /**
     * CONTACT-03: resolves the (inherited rhythm, RuleParams) pair driving the
     * RuleOverrideSection.
     *
     * Three branches:
     *   1. Override exists + decodes cleanly: (null, decoded RuleParams). With
     *      an override stored the section shows the editor, never the
     *      "Comes up every ..." sentence, so there is nothing to describe.
     *   2. Override exists + decode throws (corrupted JSON): (null, null).
     *      The screen passes a fresh default RuleParams so the editor still
     *      renders.
     *   3. No override: (how often the primary list brings people up, null).
     *      The list's own override or template, resolved the way List
     *      settings resolves it ([resolveRuleParams]), described by its
     *      interval ("every 14 days", LIST-30: no rhythm names), or null when
     *      the list has no readable rhythm.
     */
    private fun deriveOverrideDisplay(
        ruleOverrideJson: String?,
        memberships: List<ListMembershipEntity>,
        allLists: List<ListEntity>,
        templates: List<RuleTemplateEntity>
    ): Pair<UiText?, RuleParams?> {
        if (ruleOverrideJson != null) {
            return try {
                Pair(null, JsonProvider.json.decodeFromString<RuleParams>(ruleOverrideJson))
            } catch (_: Throwable) {
                Pair(null, null)
            }
        }
        // No per-contact override: describe the primary list's rhythm. The
        // "primary" list is the first membership row; the RoundRobinEngine
        // treats memberships as ordered so this matches the surfacing path's
        // notion of "first".
        val primaryListId = memberships.firstOrNull()?.listId
        val primaryList = primaryListId?.let { lid -> allLists.firstOrNull { it.id == lid } }
        val template = primaryList?.ruleTemplateId?.let { tid -> templates.firstOrNull { it.id == tid } }
        val resolved = resolveRuleParams(
            overrideJson = primaryList?.ruleParamsOverrideJson,
            templateParamsJson = template?.paramsJson,
            json = JsonProvider.json,
        )
        val inherited = (resolved as? RuleParamsResolution.Decoded)?.let { everyFor(it.params.baseIntervalHours) }
        return Pair(inherited, null)
    }

    /** "every day" or "every 14 days", as it sits mid-sentence (strings_contact.xml). */
    private fun everyFor(hours: Int): UiText {
        val days = (hours / 24).coerceAtLeast(1)
        return if (days == 1) {
            UiText.res(R.string.contact_rhythm_every_day)
        } else {
            UiText.plural(R.plurals.contact_rhythm_every_days, days, days)
        }
    }

    /**
     * Longest gap between consecutive call events: "21 days". Null if fewer
     * than two events, or if they all fell on one day: the screen then says
     * "Not enough calls yet" (it used to be an em dash the screen filtered).
     */
    private fun computeLongestGap(events: List<CallEventEntity>): UiText? {
        if (events.size < 2) return null
        val sorted = events.sortedBy { it.occurredAt }
        var maxDays = 0L
        for (i in 1 until sorted.size) {
            val days = Duration.between(sorted[i - 1].occurredAt, sorted[i].occurredAt).toDays()
            if (days > maxDays) maxDays = days
        }
        if (maxDays == 0L) return null
        return maxDays.toInt().let { UiText.plural(R.plurals.time_span_days, it, it) }
    }

    /**
     * Concrete CallEventEntity → UI CallEntry mapper. Ground truth (Model.kt:39):
     *   `data class CallEntry(direction, relativeWhen, lengthLabel)`
     * Earlier drafts used `kotlin.TODO()` here — that crashed the moment a user
     * opened a contact with any history. Lifted formatters live in
     * `app.orbit.ui.util.RelativeTime` so VM and screen share one source.
     */
    private fun CallEventEntity.toUiCallEntry(now: Instant = Instant.now()): CallEntry = CallEntry(
        // Entity enum (OUTGOING / INCOMING) → UI enum (Outgoing / Incoming).
        // Two enums are deliberate: entity layer follows SQL-style upper case,
        // UI layer follows Kotlin convention. Map at the seam.
        direction = when (this.direction) {
            app.orbit.data.entity.CallDirection.OUTGOING -> app.orbit.data.CallDirection.Outgoing
            app.orbit.data.entity.CallDirection.INCOMING -> app.orbit.data.CallDirection.Incoming
        },
        relativeWhen = formatRelative(this.occurredAt, now),
        lengthLabel = formatDuration(this.durationSeconds)
    )

    /**
     * NOTE-01 — adds a note via [AddNoteUseCase]; clears the draft on a
     * successful insert (use case returns null for blank/empty bodies).
     */
    fun addNote(body: String) {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation(UiText.res(R.string.contact_snackbar_add_note_failed)) {
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
            runMutation(UiText.res(R.string.contact_snackbar_add_note_failed)) {
                // O(1) primary-key lookup. NOT observeAll().first().
                val event = callEventRepo.byId(callEventId) ?: return@runMutation
                val rowId = addRetroactiveNoteUseCase(cid, body, event.occurredAt)
                if (rowId != null) {
                    _snackbarEvents.tryEmit(
                        SnackbarEvent(UiText.res(R.string.contact_snackbar_note_saved))
                    )
                }
            }
        }
    }

    /**
     * Manual connection / attempt log ("Log a connection", CONTACT-09):
     * records something Orbit's call-log sync can't see. The write is the
     * shared [LogConnectionUseCase], the one the card calls too (CARD-10):
     * a connection is `CallSource.MANUAL`, an attempt `CallSource.ATTEMPT`,
     * through MarkCalledUseCase so every list's `nextDueAt` recomputes, with
     * the optional note back-dated to the same moment. The use case owns how
     * Today, Yesterday and a picked date become an instant. The screen's
     * state refreshes by itself: [recentEventsSource] is a Room flow that
     * re-emits on insert.
     */
    fun onLogConnection(whenChoice: LogConnectionWhen, note: String, isAttempt: Boolean = false) {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation(UiText.res(R.string.contact_snackbar_log_failed)) {
                logConnection(cid, whenChoice, note, isAttempt)
                _snackbarEvents.tryEmit(
                    SnackbarEvent(
                        UiText.res(
                            if (isAttempt) R.string.contact_snackbar_attempt_logged else R.string.contact_snackbar_logged
                        )
                    )
                )
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
            createdAt = Instant.ofEpochMilli(noteRow.createdAtMs)
        )
        viewModelScope.launch {
            runMutation(UiText.res(R.string.contact_snackbar_delete_note_failed)) {
                val result = deleteNoteUseCase(noteEntity)
                undoStack.put(UndoStack.PendingUndo(result.inverse))
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(UiText.res(R.string.contact_snackbar_note_deleted))
                )
            }
        }
    }

    /** NOTE-01 — long-press inline edit commit. */
    fun onEditNote(noteRow: NoteRow, newBody: String) {
        val updated = NoteEntity(
            id = noteRow.id,
            contactId = noteRow.contactId,
            body = newBody.trim(),
            createdAt = Instant.ofEpochMilli(noteRow.createdAtMs)
        )
        viewModelScope.launch {
            runMutation(
                UiText.res(R.string.contact_snackbar_update_note_failed)
            ) { editNoteUseCase(updated) }
        }
    }

    /** Snackbar "Undo" tap — pops UndoStack and runs the inverse closure. */
    fun onUndo() = viewModelScope.launch {
        runMutation(UiText.res(R.string.contact_snackbar_undo_failed)) {
            undoStack.take()?.inverse?.invoke()
        }
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
            runMutation(UiText.res(R.string.contact_snackbar_ignore_failed, contactName)) {
                val result = ignoreContactUseCase(cid)
                undoStack.put(UndoStack.PendingUndo(result.inverse))
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(
                        UiText.res(R.string.components_snackbar_ignored, contactName)
                    )
                )
            }
        }
    }

    /**
     * CONTACT-10: the inverse of [onIgnore], offered while the person is
     * ignored: [unignoreContactUseCase] restores their memberships from the
     * snapshot taken at ignore time (IGNORE-07) and the snackbar's Undo
     * re-ignores through [ignoreContactUseCase], the same pairing Settings >
     * Ignored uses. Before this the page offered Ignore again and the way
     * back was two screens away.
     */
    fun onUnignore(contactName: String) {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation(UiText.res(R.string.contact_snackbar_unignore_failed, contactName)) {
                unignoreContactUseCase(cid)
                undoStack.put(UndoStack.PendingUndo(inverse = { ignoreContactUseCase(cid) }))
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(
                        UiText.res(R.string.components_snackbar_unignored, contactName)
                    )
                )
            }
        }
    }

    /**
     * CONTACT-04 — commits a pause via [pauseContact] and emits a snackbar
     * with Undo. The earlier hook only wrote `pausedUntil`; this wraps
     * that with the snackbar event the screen's `LaunchedEffect` collector
     * needs. The inverse closure clears `pausedUntil` (mirrors
     * [onUnpauseContact] semantics — there is no companion UnpauseUseCase
     * for the single-contact path; the repo setter is the inverse), and
     * asks for a widget refresh like the forward write does (WIDGET-06):
     * the use case fires the trigger on pause, so Undo must fire it too or
     * the widget keeps hiding someone who is back.
     */
    fun onPauseContact(duration: PauseDuration) {
        val cid = contactId ?: return
        val displayName = readyNameOrStandIn()
        viewModelScope.launch {
            runMutation(UiText.res(R.string.contact_snackbar_pause_failed, displayName)) {
                pauseContact(cid, duration)
                val inverse: suspend () -> Unit = {
                    contactRepo.setPausedUntil(cid, null)
                    widgetRefreshTrigger.scheduleRefresh()
                }
                undoStack.put(UndoStack.PendingUndo(inverse))
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(pausedSnackbar(displayName, duration))
                )
            }
        }
    }

    /**
     * Overflow → Unpause, while a pause is in force. Restores surfacing now and
     * offers Undo back to the exact prior pause. The only way out of an
     * indefinite pause besides its own snackbar: the banner path below only
     * ever shows once a timed pause has already expired. Both the write and
     * its Undo refresh the widget (WIDGET-06): these bypass
     * [PauseContactUseCase], which is where the trigger otherwise fires.
     */
    fun onUnpauseNow() {
        val cid = contactId ?: return
        val displayName = readyNameOrStandIn()
        viewModelScope.launch {
            runMutation(UiText.res(R.string.contact_snackbar_unpause_failed)) {
                val prior = contactRepo.getById(cid)?.pausedUntil
                contactRepo.setPausedUntil(cid, null)
                widgetRefreshTrigger.scheduleRefresh()
                undoStack.put(
                    UndoStack.PendingUndo({
                        contactRepo.setPausedUntil(cid, prior)
                        widgetRefreshTrigger.scheduleRefresh()
                    })
                )
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(
                        UiText.res(R.string.components_snackbar_unpaused, displayName)
                    )
                )
            }
        }
    }

    /** The unpause banner's dismiss (CONTACT-05): the pause has lapsed, so clearing it only tidies up. */
    fun onUnpauseContact() {
        val cid = contactId ?: return
        viewModelScope.launch {
            runMutation(UiText.res(R.string.contact_snackbar_unpause_failed)) {
                contactRepo.setPausedUntil(cid, null)
                widgetRefreshTrigger.scheduleRefresh()
            }
        }
    }

    /**
     * CONTACT-06 — Re-link tap on the OrphanBanner. Emits a one-shot
     * [NavEvent.RelinkPicker] so the screen's NavHost-side caller can navigate
     * to `Routes.relinkContact(contactId)`: the picker in Relink mode
     * (CONTACT-07), which merges the picked phone contact into this one with
     * [app.orbit.domain.usecase.RelinkContactUseCase]. This contact's id
     * survives the merge, so popping back lands on the same screen, now
     * showing the linked contact.
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
            runMutation(UiText.res(R.string.contact_snackbar_archive_failed, contactName)) {
                val result = archiveContactUseCase(cid)
                undoStack.put(UndoStack.PendingUndo(result.inverse))
                _snackbarEvents.tryEmit(
                    SnackbarEvent.undoable(
                        UiText.res(R.string.contact_snackbar_archived, contactName)
                    )
                )
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
     * to the no-override branch ("Comes up every 14 days, like the rest of {primary list}").
     *
     * Also covers the corrupted-JSON recovery path: when decode fails the
     * section shows the editor primed with default RuleParams under its
     * usual "Custom schedule" label (no special copy; `inheritedRhythm`
     * is null and nothing displays it), and tapping Reset to default here
     * clears the corrupted column without forcing the user to overwrite it.
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
        failureLabel: UiText = UiText.res(R.string.components_snackbar_save_failed),
        block: suspend () -> Unit
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
        absoluteTimestamp = formatAbsolute(createdAt)
    )

    /**
     * The person's name for a snackbar, or the curtain's neutral "Contact"
     * when the screen isn't showing a person yet (the old literal fallback).
     * A [UiText] stand-in nests as an argument like a name does.
     */
    private fun readyNameOrStandIn(): Any =
        (uiState.value as? ContactDetailUiState.Ready)?.contact?.name
            ?: UiText.res(R.string.components_curtain_contact)

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

/** "12 Oct": short and unambiguous next to "Paused until". */
private val PAUSED_UNTIL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(
    "d MMM",
    Locale.getDefault()
)
